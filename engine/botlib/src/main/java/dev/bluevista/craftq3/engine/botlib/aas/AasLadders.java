package dev.bluevista.craftq3.engine.botlib.aas;

import dev.bluevista.craftq3.engine.botlib.Contents;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Objects;

/**
 * Ladder contact ({@code AAS_AgainstLadder}): the point lies in a normal-presence ladder area and
 * within three units of one of that area's ladder faces, inside the face's edges. A point in solid
 * is nudged by up to one unit along x and y to find its area first.
 */
public final class AasLadders {
  private static final float PLANE_DISTANCE = 3;
  private static final float EDGE_EPSILON = .1f;

  private final AasNavigation navigation;
  private final AasWorld map;

  public AasLadders(AasNavigation navigation) {
    this.navigation = Objects.requireNonNull(navigation);
    this.map = navigation.map();
  }

  public boolean against(Vec3f origin) {
    int area = navigation.pointArea(origin);
    if (area == 0) area = navigation.pointArea(origin.add(new Vec3f(1, 0, 0)));
    if (area == 0) area = navigation.pointArea(origin.add(new Vec3f(1, 1, 0)));
    if (area == 0) area = navigation.pointArea(origin.add(new Vec3f(-1, 1, 0)));
    if (area == 0) area = navigation.pointArea(origin.add(new Vec3f(-1, -1, 0)));
    if (area == 0) return false;
    var settings = map.areaSettings().get(area);
    if ((settings.flags() & Contents.AREAFLAG_LADDER) == 0) return false;
    if ((settings.presenceType() & Contents.PRESENCE_NORMAL) == 0) return false;
    var geometry = map.areas().get(area);
    for (int i = 0; i < geometry.faceCount(); i++) {
      int index = geometry.firstFace() + i;
      if (index < 0 || index >= map.faceIndices().size()) return false;
      int signed = map.faceIndices().get(index);
      int number = Math.abs(signed);
      if (number >= map.faces().size()) continue;
      var face = map.faces().get(number);
      if ((face.flags() & Contents.FACE_LADDER) == 0) continue;
      int planeNumber = face.plane() ^ (signed < 0 ? 1 : 0);
      if (planeNumber < 0 || planeNumber >= map.planes().size()) continue;
      var plane = map.planes().get(planeNumber);
      // The original converts the float distance to int before taking its absolute value.
      int distance = (int) (plane.normal().dot(origin) - plane.distance());
      if (Math.abs(distance) < PLANE_DISTANCE && insideFace(number, origin)) return true;
    }
    return false;
  }

  /** {@code AAS_PointInsideFace}: on the inner side of every edge's separating plane. */
  boolean insideFace(int faceNumber, Vec3f point) {
    var face = map.faces().get(faceNumber);
    if (face.plane() < 0 || face.plane() >= map.planes().size()) return false;
    Vec3f normal = map.planes().get(face.plane()).normal();
    for (int i = 0; i < face.edgeCount(); i++) {
      int index = face.firstEdge() + i;
      if (index < 0 || index >= map.edgeIndices().size()) return false;
      int signed = map.edgeIndices().get(index);
      int edgeNumber = Math.abs(signed);
      if (edgeNumber >= map.edges().size()) return false;
      var edge = map.edges().get(edgeNumber);
      int first = signed < 0 ? edge.endVertex() : edge.startVertex();
      int second = signed < 0 ? edge.startVertex() : edge.endVertex();
      if (first >= map.vertices().size() || second >= map.vertices().size()) return false;
      Vec3f v1 = map.vertices().get(first);
      Vec3f edgeVector = map.vertices().get(second).subtract(v1);
      Vec3f separating = edgeVector.cross(normal);
      if (point.subtract(v1).dot(separating) < -EDGE_EPSILON) return false;
    }
    return true;
  }
}
