package dev.bluevista.craftq3.engine.render;

import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.bsp.SurfaceType;
import dev.bluevista.craftq3.engine.render.material.MaterialProgram;
import dev.bluevista.craftq3.engine.render.material.StageEvaluator;
import dev.bluevista.craftq3.engine.render.material.VertexArrays;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Builds {@link WorldGeometry} from BSP faces: indexed faces, patches ({@link PatchTessellator}),
 * batching.
 */
final class WorldBuilder {
  // The largest map in the test corpus needs ~370K vertices and ~400K triangles even at
  // r_subdivisions 0; the budget leaves room for larger community maps.
  static final int MAX_VERTICES = 2_000_000;
  static final int MAX_TRIANGLES = 2_000_000;

  private WorldBuilder() {}

  static WorldGeometry build(
      String name,
      BspMap map,
      RenderScene.Options options,
      int[] faceMaterial,
      List<MaterialProgram> programs) {
    BspMap.Faces faces = map.faces();
    int faceCount = faces.count();
    int[] surfaces = new int[faceCount * WorldGeometry.SURFACE_INTS];
    float[] bounds = new float[faceCount * 6];
    int[] localFirstIndex = new int[faceCount];
    MeshBuffer mesh = new MeshBuffer(MAX_VERTICES, MAX_TRIANGLES * 3);
    BspMap.Vertices source = map.vertices();
    VertexArrays raw =
        new VertexArrays(
            source.positionsCopy(),
            source.normalsCopy(),
            source.texCoordsCopy(),
            source.lightmapCoordsCopy(),
            source.colorsCopy());
    int[] meshVertices = map.meshVertices().toArray();
    Arrays.fill(surfaces, -1);
    for (int i = 0; i < faceCount; i++) {
      surfaces[i * WorldGeometry.SURFACE_INTS + WorldGeometry.MODEL] = -1;
      surfaces[i * WorldGeometry.SURFACE_INTS + WorldGeometry.BATCH] = -1;
    }
    for (int model = 0; model < map.models().size(); model++) {
      BspMap.Model m = map.models().get(model);
      for (int face = m.firstFace(); face < m.firstFace() + m.faceCount(); face++)
        if (surfaces[face * WorldGeometry.SURFACE_INTS + WorldGeometry.MODEL] < 0)
          surfaces[face * WorldGeometry.SURFACE_INTS + WorldGeometry.MODEL] = model;
    }
    boolean[] inModel = new boolean[faceCount];
    for (int face = 0; face < faceCount; face++)
      inModel[face] = surfaces[face * WorldGeometry.SURFACE_INTS + WorldGeometry.MODEL] >= 0;
    PatchTessellator patches = PatchTessellator.plan(name, map, raw, inModel, options);
    for (int face = 0; face < faceCount; face++) {
      int at = face * WorldGeometry.SURFACE_INTS;
      int texture = faces.texture(face);
      int effect = faces.effect(face);
      surfaces[at + WorldGeometry.TYPE] = faces.surfaceType(face).id();
      surfaces[at + WorldGeometry.MATERIAL] = faceMaterial[face];
      surfaces[at + WorldGeometry.LIGHTMAP] = faces.lightmap(face);
      surfaces[at + WorldGeometry.FOG] = effect >= 0 && effect < map.effects().size() ? effect : -1;
      surfaces[at + WorldGeometry.FLAGS] =
          texture >= 0 && texture < map.textures().size()
              ? map.textures().get(texture).surfaceFlags()
              : 0;
      int firstVertex = mesh.vertexCount();
      int firstIndex = mesh.indexCount();
      if (surfaces[at + WorldGeometry.MODEL] >= 0) {
        SurfaceType type = faces.surfaceType(face);
        if (type.indexed()) indexed(map, face, raw, meshVertices, mesh);
        else if (type == SurfaceType.PATCH) patches.emit(face, mesh);
      }
      surfaces[at + WorldGeometry.FIRST_VERTEX] = firstVertex;
      surfaces[at + WorldGeometry.VERTEX_COUNT] = mesh.vertexCount() - firstVertex;
      surfaces[at + WorldGeometry.INDEX_COUNT] = mesh.indexCount() - firstIndex;
      localFirstIndex[face] = firstIndex;
      if (mesh.vertexCount() > firstVertex)
        mesh.bounds(firstVertex, mesh.vertexCount() - firstVertex, bounds, face * 6);
      else for (int k = 0; k < 6; k++) bounds[face * 6 + k] = faces.lightmapOrigin(face, k % 3);
    }

    // Group drawable faces into batches and lay their indices out batch by batch.
    List<int[]> keys = new ArrayList<>();
    for (int face = 0; face < faceCount; face++) {
      int at = face * WorldGeometry.SURFACE_INTS;
      int model = surfaces[at + WorldGeometry.MODEL];
      if (model < 0 || surfaces[at + WorldGeometry.INDEX_COUNT] == 0) continue;
      MaterialProgram program = programs.get(surfaces[at + WorldGeometry.MATERIAL]);
      if (program.noDraw() || (surfaces[at + WorldGeometry.FLAGS] & WorldGeometry.SURF_NODRAW) != 0)
        continue;
      keys.add(
          new int[] {
            model,
            program.sortRank(),
            surfaces[at + WorldGeometry.MATERIAL],
            surfaces[at + WorldGeometry.LIGHTMAP],
            surfaces[at + WorldGeometry.FOG],
            face
          });
    }
    keys.sort(
        Comparator.<int[]>comparingInt(k -> k[0])
            .thenComparingInt(k -> k[1])
            .thenComparingInt(k -> k[2])
            .thenComparingInt(k -> k[3])
            .thenComparingInt(k -> k[4])
            .thenComparingInt(k -> k[5]));
    int[] localIndices = mesh.indices();
    int[] indices = new int[mesh.indexCount()];
    int[] batchSurfaces = new int[keys.size()];
    List<DrawBatch> batches = new ArrayList<>();
    int[] modelFirst = new int[map.models().size()];
    int[] modelCount = new int[map.models().size()];
    int written = 0;
    int k = 0;
    while (k < keys.size()) {
      int start = k;
      int[] head = keys.get(start);
      int first = written;
      for (; k < keys.size() && sameBatch(head, keys.get(k)); k++) {
        int face = keys.get(k)[5];
        int at = face * WorldGeometry.SURFACE_INTS;
        int count = surfaces[at + WorldGeometry.INDEX_COUNT];
        System.arraycopy(localIndices, localFirstIndex[face], indices, written, count);
        surfaces[at + WorldGeometry.FIRST_INDEX] = written;
        surfaces[at + WorldGeometry.BATCH] = batches.size();
        batchSurfaces[k] = face;
        written += count;
      }
      int model = head[0];
      if (modelCount[model] == 0) modelFirst[model] = batches.size();
      modelCount[model]++;
      boolean sky =
          programs.get(head[2]).isSky()
              || (surfaces[head[5] * WorldGeometry.SURFACE_INTS + WorldGeometry.FLAGS]
                      & WorldGeometry.SURF_SKY)
                  != 0;
      batches.add(
          new DrawBatch(
              batches.size(),
              model,
              head[2],
              head[3],
              head[4],
              first,
              written - first,
              start,
              k - start,
              sky,
              head[1]));
    }
    int unbatchedFirst = written;
    for (int face = 0; face < faceCount; face++) {
      int at = face * WorldGeometry.SURFACE_INTS;
      if (surfaces[at + WorldGeometry.BATCH] >= 0) continue;
      int count = surfaces[at + WorldGeometry.INDEX_COUNT];
      System.arraycopy(localIndices, localFirstIndex[face], indices, written, count);
      surfaces[at + WorldGeometry.FIRST_INDEX] = written;
      written += count;
    }
    int vertices = mesh.vertexCount();
    VertexArrays packed =
        new VertexArrays(
            Arrays.copyOf(mesh.positions(), vertices * 3),
            Arrays.copyOf(mesh.normals(), vertices * 3),
            Arrays.copyOf(mesh.texCoords(), vertices * 2),
            Arrays.copyOf(mesh.lightmapCoords(), vertices * 2),
            Arrays.copyOf(mesh.colors(), vertices * 4));
    return new WorldGeometry(
        packed,
        vertices,
        indices,
        surfaces,
        bounds,
        batches,
        batchSurfaces,
        modelFirst,
        modelCount,
        unbatchedFirst);
  }

  private static boolean sameBatch(int[] a, int[] b) {
    return a[0] == b[0] && a[2] == b[2] && a[3] == b[3] && a[4] == b[4];
  }

  /** Planar faces and triangle soups: copy the face's vertices and its relative mesh indices. */
  private static void indexed(
      BspMap map, int face, VertexArrays raw, int[] meshVertices, MeshBuffer mesh) {
    BspMap.Faces faces = map.faces();
    int count = faces.vertexCount(face);
    int indexCount = faces.meshVertexCount(face) / 3 * 3;
    if (count == 0 || indexCount == 0) return;
    int base = mesh.copyVertices(raw, faces.firstVertex(face), count);
    restoreColors(mesh, base, count);
    mesh.copyIndices(meshVertices, faces.firstMeshVertex(face), indexCount, base);
  }

  private static void restoreColors(MeshBuffer mesh, int first, int count) {
    VertexArrays arrays = mesh.arrays();
    for (int v = first; v < first + count; v++)
      VertexArrays.putRgba(
          mesh.colors(),
          v,
          StageEvaluator.restoreMapLightColor(arrays.rgba(v), WorldGeometry.MAP_OVERBRIGHT_BITS));
  }
}
