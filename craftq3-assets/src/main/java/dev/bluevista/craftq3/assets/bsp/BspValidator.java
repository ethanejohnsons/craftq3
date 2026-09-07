package dev.bluevista.craftq3.assets.bsp;

import static dev.bluevista.craftq3.assets.bsp.BspReader.bad;

import dev.bluevista.craftq3.assets.bsp.BspMap.*;
import java.util.ArrayDeque;

final class BspValidator {
  private BspValidator() {}

  static void validate(BspMap m) throws BspFormatException {
    for (Node n : m.nodes()) {
      index(n.plane(), m.planes().size(), "node plane");
      child(n.front(), m);
      child(n.back(), m);
    }
    acyclic(m);
    for (Leaf l : m.leaves()) {
      if (l.cluster() < -1
          || (m.visibility().clusters() > 0 && l.cluster() >= m.visibility().clusters()))
        throw bad("Invalid leaf cluster");
      range(l.firstFace(), l.faceCount(), m.leafFaces().size(), "leaf faces");
      range(l.firstBrush(), l.brushCount(), m.leafBrushes().size(), "leaf brushes");
    }
    for (int i : m.leafFaces()) index(i, m.faces().size(), "leaf face");
    for (int i : m.leafBrushes()) index(i, m.brushes().size(), "leaf brush");
    for (Model model : m.models()) {
      range(model.firstFace(), model.faceCount(), m.faces().size(), "model faces");
      range(model.firstBrush(), model.brushCount(), m.brushes().size(), "model brushes");
    }
    for (Brush b : m.brushes()) {
      range(b.firstSide(), b.sideCount(), m.brushSides().size(), "brush sides");
      index(b.texture(), m.textures().size(), "brush texture");
    }
    for (BrushSide side : m.brushSides()) {
      index(side.plane(), m.planes().size(), "brush side plane");
      index(side.texture(), m.textures().size(), "brush side texture");
    }
    for (Effect effect : m.effects()) {
      optional(effect.brush(), m.brushes().size(), "effect brush");
      if (effect.brush() >= 0)
        optional(
            effect.visibleSide(),
            m.brushes().get(effect.brush()).sideCount(),
            "effect visible side");
    }
    long meshChecks = 0;
    for (Face face : m.faces()) {
      index(face.texture(), m.textures().size(), "face texture");
      // Original q3dm17 flares leave this unused field at zero despite an empty effects lump.
      // Mesh surfaces still require a real effect or the normal -1 sentinel.
      if (!(face.type() == 4 && face.effect() == 0 && m.effects().isEmpty()))
        optional(face.effect(), m.effects().size(), "face effect");
      if (face.lightmap() < -3) throw bad("Invalid lightmap sentinel");
      if (face.lightmap() >= 0) index(face.lightmap(), m.lightmaps().size(), "face lightmap");
      if (face.type() < 1 || face.type() > 4) throw bad("Unsupported surface type " + face.type());
      range(face.firstVertex(), face.vertexCount(), m.vertices().size(), "face vertices");
      // Patches use only their control grid; compiler output can leave arbitrary index fields.
      // Keep the existing index checks for every other surface type.
      if (face.type() != 2) {
        range(
            face.firstMeshVertex(),
            face.meshVertexCount(),
            m.meshVertices().size(),
            "face meshverts");
        meshChecks += face.meshVertexCount();
        if (meshChecks > 8_000_000) throw bad("Face validation work budget exceeded");
        if ((face.type() == 1 || face.type() == 3) && face.meshVertexCount() % 3 != 0)
          throw bad("Incomplete triangle");
        for (int i = 0; i < face.meshVertexCount(); i++)
          index(
              m.meshVertices().get(face.firstMeshVertex() + i),
              face.vertexCount(),
              "relative mesh vertex");
      }
      if (face.type() == 2
          && (face.patchWidth() < 3
              || face.patchHeight() < 3
              || (face.patchWidth() & 1) == 0
              || (face.patchHeight() & 1) == 0
              || (long) face.patchWidth() * face.patchHeight() != face.vertexCount()))
        throw bad("Invalid patch control grid");
    }
  }

  private static void child(int value, BspMap m) throws BspFormatException {
    if (value >= 0) index(value, m.nodes().size(), "node child");
    else if (-(long) value - 1 >= m.leaves().size()) throw bad("Invalid node leaf");
  }

  private static void index(int value, int size, String label) throws BspFormatException {
    if (value < 0 || value >= size)
      throw bad("Invalid " + label + " index: " + value + " / " + size);
  }

  private static void optional(int value, int size, String label) throws BspFormatException {
    if (value != -1) index(value, size, label);
  }

  private static void range(int start, int count, int size, String label)
      throws BspFormatException {
    if (start < 0 || count < 0 || (long) start + count > size)
      throw bad("Invalid " + label + " range");
  }

  /** Iterative DFS also checks unreachable nodes; no recursion on attacker-controlled trees. */
  private static void acyclic(BspMap m) throws BspFormatException {
    byte[] state = new byte[m.nodes().size()];
    ArrayDeque<Integer> stack = new ArrayDeque<>();
    for (int root = 0; root < state.length; root++) {
      if (state[root] != 0) continue;
      stack.push(root);
      while (!stack.isEmpty()) {
        int item = stack.pop();
        if (item < 0) {
          state[~item] = 2;
          continue;
        }
        if (state[item] == 1) throw bad("Cyclic BSP tree");
        if (state[item] == 2) continue;
        state[item] = 1;
        stack.push(~item);
        Node node = m.nodes().get(item);
        if (node.back() >= 0) stack.push(node.back());
        if (node.front() >= 0) stack.push(node.front());
      }
    }
  }
}
