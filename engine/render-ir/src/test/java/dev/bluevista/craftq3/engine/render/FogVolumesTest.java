package dev.bluevista.craftq3.engine.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Ported from the legacy {@code FogVolumesTest}. */
class FogVolumesTest {
  private static final double EPSILON = 1e-6;

  @Test
  void clipsOnlyTheRayLengthInsideEachFogBrush() {
    BspMap map = brushes(new float[][] {{0, -10, -10, 10, 10, 10}});
    FogVolumes fog = from(map, Map.of("fog/test0", shader("fog/test0", new Vec3f(1, 0, 0), 40)));
    assertEquals(1, fog.count());
    assertEquals(6, fog.volumes().getFirst().planeCount());
    assertEquals(0, fog.volumeForEffect(0));
    assertEquals(0.5, opacity(fog, -5, 15), EPSILON);
    assertEquals(Math.sqrt(5.0 / 40), opacity(fog, 5, 15), EPSILON);
    assertEquals(Math.sqrt(3.0 / 40), opacity(fog, 2, 5), EPSILON);
    assertEquals(0, opacity(fog, -5, -1));
    assertEquals(0, fog.sample(new Vec3f(-5, 20, 0), new Vec3f(15, 20, 0)).opacity());
    assertEquals(0, opacity(fog, 5, 5));
    float[] out = new float[4];
    fog.sample(-Double.MAX_VALUE, 0, 0, Double.MAX_VALUE, 0, 0, out, fog.newScratch());
    assertEquals(0, out[3], "overflowing segments are clear");
    assertThrows(UnsupportedOperationException.class, () -> fog.volumes().clear());
    assertEquals(0, fog.volumeContaining(new float[] {4, -1, -1, 6, 1, 1}, 0));
    assertEquals(-1, fog.volumeContaining(new float[] {40, -1, -1, 60, 1, 1}, 0));
  }

  @Test
  void compositesVolumesInEyeOrderAndReachesOpaqueDepth() {
    BspMap map = brushes(new float[][] {{0, -10, -10, 10, 10, 10}, {20, -10, -10, 30, 10, 10}});
    FogVolumes fog =
        from(
            map,
            Map.of(
                "fog/test0", shader("fog/test0", new Vec3f(1, 0, 0), 40),
                "fog/test1", shader("fog/test1", new Vec3f(0, 0, 1), 40)));
    FogVolumes.Sample forward = fog.sample(new Vec3f(-5, 0, 0), new Vec3f(35, 0, 0));
    assertEquals(0.75, forward.opacity(), EPSILON);
    assertEquals(2.0 / 3, forward.color().x(), EPSILON);
    assertEquals(1.0 / 3, forward.color().z(), EPSILON);
    FogVolumes.Sample backward = fog.sample(new Vec3f(35, 0, 0), new Vec3f(-5, 0, 0));
    assertEquals(1.0 / 3, backward.color().x(), EPSILON);
    assertEquals(2.0 / 3, backward.color().z(), EPSILON);
    FogVolumes dense = from(map, Map.of("fog/test0", shader("fog/test0", new Vec3f(1, 0, 0), 5)));
    assertEquals(1, dense.sample(new Vec3f(-5, 0, 0), new Vec3f(35, 0, 0)).opacity());
  }

  @Test
  void ignoresMissingShadersAndNonFogEffects() {
    BspMap map = brushes(new float[][] {{0, -10, -10, 10, 10, 10}});
    assertEquals(0, from(map, Map.of()).count());
    assertEquals(
        0, from(map, Map.of("fog/test0", ShaderDefinition.implicit("fog/test0", false))).count());
    assertEquals(0, FogVolumes.none().count());
    assertEquals(-1, FogVolumes.none().volumeForEffect(0));
  }

  private static double opacity(FogVolumes fog, float fromX, float toX) {
    return fog.sample(new Vec3f(fromX, 0, 0), new Vec3f(toX, 0, 0)).opacity();
  }

  private static FogVolumes from(BspMap map, Map<String, ShaderDefinition> shaders) {
    return FogVolumes.from(
        map, name -> Optional.ofNullable(shaders.get(ShaderDefinition.canonicalName(name))));
  }

  /** One box brush and one {@code fog/testN} effect per box. */
  static BspMap brushes(float[][] boxes) {
    TestBsp bsp = new TestBsp();
    bsp.texture("textures/test", 0);
    for (int i = 0; i < boxes.length; i++) {
      float[] b = boxes[i];
      int brush = bsp.box(new float[] {b[0], b[1], b[2]}, new float[] {b[3], b[4], b[5]});
      bsp.effect("fog/test" + i, brush);
    }
    bsp.model(new float[] {-100, -100, -100}, new float[] {100, 100, 100}, 0, 0, 0, boxes.length);
    return bsp.read();
  }

  private static ShaderDefinition shader(String name, Vec3f color, float depth) {
    return new ShaderDefinition(
        name,
        List.of(),
        ShaderDefinition.Cull.NONE,
        3,
        false,
        false,
        false,
        Set.of("fog"),
        Optional.empty(),
        Optional.of(new ShaderDefinition.Fog(color, depth)),
        List.of(),
        false,
        0);
  }
}
