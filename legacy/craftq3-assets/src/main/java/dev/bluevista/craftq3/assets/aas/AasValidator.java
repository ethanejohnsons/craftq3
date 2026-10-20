package dev.bluevista.craftq3.assets.aas;

import dev.bluevista.craftq3.assets.aas.AasMap.*;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayDeque;

/**
 * Cross-reference checks remain linear in stored elements, even for deliberately aliased ranges.
 */
final class AasValidator {
  private AasValidator() {}

  static void validate(AasMap map) throws AasFormatException {
    if (map.areas().size() != map.areaSettings().size()) throw bad("Area/settings counts differ");
    for (BoundingBox box : map.boundingBoxes()) bounds(box.min(), box.max(), "bounding box");
    for (Plane plane : map.planes()) {
      Vec3 n = plane.normal();
      if (plane.type() < 0
          || plane.type() > 5
          || n.x() * n.x() + n.y() * n.y() + n.z() * n.z() < 1e-12) throw bad("Invalid AAS plane");
    }
    for (Edge edge : map.edges()) {
      reference(edge.startVertex(), map.vertices().size(), true, "edge vertex");
      reference(edge.endVertex(), map.vertices().size(), true, "edge vertex");
    }
    for (int i = 0; i < map.edgeIndices().size(); i++)
      signedReference(map.edgeIndices().get(i), map.edges().size(), false, "edge index");
    for (int i = 0; i < map.faceIndices().size(); i++)
      signedReference(map.faceIndices().get(i), map.faces().size(), false, "face index");
    for (int i = 0; i < map.portalIndices().size(); i++)
      reference(map.portalIndices().get(i), map.portals().size(), false, "portal index");

    boolean[] edgeRanges = new boolean[map.edgeIndices().size()];
    for (Face face : map.faces()) {
      reference(face.plane(), map.planes().size(), true, "face plane");
      reference(face.frontArea(), map.areas().size(), true, "front area");
      reference(face.backArea(), map.areas().size(), true, "back area");
      claim(face.firstEdge(), face.edgeCount(), edgeRanges, "face edges");
    }

    boolean[] faceRanges = new boolean[map.faceIndices().size()];
    boolean[] reachRanges = new boolean[map.reachabilities().size()];
    for (int i = 0; i < map.areas().size(); i++) {
      Area area = map.areas().get(i);
      if (area.number() != i) throw bad("AAS area number differs from its index");
      bounds(area.min(), area.max(), "area");
      claim(area.firstFace(), area.faceCount(), faceRanges, "area faces");
      for (int j = area.firstFace(); j < area.firstFace() + area.faceCount(); j++) {
        int faceIndex = map.faceIndices().get(j);
        Face face = map.faces().get(Math.abs(faceIndex));
        if (i != (faceIndex > 0 ? face.frontArea() : face.backArea()))
          throw bad("Area/face side references disagree");
      }
      AreaSettings settings = map.areaSettings().get(i);
      claim(
          settings.firstReachability(),
          settings.reachabilityCount(),
          reachRanges,
          "area reachabilities");
      if (settings.clusterArea() < 0) throw bad("Negative cluster-area index");
      if (settings.cluster() < 0) {
        int portal =
            signedReference(settings.cluster(), map.portals().size(), false, "area portal");
        if ((settings.contents() & 8) == 0 || map.portals().get(portal).area() != i)
          throw bad("Area/portal references disagree");
      } else {
        reference(settings.cluster(), map.clusters().size(), true, "area cluster");
        if (settings.cluster() > 0)
          reference(
              settings.clusterArea(),
              map.clusters().get(settings.cluster()).areaCount(),
              true,
              "area within cluster");
      }
    }

    for (int i = 0; i < map.reachabilities().size(); i++) {
      Reachability reach = map.reachabilities().get(i);
      reference(reach.area(), map.areas().size(), i == 0, "reachable area");
      if (reach.hasGeometryReferences()) {
        signedReference(reach.face(), map.faces().size(), true, "reachability face");
        signedReference(reach.edge(), map.edges().size(), true, "reachability edge");
      }
    }

    for (Node node : map.nodes()) {
      reference(node.plane(), map.planes().size(), true, "node plane");
      child(node.front(), map);
      child(node.back(), map);
    }
    acyclicNodes(map);

    for (int i = 0; i < map.portals().size(); i++) {
      Portal portal = map.portals().get(i);
      reference(portal.area(), map.areas().size(), i == 0, "portal area");
      reference(portal.frontCluster(), map.clusters().size(), i == 0, "front cluster");
      reference(portal.backCluster(), map.clusters().size(), i == 0, "back cluster");
      if (i == 0) continue;
      if (map.areaSettings().get(portal.area()).cluster() != -i)
        throw bad("Portal/area cluster references disagree");
      reference(
          portal.frontClusterArea(),
          map.clusters().get(portal.frontCluster()).areaCount(),
          true,
          "portal front cluster-area");
      reference(
          portal.backClusterArea(),
          map.clusters().get(portal.backCluster()).areaCount(),
          true,
          "portal back cluster-area");
    }

    boolean[] portalRanges = new boolean[map.portalIndices().size()];
    for (int i = 0; i < map.clusters().size(); i++) {
      Cluster cluster = map.clusters().get(i);
      if (cluster.areaCount() < 0
          || cluster.areaCount() > map.areas().size()
          || cluster.reachabilityAreaCount() < 0
          || cluster.reachabilityAreaCount() > cluster.areaCount())
        throw bad("Invalid AAS cluster area counts");
      claim(cluster.firstPortal(), cluster.portalCount(), portalRanges, "cluster portals");
      for (int j = cluster.firstPortal(); j < cluster.firstPortal() + cluster.portalCount(); j++) {
        Portal portal = map.portals().get(map.portalIndices().get(j));
        if (portal.frontCluster() != i && portal.backCluster() != i)
          throw bad("Cluster/portal references disagree");
      }
    }
  }

