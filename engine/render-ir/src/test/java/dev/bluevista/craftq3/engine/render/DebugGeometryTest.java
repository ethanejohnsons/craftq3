package dev.bluevista.craftq3.engine.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import org.junit.jupiter.api.Test;

/** Ported from the legacy {@code DebugGeometryTest}. */
class DebugGeometryTest {
  /** One node, one leaf, one upward triangle and a 2x2x2 cube brush. */
  private static RenderScene cube() {
    TestBsp bsp = new TestBsp();
    int texture = bsp.texture("textures/test", 0);
    bsp.box(new float[] {-1, -1, -1}, new float[] {1, 1, 1});
    bsp.vertex(-128, -128, 0, 0, 0, 0, 0, 0, 0, 1, -1);
    bsp.vertex(128, -128, 0, 0, 0, 0, 0, 0, 0, 1, -1);
    bsp.vertex(-128, 128, 0, 0, 0, 0, 0, 0, 0, 1, -1);
    bsp.planar(texture, -1, 0, 0, 3, new float[] {0, 0, 1}, 0, 1, 2);
    int plane = bsp.plane(0, 0, 1, 0);
    bsp.node(plane, -1, -1, new int[] {-256, -256, -256}, new int[] {256, 256, 256});
    bsp.leaf(0, 0, 0);
    bsp.model(new float[] {-256, -256, -256}, new float[] {256, 256, 256}, 0, 1, 0, 1);
    return TestBsp.scene(bsp.read());
  }

  @Test
  void clipsBrushPlanesIntoTwelveCubeEdges() {
    DebugGeometry.Lines lines = DebugGeometry.build(cube(), DebugGeometry.Mode.BRUSHES);
    assertFalse(lines.truncated());
    assertEquals(12, lines.count());
    for (int i = 0; i < lines.count(); i++) {
      Vec3f a = lines.start(i), b = lines.end(i);
      for (int axis = 0; axis < 3; axis++) {
        assertEquals(1, Math.abs(a.get(axis)), 1e-5);
        assertEquals(1, Math.abs(b.get(axis)), 1e-5);
      }
      double distance = Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z());
      assertEquals(2, distance, 1e-5);
    }
  }

  @Test
  void drawsLeafNodeAndNormalDiagnostics() {
    RenderScene scene = cube();
    assertEquals(12, DebugGeometry.build(scene, DebugGeometry.Mode.NODES).count());
    assertEquals(12, DebugGeometry.build(scene, DebugGeometry.Mode.LEAVES).count());
    DebugGeometry.Lines normals = DebugGeometry.build(scene, DebugGeometry.Mode.NORMALS);
    assertEquals(1, normals.count());
    assertEquals(12, normals.end(0).z() - normals.start(0).z(), 1e-5);
  }
}
