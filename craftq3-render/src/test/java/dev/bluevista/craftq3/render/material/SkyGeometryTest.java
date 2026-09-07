package dev.bluevista.craftq3.render.material;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspMap.Vertex;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.render.RenderScene.Camera;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SkyGeometryTest {
  private static final Camera CAMERA = new Camera(new Vec3(0, 0, 0), 0, 0, 90);

  @Test
  void allSixCubeFacesHaveCompleteUvsAndInwardWinding() {
    var box = SkyGeometry.box(CAMERA, 1000);
    assertEquals(Set.of("rt", "lf", "bk", "ft", "up", "dn"), box.keySet());
    Map<String, Vec3> normals =
        Map.of(
            "rt",
            new Vec3(-1, 0, 0),
            "lf",
            new Vec3(1, 0, 0),
            "bk",
            new Vec3(0, -1, 0),
            "ft",
            new Vec3(0, 1, 0),
            "up",
            new Vec3(0, 0, -1),
            "dn",
            new Vec3(0, 0, 1));
    for (var side : box.entrySet()) {
      assertEquals(6, side.getValue().size());
      assertEquals(4, side.getValue().stream().map(Vertex::textureUv).distinct().count());
      for (Vertex vertex : side.getValue()) {
        assertEquals(normals.get(side.getKey()), vertex.normal());
        assertTrue(vertex.textureUv().u() >= 0 && vertex.textureUv().u() <= 1);
        assertTrue(vertex.textureUv().v() >= 0 && vertex.textureUv().v() <= 1);
      }
      for (int i = 0; i < 6; i += 3) {
        assertTrue(
            dot(
                    cross(
                        subtract(
                            side.getValue().get(i + 1).position(),
                            side.getValue().get(i).position()),
                        subtract(
                            side.getValue().get(i + 2).position(),
                            side.getValue().get(i).position())),
                    normals.get(side.getKey()))
                > 0);
      }
    }
    assertThrows(UnsupportedOperationException.class, () -> box.clear());
    assertThrows(UnsupportedOperationException.class, () -> box.get("rt").clear());
  }

  @Test
  void cloudProjectionIsContinuousAcrossFacesAndIndependentOfCameraTranslation() {
    var original = SkyGeometry.clouds(CAMERA, 1000, 256);
    Vec3 translation = new Vec3(200, -30, 100);
    var moved = SkyGeometry.clouds(new Camera(translation, 80, 20, 100), 1000, 256);
    assertEquals(5 * 8 * 8 * 6, original.size());
    Map<Vec3, dev.bluevista.craftq3.assets.bsp.BspMap.Uv> seams = new java.util.HashMap<>();
    for (int i = 0; i < original.size(); i++) {
      Vertex first = original.get(i);
      assertEquals(first.textureUv(), moved.get(i).textureUv());
      assertEquals(first.position().add(translation), moved.get(i).position());
      assertTrue(Float.isFinite(first.textureUv().u()));
      assertTrue(Float.isFinite(first.textureUv().v()));
      var prior = seams.putIfAbsent(first.position(), first.textureUv());
      if (prior != null) assertEquals(prior, first.textureUv());
    }
    assertThrows(UnsupportedOperationException.class, () -> original.clear());
  }

  @Test
  void cloudHeightChangesProjectionAndRejectsInvalidInputs() {
    var low = SkyGeometry.clouds(CAMERA, 1000, 64);
    var high = SkyGeometry.clouds(CAMERA, 1000, 512);
    long changed =
        java.util.stream.IntStream.range(0, low.size())
            .filter(i -> !low.get(i).textureUv().equals(high.get(i).textureUv()))
            .count();
    assertTrue(changed > low.size() * .9);
    assertThrows(IllegalArgumentException.class, () -> SkyGeometry.clouds(CAMERA, 1000, 0));
    assertThrows(IllegalArgumentException.class, () -> SkyGeometry.box(CAMERA, Float.NaN));
  }

  private static Vec3 subtract(Vec3 a, Vec3 b) {
    return a.add(b.scale(-1));
  }

  private static double dot(Vec3 a, Vec3 b) {
    return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
  }

  private static Vec3 cross(Vec3 a, Vec3 b) {
    return new Vec3(
        a.y() * b.z() - a.z() * b.y(),
        a.z() * b.x() - a.x() * b.z(),
        a.x() * b.y() - a.y() * b.x());
  }
}
