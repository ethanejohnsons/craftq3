package dev.bluevista.craftq3.engine.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.shader.ShaderLibrary;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Ported from the legacy {@code BspVisibilityTest}, plus area masks, runs and CPU surfaces. */
class VisibilityTest {
  private static final int[] WORLD_MIN = {-100, -100, -100};
  private static final int[] WORLD_MAX = {100, 100, 100};

  @Test
  void traversesPlanesDeduplicatesFacesAndKeepsUnreferencedFaces() {
    RenderScene scene = scene(true, 0, 0, "textures/test");
    Visibility visibility = scene.visibility();
    VisibleSet front = select(scene, 10, true, false);
    assertEquals(0, front.cameraLeaf());
    assertEquals(0, front.cameraCluster());
    assertEquals(0, visibility.clusterForSurface(0));
    assertEquals(1, visibility.clusterForSurface(1));
    assertEquals(-1, visibility.clusterForSurface(2));
    assertEquals(-1, visibility.clusterForSurface(-1));
    assertTrue(front.pvsApplied());
    assertTrue(front.surfaceVisible(0) && !front.surfaceVisible(1) && front.surfaceVisible(2));
    assertEquals(2, front.visibleSurfaceCount());
    assertEquals(2, front.pvsSurfaceCount());
    assertEquals(1, front.visibleLeaves());
    assertEquals(1, front.batchCount());
    assertEquals(2, front.runCount(), "surface 1 splits the batch into two runs");
    assertEquals(12, front.visibleIndexCount());
    VisibleSet back = select(scene, -10, true, false);
    assertEquals(1, back.cameraLeaf());
    assertEquals(3, back.visibleSurfaceCount());
    assertEquals(2, back.visibleLeaves());
    assertEquals(1, back.runCount(), "adjacent surfaces merge into one run");
    assertEquals(18, back.runIndexCount(0));
    assertEquals(3, select(scene, 10, false, false).visibleSurfaceCount());
  }

  @Test
  void conservativelyDisablesPvsWithoutVisibilityOrOutsideWorld() {
    RenderScene scene = scene(true, 0, 0, "textures/test");
    VisibleSet outside = select(scene, 200, true, false);
    assertFalse(outside.pvsApplied());
    assertEquals(3, outside.visibleSurfaceCount());
    RenderScene noVis = scene(false, 0, 0, "textures/test");
    assertEquals(3, select(noVis, 10, true, false).visibleSurfaceCount());
  }

  @Test
  void areaMaskHidesLeavesOfClosedAreas() {
    RenderScene scene = scene(true, 0, 1, "textures/test");
    VisibleSet out = new VisibleSet();
    scene.visibility().select(new Vec3f(-10, 0, 0), AreaMask.of(new byte[] {2}), null, true, out);
    assertTrue(out.surfaceVisible(0));
    assertFalse(out.surfaceVisible(1), "leaf 1 is in hidden area 1");
    assertTrue(out.surfaceVisible(2), "faces no leaf lists stay visible");
    assertEquals(1, out.visibleLeaves());
    scene.visibility().select(new Vec3f(-10, 0, 0), AreaMask.of(new byte[] {2}), null, false, out);
    assertFalse(out.surfaceVisible(1), "the mask applies without PVS as well");
  }

  @Test
  void frustumCullsBoxesExceptDeformedMaterialsAndRoutesCpuDeformsSeparately() {
    RenderScene plain = scene(false, 0, 0, "textures/test");
    VisibleSet out = new VisibleSet();
    Camera away = new Camera(new Vec3f(0, 0, 0), 180, 0, 90);
    plain.visibility().select(ViewDefinition.of(away, 1), false, true, out);
    assertEquals(0, out.visibleSurfaceCount());
    RenderScene waving = scene(false, 0, 0, "textures/wave");
    waving.visibility().select(ViewDefinition.of(away, 1), false, true, out);
    assertEquals(3, out.visibleSurfaceCount(), "deformed bounds are not conservative");
    RenderScene sprites = scene(false, 0, 0, "textures/sprite");
    sprites.visibility().select(ViewDefinition.of(away, 1), false, false, out);
    assertEquals(0, out.batchCount());
    assertEquals(3, out.cpuSurfaceCount());
    assertEquals(0, out.cpuSurface(0));
  }

