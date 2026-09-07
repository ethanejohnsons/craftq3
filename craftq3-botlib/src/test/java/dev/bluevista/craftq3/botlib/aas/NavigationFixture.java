package dev.bluevista.craftq3.botlib.aas;

import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.assets.aas.AasMap.*;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.List;

/** Authored four-area graph and simple BSP; no game asset bytes. */
final class NavigationFixture {
  private NavigationFixture() {}

  static AasMap map() {
    Vec3 zero = new Vec3(0, 0, 0);
    return new AasMap(
        4,
        0,
        List.of(),
        List.of(),
        List.of(),
        List.of(
            new Plane(new Vec3(1, 0, 0), 0, 0),
            new Plane(new Vec3(0, 1, 0), 0, 1),
            new Plane(new Vec3(1, 0, 0), -10, 0)),
        List.of(),
        new Indices(new int[0]),
        List.of(),
        new Indices(new int[0]),
        List.of(
            new Area(0, 0, 0, zero, zero, zero),
            area(1, 0, 0),
            area(2, 0, -10),
            area(3, -10, 0),
            area(4, -10, -10)),
        List.of(settings(0, 0), settings(3, 1), settings(2, 4), settings(2, 6), settings(0, 8)),
        List.of(
            link(0, 0, 0),
            link(2, 2, 40),
            link(3, 5, 5),
            link(4, 2, 100),
            link(4, 2, 5),
            link(1, 2, 0),
            link(2, 2, 5),
            link(4, 2, 20)),
        List.of(
            new Node(0, 0, 0),
            new Node(0, 2, 3),
            new Node(1, -1, -2),
            new Node(2, 4, 0),
            new Node(1, -3, -4)),
        List.of(),
        new Indices(new int[0]),
        List.of());
  }

  static AasMap with(AasMap source, List<AreaSettings> settings, List<Reachability> reaches) {
    return new AasMap(
        source.version(),
        source.bspChecksum(),
        source.lumps(),
        source.boundingBoxes(),
        source.vertices(),
        source.planes(),
        source.edges(),
        source.edgeIndices(),
        source.faces(),
        source.faceIndices(),
        source.areas(),
        settings,
        reaches,
        source.nodes(),
        source.portals(),
        source.portalIndices(),
        source.clusters());
  }

  static Reachability link(int area, int type, int cost) {
    return new Reachability(area, 0, 0, new Vec3(0, 0, 0), new Vec3(0, 0, 0), type, cost, 0);
  }

  private static Area area(int number, double x, double y) {
    return new Area(
        number, 0, 0, new Vec3(x, y, -10), new Vec3(x + 10, y + 10, 10), new Vec3(x + 5, y + 5, 0));
  }

  private static AreaSettings settings(int count, int first) {
    return new AreaSettings(0, 1, 2, 0, 0, count, first);
  }
}
