package dev.bluevista.craftq3.engine.formats.bsp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Cross-reference checks. {@link #validate} runs on every read and rejects references that a
 * consumer would follow out of bounds, the ones the original engine itself refuses ({@code bad
 * shaderNum}, invalid patch grids) and cyclic trees. {@link #strictIssues} lists the remaining
 * inconsistencies the original engine tolerates; it is for validation tooling only.
 */
public final class BspValidator {
  /** The renderer's {@code MAX_PATCH_SIZE}: larger control grids are refused. */
  public static final int MAX_PATCH_SIZE = 32;

  /** Bounds the per-face mesh-index scan. */
  public static final long MAX_MESH_CHECKS = 16_000_000;

  private BspValidator() {}

  static void validate(BspMap map) {
    validateTree(map);
    validateBrushes(map);
    validateModels(map);
    validateEffects(map);
    validateFaces(map);
  }

  private static void validateTree(BspMap m) {
    BspMap.Nodes nodes = m.nodes();
    for (int i = 0; i < nodes.count(); i++) {
      index(nodes.plane(i), m.planes().count(), "node plane");
      child(nodes.front(i), m);
      child(nodes.back(i), m);
    }
    acyclic(nodes);
    BspMap.Leaves leaves = m.leaves();
    for (int i = 0; i < leaves.count(); i++) {
      range(leaves.firstLeafFace(i), leaves.leafFaceCount(i), m.leafFaces().count(), "leaf faces");
      range(
          leaves.firstLeafBrush(i),
          leaves.leafBrushCount(i),
          m.leafBrushes().count(),
          "leaf brushes");
    }
    for (int i = 0; i < m.leafFaces().count(); i++)
      index(m.leafFaces().get(i), m.faces().count(), "leaf face");
    for (int i = 0; i < m.leafBrushes().count(); i++)
      index(m.leafBrushes().get(i), m.brushes().count(), "leaf brush");
  }

  private static void validateBrushes(BspMap m) {
    BspMap.Brushes brushes = m.brushes();
    for (int i = 0; i < brushes.count(); i++) {
      range(brushes.firstSide(i), brushes.sideCount(i), m.brushSides().count(), "brush sides");
      index(brushes.texture(i), m.textures().size(), "brush texture");
    }
    BspMap.BrushSides sides = m.brushSides();
    for (int i = 0; i < sides.count(); i++) {
      index(sides.plane(i), m.planes().count(), "brush side plane");
      index(sides.texture(i), m.textures().size(), "brush side texture");
    }
  }

  private static void validateModels(BspMap m) {
    for (BspMap.Model model : m.models()) {
      range(model.firstFace(), model.faceCount(), m.faces().count(), "model faces");
      range(model.firstBrush(), model.brushCount(), m.brushes().count(), "model brushes");
    }
  }

  private static void validateEffects(BspMap m) {
    for (BspMap.Effect effect : m.effects()) {
      if (effect.brush() != -1) index(effect.brush(), m.brushes().count(), "fog brush");
    }
  }

  private static void validateFaces(BspMap m) {
    BspMap.Faces faces = m.faces();
    long meshChecks = 0;
    for (int face = 0; face < faces.count(); face++) {
      index(faces.texture(face), m.textures().size(), "face texture");
      SurfaceType type = faces.surfaceType(face);
      if (type == SurfaceType.BAD || type == SurfaceType.FLARE) continue;
      range(
          faces.firstVertex(face), faces.vertexCount(face), m.vertices().count(), "face vertices");
      if (type == SurfaceType.PATCH) {
        patch(faces, face);
        continue;
      }
      range(
          faces.firstMeshVertex(face),
          faces.meshVertexCount(face),
          m.meshVertices().count(),
          "face mesh vertices");
      meshChecks += faces.meshVertexCount(face);
      if (meshChecks > MAX_MESH_CHECKS)
        throw new BspFormatException("Face validation work budget exceeded");
      int first = faces.firstMeshVertex(face);
      for (int i = 0; i < faces.meshVertexCount(face); i++)
        index(m.meshVertices().get(first + i), faces.vertexCount(face), "relative mesh vertex");
    }
  }

  /** Patches use only their control grid; compilers can leave arbitrary mesh index fields. */
  private static void patch(BspMap.Faces faces, int face) {
    int width = faces.patchWidth(face);
    int height = faces.patchHeight(face);
    if (width < 3
        || height < 3
        || width > MAX_PATCH_SIZE
        || height > MAX_PATCH_SIZE
        || (width & 1) == 0
        || (height & 1) == 0
        || width * height > faces.vertexCount(face))
      throw new BspFormatException(
          "Invalid patch control grid "
              + width
              + "x"
              + height
              + " with "
              + faces.vertexCount(face)
              + " vertices at face "
              + face);
  }

  /** Inconsistencies the original engine tolerates, for validation tooling. */
  public static List<String> strictIssues(BspMap m) {
    List<String> issues = new ArrayList<>(m.diagnostics());
    int clusters = m.visibility().clusters();
    for (int i = 0; i < m.leaves().count(); i++) {
      int cluster = m.leaves().cluster(i);
      if (cluster < -1 || (clusters > 0 && cluster >= clusters))
        issues.add("Leaf " + i + " cluster " + cluster + " outside " + clusters + " clusters");
    }
    for (int i = 0; i < m.effects().size(); i++) {
      BspMap.Effect effect = m.effects().get(i);
      if (effect.brush() >= 0
          && effect.visibleSide() != -1
          && (effect.visibleSide() < 0
              || effect.visibleSide() >= m.brushes().sideCount(effect.brush())))
        issues.add("Fog " + i + " visible side " + effect.visibleSide() + " outside its brush");
    }
    BspMap.Faces faces = m.faces();
    for (int face = 0; face < faces.count(); face++) {
      SurfaceType type = faces.surfaceType(face);
      if (type == SurfaceType.BAD)
        issues.add("Face " + face + " has surface type " + faces.type(face));
      int lightmap = faces.lightmap(face);
      if (lightmap < BspMap.LIGHTMAP_BY_VERTEX || lightmap >= m.lightmaps().count())
        issues.add("Face " + face + " lightmap " + lightmap + " outside " + m.lightmaps().count());
      if (type.indexed() && faces.meshVertexCount(face) % 3 != 0)
        issues.add("Face " + face + " has an incomplete triangle");
    }
    return issues;
  }

  private static void child(int value, BspMap m) {
    if (value >= 0) index(value, m.nodes().count(), "node child");
    else if (-(long) value - 1 >= m.leaves().count())
      throw new BspFormatException("Invalid node leaf child " + value);
  }

  private static void index(int value, int size, String label) {
    if (value < 0 || value >= size)
      throw new BspFormatException("Invalid " + label + " index " + value + " (of " + size + ")");
  }

  private static void range(int start, int count, int size, String label) {
    if (start < 0 || count < 0 || (long) start + count > size)
      throw new BspFormatException(
          "Invalid " + label + " range [" + start + ", +" + count + ") of " + size);
  }

  /**
   * Iterative depth-first search over every node, so unreachable nodes are checked too and an
   * attacker-controlled tree cannot exhaust the call stack. A node reached twice through different
   * parents is fine; only a node on its own path is a cycle.
   */
  private static void acyclic(BspMap.Nodes nodes) {
    int count = nodes.count();
    byte[] state = new byte[count]; // 0 unvisited, 1 on the current path, 2 finished
    int[] stack = new int[Math.max(16, count * 3)];
    for (int root = 0; root < count; root++) {
      if (state[root] != 0) continue;
      int top = 0;
      stack[top++] = root;
      while (top > 0) {
        int item = stack[--top];
        if (item < 0) {
          state[~item] = 2;
          continue;
        }
        if (state[item] == 1) throw new BspFormatException("Cyclic BSP tree at node " + item);
        if (state[item] == 2) continue;
        state[item] = 1;
        if (top + 3 > stack.length) stack = Arrays.copyOf(stack, stack.length * 2);
        stack[top++] = ~item;
        if (nodes.back(item) >= 0) stack[top++] = nodes.back(item);
        if (nodes.front(item) >= 0) stack[top++] = nodes.front(item);
      }
    }
  }
}