  @Test
  void frustumUsesHorizontalFovAspectPitchAndBoxIntersection() {
    Frustum frustum = Frustum.of(camera(0, 0, 0, 0, 0), 2, 1, 100);
    assertTrue(frustum.intersects(box(10, -1, -1), box(20, 1, 1)));
    assertTrue(frustum.intersects(box(-5, -5, -5), box(5, 5, 5)));
    assertFalse(frustum.intersects(box(-20, -1, -1), box(-10, 1, 1)));
    assertFalse(frustum.intersects(box(10, 30, 0), box(20, 40, 1)));
    assertFalse(frustum.intersects(box(10, 0, 15), box(20, 1, 20)));
    assertTrue(frustum.intersects(box(10, 0, 9), box(20, 1, 20)));
    assertFalse(frustum.intersects(box(101, 0, 0), box(102, 1, 1)));
    Frustum down = Frustum.of(camera(0, 0, 0, 90, 45), 1, 1, 100);
    assertTrue(down.intersects(box(-1, 19, -21), box(1, 21, -19)));
    assertFalse(down.intersects(box(-1, -21, 19), box(1, -19, 21)));
    assertThrows(
        IllegalArgumentException.class, () -> Frustum.of(camera(0, 0, 0, 0, 0), 0, 1, 100));
    Frustum clipped =
        Frustum.of(
            new Vec3f(0, 0, 0),
            camera(0, 0, 0, 0, 0).basis(),
            1,
            1,
            1,
            100,
            new Plane(new Vec3f(1, 0, 0), 50));
    assertFalse(clipped.intersects(box(10, -1, -1), box(20, 1, 1)), "behind the portal plane");
    assertTrue(clipped.intersects(box(60, -1, -1), box(70, 1, 1)));
  }

  /**
   * Plane x=0 splits leaf 0 (front, cluster 0, faces 0 and 0) from leaf 1 (back, cluster 1, face
   * 1); face 2 is in no leaf. Cluster 0 sees only itself; cluster 1 sees both.
   */
  private static RenderScene scene(boolean vis, int area0, int area1, String shader) {
    TestBsp bsp = new TestBsp();
    int texture = bsp.texture(shader, 0);
    bsp.vertex(10, -1, -1, 0, 0, 0, 0, 0, 0, 1, -1);
    bsp.vertex(20, 1, -1, 1, 0, 0, 0, 0, 0, 1, -1);
    bsp.vertex(10, 1, 1, 0, 1, 0, 0, 0, 0, 1, -1);
    bsp.vertex(20, -1, 1, 1, 1, 0, 0, 0, 0, 1, -1);
    float[] up = {0, 0, 1};
    for (int i = 0; i < 3; i++) bsp.planar(texture, -1, 0, 0, 4, up, 0, 1, 2, 0, 2, 3);
    int plane = bsp.plane(1, 0, 0, 0);
    bsp.node(plane, -1, -2, WORLD_MIN, WORLD_MAX);
    bsp.leaf(0, area0, 0, 0);
    bsp.leaf(1, area1, 1);
    bsp.model(new float[] {-100, -100, -100}, new float[] {100, 100, 100}, 0, 3, 0, 0);
    if (vis) bsp.visibility(2, 1, (byte) 1, (byte) 3);
    ShaderLibrary library =
        ShaderLibrary.of(
            List.of(
                new ShaderLibrary.Script(
                    "scripts/v.shader",
                    """
                    textures/wave
                    {
                      deformVertexes wave 100 sin 0 4 0 1
                      {
                        map textures/wave.tga
                      }
                    }
                    textures/sprite
                    {
                      deformVertexes autosprite
                      {
                        map textures/sprite.tga
                      }
                    }
                    """)));
    return RenderScene.build("pvs", bsp.read(), library::resolve);
  }

  private static VisibleSet select(RenderScene scene, float x, boolean pvs, boolean frustum) {
    VisibleSet out = new VisibleSet();
    scene.visibility().select(ViewDefinition.of(camera(x, 0, 0, 0, 0), 1), pvs, frustum, out);
    return out;
  }

  private static Camera camera(float x, float y, float z, float yaw, float pitch) {
    return new Camera(new Vec3f(x, y, z), yaw, pitch, 90);
  }

  private static Vec3f box(float x, float y, float z) {
    return new Vec3f(x, y, z);
  }
}
