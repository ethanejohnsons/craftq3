package dev.bluevista.craftq3.render;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class DebugGeometryTest {
  @Test
  void clipsBrushPlanesIntoTwelveCubeEdges() throws Exception {
    var map = RenderFixtures.brushes(List.of(RenderFixtures.box(-1, -1, -1, 1, 1, 1)));
    var scene = BspSceneBuilder.build("cube", map, 2);
    var result = DebugGeometry.build(scene, DebugGeometry.Mode.BRUSHES);
    assertFalse(result.truncated());
    assertEquals(12, result.lines().size());
    for (var line : result.lines()) {
      assertEquals(1, Math.abs(line.a().x()), 1e-8);
      assertEquals(1, Math.abs(line.a().y()), 1e-8);
      assertEquals(1, Math.abs(line.a().z()), 1e-8);
      assertEquals(1, Math.abs(line.b().x()), 1e-8);
      assertEquals(1, Math.abs(line.b().y()), 1e-8);
      assertEquals(1, Math.abs(line.b().z()), 1e-8);
      double distance =
          Math.abs(line.a().x() - line.b().x())
              + Math.abs(line.a().y() - line.b().y())
              + Math.abs(line.a().z() - line.b().z());
      assertEquals(2, distance, 1e-8);
    }
  }

  @Test
  void drawsLeafNodeAndNormalDiagnostics() throws Exception {
    var map = RenderFixtures.brushes(List.of(RenderFixtures.box(-1, -1, -1, 1, 1, 1)));
    var scene = BspSceneBuilder.build("cube", map, 2);
    assertEquals(12, DebugGeometry.build(scene, DebugGeometry.Mode.NODES).lines().size());
    assertEquals(12, DebugGeometry.build(scene, DebugGeometry.Mode.LEAVES).lines().size());
    var normals = DebugGeometry.build(scene, DebugGeometry.Mode.NORMALS);
    assertEquals(1, normals.lines().size());
    assertEquals(12, normals.lines().getFirst().b().z() - normals.lines().getFirst().a().z(), 1e-8);
  }
}
