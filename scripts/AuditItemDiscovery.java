import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.botlib.item.*;
import dev.bluevista.craftq3.botlib.script.ScriptSources;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Native map-item semantics with identical controlled placement callbacks; not an AAS physics audit. */
class AuditItemDiscovery {
  public static void main(String[] args)throws Exception {
    Path install=Path.of(args.length>0?args[0]:"run/craftq3/games");
    Path oracle=Path.of(args.length>1?args[1]:".tools/item-oracle/item-oracle");
    Path pk3=install.resolve("baseq3/pak0.pk3");
    int maps=0,levels=0,queries=0;
    try(var fs=Pk3FileSystem.mount(install,"baseq3");var scripts=new ScriptSources(fs)) {
      var config=ItemConfig.load(scripts,"botfiles/items.c");
      for(var path:fs.list("maps")) {
        if(!path.value().endsWith(".bsp")||!fs.which(path).orElseThrow().archive())continue;
        var entities=BspReader.read(fs.read(path)).entities();
        for(int gameType:new int[]{0,2,3,4}) {
          var registry=new ItemRegistry(config,(info,origin,suspended)->{
            if(suspended)return Optional.of(new ItemRegistry.Placement(origin,origin,9));
            var dropped=new Vec3(origin.x(),origin.y(),(float)origin.z()-3f);
            return Optional.of(new ItemRegistry.Placement(dropped,new Vec3(dropped.x(),dropped.y(),(float)dropped.z()+.5f),7));
          },message->{});
          registry.initialize(entities);
          var expected=new ArrayList<String>();
          for(var item:registry.items()) {
            expected.add("LEVEL "+HexFormat.of().formatHex(levelBytes(item)));
            expected.add("AVOID "+item.number()+" "+String.format(java.util.Locale.ROOT,"%.9g",registry.automaticAvoidDuration(item.number()).orElseThrow()));
            levels++;
          }
          for(var info:config.items()) {
            int cursor=-1;
            while(true) {
              var next=registry.nextGoal(cursor,info.name(),gameType);if(next.isEmpty())break;
              var goal=next.orElseThrow();cursor=goal.number();var bytes=ByteBuffer.allocate(56);goal.writeTo(bytes,0);
              expected.add("QUERY "+info.classname()+" "+cursor+" "+HexFormat.of().formatHex(bytes.array()));queries++;
            }
          }
          var process=new ProcessBuilder(oracle.toAbsolutePath().toString(),pk3.toAbsolutePath().toString(),"items.c","0")
              .redirectError(ProcessBuilder.Redirect.DISCARD).start();
          try(var input=process.outputWriter(StandardCharsets.US_ASCII)) {
            input.write(gameType+" "+entities.size()+"\n");
            for(var entity:entities) {
              input.write(entity.size()+"\n");
              for(var entry:entity.entrySet())input.write(hex(entry.getKey())+" "+hex(entry.getValue())+"\n");
            }
          }
          try(var output=process.inputReader(StandardCharsets.US_ASCII)) {
            for(String wanted:expected) {
              String actual=output.readLine();
              if(wanted.startsWith("AVOID ")) {
                String[] a=actual.split(" "),b=wanted.split(" ");
                if(!a[0].equals(b[0])||!a[1].equals(b[1])||Float.parseFloat(a[2])!=Float.parseFloat(b[2]))throw new AssertionError(path+" avoid differs");
              } else if(!wanted.equals(actual))throw new AssertionError(path+" type"+gameType+" expected"+wanted+" got"+actual);
            }
            if(output.readLine()!=null)throw new AssertionError(path+" extra native item data");
          }
          if(process.waitFor()!=0)throw new AssertionError("Native discovery failed");
        }
        maps++;
      }
    }
    System.out.printf("Item discovery PASS: %d original maps x4 game types, %d item records, %d goal queries; zero native metadata/order/filter mismatches with controlled placement%n",maps,levels,queries);
  }
  static byte[] levelBytes(ItemRegistry.LevelItem item) {
    var bytes=ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN);
    bytes.putInt(0,item.number()).putInt(4,item.info().number()).putInt(8,item.flags()).putFloat(12,item.weight());
    vector(bytes,16,item.placement().origin());bytes.putInt(28,item.placement().area());
    vector(bytes,32,item.placement().goalOrigin());bytes.putInt(44,item.entity()).putFloat(48,item.timeout());return bytes.array();
  }
  static void vector(ByteBuffer bytes,int offset,Vec3 value) {bytes.putFloat(offset,(float)value.x()).putFloat(offset+4,(float)value.y()).putFloat(offset+8,(float)value.z());}
  static String hex(String text) {return text.isEmpty()?"-":HexFormat.of().formatHex(text.getBytes(StandardCharsets.ISO_8859_1));}
}
