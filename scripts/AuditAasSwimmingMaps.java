import dev.bluevista.craftq3.assets.aas.*;
import dev.bluevista.craftq3.assets.bsp.*;
import dev.bluevista.craftq3.botlib.aas.*;
import dev.bluevista.craftq3.botlib.movement.*;
import dev.bluevista.craftq3.collision.*;
import dev.bluevista.craftq3.core.math.Vec3;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Native AAS and Java AAS prediction share only the actual Java BSP contents callback. */
class AuditAasSwimmingMaps {
  static final HexFormat HEX=HexFormat.of();
  static final Set<Integer> FLOAT_WORDS=Set.of(0,1,2,4,5,6,8,9,10,11,19);
  public static void main(String[] args)throws Exception {
    if(args.length!=2)throw new IllegalArgumentException("AuditAasSwimmingMaps <pak0.pk3> <native-map-observer>");
    int checked=0,failures=0,guards=0,maps=0;double worst=0;var random=new Random(853);
    try(var zip=new ZipFile(args[0])) {
      var names=zip.stream().map(ZipEntry::getName).filter(n->n.matches("maps/[^/]+\\.aas")).sorted().toList();
      for(var name:names){
        var aas=AasReader.read(zip.getInputStream(zip.getEntry(name)).readAllBytes());
        var bsp=BspReader.read(zip.getInputStream(zip.getEntry(name.replace(".aas",".bsp"))).readAllBytes());
        var collision=new BspTraceWorld(bsp);var starts=new ArrayList<Vec3>();
        for(int area=1;area<aas.areas().size();area++){
          var point=aas.areas().get(area).center();
          if((aas.areaSettings().get(area).contents()&7)!=0&&(collision.pointContents(new Vec3(point.x(),point.y(),(float)point.z()-1.75f),-1,-1)&56)!=0)starts.add(point);
        }
        if(starts.isEmpty())continue;
        String map=name.substring(5,name.length()-4);int mapChecked=0,mapFailed=0,mapGuards=0;double mapWorst=0;
        var process=new ProcessBuilder(args[1],args[0],map).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var watchdog=Thread.ofVirtual().start(()->{try{Thread.sleep(90000);process.destroyForcibly();}catch(InterruptedException ignored){}});
        try(var input=new BufferedReader(new InputStreamReader(process.getInputStream()));var output=new PrintWriter(process.getOutputStream(),true)){
          String row;while((row=input.readLine())!=null&&!row.startsWith("READY "))reply(row,output,collision,null);
          if(row==null)throw new AssertionError("Native startup "+map);
          var javaQueries=new ArrayList<Vec3>();
          var world=new AasMovementWorld(new AasNavigation(aas),(entity,request)->{throw new AssertionError("Unexpected dynamic entity");},point->{javaQueries.add(point);return collision.pointContents(point,-1,-1);});
          var predictor=new AasMovementPredictor(world,AasMovementPredictor.Settings.defaults());
          for(int iteration=0;iteration<500;iteration++){
            var origin=starts.get(random.nextInt(starts.size()));
            var velocity=new Vec3(f(random,-500,500),f(random,-500,500),f(random,-500,500));
            var command=new Vec3(f(random,-400,400),f(random,-400,400),f(random,-400,400));
            int presence=random.nextBoolean()?2:4,cf=random.nextInt(5),mf=1+random.nextInt(10),events=new int[]{0,1,2,4,28,32,60,64,124}[random.nextInt(9)];
            float dt=new float[]{.05f,.1f,.2f}[random.nextInt(3)];boolean ground=random.nextBoolean();
            var request=new AasMovementPredictor.Request(-1,origin,presence,ground,velocity,command,cf,mf,dt,events);
            var nativeQueries=new ArrayList<Vec3>();
            output.println("predictswim "+vec(origin)+" "+vec(velocity)+" "+vec(command)+" "+presence+" "+(ground?1:0)+" "+cf+" "+mf+" "+dt+" "+events);
            while((row=input.readLine())!=null&&!row.startsWith("SWIM_RESULT "))reply(row,output,collision,nativeQueries);
            if(row==null)throw new AssertionError("Native prediction EOF");
            var fields=row.split(" ");boolean nativeOk=fields[1].equals("1");var nativeBytes=HEX.parseHex(fields[2]);
            javaQueries.clear();Optional<AasMovementPredictor.Prediction> result;
            try{result=predictor.predict(request);}catch(UnsupportedOperationException ex){mapGuards++;System.out.println("GUARD "+map+" "+request+" "+ex.getMessage());continue;}
            boolean bad=nativeOk!=result.isPresent();double error=0;
            if(nativeOk&&result.isPresent()){
              var actual=pack(result.get());bad|=!Arrays.equals(nativeBytes,actual);var n=ByteBuffer.wrap(nativeBytes).order(ByteOrder.LITTLE_ENDIAN);var j=ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN);
              for(int word=0;word<21;word++)if(FLOAT_WORDS.contains(word)){double d=Math.abs(n.getFloat(word*4)-j.getFloat(word*4));error=Math.max(error,d);bad|=d>.001;}else bad|=n.getInt(word*4)!=j.getInt(word*4);
            }
            bad|=nativeQueries.size()!=javaQueries.size();
            if(nativeQueries.size()==javaQueries.size())for(int q=0;q<nativeQueries.size();q++)bad|=!nativeQueries.get(q).equals(javaQueries.get(q));
            if(bad){mapFailed++;if(mapFailed<=3)System.out.println("MISMATCH "+map+" "+request+" native="+row+" java="+result+" queries="+nativeQueries.size()+"/"+javaQueries.size()+" error="+error);}
            mapWorst=Math.max(mapWorst,error);mapChecked++;
          }
        }finally{process.destroy();watchdog.interrupt();}
        System.out.println(map+": comparisons="+mapChecked+" mismatches="+mapFailed+" guards="+mapGuards+" maxError="+mapWorst+" original-fluid-areas="+starts.size());
        checked+=mapChecked;failures+=mapFailed;guards+=mapGuards;worst=Math.max(worst,mapWorst);maps++;
      }
    }
    System.out.println("TOTAL maps="+maps+" comparisons="+checked+" mismatches="+failures+" guards="+guards+" maxError="+worst);
    if(failures!=0)throw new AssertionError("Original-map swimming mismatch");
  }
  static float f(Random random,float min,float max){return min+random.nextFloat()*(max-min);}
  static String vec(Vec3 p){return (float)p.x()+" "+(float)p.y()+" "+(float)p.z();}
  static double distance(Vec3 a,Vec3 b){return Math.max(Math.abs(a.x()-b.x()),Math.max(Math.abs(a.y()-b.y()),Math.abs(a.z()-b.z())));}
  static void reply(String line,PrintWriter output,BspTraceWorld collision,List<Vec3> queries){if(!line.startsWith("CONTENTS_QUERY "))return;var fields=line.split(" ");var p=new Vec3(Float.parseFloat(fields[1]),Float.parseFloat(fields[2]),Float.parseFloat(fields[3]));if(queries!=null)queries.add(p);output.println("CONTENTS_REPLY "+collision.pointContents(p,-1,-1));}
  static byte[] pack(AasMovementPredictor.Prediction r){var tr=r.trace().orElseThrow();var b=ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN);putVec(b,r.endPosition());b.putInt(r.endArea());putVec(b,r.velocity());b.putInt(tr.startSolid()?1:0).putFloat(tr.fraction());putVec(b,tr.endPosition());b.putInt(tr.entity()).putInt(tr.lastArea()).putInt(tr.area()).putInt(tr.planeNumber()).putInt(r.presence()).putInt(r.stopEvent()).putInt(r.endContents()).putFloat(r.time()).putInt(r.frames());return b.array();}
  static void putVec(ByteBuffer b,Vec3 v){b.putFloat((float)v.x()).putFloat((float)v.y()).putFloat((float)v.z());}
}
