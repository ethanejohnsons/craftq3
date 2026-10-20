package dev.bluevista.craftq3.engine.botlib.aas;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Arrays;
import java.util.Objects;

/** Native-observed signed polyhedral volume, with bounded, per-map lazy caches. */
public final class AasAreaVolume {
  private final AasWorld map;
  private final float[] areas, faces;
  private final int maxGeometryVisits;

  public AasAreaVolume(AasWorld map) {
    this(map, 10_000_000);
  }

  /** The work limit counts boundary faces and uncached face triangles for each query. */
  public AasAreaVolume(AasWorld map, int maxGeometryVisits) {
    this.map = Objects.requireNonNull(map);
    if (maxGeometryVisits < 1
        || maxGeometryVisits > 100_000_000
        || map.areas().size() > 65536
        || map.faces().size() > 262144)
      throw new IllegalArgumentException("Invalid AAS volume limits");
    this.maxGeometryVisits = maxGeometryVisits;
    areas = new float[map.areas().size()];
    faces = new float[map.faces().size()];
    Arrays.fill(areas, Float.NaN);
    Arrays.fill(faces, Float.NaN);
  }

  /** Empty areas return zero; inconsistent geometry can produce a signed result. */
  public float volume(int areaNumber) {
    if (areaNumber < 0 || areaNumber >= areas.length)
      throw new IllegalArgumentException("Invalid AAS volume area");
    if (!Float.isNaN(areas[areaNumber])) return areas[areaNumber];
    var area = map.areas().get(areaNumber);
    range(area.firstFace(), area.faceCount(), map.faceIndices().size());
    if (area.faceCount() == 0) return areas[areaNumber] = 0;
    var first = map.faces().get(faceIndex(area.firstFace()));
    range(first.firstEdge(), Math.max(1, first.edgeCount()), map.edgeIndices().size());
    // The volume anchor uses endpoint zero of the absolute edge, independently of its sign.
    var firstEdge = map.edges().get(absolute(map.edgeIndices().get(first.firstEdge())));
    Vec3f anchor = point(map.vertices().get(firstEdge.startVertex()));
    var work = new Work();
    float sum = 0;
    for (int index = 0; index < area.faceCount(); index++) {
      work.visit();
      int number = faceIndex(area.firstFace() + index);
      var face = map.faces().get(number);
      var plane = map.planes().get(face.plane() ^ (face.frontArea() == areaNumber ? 1 : 0));
      Vec3f normal = point(plane.normal());
      float distance = finite(plane.distance() - finite(anchor.dot(normal)));
      sum = finite(sum + finite(faceArea(number, work) * distance));
    }
    return areas[areaNumber] = finite(sum / 3);
  }

  private int faceIndex(int index) {
    return absolute(map.faceIndices().get(index));
  }

  private float faceArea(int number, Work work) {
    if (!Float.isNaN(faces[number])) return faces[number];
    var face = map.faces().get(number);
    range(face.firstEdge(), face.edgeCount(), map.edgeIndices().size());
    if (face.edgeCount() < 3) return faces[number] = 0;
    Vec3f origin = vertex(face.firstEdge(), false);
    float sum = 0;
    for (int index = 1; index < face.edgeCount() - 1; index++) {
      work.visit();
      Vec3f a = point(vertex(face.firstEdge() + index, false).subtract(origin));
      Vec3f b = point(vertex(face.firstEdge() + index, true).subtract(origin));
      Vec3f cross = point(a.cross(b));
      sum = finite(sum + (float) Math.sqrt(finite(cross.dot(cross))));
    }
    return faces[number] = finite(sum * .5f);
  }

  private Vec3f vertex(int index, boolean end) {
    int signed = map.edgeIndices().get(index);
    var edge = map.edges().get(absolute(signed));
    return point(map.vertices().get((signed < 0) ^ end ? edge.endVertex() : edge.startVertex()));
  }

  private static int absolute(int value) {
    if (value == Integer.MIN_VALUE) throw new IllegalArgumentException("Invalid signed AAS index");
    return Math.abs(value);
  }

  private static void range(int start, int count, int size) {
    if (start < 0 || count < 0 || (long) start + count > size)
      throw new IllegalArgumentException("Invalid AAS volume geometry range");
  }

  private static Vec3f point(Vec3f value) {
    if (!value.isFinite())
      throw new IllegalArgumentException("AAS volume exceeds finite float range");
    return value;
  }

  private static float finite(float value) {
    if (!Float.isFinite(value))
      throw new IllegalArgumentException("AAS volume exceeds finite float range");
    return value;
  }

  private final class Work {
    private int visits;

    void visit() {
      if (visits++ == maxGeometryVisits)
        throw new IllegalStateException("AAS volume geometry budget exceeded");
    }
  }
}
