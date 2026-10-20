package dev.bluevista.craftq3.botlib.item;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.botlib.script.ScriptException;
import dev.bluevista.craftq3.botlib.script.ScriptSources;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ItemConfigTest {
  @Test
  void namedMetadataMacrosVectorsAndDefaults() throws Exception {
    var config =
        load(
            """
        #define SLOT 9
        iteminfo "test_item" {
          name "Test item" model "models/test.md3" modelindex 17 type 2 index SLOT
          respawntime 23.5 mins {-8, -9, -10} maxs {8, 9, 10}
        }
        iteminfo "empty" {}
        """);
    var item = config.items().getFirst();
    assertEquals(
        new ItemInfo(
            "test_item",
            "Test item",
            "models/test.md3",
            17,
            2,
            9,
            23.5f,
            new Vec3(-8, -9, -10),
            new Vec3(8, 9, 10),
            0),
        item);
    assertEquals(1, config.items().get(1).number());
    assertEquals(0, config.items().get(1).respawnTime());
    assertTrue(config.first("missing").isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> config.items().clear());
  }

  @Test
  void duplicateDeclarationsRetainOrderAndFieldsUseLastValue() throws Exception {
    var config = load("iteminfo \"same\" { index 1 index 2 } iteminfo \"same\" {index 3}");
    assertEquals(2, config.items().size());
    assertEquals(2, config.first("same").orElseThrow().inventoryIndex());
    assertEquals(3, config.items().get(1).inventoryIndex());
    assertTrue(config.first("SAME").isEmpty());
  }

  @Test
  void structureStringsTruncateAndShortVectorsZeroFill() throws Exception {
    var config = load("iteminfo \"short\" { name \"" + "x".repeat(90) + "\" mins {-1} maxs {} }");
    assertEquals(79, config.items().getFirst().name().length());
    assertEquals(new Vec3(-1, 0, 0), config.items().getFirst().mins());
    assertEquals(new Vec3(0, 0, 0), config.items().getFirst().maxs());
    assertEquals(1, config.diagnostics().size());
  }

  @Test
  void malformedAndOversizedDeclarationsFailAndReleaseSourceHandles() throws Exception {
    for (String text :
        List.of(
            "iteminfo \"x\" {",
            "iteminfo x {}",
            "iteminfo \"x\" { bogus 1 }",
            "iteminfo \"x\" { index 1.5 }",
            "iteminfo \"x\" { maxs {1,2,3,4} }",
            "iteminfo \"x\" { mins {1,0,0} }",
            "iteminfo \"" + "c".repeat(32) + "\" {}",
            "iteminfo \"x\" {}\n".repeat(257))) {
      try (var source = sources(text)) {
        assertThrows(
            ScriptException.class, () -> ItemConfig.load(source, "botfiles/items.c"), text);
        assertEquals(0, source.openCount());
      }
    }
  }

  @Test
  void declarationLimitIsInclusiveAndNegativeScalarsStayData() throws Exception {
    assertEquals(256, load("iteminfo \"x\" {}\n".repeat(256)).items().size());
    var item =
        load("iteminfo \"negative\" { type -1 index -2 modelindex -3 respawntime -4.5 }")
            .items()
            .getFirst();
    assertEquals(-1, item.type());
    assertEquals(-2, item.inventoryIndex());
    assertEquals(-3, item.modelIndex());
    assertEquals(-4.5f, item.respawnTime());
  }

  private static ItemConfig load(String text) throws Exception {
    try (var source = sources(text)) {
      return ItemConfig.load(source, "botfiles/items.c");
    }
  }

  private static ScriptSources sources(String text) {
    Map<String, byte[]> files =
        Map.of("botfiles/items.c", text.getBytes(StandardCharsets.ISO_8859_1));
    return new ScriptSources(
        new VirtualFileSystem() {
          @Override
          public Optional<Origin> which(VirtualPath path) {
            return files.containsKey(path.value())
                ? Optional.of(new Origin("baseq3", "authored-item-fixture", false))
                : Optional.empty();
          }

          @Override
          public List<VirtualPath> list(String path) {
            return List.of();
          }

          @Override
          public List<Origin> searchOrder() {
            return List.of();
          }

          @Override
          public byte[] read(VirtualPath path) throws NoSuchFileException {
            byte[] bytes = files.get(path.value());
            if (bytes == null) throw new NoSuchFileException(path.value());
            return bytes.clone();
          }

          @Override
          public void close() {}
        });
  }
}
