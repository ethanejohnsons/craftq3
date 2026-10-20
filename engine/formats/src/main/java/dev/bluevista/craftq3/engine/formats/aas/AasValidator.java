package dev.bluevista.craftq3.engine.formats.aas;

import static dev.bluevista.craftq3.engine.formats.aas.AasMap.CLUSTER_AREA_COUNT;
import static dev.bluevista.craftq3.engine.formats.aas.AasMap.CLUSTER_FIRST_PORTAL;
import static dev.bluevista.craftq3.engine.formats.aas.AasMap.CLUSTER_PORTAL_COUNT;
import static dev.bluevista.craftq3.engine.formats.aas.AasMap.CLUSTER_REACH_AREA_COUNT;
import static dev.bluevista.craftq3.engine.formats.aas.AasMap.EDGE_END;
import static dev.bluevista.craftq3.engine.formats.aas.AasMap.EDGE_START;
import static dev.bluevista.craftq3.engine.formats.aas.AasMap.FACE_BACK_AREA;
import static dev.bluevista.craftq3.engine.formats.aas.AasMap.FACE_EDGE_COUNT;
import static dev.bluevista.craftq3.engine.formats.aas.AasMap.FACE_FIRST_EDGE;
import static dev.bluevista.craftq3.engine.formats.aas.AasMap.FACE_FRONT_AREA;
import static dev.bluevista.craftq3.engine.formats.aas.AasMap.FACE_PLANE;
import static dev.bluevista.craftq3.engine.formats.aas.AasMap.NODE_BACK;
import static dev.bluevista.craftq3.engine.formats.aas.AasMap.NODE_FRONT;
import static dev.bluevista.craftq3.engine.formats.aas.AasMap.NODE_PLANE;
import static dev.bluevista.craftq3.engine.formats.aas.AasMap.PORTAL_AREA;
import static dev.bluevista.craftq3.engine.formats.aas.AasMap.PORTAL_BACK_CLUSTER;
import static dev.bluevista.craftq3.engine.formats.aas.AasMap.PORTAL_BACK_CLUSTER_AREA;
import static dev.bluevista.craftq3.engine.formats.aas.AasMap.PORTAL_FRONT_CLUSTER;
import static dev.bluevista.craftq3.engine.formats.aas.AasMap.PORTAL_FRONT_CLUSTER_AREA;
import static dev.bluevista.craftq3.engine.formats.aas.AasMap.SETTINGS_CLUSTER;
import static dev.bluevista.craftq3.engine.formats.aas.AasMap.SETTINGS_CLUSTER_AREA;
import static dev.bluevista.craftq3.engine.formats.aas.AasMap.SETTINGS_CONTENTS;
import static dev.bluevista.craftq3.engine.formats.aas.AasMap.SETTINGS_FIRST_REACH;
import static dev.bluevista.craftq3.engine.formats.aas.AasMap.SETTINGS_REACH_COUNT;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * AAS cross-reference checks. {@link #validate} runs on every read and rejects only indices botlib
 * would follow out of bounds and cyclic node graphs. {@link #strictIssues} reports redundant fields
 * that disagree (aliased ranges, mismatched area/face sides, portal back-references), which the
 * original loader never checks; it is for validation tooling. All checks are linear in the stored
 * elements.
 */
public final class AasValidator {
  private AasValidator() {}

  static void validate(AasMap map) {
    if (map.areas().count() != map.areaSettings().count())
      throw new AasFormatException(
          "AAS has "
              + map.areas().count()
              + " areas but "
              + map.areaSettings().count()
              + " settings");
    validateEdgesAndFaces(map);
    validateAreas(map);
    validateReachabilities(map);
    validateNodes(map);
    validatePortalsAndClusters(map);
  }

  private static void validateEdgesAndFaces(AasMap m) {
    // Record 0 of edges and faces is an unused placeholder; compilers may leave its fields stale.
    for (int i = 1; i < m.edges().count(); i++) {
      reference(m.edges().get(i, EDGE_START), m.vertices().count(), true, "edge vertex");
      reference(m.edges().get(i, EDGE_END), m.vertices().count(), true, "edge vertex");
    }
    for (int i = 0; i < m.edgeIndices().count(); i++)
      signedReference(m.edgeIndices().get(i), m.edges().count(), true, "edge index");
    for (int i = 0; i < m.faceIndices().count(); i++)
      signedReference(m.faceIndices().get(i), m.faces().count(), true, "face index");
    for (int i = 0; i < m.portalIndices().count(); i++)
      reference(m.portalIndices().get(i), m.portals().count(), true, "portal index");
    for (int i = 1; i < m.faces().count(); i++) {
      reference(m.faces().get(i, FACE_PLANE), m.planes().count(), true, "face plane");
      reference(m.faces().get(i, FACE_FRONT_AREA), m.areas().count(), true, "front area");
      reference(m.faces().get(i, FACE_BACK_AREA), m.areas().count(), true, "back area");
      range(
          m.faces().get(i, FACE_FIRST_EDGE),
          m.faces().get(i, FACE_EDGE_COUNT),
          m.edgeIndices().count(),
          "face edges");
    }
  }

  private static void validateAreas(AasMap m) {
    for (int i = 0; i < m.areas().count(); i++) {
      range(m.areas().firstFace(i), m.areas().faceCount(i), m.faceIndices().count(), "area faces");
      range(
          m.areaSettings().get(i, SETTINGS_FIRST_REACH),
          m.areaSettings().get(i, SETTINGS_REACH_COUNT),
          m.reachabilities().count(),
          "area reachabilities");
      int cluster = m.areaSettings().get(i, SETTINGS_CLUSTER);
      if (cluster < 0) {
        signedReference(cluster, m.portals().count(), false, "area portal");
      } else {
        reference(cluster, m.clusters().count(), true, "area cluster");
        if (cluster > 0)
          reference(
              m.areaSettings().get(i, SETTINGS_CLUSTER_AREA),
              m.clusters().get(cluster, CLUSTER_AREA_COUNT),
              true,
              "area within cluster");
      }
    }
  }

  private static void validateReachabilities(AasMap m) {
    AasMap.Reachabilities reach = m.reachabilities();
    for (int i = 0; i < reach.count(); i++) {
      reference(reach.area(i), m.areas().count(), true, "reachable area");
      if (reach.hasGeometryReferences(i)) {
        signedReference(reach.face(i), m.faces().count(), true, "reachability face");
        signedReference(reach.edge(i), m.edges().count(), true, "reachability edge");
      }
    }
  }

  private static void validateNodes(AasMap m) {
    for (int i = 0; i < m.nodes().count(); i++) {
      reference(m.nodes().get(i, NODE_PLANE), m.planes().count(), true, "node plane");
      child(m.nodes().get(i, NODE_FRONT), m);
      child(m.nodes().get(i, NODE_BACK), m);
    }
    acyclicNodes(m.nodes());
  }

  private static void validatePortalsAndClusters(AasMap m) {
    for (int i = 0; i < m.portals().count(); i++) {
      reference(m.portals().get(i, PORTAL_AREA), m.areas().count(), true, "portal area");
      int front = m.portals().get(i, PORTAL_FRONT_CLUSTER);
      int back = m.portals().get(i, PORTAL_BACK_CLUSTER);
      reference(front, m.clusters().count(), true, "front cluster");
      reference(back, m.clusters().count(), true, "back cluster");
      if (i == 0) continue;
      reference(
          m.portals().get(i, PORTAL_FRONT_CLUSTER_AREA),
          m.clusters().get(front, CLUSTER_AREA_COUNT),
          true,
          "portal front cluster-area");
      reference(
          m.portals().get(i, PORTAL_BACK_CLUSTER_AREA),
          m.clusters().get(back, CLUSTER_AREA_COUNT),
          true,
          "portal back cluster-area");
    }
    for (int i = 0; i < m.clusters().count(); i++) {
      int areaCount = m.clusters().get(i, CLUSTER_AREA_COUNT);
      int reachAreaCount = m.clusters().get(i, CLUSTER_REACH_AREA_COUNT);
      if (areaCount < 0 || areaCount > m.areas().count() || reachAreaCount < 0)
        throw new AasFormatException("Invalid AAS cluster " + i + " area counts");
      range(
          m.clusters().get(i, CLUSTER_FIRST_PORTAL),
          m.clusters().get(i, CLUSTER_PORTAL_COUNT),
          m.portalIndices().count(),
          "cluster portals");
    }
  }

  /** Redundant fields that disagree; the original loader accepts all of these. */
  public static List<String> strictIssues(AasMap map) {
    List<String> issues = new ArrayList<>(map.diagnostics());
    strictGeometry(map, issues);
    strictAreas(map, issues);
    strictPortals(map, issues);
    return issues;
  }

  private static void strictGeometry(AasMap m, List<String> issues) {
    for (AasMap.BoundingBox box : m.boundingBoxes())
      if (inverted(box.mins().toArray(), box.maxs().toArray()))
        issues.add("Inverted bounding box " + box);
    for (int i = 0; i < m.planes().count(); i++) {
      float x = m.planes().normal(i, 0);
      float y = m.planes().normal(i, 1);
      float z = m.planes().normal(i, 2);
      if (m.planes().type(i) < 0 || m.planes().type(i) > 5 || x * x + y * y + z * z < 1e-12f)
        issues.add("Invalid plane " + i);
    }
    boolean[] edgeOwners = new boolean[m.edgeIndices().count()];
    for (int i = 0; i < m.faces().count(); i++)
      claim(
          m.faces().get(i, FACE_FIRST_EDGE),
          m.faces().get(i, FACE_EDGE_COUNT),
          edgeOwners,
          "face edges",
          issues);
  }

  private static void strictAreas(AasMap m, List<String> issues) {
    boolean[] faceOwners = new boolean[m.faceIndices().count()];
    boolean[] reachOwners = new boolean[m.reachabilities().count()];
    for (int i = 0; i < m.areas().count(); i++) {
      if (m.areas().number(i) != i) issues.add("Area " + i + " is numbered " + m.areas().number(i));
      float[] mins = m.areas().mins(i).toArray();
      float[] maxs = m.areas().maxs(i).toArray();
      if (inverted(mins, maxs)) issues.add("Inverted bounds of area " + i);
      int first = m.areas().firstFace(i);
      int count = m.areas().faceCount(i);
      claim(first, count, faceOwners, "area faces", issues);
      for (int j = first; j < first + count; j++) {
        int faceIndex = m.faceIndices().get(j);
        int face = Math.abs(faceIndex);
        int side = faceIndex > 0 ? FACE_FRONT_AREA : FACE_BACK_AREA;
        if (m.faces().get(face, side) != i)
          issues.add("Area " + i + " face " + faceIndex + " names a different area");
      }
      claim(
          m.areaSettings().get(i, SETTINGS_FIRST_REACH),
          m.areaSettings().get(i, SETTINGS_REACH_COUNT),
          reachOwners,
          "area reachabilities",
          issues);
      int cluster = m.areaSettings().get(i, SETTINGS_CLUSTER);
      if (cluster < 0) {
        int portal = -cluster;
        if ((m.areaSettings().get(i, SETTINGS_CONTENTS) & AasMap.AREACONTENTS_CLUSTERPORTAL) == 0
            || m.portals().get(portal, PORTAL_AREA) != i)
          issues.add("Area " + i + " and portal " + portal + " disagree");
      }
    }
  }

  private static void strictPortals(AasMap m, List<String> issues) {
    for (int i = 1; i < m.portals().count(); i++) {
      int area = m.portals().get(i, PORTAL_AREA);
      if (m.areaSettings().get(area, SETTINGS_CLUSTER) != -i)
        issues.add("Portal " + i + " area " + area + " does not name the portal");
    }
    boolean[] portalOwners = new boolean[m.portalIndices().count()];
    for (int i = 0; i < m.clusters().count(); i++) {
      int first = m.clusters().get(i, CLUSTER_FIRST_PORTAL);
      int count = m.clusters().get(i, CLUSTER_PORTAL_COUNT);
      if (m.clusters().get(i, CLUSTER_REACH_AREA_COUNT) > m.clusters().get(i, CLUSTER_AREA_COUNT))
        issues.add("Cluster " + i + " has more reachability areas than areas");
      claim(first, count, portalOwners, "cluster portals", issues);
      for (int j = first; j < first + count; j++) {
        int portal = m.portalIndices().get(j);
        if (m.portals().get(portal, PORTAL_FRONT_CLUSTER) != i
            && m.portals().get(portal, PORTAL_BACK_CLUSTER) != i)
          issues.add("Cluster " + i + " lists portal " + portal + " that does not border it");
      }
    }
  }

  private static boolean inverted(float[] mins, float[] maxs) {
    return mins[0] > maxs[0] || mins[1] > maxs[1] || mins[2] > maxs[2];
  }

  private static void claim(
      int first, int count, boolean[] owners, String label, List<String> issues) {
    for (int i = first; i < first + count; i++) {
      if (owners[i]) {
        issues.add("Overlapping " + label + " ranges at " + i);
        return;
      }
      owners[i] = true;
    }
  }

  /** Iterative DFS from every node; node 0 is the unused solid sentinel. */
  private static void acyclicNodes(AasMap.Records nodes) {
    int count = nodes.count();
    byte[] state = new byte[count];
    int[] stack = new int[Math.max(16, count * 3)];
    for (int root = 1; root < count; root++) {
      if (state[root] != 0) continue;
      int top = 0;
      stack[top++] = root;
      while (top > 0) {
        int entry = stack[--top];
        if (entry < 0) {
          state[-entry] = 2;
          continue;
        }
        if (state[entry] == 2) continue;
        if (state[entry] == 1) throw new AasFormatException("Cyclic AAS node graph at " + entry);
        state[entry] = 1;
        if (top + 3 > stack.length) stack = Arrays.copyOf(stack, stack.length * 2);
        stack[top++] = -entry;
        int front = nodes.get(entry, NODE_FRONT);
        int back = nodes.get(entry, NODE_BACK);
        if (front > 0) stack[top++] = front;
        if (back > 0) stack[top++] = back;
      }
    }
  }

  private static void child(int child, AasMap map) {
    if (child > 0) reference(child, map.nodes().count(), false, "child node");
    else if (child < 0) signedReference(child, map.areas().count(), false, "node area leaf");
  }

  private static void range(int first, int count, int size, String label) {
    if (first < 0 || count < 0 || (long) first + count > size)
      throw new AasFormatException(
          "AAS " + label + " range [" + first + ", +" + count + ") outside " + size);
  }

  private static void reference(int index, int size, boolean allowZero, String label) {
    if (index < (allowZero ? 0 : 1) || index >= size)
      throw new AasFormatException("Invalid AAS " + label + " reference " + index + " of " + size);
  }

  private static int signedReference(int index, int size, boolean allowZero, String label) {
    if (index == Integer.MIN_VALUE)
      throw new AasFormatException("Unrepresentable signed AAS " + label);
    int absolute = Math.abs(index);
    reference(absolute, size, allowZero, label);
    return absolute;
  }
}
