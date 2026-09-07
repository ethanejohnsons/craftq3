package dev.bluevista.craftq3.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.image.Q3Image;
import java.util.*;
import org.junit.jupiter.api.Test;

class CinematicImageTest {
  private static Q3Image pixels(int width, int height) {
    return new Q3Image(width, height, new byte[width * height * 4]);
  }

  @Test
  void preservesOpaqueFrameGeometryUvAndSubmissionOrder() {
    var movie = new CgameFrame.Image(42, 13, 17, 512, 256, pixels(2, 2));
    var overlay = new CgameFrame.Quad(0, 0, 5, 5, 0, 0, 1, 1, "$whiteimage", -1);
    var frame = new CgameFrame(List.of(overlay, movie, overlay), SceneAssets.EMPTY, 100);
    assertSame(movie, frame.commands().get(1));
    var batch = SubmittedMaterials.image(movie).getFirst();
    assertEquals("$cinematic/42", batch.texture());
    assertTrue(batch.overlay());
    assertTrue(batch.stage().blend().opaque());
    assertEquals(6, batch.vertices().size());
    assertEquals(13, batch.bounds().min().x());
    assertEquals(17, batch.bounds().min().y());
    assertEquals(525, batch.bounds().max().x());
    assertEquals(273, batch.bounds().max().y());
    assertEquals(0, batch.vertices().getFirst().textureUv().u());
    assertEquals(0, batch.vertices().getFirst().textureUv().v());
    assertEquals(1, batch.vertices().get(2).textureUv().u());
    assertEquals(1, batch.vertices().get(2).textureUv().v());
    assertTrue(batch.vertices().stream().allMatch(v -> v.rgba() == -1));
  }

  @Test
  void rejectsInvalidExtentsAndConflictingSnapshots() {
    var image = pixels(1, 1);
    assertThrows(IllegalArgumentException.class, () -> new CgameFrame.Image(-1, 0, 0, 1, 1, image));
    assertThrows(
        IllegalArgumentException.class, () -> new CgameFrame.Image(0, Float.NaN, 0, 1, 1, image));
    assertThrows(IllegalArgumentException.class, () -> new CgameFrame.Image(0, 0, 0, 0, 1, image));
    var a = new CgameFrame.Image(1, 0, 0, 1, 1, image);
    var b = new CgameFrame.Image(1, 2, 2, 1, 1, image);
    assertDoesNotThrow(() -> new CgameFrame(List.of(a, b), SceneAssets.EMPTY));
    var changed = new CgameFrame.Image(1, 0, 0, 1, 1, pixels(1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CgameFrame(List.of(a, changed), SceneAssets.EMPTY));
  }

  @Test
  void boundsDistinctGpuImagesWhileAllowingRepeatedDraws() {
    var commands = new ArrayList<CgameFrame.Command>();
    var tiny = pixels(1, 1);
    for (int i = 0; i < 33; i++) commands.add(new CgameFrame.Image(i, 0, 0, 1, 1, tiny));
    assertThrows(IllegalArgumentException.class, () -> new CgameFrame(commands, SceneAssets.EMPTY));
    commands.clear();
    var large = pixels(2048, 2048);
    for (int i = 0; i < 4; i++) commands.add(new CgameFrame.Image(i, 0, 0, 1, 1, large));
    assertDoesNotThrow(() -> new CgameFrame(commands, SceneAssets.EMPTY));
    commands.add(new CgameFrame.Image(0, 0, 0, 1, 1, large));
    assertDoesNotThrow(() -> new CgameFrame(commands, SceneAssets.EMPTY));
    commands.add(new CgameFrame.Image(4, 0, 0, 1, 1, large));
    assertThrows(IllegalArgumentException.class, () -> new CgameFrame(commands, SceneAssets.EMPTY));
  }
}
