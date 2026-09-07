import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.botlib.script.ScriptSources;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.nio.file.Path;
import java.util.Set;

/** Optional read-only audit. Commercial script source remains in the user's own installation. */
class AuditBotScripts {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("AuditBotScripts <installation directory>");
    Set<String> templates = Set.of("botfiles/fw_items.c", "botfiles/fw_weap.c");
    int passed = 0, failed = 0, itemCallers = 0, weaponCallers = 0, includeTemplates = 0;
    long tokens = 0;
    try (var fs = Pk3FileSystem.mount(Path.of(args[0]), "baseq3"); var scripts = new ScriptSources(fs)) {
      for (VirtualPath path : fs.list("botfiles")) {
        if (!path.value().endsWith(".c") && !path.value().endsWith(".h")) continue;
        if (templates.contains(path.value())) { includeTemplates++; continue; }
        int handle = 0;
        try {
          handle = scripts.load(path.value());
          while (scripts.read(handle).isPresent()) tokens++;
          passed++;
          String original = fs.readText(path);
          if (original.contains("\"fw_items.c\"")) itemCallers++;
          if (original.contains("\"fw_weap.c\"")) weaponCallers++;
        } catch (Exception error) {
          failed++;
          System.out.println(path.value() + ": " + error.getMessage());
        } finally {
          if (handle != 0) scripts.free(handle);
        }
      }
      if (scripts.openCount() != 0) throw new AssertionError("Source handles leaked during audit");
    }
    System.out.printf("Standalone sources passed=%d failed=%d tokens=%d; include-only templates=%d; "
        + "successful item/weapon template callers=%d/%d%n", passed, failed, tokens, includeTemplates, itemCallers, weaponCallers);
    if (failed != 0 || passed == 0 || includeTemplates > 0 && (itemCallers == 0 || weaponCallers == 0))
      throw new AssertionError("Bot script corpus audit failed or template coverage is absent");
  }
}
