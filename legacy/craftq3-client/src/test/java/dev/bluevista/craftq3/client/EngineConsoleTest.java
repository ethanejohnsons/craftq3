package dev.bluevista.craftq3.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.render.CgameFrame;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class EngineConsoleTest {
  @Test
  void carriesShaderTimeCharsetCoordinatesAndQuakeColorsInOwnedQuads() throws Exception {
    var console =
        new EngineConsole(
            new ClientTestData.Files(Map.of()), new ClientTestData.Audio(), text -> {});
    var frame = console.frame(List.of("^2Color"), "map", 3, 640, 480, 1234, 0);
    assertEquals(1234, frame.timeMillis());
    var background = (CgameFrame.Quad) frame.commands().getFirst();
    assertEquals("console", background.shader());
    assertEquals(240, background.height());
    var letter =
        frame.commands().stream()
            .filter(CgameFrame.Quad.class::isInstance)
            .map(CgameFrame.Quad.class::cast)
            .filter(q -> q.rgba() == 0x00ff00ff)
            .findFirst()
            .orElseThrow();
    assertEquals("gfx/2d/bigchars", letter.shader());
    assertEquals(3 / 16f, letter.s1());
    assertEquals(4 / 16f, letter.t1());
    assertTrue(frame.assets().shaders().containsKey("console"));
    assertFalse(frame.assets().shaders().isEmpty());
  }

  @Test
  void wrappingScrollingAndTinyViewportsRemainBounded() throws Exception {
    var console =
        new EngineConsole(
            new ClientTestData.Files(Map.of()), new ClientTestData.Audio(), text -> {});
    var lines = java.util.Collections.nCopies(256, "^1" + "x".repeat(2048));
    for (int width : new int[] {1, 640, 32768}) {
      var frame = console.frame(lines, "x".repeat(1024), 1024, width, 960, 400, 8);
      assertTrue(frame.commands().size() < 16384);
      assertTrue(frame.commands().stream().allMatch(CgameFrame.Quad.class::isInstance));
    }
    assertThrows(
        IllegalArgumentException.class, () -> console.frame(List.of(), "x", 2, 640, 480, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> console.frame(List.of(), "x".repeat(1025), 0, 640, 480, 0, 0));
  }
}
