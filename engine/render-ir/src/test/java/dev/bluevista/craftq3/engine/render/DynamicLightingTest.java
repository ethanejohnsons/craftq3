package dev.bluevista.craftq3.engine.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Ported from the legacy {@code DynamicLightingTest}. */
class DynamicLightingTest {
  private static final Vec3f UP = new Vec3f(0, 0, 1);

  @Test
  void lightFallsToZeroAtRadiusAndRejectsBackFacingNormals() {
    DynamicLight light = new DynamicLight(new Vec3f(0, 0, 5), 10, new Vec3f(1, 0.5f, 0));
    assertEquals(
        new Vec3f(0.75f, 0.375f, 0), DynamicLighting.sample(List.of(light), Vec3f.ZERO, UP));
    assertEquals(Vec3f.ZERO, DynamicLighting.sample(List.of(light), new Vec3f(0, 0, -5), UP));
    assertEquals(Vec3f.ZERO, DynamicLighting.sample(List.of(light), Vec3f.ZERO, UP.negate()));
    assertEquals(
        light.color(), DynamicLighting.sample(List.of(light), light.origin(), new Vec3f(0, 0, 2)));
  }

  @Test
  void multipleLightsAddAndInvalidLightsAreRejected() {
    DynamicLight light = new DynamicLight(new Vec3f(0, 0, 1), 2, new Vec3f(1, 1, 1));
    assertEquals(
        new Vec3f(1.5f, 1.5f, 1.5f), DynamicLighting.sample(List.of(light, light), Vec3f.ZERO, UP));
    float[] ambient = {0.1f, 0.2f, 0.3f, 0, 0, 0, 0, 0, 1};
    DynamicLighting.addedToAmbient(List.of(light)).sample(0, 0, 0, 0, 0, 1, ambient);
    assertEquals(0.85f, ambient[0], 1e-6);
    assertEquals(0.95f, ambient[1], 1e-6);
    assertThrows(
        IllegalArgumentException.class, () -> new DynamicLight(Vec3f.ZERO, 0, new Vec3f(1, 1, 1)));
    assertThrows(
        IllegalArgumentException.class, () -> new DynamicLight(Vec3f.ZERO, 10, new Vec3f(2, 1, 1)));
  }
}
