import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.render.BspSceneBuilder;
import dev.bluevista.craftq3.render.MaterialLibrary;
import java.nio.file.Path;

/** Optional local-data audit. Reads user assets in place and never copies them into the repository. */
public class AuditAssets {
  public static void main(String[] args) throws Exception {
    if (args.length < 1 || args.length > 2) {
      throw new IllegalArgumentException("Usage: AuditAssets <game-installation-directory> [game]");
    }
    int passed = 0;
    int failed = 0;
    try (var fs = Pk3FileSystem.mount(Path.of(args[0]), args.length == 2 ? args[1] : "baseq3")) {
      for (VirtualPath path : fs.list("maps")) {
        if (!path.value().endsWith(".bsp")) continue;
        String name = path.value().substring("maps/".length(), path.value().length() - 4);
        try {
          var bsp = BspReader.read(fs.read(path));
          var scene = BspSceneBuilder.build(name, bsp, 8);
          var materials = MaterialLibrary.load(fs, scene);
          long missing = materials.diagnostics().stream().filter(d -> d.startsWith("Missing texture:")).count();
          System.out.printf(
              "%s: surfaces=%d triangles=%d textures=%d lightmaps=%d missing=%d diagnostics=%d%n",
              name,
              scene.surfaces().size(),
              scene.triangles().size(),
              materials.images().size(),
              materials.lightmaps().size(),
              missing,
              materials.diagnostics().size());
          passed++;
        } catch (Exception failure) {
          System.out.printf("%s: FAILED %s: %s%n", name, failure.getClass().getSimpleName(), failure.getMessage());
          failed++;
        }
      }
    }
    System.out.printf("Asset audit: %d maps passed; %d failed%n", passed, failed);
    if (failed != 0 || passed == 0) System.exit(1);
  }
}
