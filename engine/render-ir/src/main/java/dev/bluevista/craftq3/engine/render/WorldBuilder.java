package dev.bluevista.craftq3.engine.render;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.bsp.SurfaceType;
import dev.bluevista.craftq3.engine.render.material.MaterialProgram;
import dev.bluevista.craftq3.engine.render.material.StageEvaluator;
import dev.bluevista.craftq3.engine.render.material.VertexArrays;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Tessellates BSP faces into {@link WorldGeometry}: indexed faces, Bernstein patches, batching. */
final class WorldBuilder {
  static final int MAX_VERTICES = 8_000_000;
  static final int MAX_TRIANGLES = 8_000_000;
  private static final Log LOG = Log.get(WorldBuilder.class);

  private WorldBuilder() {}

  static WorldGeometry build(
      String name,
      BspMap map,
      int subdivisions,
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
        else if (type == SurfaceType.PATCH) patch(name, map, face, raw, subdivisions, mesh);
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

  /**
   * A patch of (w-1)/2 by (h-1)/2 biquadratic blocks, sampled on one shared grid: every block uses
   * the same parameter steps, so neighbouring blocks and patches with matching control edges meet
   * without cracks and without camera-dependent level of detail.
   */
  private static void patch(
      String name, BspMap map, int face, VertexArrays raw, int subdivisions, MeshBuffer mesh) {
    BspMap.Faces faces = map.faces();
    int width = faces.patchWidth(face), height = faces.patchHeight(face);
    if (width < 3
        || height < 3
        || (width & 1) == 0
        || (height & 1) == 0
        || (long) width * height > faces.vertexCount(face)) {
      LOG.warnOnce(
          "patch:" + name + ":" + face,
          name + ": patch face " + face + " has invalid size " + width + "x" + height);
      return;
    }
    int blocksX = (width - 1) / 2, blocksY = (height - 1) / 2;
    int columns = blocksX * subdivisions + 1, rows = blocksY * subdivisions + 1;
    mesh.reserveVertices(columns * rows);
    mesh.reserveIndices(Math.multiplyExact((columns - 1) * (rows - 1), 6));
    int base = mesh.vertexCount();
    int first = faces.firstVertex(face);
    float[] p = raw.positions(), n = raw.normals(), uv = raw.texCoords(), lm = raw.lightmapCoords();
    double[] a = new double[3], b = new double[3];
    for (int row = 0; row < rows; row++) {
      int by = Math.min(row / subdivisions, blocksY - 1);
      double v = (row - by * subdivisions) / (double) subdivisions;
      basis(v, b);
      for (int column = 0; column < columns; column++) {
        int bx = Math.min(column / subdivisions, blocksX - 1);
        double u = (column - bx * subdivisions) / (double) subdivisions;
        basis(u, a);
        double px = 0, py = 0, pz = 0, nx = 0, ny = 0, nz = 0;
        double s = 0, t = 0, ls = 0, lt = 0, red = 0, green = 0, blue = 0, alpha = 0;
        for (int j = 0; j < 3; j++)
          for (int i = 0; i < 3; i++) {
            int c = first + (2 * by + j) * width + 2 * bx + i;
            double w = a[i] * b[j];
            px += p[c * 3] * w;
            py += p[c * 3 + 1] * w;
            pz += p[c * 3 + 2] * w;
            nx += n[c * 3] * w;
            ny += n[c * 3 + 1] * w;
            nz += n[c * 3 + 2] * w;
            s += uv[c * 2] * w;
            t += uv[c * 2 + 1] * w;
            ls += lm[c * 2] * w;
            lt += lm[c * 2 + 1] * w;
            int rgba = raw.rgba(c);
            red += (rgba >>> 24) * w;
            green += ((rgba >>> 16) & 255) * w;
            blue += ((rgba >>> 8) & 255) * w;
            alpha += (rgba & 255) * w;
          }
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length > 1e-12) {
          double inverse = 1 / length;
          nx *= inverse;
          ny *= inverse;
          nz *= inverse;
        }
        int color = channel(red) << 24 | channel(green) << 16 | channel(blue) << 8 | channel(alpha);
        mesh.addVertex(
            (float) px,
            (float) py,
            (float) pz,
            (float) nx,
            (float) ny,
            (float) nz,
            (float) s,
            (float) t,
            (float) ls,
            (float) lt,
            StageEvaluator.restoreMapLightColor(color, WorldGeometry.MAP_OVERBRIGHT_BITS));
      }
    }
    // Q3 mesh indices wind opposite the control grid; reverse the grid traversal so patches share
    // the culling convention of indexed faces.
    for (int row = 0; row < rows - 1; row++)
      for (int column = 0; column < columns - 1; column++) {
        int v00 = base + row * columns + column, v01 = v00 + 1;
        int v10 = v00 + columns, v11 = v10 + 1;
        mesh.addTriangle(v00, v10, v01);
        mesh.addTriangle(v01, v10, v11);
      }
  }

  private static void basis(double t, double[] out) {
    out[0] = (1 - t) * (1 - t);
    out[1] = 2 * t * (1 - t);
    out[2] = t * t;
  }

  private static void restoreColors(MeshBuffer mesh, int first, int count) {
    VertexArrays arrays = mesh.arrays();
    for (int v = first; v < first + count; v++)
      VertexArrays.putRgba(
          mesh.colors(),
          v,
          StageEvaluator.restoreMapLightColor(arrays.rgba(v), WorldGeometry.MAP_OVERBRIGHT_BITS));
  }

  private static int channel(double value) {
    return Math.clamp((int) Math.round(value), 0, 255);
  }
}
