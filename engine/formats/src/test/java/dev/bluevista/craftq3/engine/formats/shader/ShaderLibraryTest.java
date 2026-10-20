package dev.bluevista.craftq3.engine.formats.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.formats.MemoryAssets;
import org.junit.jupiter.api.Test;

class ShaderLibraryTest {
  /** A mod's script listed first (highest priority), then the base game's. */
  private static MemoryAssets searchPath() {
    return new MemoryAssets()
        .put("scripts/z.shader", "Textures/Test { { map textures/mod.tga } }")
        .put("scripts/bad.shader", "broken { {\n")
        .put("scripts/ignored.txt", "not a shader")
        .put(
            "scripts/a.shader",
            "textures/test { { map textures/base.tga } }\n"
                + "textures/onlybase { surfaceparm nodraw }\n"
                + "textures/onlybase { surfaceparm sky }");
  }

  @Test
  void theFileListedLastWinsAcrossFilesAndTheFirstDefinitionWinsWithinOne() {
    ShaderLibrary library = ShaderLibrary.load(searchPath());
    assertEquals(2, library.definitions().size());
    // Combined text is in reverse listing order, so the base script's definition is found first.
    assertEquals(
        "textures/base.tga",
        library
            .resolve("TEXTURES\\TEST.TGA", true)
            .stages()
            .getFirst()
            .texture()
            .frames()
            .getFirst());
    assertEquals("scripts/a.shader", library.scriptOf("textures/test").orElseThrow());
    assertTrue(library.resolve("textures/onlybase", true).noDraw());
    assertTrue(library.find("textures/missing").isEmpty());
    assertEquals(2, library.resolve("textures/missing", true).stages().size());
    assertEquals(1, library.resolve("textures/missing", false).stages().size());
    assertThrows(UnsupportedOperationException.class, () -> library.definitions().clear());
  }

  @Test
  void aDamagedShaderIsSkippedWithoutLosingTheRestOfItsFile() {
    ShaderLibrary library =
        ShaderLibrary.load(
            new MemoryAssets()
                .put(
                    "scripts/mixed.shader",
                    "textures/good1 { surfaceparm nodraw }\n"
                        + "textures/bad { { { } } }\n"
                        + "textures/good2 { { map $whiteimage } }\n"));
    assertEquals(2, library.definitions().size());
    assertTrue(library.find("textures/good2").isPresent());
    assertTrue(library.find("textures/bad").isEmpty());
    assertTrue(
        library.diagnostics().stream().anyMatch(d -> d.message().startsWith("malformed shader")));
  }

  @Test
  void duplicateDiagnosticsNameTheWinningScript() {
    ShaderLibrary library = ShaderLibrary.load(searchPath());
    assertTrue(
        library.diagnostics().stream()
            .anyMatch(
                d ->
                    d.source().equals("scripts/z.shader")
                        && d.message().contains("scripts/a.shader takes precedence")));
  }
}
