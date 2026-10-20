package dev.bluevista.craftq3.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspFixture;
import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

class BspVisibilityTest {
  @Test
  void traversesPlanesDeduplicatesFacesAndKeepsUnreferencedFaces() throws Exception {
    var visibility =
        new BspVisibility(scene(new BspMap.Visibility(2, 1, new BspMap.Bytes(new byte[] {1, 3}))));
    var front = visibility.select(camera(10, 0, 0, 0, 0), 1, 1, 1000, true, false);
    assertEquals(0, front.cameraLeaf());
    assertEquals(0, front.cameraCluster());
    assertEquals(0, visibility.clusterForSurface(0));
    assertEquals(1, visibility.clusterForSurface(1));
    assertEquals(-1, visibility.clusterForSurface(2));
    assertEquals(-1, visibility.clusterForSurface(-1));
    assertTrue(front.pvsApplied());
    assertEquals(List.of(0, 2), front.surfaces().stream().map(RenderScene.Surface::id).toList());
    assertEquals(2, front.pvsSurfaceCount());
    assertEquals(1, front.visibleLeaves());
    var back = visibility.select(camera(-10, 0, 0, 0, 0), 1, 1, 1000, true, false);
    assertEquals(1, back.cameraLeaf());
    assertEquals(3, back.surfaces().size());
    assertEquals(2, back.visibleLeaves());
    assertEquals(
        3, visibility.select(camera(10, 0, 0, 0, 0), 1, 1, 1000, false, false).surfaces().size());
  }

  @Test
  void conservativelyDisablesPvsWithoutVisibilityOrOutsideWorld() throws Exception {
    var scene = scene(new BspMap.Visibility(2, 1, new BspMap.Bytes(new byte[] {1, 3})));
    var outside = new BspVisibility(scene).select(camera(200, 0, 0, 0, 0), 1, 1, 1000, true, false);
    assertFalse(outside.pvsApplied());
    assertEquals(3, outside.surfaces().size());
    var noVis =
        new BspVisibility(scene(new BspMap.Visibility(0, 0, new BspMap.Bytes(new byte[0]))));
    assertEquals(
        3, noVis.select(camera(10, 0, 0, 0, 0), 1, 1, 1000, true, false).surfaces().size());
  }

  @Test
  void frustumUsesHorizontalFovAspectPitchAndBoxIntersection() {
    var frustum = new BspVisibility.Frustum(camera(0, 0, 0, 0, 0), 2, 1, 100);
    assertTrue(frustum.intersects(box(10, -1, -1, 20, 1, 1)));
    assertTrue(frustum.intersects(box(-5, -5, -5, 5, 5, 5)));
    assertFalse(frustum.intersects(box(-20, -1, -1, -10, 1, 1)));
    assertFalse(frustum.intersects(box(10, 30, 0, 20, 40, 1)));
    assertFalse(frustum.intersects(box(10, 0, 15, 20, 1, 20)));
    assertTrue(frustum.intersects(box(10, 0, 9, 20, 1, 20)));
    assertFalse(frustum.intersects(box(101, 0, 0, 102, 1, 1)));
    var down = new BspVisibility.Frustum(camera(0, 0, 0, 90, 45), 1, 1, 100);
    assertTrue(down.intersects(box(-1, 19, -21, 1, 21, -19)));
    assertFalse(down.intersects(box(-1, -21, 19, 1, -19, 21)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BspVisibility.Frustum(camera(0, 0, 0, 0, 0), 0, 1, 100));
  }

  private static RenderScene scene(BspMap.Visibility visibility) throws Exception {
    BspMap base = BspReader.read(BspFixture.map(false));
    BspMap.Bounds world = box(-100, -100, -100, 100, 100, 100);
    BspMap bsp =
        new BspMap(
            base.entities(),
            base.textures(),
            List.of(new BspMap.Plane(new Vec3(1, 0, 0), 0)),
            List.of(new BspMap.Node(0, -1, -2, world)),
            List.of(
                new BspMap.Leaf(0, 0, world, 0, 2, 0, 0), new BspMap.Leaf(1, 0, world, 2, 1, 0, 0)),
            List.of(0, 0, 1),
            List.of(),
            List.of(new BspMap.Model(world, 0, 3, 0, 0)),
            List.of(),
            List.of(),
            base.vertices(),
            base.meshVertices(),
            base.effects(),
            List.of(base.faces().getFirst(), base.faces().getFirst(), base.faces().getFirst()),
            base.lightmaps(),
            base.lightVolumes(),
            visibility);
    List<RenderScene.Surface> surfaces = List.of(surface(0), surface(1), surface(2));
    return new RenderScene("pvs", List.of(), camera(10, 0, 0, 0, 0), List.of(), surfaces, bsp);
  }

  private static RenderScene.Surface surface(int id) {
    return new RenderScene.Surface(id, 0, "test", 0, 1, 0, 3, box(10, -1, -1, 20, 1, 1));
  }

  private static RenderScene.Camera camera(double x, double y, double z, float yaw, float pitch) {
    return new RenderScene.Camera(new Vec3(x, y, z), yaw, pitch, 90);
  }

  private static BspMap.Bounds box(double a, double b, double c, double d, double e, double f) {
    return new BspMap.Bounds(new Vec3(a, b, c), new Vec3(d, e, f));
  }
}
