package dev.bluevista.craftq3.engine.formats;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.fs.SearchPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileViewAssetsTest {
  @Test
  void readsAndListsThroughTheSearchPath(@TempDir Path root) throws Exception {
    Path scripts = Files.createDirectories(root.resolve("baseq3/scripts"));
    Files.writeString(scripts.resolve("base.shader"), "textures/a { }");
    try (SearchPath path = SearchPath.mount(root, "baseq3")) {
      AssetLookup assets = new FileViewAssets(path.view());
      assertArrayEquals(
          "textures/a { }".getBytes(StandardCharsets.ISO_8859_1),
          assets.read("Scripts\\BASE.shader").orElseThrow());
      assertEquals(List.of("scripts/base.shader"), assets.list("scripts", "shader"));
      assertTrue(assets.exists("scripts/base.shader"));
      assertFalse(assets.exists("../escape.shader"));
      assertTrue(assets.read("scripts/missing.shader").isEmpty());
    }
  }
}
