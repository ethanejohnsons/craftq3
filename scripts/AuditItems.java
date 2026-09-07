import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.botlib.item.ItemConfig;
import dev.bluevista.craftq3.botlib.item.ItemInfo;
import dev.bluevista.craftq3.botlib.script.ScriptSources;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Compares user item metadata with the independent observation of native LoadItemConfig. */
class AuditItems {
  public static void main(String[] args) throws Exception {
    Path install = Path.of(args.length > 0 ? args[0] : "run/craftq3/games");
    Path oracle = Path.of(args.length > 1 ? args[1] : ".tools/item-oracle/item-oracle");
    Path pk3 = Path.of(args.length > 2 ? args[2] : install.resolve("baseq3/pak0.pk3").toString());
    try (var fs = Pk3FileSystem.mount(install,"baseq3"); var sources = new ScriptSources(fs)) {
      var config = ItemConfig.load(sources,"botfiles/items.c");
      var process = new ProcessBuilder(oracle.toAbsolutePath().toString(),pk3.toAbsolutePath().toString(),"items.c")
          .redirectError(ProcessBuilder.Redirect.INHERIT).start();
      try (var output = process.inputReader(StandardCharsets.US_ASCII)) {
        if (!output.readLine().equals("ITEMS " + config.items().size())) throw new AssertionError("Native item count");
        for (ItemInfo item : config.items()) {
          String nativeBytes = output.readLine();
          String javaBytes = HexFormat.of().formatHex(bytes(item));
          if (!javaBytes.equals(nativeBytes)) throw new AssertionError("Metadata differs for " + item.classname());
        }
        if (output.readLine() != null) throw new AssertionError("Extra native item data");
      }
      if (process.waitFor() != 0) throw new AssertionError("Native item oracle failed");
      System.out.printf("Item metadata PASS: %d original declarations, %d bytes exact; zero native mismatches%n",
          config.items().size(),236*config.items().size());
    }
  }
  static byte[] bytes(ItemInfo item) {
    var bytes = ByteBuffer.allocate(236).order(ByteOrder.LITTLE_ENDIAN);
    string(bytes,0,32,item.classname()); string(bytes,32,80,item.name()); string(bytes,112,80,item.model());
    bytes.putInt(192,item.modelIndex()).putInt(196,item.type()).putInt(200,item.inventoryIndex()).putFloat(204,item.respawnTime());
    vector(bytes,208,item.mins()); vector(bytes,220,item.maxs()); bytes.putInt(232,item.number());
    return bytes.array();
  }
  static void string(ByteBuffer bytes,int offset,int capacity,String value) {
    byte[] string = value.getBytes(StandardCharsets.ISO_8859_1);
    if (string.length >= capacity) throw new AssertionError("Field bound");
    bytes.position(offset); bytes.put(string);
  }
  static void vector(ByteBuffer bytes,int offset,Vec3 value) {
    bytes.putFloat(offset,(float)value.x()).putFloat(offset+4,(float)value.y()).putFloat(offset+8,(float)value.z());
  }
}