  private static void acyclicNodes(AasMap map) throws AasFormatException {
    byte[] state = new byte[map.nodes().size()];
    ArrayDeque<Integer> stack = new ArrayDeque<>();
    for (int root = 1; root < state.length; root++) {
      if (state[root] != 0) continue;
      stack.push(root);
      while (!stack.isEmpty()) {
        int entry = stack.pop();
        if (entry < 0) {
          state[-entry] = 2;
          continue;
        }
        if (state[entry] == 2) continue;
        if (state[entry] == 1) throw bad("Cyclic AAS node graph");
        state[entry] = 1;
        stack.push(-entry);
        Node node = map.nodes().get(entry);
        if (node.front() > 0) stack.push(node.front());
        if (node.back() > 0) stack.push(node.back());
      }
    }
  }

  private static void child(int child, AasMap map) throws AasFormatException {
    if (child > 0) reference(child, map.nodes().size(), false, "child node");
    else if (child < 0) signedReference(child, map.areas().size(), false, "node area leaf");
  }

  private static void claim(int first, int count, boolean[] owners, String label)
      throws AasFormatException {
    if (first < 0 || count < 0 || (long) first + count > owners.length)
      throw bad("AAS " + label + " range outside index array");
    for (int i = first; i < first + count; i++) {
      if (owners[i]) throw bad("Overlapping AAS " + label + " ranges");
      owners[i] = true;
    }
  }

  private static void reference(int index, int size, boolean allowZero, String label)
      throws AasFormatException {
    if (index < (allowZero ? 0 : 1) || index >= size)
      throw bad("Invalid AAS " + label + " reference " + index);
  }

  private static int signedReference(int index, int size, boolean allowZero, String label)
      throws AasFormatException {
    if (index == Integer.MIN_VALUE) throw bad("Unrepresentable signed AAS " + label);
    int absolute = Math.abs(index);
    reference(absolute, size, allowZero, label);
    return absolute;
  }

  private static void bounds(Vec3 min, Vec3 max, String label) throws AasFormatException {
    if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z())
      throw bad("Inverted AAS " + label + " bounds");
  }

  private static AasFormatException bad(String message) {
    return new AasFormatException(message);
  }
}
