import dev.bluevista.craftq3.assets.aas.*;
import dev.bluevista.craftq3.botlib.aas.*;
import dev.bluevista.craftq3.botlib.goal.*;
import dev.bluevista.craftq3.botlib.movement.*;
import dev.bluevista.craftq3.core.math.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.zip.*;

/** Authored read-only movement view differential using an isolated native oracle. */
class AuditMovementViews {
  record Query(String commands, BotMovementView.Result result) {}
  public static void main(String[]args)throws Exception {
    if(args.length<2||args.length>4)throw new IllegalArgumentException("AuditMovementViews PK3 oracle [count/map] [map]");
    int count=args.length>2?Integer.parseInt(args[2]):1000;
    if(count<1||count>10000)throw new IllegalArgumentException("Invalid query count");
    int maps=0,total=0,diffs=0;double max=0;
    try(var zip=new ZipFile(args[0])) {
      for(var entry:zip.stream().filter(e->e.getName().startsWith("maps/")&&e.getName().endsWith(".aas")).sorted(Comparator.comparing(ZipEntry::getName)).toList()) {
        String name=entry.getName().substring(5,entry.getName().length()-4);
        if(args.length==4&&!name.equals(args[3]))continue;
        AasMap map;try(var in=zip.getInputStream(entry)){map=AasReader.read(in.readNBytes(AasReader.MAX_BYTES+1));}
        var routes=new AasMovementRoutes(map);var service=new BotMovementView(map,routes);var random=new Random(554);
        var commands=new StringBuilder("frame 100\n");var queries=new ArrayList<Query>();
        for(int i=0;i<count;i++) {
          int reach=1+random.nextInt(map.reachabilities().size()-1);var r=map.reachabilities().get(reach);
          Vec3 start=r.start().add(new Vec3(random.nextInt(101)-50,random.nextInt(101)-50,random.nextInt(101)-50));
          int goalArea=i%5==0?r.area():1+random.nextInt(map.areas().size()-1);var zero=new Vec3(0,0,0);
          var goal=new Goal(map.areas().get(goalArea).center(),goalArea,zero,zero,0,0,0,0);
          int flags=i%7==0?0:0xffffff;
          float ahead=new float[]{-1,0,1,10,32,128,512,4096,16384}[i%9];
          int previousGoal=i%3==0?goalArea:0;
          int previousArea=i%3==0?1+random.nextInt(map.areas().size()-1):0;
          int avoid=i%4==0?1+random.nextInt(map.reachabilities().size()-1):0;
          var context=new AasMovementRoutes.Context(previousGoal,previousArea,100,avoid==0?List.of():List.of(new AasMovementRoutes.AvoidReach(avoid,1000,5)));
          String command="init "+xyz(start)+"\nhistory 0 "+previousArea+" "+previousGoal+" "+reach+" 0\navoid "+avoid+" 1000 5\nview "+goalArea+" "+xyz(goal.origin())+" "+flags+" "+ahead+"\n";
          try { queries.add(new Query(command,service.target(start,reach,goal,flags,ahead,context))); } catch (RuntimeException failure) { System.out.println("ERROR " + name + " " + command); throw failure; } commands.append(command);
        }
        var actual=oracle(args[1],args[0],name,commands.toString());if(actual.size()!=queries.size())throw new AssertionError("Query count");
        int md=0;
        for(int i=0;i<queries.size();i++) {
          var e=queries.get(i).result();var a=actual.get(i);double delta=0;
          if(e.target().isPresent()&&a.target().isPresent()) {
            Vec3 p=e.target().get(),q=a.target().get();delta=Math.max(Math.abs(p.x()-q.x()),Math.max(Math.abs(p.y()-q.y()),Math.abs(p.z()-q.z())));max=Math.max(max,delta);
          }
          if(e.success()!=a.success()||e.target().isPresent()!=a.target().isPresent()||delta>.002) {
            if(diffs++<15)System.out.println("DIFF "+name+" "+queries.get(i)+" native="+a+" delta="+delta);md++;
          }
        }
        maps++;total+=count;System.out.println(name+" queries="+count+" differences="+md);
      }
    }
    System.out.println("Movement views maps="+maps+" queries="+total+" differences="+diffs+" maximumCoordinateError="+max);
    if(maps==0||diffs!=0)throw new AssertionError("Movement view audit failed");
  }
  static String xyz(Vec3 p){return (float)p.x()+" "+(float)p.y()+" "+(float)p.z();}
  static List<BotMovementView.Result> oracle(String path,String pk3,String map,String input)throws Exception {
    var p=new ProcessBuilder(path,pk3,map).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
      var read=executor.submit(()->{byte[]b=p.getInputStream().readNBytes(32*1024*1024+1);if(b.length>32*1024*1024)throw new IllegalStateException("Output cap");return new String(b,StandardCharsets.US_ASCII);});
      try(var write=p.getOutputStream()){write.write(input.getBytes(StandardCharsets.US_ASCII));}
      if(!p.waitFor(60,TimeUnit.SECONDS))throw new IllegalStateException("Oracle timeout");if(p.exitValue()!=0)throw new IllegalStateException("Oracle exit "+p.exitValue());
      return read.get(5,TimeUnit.SECONDS).lines().filter(l->l.startsWith("VIEW ")).map(l->{var s=l.split(" ");var v=new Vec3(Float.parseFloat(s[2]),Float.parseFloat(s[3]),Float.parseFloat(s[4]));return new BotMovementView.Result(s[1].equals("1"),v.equals(new Vec3(12345,23456,34567))?Optional.empty():Optional.of(v));}).toList();
    }finally{if(p.isAlive())p.destroyForcibly();}
  }
}
