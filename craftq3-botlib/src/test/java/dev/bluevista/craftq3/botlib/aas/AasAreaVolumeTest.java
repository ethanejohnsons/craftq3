package dev.bluevista.craftq3.botlib.aas;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.assets.aas.AasMap.*;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class AasAreaVolumeTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);

  @Test
  void polyhedralVolumeIgnoresStoredBoundsAndCenterAndCachesItsResult() {
    var volumes = new AasAreaVolume(box(0, 0, 0, 2, 3, 4, false, false, false));
    assertEquals(0, volumes.volume(0));
    assertEquals(24, volumes.volume(1));
    assertEquals(24, volumes.volume(1));
    assertEquals(
        31.078125f,
        new AasAreaVolume(box(100.125, -200.75, 300.25, 2.125, 3.25, 4.5, false, false, false))
            .volume(1));
  }

  @Test
  void volumeAnchorIgnoresTheFirstEdgesDirectionAsObservedNatively() {
    // The reversed first loop and deliberately changed plane isolate the anchor endpoint.
    var map = box(100.125, -200.75, 300.25, 2.125, 3.25, 4.5, true, true, false);
    assertEquals(660.609375f, new AasAreaVolume(map).volume(1));
  }

  @Test
  void frontAreaUsesTheOppositePlaneAndInconsistentGeometryIsNotClamped() {
    assertEquals(8, new AasAreaVolume(box(0, 0, 0, 2, 3, 4, false, false, true)).volume(1));
    var map = box(0, 0, 0, 2, 3, 4, false, false, false);
    var planes = new ArrayList<>(map.planes());
    planes.set(2, new Plane(new Vec3(1, 0, 0), -100, 0));
    assertEquals(-384, new AasAreaVolume(withPlanes(map, planes)).volume(1));
  }

  @Test
  void degenerateFacesAndEmptyAreasHaveZeroVolume() {
    assertEquals(0, new AasAreaVolume(box(0, 0, 0, 0, 3, 4, false, false, false)).volume(1));
  }

  @Test
  void geometryWorkAndInputBoundsAreExplicit() {
    var map = box(0, 0, 0, 2, 3, 4, false, false, false);
    assertEquals(24, new AasAreaVolume(map, 18).volume(1));
    assertThrows(IllegalStateException.class, () -> new AasAreaVolume(map, 17).volume(1));
    assertThrows(IllegalArgumentException.class, () -> new AasAreaVolume(map, 0));
    assertThrows(IllegalArgumentException.class, () -> new AasAreaVolume(map).volume(-1));
    assertThrows(IllegalArgumentException.class, () -> new AasAreaVolume(map).volume(2));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AasAreaVolume(box(0, 0, 0, 1e30, 1e30, 1e30, false, false, false)).volume(1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AasAreaVolume(box(1e100, 0, 0, 2, 3, 4, false, false, false)).volume(1));
  }

  private static AasMap withPlanes(AasMap m, List<Plane> p) {
    return new AasMap(
        m.version(),
        m.bspChecksum(),
        m.lumps(),
        m.boundingBoxes(),
        m.vertices(),
        p,
        m.edges(),
        m.edgeIndices(),
        m.faces(),
        m.faceIndices(),
        m.areas(),
        m.areaSettings(),
        m.reachabilities(),
        m.nodes(),
        m.portals(),
        m.portalIndices(),
        m.clusters());
  }

  private static AasMap box(
      double x,
      double y,
      double z,
      double sx,
      double sy,
      double sz,
      boolean reverseFirst,
      boolean alterNormal,
      boolean frontFace) {
    var vertices = new ArrayList<Vec3>();
    for (int i = 0; i < 8; i++)
      vertices.add(
          new Vec3(
              x + ((i & 1) == 0 ? 0 : sx),
              y + ((i & 2) == 0 ? 0 : sy),
              z + ((i & 4) == 0 ? 0 : sz)));
    int[][] loops = {
      {0, 4, 6, 2}, {1, 3, 7, 5}, {0, 1, 5, 4}, {2, 6, 7, 3}, {0, 2, 3, 1}, {4, 5, 7, 6}
    };
    var edges = new ArrayList<Edge>();
    edges.add(new Edge(0, 0));
    var faces = new ArrayList<Face>();
    faces.add(new Face(0, 0, 0, 0, 0, 0));
    var planes = new ArrayList<Plane>();
    int[] indices = new int[24], boundaries = {-1, -2, -3, -4, -5, -6};
    for (int i = 0; i < 6; i++) {
      for (int j = 0; j < 4; j++) {
        indices[i * 4 + j] = edges.size();
        edges.add(new Edge(loops[i][j], loops[i][(j + 1) % 4]));
      }
      float[] n = new float[3];
      n[i / 2] = (i & 1) == 0 ? -1 : 1;
      Vec3 vertex = vertices.get(loops[i][0]);
      float distance =
          n[0] * (float) vertex.x() + n[1] * (float) vertex.y() + n[2] * (float) vertex.z();
      if (alterNormal && i == 3) n[1] *= 2;
      planes.add(new Plane(new Vec3(n[0], n[1], n[2]), distance, i / 2));
      planes.add(new Plane(new Vec3(-n[0], -n[1], -n[2]), -distance, i / 2));
      faces.add(
          new Face(i * 2, 0, 4, i * 4, frontFace && i == 1 ? 1 : 0, frontFace && i == 1 ? 0 : 1));
    }
    if (reverseFirst) for (int i = 0; i < 4; i++) indices[i] = -(4 - i);
    var areas =
        List.of(
            new Area(0, 0, 0, ZERO, ZERO, ZERO),
            new Area(1, 6, 0, ZERO, ZERO, new Vec3(999, -999, 555)));
    return new AasMap(
        4,
        0,
        List.of(),
        List.of(),
        vertices,
        planes,
        edges,
        new Indices(indices),
        faces,
        new Indices(boundaries),
        areas,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Indices(new int[0]),
        List.of());
  }
}
