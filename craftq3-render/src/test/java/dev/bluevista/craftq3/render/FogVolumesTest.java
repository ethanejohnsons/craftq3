package dev.bluevista.craftq3.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.shader.ShaderDefinition;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FogVolumesTest {
  @Test
  void clipsOnlyTheRayLengthInsideEachFogBrush() throws Exception {
    var map = RenderFixtures.brushes(List.of(RenderFixtures.box(0, -10, -10, 10, 10, 10)));
    var fog = FogVolumes.from(map, Map.of("fog/test0", shader("fog/test0", new Vec3(1, 0, 0), 40)));
    assertEquals(1, fog.count());
    assertEquals(6, fog.volumes().getFirst().planes().size());
    assertEquals(0.5, fog.sample(new Vec3(-5, 0, 0), new Vec3(15, 0, 0)).opacity(), 1e-6);
    assertEquals(
        Math.sqrt(5.0 / 40), fog.sample(new Vec3(5, 0, 0), new Vec3(15, 0, 0)).opacity(), 1e-6);
    assertEquals(
        Math.sqrt(3.0 / 40), fog.sample(new Vec3(2, 0, 0), new Vec3(5, 0, 0)).opacity(), 1e-6);
    assertEquals(0, fog.sample(new Vec3(-5, 0, 0), new Vec3(-1, 0, 0)).opacity());
    assertEquals(0, fog.sample(new Vec3(-5, 20, 0), new Vec3(15, 20, 0)).opacity());
    assertEquals(0, fog.sample(new Vec3(5, 0, 0), new Vec3(5, 0, 0)).opacity());
    assertEquals(
        0,
        fog.sample(new Vec3(-Double.MAX_VALUE, 0, 0), new Vec3(Double.MAX_VALUE, 0, 0)).opacity());
    assertThrows(UnsupportedOperationException.class, () -> fog.volumes().clear());
    assertThrows(
        UnsupportedOperationException.class, () -> fog.volumes().getFirst().planes().clear());
  }

  @Test
  void compositesVolumesInEyeOrderAndReachesOpaqueDepth() throws Exception {
    var map =
        RenderFixtures.brushes(
            List.of(
                RenderFixtures.box(0, -10, -10, 10, 10, 10),
                RenderFixtures.box(20, -10, -10, 30, 10, 10)));
    var fog =
        FogVolumes.from(
            map,
            Map.of(
                "fog/test0", shader("fog/test0", new Vec3(1, 0, 0), 40),
                "fog/test1", shader("fog/test1", new Vec3(0, 0, 1), 40)));
    var forward = fog.sample(new Vec3(-5, 0, 0), new Vec3(35, 0, 0));
    assertEquals(0.75, forward.opacity(), 1e-6);
    assertEquals(2.0 / 3, forward.color().x(), 1e-6);
    assertEquals(1.0 / 3, forward.color().z(), 1e-6);
    var backward = fog.sample(new Vec3(35, 0, 0), new Vec3(-5, 0, 0));
    assertEquals(1.0 / 3, backward.color().x(), 1e-6);
    assertEquals(2.0 / 3, backward.color().z(), 1e-6);
    var dense =
        FogVolumes.from(map, Map.of("fog/test0", shader("fog/test0", new Vec3(1, 0, 0), 5)));
    assertEquals(1, dense.sample(new Vec3(-5, 0, 0), new Vec3(35, 0, 0)).opacity());
  }

  @Test
  void ignoresMissingShadersAndNonFogEffects() throws Exception {
    var map = RenderFixtures.brushes(List.of(RenderFixtures.box(0, -10, -10, 10, 10, 10)));
    assertEquals(0, FogVolumes.from(map, Map.of()).count());
    assertEquals(
        0,
        FogVolumes.from(map, Map.of("fog/test0", ShaderDefinition.implicit("fog/test0", false)))
            .count());
    assertEquals(0, FogVolumes.from(null, Map.of()).count());
  }

  private static ShaderDefinition shader(String name, Vec3 color, float depth) {
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
