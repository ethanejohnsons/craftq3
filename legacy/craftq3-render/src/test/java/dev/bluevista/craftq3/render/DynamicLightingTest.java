package dev.bluevista.craftq3.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

class DynamicLightingTest {
  @Test
  void lightFallsToZeroAtRadiusAndRejectsBackFacingNormals() {
    var light = new RenderScene.DynamicLight(new Vec3(0, 0, 5), 10, new Vec3(1, 0.5, 0));
    assertEquals(
        new Vec3(0.75, 0.375, 0),
        DynamicLighting.sample(List.of(light), vertex(new Vec3(0, 0, 0), new Vec3(0, 0, 1))));
    assertEquals(
        new Vec3(0, 0, 0),
        DynamicLighting.sample(List.of(light), vertex(new Vec3(0, 0, -5), new Vec3(0, 0, 1))));
    assertEquals(
        new Vec3(0, 0, 0),
        DynamicLighting.sample(List.of(light), vertex(new Vec3(0, 0, 0), new Vec3(0, 0, -1))));
    assertEquals(
        light.color(),
        DynamicLighting.sample(List.of(light), vertex(light.origin(), new Vec3(0, 0, 2))));
  }

  @Test
  void multipleLightsAddAndSceneCameraUpdatesKeepImmutableLights() {
    var light = new RenderScene.DynamicLight(new Vec3(0, 0, 1), 2, new Vec3(1, 1, 1));
    var lights = List.of(light, light);
    assertEquals(
        new Vec3(1.5, 1.5, 1.5),
        DynamicLighting.sample(lights, vertex(new Vec3(0, 0, 0), new Vec3(0, 0, 1))));
    var camera = new RenderScene.Camera(new Vec3(0, 0, 0), 0, 0, 90);
    var scene = new RenderScene("test", List.of(), camera).withLights(lights);
    var moved = scene.withCamera(new RenderScene.Camera(new Vec3(1, 0, 0), 0, 0, 90));
    assertSame(scene.lights(), moved.lights());
    assertThrows(UnsupportedOperationException.class, () -> scene.lights().clear());
    assertThrows(
        IllegalArgumentException.class,
        () -> new RenderScene.DynamicLight(new Vec3(0, 0, 0), 0, new Vec3(1, 1, 1)));
  }

  private static BspMap.Vertex vertex(Vec3 position, Vec3 normal) {
    return new BspMap.Vertex(
        position, new BspMap.Uv(0, 0), new BspMap.Uv(0, 0), normal, 0xffffffff);
  }
}
