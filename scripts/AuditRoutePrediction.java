import dev.bluevista.craftq3.assets.aas.AasReader;
import dev.bluevista.craftq3.botlib.aas.*;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

/** Read-only differential using caller-owned original assets and an unchanged native oracle. */
class AuditRoutePrediction {
  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 4) throw new IllegalArgumentException("AuditRoutePrediction <PK3> <native oracle> [queries/map] [map]");
    int count = args.length > 2 ? Integer.parseInt(args[2]) : 1000;
    if (count < 1 || count > 100000) throw new IllegalArgumentException("Invalid query count");
    int total = 0, bad = 0, maps = 0;
    try (var zip = new ZipFile(Path.of(args[0]).toFile())) {
      for (var entry : zip.stream().filter(e -> e.getName().startsWith("maps/") && e.getName().endsWith(".aas")).sorted(Comparator.comparing(java.util.zip.ZipEntry::getName)).toList()) {
        String name = entry.getName().substring(5, entry.getName().length()-4);
        if (args.length > 3 && !name.equals(args[3])) continue;
        byte[] bytes; try (var in = zip.getInputStream(entry)) { bytes = in.readNBytes(AasReader.MAX_BYTES+1); }
        var map = AasReader.read(bytes); var times = new AasRouteTimes(map);
        var predictor = new AasRoutePredictor(new AasNavigation(map),times);
        var areas = new ArrayList<Integer>();
        for(int a=1;a<map.areas().size();a++) if(map.areaSettings().get(a).reachabilityCount()>0 && map.areaSettings().get(a).cluster()!=0) areas.add(a);
        var random = new Random(576); var expected = new ArrayList<AasRoutePredictor.Result>();
        var commands = new ArrayList<String>();
        for(int i=0;i<count;i++) {
          int start=areas.get(random.nextInt(areas.size())), goal=areas.get(random.nextInt(areas.size()));
          if (i%23==0) start=random.nextInt(map.areas().size());
          if (i%29==0) goal=random.nextInt(map.areas().size());
          if (i%31==0) goal=start;
          var origin=map.areas().get(start).center();
          if(i%5==0) origin=origin.add(new Vec3(random.nextFloat()*128-64,random.nextFloat()*128-64,random.nextFloat()*128-64));
          int flags=switch(i%4){case 0->TravelFlags.ALL;case 1->TravelFlags.DEFAULT & ~TravelFlags.JUMP;case 2->0;default->TravelFlags.DEFAULT;};
          int maxAreas=new int[]{-1,0,1,2,10,100}[random.nextInt(6)],maxTime=new int[]{-1,0,1,50,100,300,1000}[random.nextInt(7)];
          int events=random.nextInt(16), contents=new int[]{0,1,2,4,7,8,16,128,256,2048,-1}[random.nextInt(11)];
          int stopFlags=new int[]{0,1,2,4,128,TravelFlags.JUMP_PAD,TravelFlags.WATER,TravelFlags.AIR,TravelFlags.DEFAULT,-1}[random.nextInt(10)];
          int stopArea=goal;
          var settings=map.areaSettings().get(start);
          if(i%2==0 && settings.reachabilityCount()>0) stopArea=map.reachabilities().get(settings.firstReachability()+random.nextInt(settings.reachabilityCount())).area();
          var request=new AasRoutePredictor.Request(start,origin,goal,TravelPolicy.ofFlags(flags),maxAreas,maxTime,events,contents,stopFlags,stopArea);
          try { expected.add(predictor.predict(request)); } catch (IllegalStateException failure) { System.out.println("FAILED "+name+" "+request); throw failure; }
          commands.add("predict "+start+" "+(float)origin.x()+" "+(float)origin.y()+" "+(float)origin.z()+" "+goal+" "+flags+" "+maxAreas+" "+maxTime+" "+events+" "+contents+" "+stopFlags+" "+stopArea);
        }
        Process process=new ProcessBuilder(args[1],args[0],name).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        List<String> output;
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
          var reader=executor.submit(()->{try(var in=process.inputReader()){return in.lines().filter(s->s.startsWith("PREDICT ")).toList();}});
          try(var writer=process.outputWriter()){for(String line:commands){writer.write(line);writer.newLine();}}
          if(!process.waitFor(60,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("Oracle timeout "+name);}
          output=reader.get(10,TimeUnit.SECONDS);
        }
        if(process.exitValue()!=0 || output.size()!=count) throw new IllegalStateException("Oracle output count "+output.size()+" "+name);
        int mapBad=0;
        for(int i=0;i<count;i++) {
          String[] words=output.get(i).split(" ");var result=expected.get(i);var p=result.prediction();
          boolean match=(Integer.parseInt(words[1])!=0)==result.success();
          float[] xyz={(float)p.endPosition().x(),(float)p.endPosition().y(),(float)p.endPosition().z()};
          for(int n=0;n<3;n++) match&=Float.floatToRawIntBits(Float.parseFloat(words[n+2]))==Float.floatToRawIntBits(xyz[n]);
          int[] ints={p.endArea(),p.stopEvent(),p.endContents(),p.endTravelFlags(),0xa5a5a5a5,p.time()};
          for(int n=0;n<ints.length;n++)match&=Integer.parseInt(words[n+5])==ints[n];
          if(!match){if(bad+mapBad<30)System.out.println("MISMATCH "+name+" "+commands.get(i)+"\n Java "+result+"\n Native "+output.get(i));mapBad++;}
        }
        maps++;total+=count;bad+=mapBad;System.out.println(name+" queries="+count+" mismatches="+mapBad);
      }
    }
    System.out.println("TOTAL maps="+maps+" queries="+total+" mismatches="+bad);
    if(bad!=0)throw new AssertionError("Route prediction mismatches: "+bad);
  }
}
