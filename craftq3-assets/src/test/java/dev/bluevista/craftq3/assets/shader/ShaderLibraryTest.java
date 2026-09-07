package dev.bluevista.craftq3.assets.shader;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShaderLibraryTest {
  @TempDir Path root;

  @Test
  void resolvesCaseInsensitiveNamesAndHonorsVfsPriorityAcrossScriptNames() throws Exception {
    Files.createDirectories(root.resolve("baseq3/scripts"));
    Files.createDirectories(root.resolve("mod/scripts"));
    Files.writeString(
        root.resolve("baseq3/scripts/a.shader"),
        "textures/test { { map textures/base.tga } }\ntextures/onlybase { surfaceparm nodraw }");
    Files.writeString(
        root.resolve("mod/scripts/z.shader"), "Textures/Test { { map textures/mod.tga } }");
    Files.writeString(root.resolve("mod/scripts/bad.shader"), "broken { {");
    Files.writeString(root.resolve("mod/scripts/ignored.txt"), "not a shader");
    try (var fs = Pk3FileSystem.mount(root, "mod")) {
      var library = ShaderLibrary.load(fs);
      assertEquals(2, library.definitions().size());
      assertEquals(
          "textures/mod.tga",
          library
              .resolve("TEXTURES\\TEST.TGA", true)
              .stages()
              .getFirst()
              .texture()
              .frames()
              .getFirst());
      assertTrue(library.resolve("textures/onlybase", true).stages().isEmpty());
      assertTrue(library.find("textures/missing").isEmpty());
      assertEquals(2, library.resolve("textures/missing", true).stages().size());
      assertEquals(2, library.diagnostics().size());
      assertThrows(UnsupportedOperationException.class, () -> library.definitions().clear());
    }
  }
}
