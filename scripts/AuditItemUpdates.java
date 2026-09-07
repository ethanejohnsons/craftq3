import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.botlib.item.*;
import dev.bluevista.craftq3.botlib.script.ScriptSources;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Seeded live pickup association oracle; identical controlled goal placement on both sides. */
class AuditItemUpdates {
  public static void main(String[] args)throws Exception {
    Path install=Path.of(args.length>0?args[0]:"run/craftq3/games");
    Path oracle=Path.of(args.length>1?args[1]:".tools/item-oracle/item-oracle");
    var random=new Random(0x172345); int checks=0;
    try(var fs=Pk3FileSystem.mount(install,"baseq3");var scripts=new ScriptSources(fs)) {
      var config=ItemConfig.load(scripts,"botfiles/items.c");
      for(int trial=0;trial<250;trial++) {
        var map=new ArrayList<Map<String,String>>();
        var initial=new ArrayList<ItemInfo>();
        for(int i=0;i<6;i++) {
          ItemInfo info=config.items().get(random.nextInt(config.items().size()));initial.add(info);
          map.add(Map.of("classname",info.classname(),"origin",i*100+" 0 64"));
        }
        var registry=new ItemRegistry(config,(info,origin,suspended)->Optional.of(place(new Vec3(origin.x(),origin.y(),origin.z()-3))),s->{});
        registry.initialize(map);
        var input=new StringBuilder("0 "+map.size()+"\n");
        for(var e:map) {input.append(e.size()).append('\n');for(var entry:e.entrySet())input.append(hex(entry.getKey())).append(' ').append(hex(entry.getValue())).append('\n');}
        for(int step=0;step<6;step++) {
          var entities=new ArrayList<ItemRegistry.WorldEntity>();
          for(int i=0;i<6;i++) {
            if(random.nextInt(4)==0)continue;
            int slot=random.nextInt(6);
            int model=random.nextInt(5)==0?999:initial.get(slot).modelIndex();
            var origin=new Vec3(slot*100+random.nextInt(71)-35,0,61);
            entities.add(new ItemRegistry.WorldEntity(40+i,random.nextInt(6)==0?1:2,random.nextBoolean()?0:128,model,origin,origin));
          }
          input.append(entities.size()).append('\n');
          for(var entity:entities)input.append(entity.number()).append(' ').append(entity.type()).append(' ').append(entity.flags()).append(' ').append(entity.modelIndex()).append(' ').append(entity.origin().x()).append(' ').append(entity.origin().y()).append(' ').append(entity.origin().z()).append('\n');
          registry.update(5+step,entities,(info,origin)->place(origin));
        }
        var process=new ProcessBuilder(oracle.toAbsolutePath().toString(),install.resolve("baseq3/pak0.pk3").toAbsolutePath().toString(),"items.c","0","live")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try(var writer=process.outputWriter(StandardCharsets.US_ASCII)){writer.write(input.toString());}
        try(var output=process.inputReader(StandardCharsets.US_ASCII)) {
          for(var item:registry.items()) {
            String expected="LEVEL "+HexFormat.of().formatHex(levelBytes(item)),actual=output.readLine();
            if(!expected.equals(actual))throw new AssertionError("trial"+trial+" expected"+expected+" got"+actual);
            String[] avoid=output.readLine().split(" ");
            if(!avoid[0].equals("AVOID")||Integer.parseInt(avoid[1])!=item.number()||Float.parseFloat(avoid[2])!=registry.automaticAvoidDuration(item.number()).orElseThrow())throw new AssertionError("Avoid metadata");
            checks++;
          }
          for(var info:config.items()) {
            int cursor=-1;
            while(true) {
              var next=registry.nextGoal(cursor,info.name(),0);if(next.isEmpty())break;
              var goal=next.orElseThrow();cursor=goal.number();var bytes=ByteBuffer.allocate(56);goal.writeTo(bytes,0);
              String expected="QUERY "+info.classname()+" "+cursor+" "+HexFormat.of().formatHex(bytes.array()),actual=output.readLine();
              if(!expected.equals(actual))throw new AssertionError("trial"+trial+" query mismatch");checks++;
            }
          }
          if(output.readLine()!=null)throw new AssertionError("trial"+trial+" extra native item data");
        }
        if(process.waitFor()!=0)throw new AssertionError("Native item update failure");
      }
    }
    System.out.printf("Live item association PASS: 250 seeded scenarios/1500 frames, %d metadata/goal checks; zero native mismatches with controlled placement%n",checks);
  }
  static ItemRegistry.Placement place(Vec3 origin) {return new ItemRegistry.Placement(origin,new Vec3(origin.x(),origin.y(),(float)origin.z()+.5f),7);}
  static byte[] levelBytes(ItemRegistry.LevelItem item) {
    var bytes=ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN);
    bytes.putInt(0,item.number()).putInt(4,item.info().number()).putInt(8,item.flags()).putFloat(12,item.weight());
    vector(bytes,16,item.placement().origin());bytes.putInt(28,item.placement().area());
    vector(bytes,32,item.placement().goalOrigin());bytes.putInt(44,item.entity()).putFloat(48,item.timeout());return bytes.array();
  }
  static void vector(ByteBuffer bytes,int offset,Vec3 value) {bytes.putFloat(offset,(float)value.x()).putFloat(offset+4,(float)value.y()).putFloat(offset+8,(float)value.z());}
  static String hex(String text) {return text.isEmpty()?"-":HexFormat.of().formatHex(text.getBytes(StandardCharsets.ISO_8859_1));}
}
