import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.botlib.goal.*;
import dev.bluevista.craftq3.botlib.item.*;
import dev.bluevista.craftq3.botlib.weight.WeightConfig;
import dev.bluevista.craftq3.botlib.script.ScriptSources;
import dev.bluevista.craftq3.core.fs.*;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Original native goal policy with identical controlled route costs and per-item fuzzy weights. */
class AuditItemChoices {
  static final StringBuilder trace=new StringBuilder();
  public static void main(String[] args)throws Exception {
    Path install=Path.of("run/craftq3/games"),oracle=Path.of(".tools/item-oracle/goal-choice-oracle");
    var random=new Random(0x1700535);int checks=0;
    try(var fs=Pk3FileSystem.mount(install,"baseq3");var scripts=new ScriptSources(fs)) {
      var metadata=ItemConfig.load(scripts,"botfiles/items.c");
      var source=new StringBuilder();for(var info:metadata.items())source.append("weight \"").append(info.classname()).append("\" { return 0; }\n");
      var config=WeightConfig.load(fixture(source.toString()),"weights.c");
      for(int game:new int[]{0,2,3,4}) {
        var process=new ProcessBuilder(oracle.toAbsolutePath().toString(),install.resolve("baseq3/pak0.pk3").toAbsolutePath().toString(),Integer.toString(game)).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var items=new AtomicReference<List<ItemRegistry.LevelItem>>(List.of());var route=new Routes();float[] weights=new float[35];
        try(var input=process.outputWriter(StandardCharsets.US_ASCII);var output=process.inputReader(StandardCharsets.US_ASCII);var goals=new BotGoals(()->5,s->{})) {
          if(!"READY".equals(output.readLine()))throw new AssertionError("Native setup failed");int handle=goals.allocate(13);goals.weights(handle,config);
          var selector=new BotItemSelector(goals,items::get,route,(c,i,inventory)->weights[i],()->game,()->1000);
          for(int trial=0;trial<1000;trial++) {
            trace.setLength(0);
            cmd(input,output,"reset");goals.reset(handle);route.area=trial%9==0?0:100;cmd(input,output,"area "+route.area);
            for(int i=0;i<weights.length;i++) {weights[i]=random.nextInt(2001)-200;cmd(input,output,"weight "+i+" "+weights[i]);}
            var list=new ArrayList<ItemRegistry.LevelItem>();var rows=new StringBuilder("items 12\n");
            for(int i=0;i<12;i++) {
              var info=metadata.items().get(random.nextInt(metadata.items().size()));int area=random.nextInt(12)==0?0:i+1;
              var origin=new Vec3(100*(i+1),random.nextInt(100),random.nextInt(100));
              var place=new ItemRegistry.Placement(origin,new Vec3(origin.x(),origin.y(),origin.z()+.5f),area);
              var item=new ItemRegistry.LevelItem(i+1,i+1,info,random.nextInt(32),random.nextFloat()*4,place,random.nextInt(8)==0?0:40+i,random.nextInt(5)==0?35:0);list.add(item);
              rows.append(item.number()).append(' ').append(info.number()).append(' ').append(item.flags()).append(' ').append(item.weight()).append(' ').append(text(origin)).append(area).append(' ').append(text(place.goalOrigin())).append(item.entity()).append(' ').append(item.timeout()).append('\n');
              int cost=random.nextInt(8)==0?0:random.nextInt(1000)+1;route.cost[100][area]=cost;cmd(input,output,"route 100 "+area+" "+cost);
              int onward=random.nextInt(8)==0?0:random.nextInt(1500)+1;route.cost[area][500]=onward;cmd(input,output,"route "+area+" 500 "+onward);
            }
            items.set(List.copyOf(list));cmd(input,output,rows.toString().stripTrailing());
            for(var item:list) {
              float avoid=random.nextInt(3)==0?random.nextFloat()*20:0;
              if(trial%7==0)avoid=route.cost[100][item.placement().area()]*.009f;
              goals.setAvoidTime(handle,item.number(),avoid);cmd(input,output,"avoid "+item.number()+" "+avoid);
            }
            int direct=random.nextInt(10)==0?0:random.nextInt(1500)+1;route.cost[100][500]=direct;cmd(input,output,"route 100 500 "+direct);
            var origin=new Vec3(0,0,0);int flags=123;boolean near=(trial&1)==1;float max=trial%5==0?100:trial%5==1?500:1000;
            Goal target=trial%4==1?null:new Goal(new Vec3(500,500,500),500,new Vec3(0,0,0),new Vec3(0,0,0),0,0,0,0);
            boolean selected=near?selector.chooseNearby(handle,origin,new int[256],flags,target,max):selector.chooseLongTerm(handle,origin,new int[256],flags);
            String call=near?"nbg 0 0 0 123 "+(target==null?-1:500)+" 500 500 500 "+max:"ltg 0 0 0 123";
            trace.append(call).append('\n');input.write(call+"\n");input.flush();String actual=output.readLine();
            var top=goals.top(handle);var bytes=ByteBuffer.allocate(56);top.ifPresent(g->g.writeTo(bytes,0));
            String expected="CHOOSE "+(selected?1:0)+" TOP "+(top.isPresent()?1:0)+" "+HexFormat.of().formatHex(bytes.array());
            if(!expected.equals(actual)) {
              java.nio.file.Files.writeString(Path.of(".tools/item-oracle/choice-failure.txt"),trace);
              throw new AssertionError("game="+game+" trial="+trial+" expected "+expected+" actual "+actual);
            }
            checks++;
            for(var item:list) {
              String[] fields=output.readLine().split(" ");float actualTime=Float.parseFloat(fields[2]);
              if(Integer.parseInt(fields[1])!=item.number() || actualTime!=goals.avoidTime(handle,item.number()))throw new AssertionError("Avoid mismatch game="+game+" trial="+trial+" item="+item.number()+" java="+goals.avoidTime(handle,item.number())+" native="+actualTime);
              checks++;
            }
          }
        }
        if(process.waitFor()!=0)throw new AssertionError("Native selector observer failed");
      }
    }
    System.out.printf("Item choice PASS: 4,000 seeded LTG/NBG scenarios across four game types, %,d goal/timer checks; zero native mismatches with controlled routing/weights%n",checks);
  }
  static void cmd(java.io.Writer input,java.io.BufferedReader output,String command)throws Exception {trace.append(command).append('\n');input.write(command+"\n");input.flush();if(output.readLine()==null)throw new AssertionError("Native stopped at "+command);}
  static String text(Vec3 v){return (float)v.x()+" "+(float)v.y()+" "+(float)v.z()+" ";}
  static class Routes implements BotItemSelector.Routing {
    int area=100;int[][] cost=new int[1024][1024];
    public int reachableArea(Vec3 origin,int client){return area;}
    public boolean hasReachability(int area){return area>0;}
    public int travelTime(int area,Vec3 origin,int goal,int flags){return cost[area][goal];}
  }
  static VirtualFileSystem fixture(String source) {
    return new VirtualFileSystem() {
      public Optional<Origin> which(VirtualPath p){return Optional.of(new Origin("test","authored",false));}
      public List<VirtualPath> list(String directory){return List.of(new VirtualPath("weights.c"));}
      public List<Origin> searchOrder(){return List.of();}
      public byte[] read(VirtualPath p){return source.getBytes(StandardCharsets.ISO_8859_1);}
      public void close(){}
    };
  }
}
