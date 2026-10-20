package dev.bluevista.craftq3.engine.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Ported from the legacy {@code SkyGeometryTest}. */
class SkyGeometryTest {
  @Test
  void allSixCubeFacesHaveCompleteUvsAndInwardWinding() {
    MeshBuffer box = new MeshBuffer(1024, 1024);
    SkyGeometry.box(Vec3f.ZERO, 1000, box);
    assertEquals(24, box.vertexCount());
    assertEquals(36, box.indexCount());
    List<Vec3f> inward =
        List.of(
            new Vec3f(-1, 0, 0),
            new Vec3f(1, 0, 0),
            new Vec3f(0, -1, 0),
            new Vec3f(0, 1, 0),
            new Vec3f(0, 0, -1),
            new Vec3f(0, 0, 1));
    for (int side = 0; side < 6; side++) {
      Set<String> uvs = new HashSet<>();
      for (int v = side * 4; v < side * 4 + 4; v++) {
        float s = box.texCoords()[v * 2], t = box.texCoords()[v * 2 + 1];
        uvs.add(s + "," + t);
        assertEquals(inward.get(side), vector(box.normals(), v));
        assertTrue(s >= 0 && s <= 1 && t >= 0 && t <= 1);
      }
      assertEquals(4, uvs.size());
      for (int i = side * 6; i < side * 6 + 6; i += 3) {
        Vec3f a = vector(box.positions(), box.indices()[i]);
        Vec3f b = vector(box.positions(), box.indices()[i + 1]);
        Vec3f c = vector(box.positions(), box.indices()[i + 2]);
        assertTrue(b.subtract(a).cross(c.subtract(a)).dot(inward.get(side)) > 0);
      }
    }
  }

  @Test
  void cloudProjectionIsContinuousAcrossFacesAndIndependentOfCameraTranslation() {
    MeshBuffer original = new MeshBuffer(4096, 4096);
    MeshBuffer moved = new MeshBuffer(4096, 4096);
    Vec3f translation = new Vec3f(200, -30, 100);
    SkyGeometry.clouds(Vec3f.ZERO, 1000, 256, original);
    SkyGeometry.clouds(translation, 1000, 256, moved);
    assertEquals(5 * 8 * 8 * 6, original.indexCount());
    Map<Vec3f, String> seams = new HashMap<>();
    for (int v = 0; v < original.vertexCount(); v++) {
      float s = original.texCoords()[v * 2], t = original.texCoords()[v * 2 + 1];
      assertEquals(s, moved.texCoords()[v * 2]);
      assertEquals(t, moved.texCoords()[v * 2 + 1]);
      assertEquals(vector(original.positions(), v).add(translation), vector(moved.positions(), v));
      assertTrue(Float.isFinite(s) && Float.isFinite(t));
      String prior = seams.putIfAbsent(vector(original.positions(), v), s + "," + t);
      if (prior != null) assertEquals(prior, s + "," + t);
    }
  }

  @Test
  void cloudHeightChangesProjectionAndRejectsInvalidInputs() {
    MeshBuffer low = new MeshBuffer(4096, 4096), high = new MeshBuffer(4096, 4096);
    SkyGeometry.clouds(Vec3f.ZERO, 1000, 64, low);
    SkyGeometry.clouds(Vec3f.ZERO, 1000, 512, high);
    int changed = 0;
    for (int i = 0; i < low.vertexCount() * 2; i++)
      if (low.texCoords()[i] != high.texCoords()[i]) changed++;
    assertTrue(changed > low.vertexCount() * 2 * .85);
    assertThrows(
        IllegalArgumentException.class, () -> SkyGeometry.clouds(Vec3f.ZERO, 1000, 0, low));
    assertThrows(IllegalArgumentException.class, () -> SkyGeometry.box(Vec3f.ZERO, Float.NaN, low));
  }

  private static Vec3f vector(float[] values, int v) {
    return new Vec3f(values[v * 3], values[v * 3 + 1], values[v * 3 + 2]);
  }
}
