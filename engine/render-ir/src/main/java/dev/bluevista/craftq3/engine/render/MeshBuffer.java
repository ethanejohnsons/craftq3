package dev.bluevista.craftq3.engine.render;

import dev.bluevista.craftq3.engine.render.material.VertexArrays;
import java.util.Arrays;

/**
 * A growable structure-of-arrays triangle mesh: positions and normals (3 floats per vertex), base
 * and lightmap texture coordinates (2 floats), colours (4 bytes, R G B A) and 32-bit indices.
 * Reused across frames: {@link #clear()} keeps the storage, so steady-state frames allocate
 * nothing.
 */
public final class MeshBuffer {
  private static final int INITIAL_VERTICES = 256;
  private final int maxVertices;
  private final int maxIndices;
  private float[] positions;
  private float[] normals;
  private float[] texCoords;
  private float[] lightmapCoords;
  private byte[] colors;
  private int[] indices;
  private int vertexCount;
  private int indexCount;

  public MeshBuffer(int maxVertices, int maxIndices) {
    if (maxVertices < 1 || maxIndices < 1) throw new IllegalArgumentException("Empty mesh budget");
    this.maxVertices = maxVertices;
    this.maxIndices = maxIndices;
    int vertices = Math.min(INITIAL_VERTICES, maxVertices);
    positions = new float[vertices * 3];
    normals = new float[vertices * 3];
    texCoords = new float[vertices * 2];
    lightmapCoords = new float[vertices * 2];
    colors = new byte[vertices * 4];
    indices = new int[Math.min(INITIAL_VERTICES * 3, maxIndices)];
  }

  public void clear() {
    vertexCount = 0;
    indexCount = 0;
  }

  public int vertexCount() {
    return vertexCount;
  }

  public int indexCount() {
    return indexCount;
  }

  /** Makes room for {@code count} more vertices; throws when the budget would be exceeded. */
  public void reserveVertices(int count) {
    long needed = (long) vertexCount + count;
    if (needed > maxVertices)
      throw new IllegalArgumentException("Mesh exceeds " + maxVertices + " vertices");
    if (needed * 3 <= positions.length) return;
    int capacity = (int) Math.min(maxVertices, Math.max(needed, positions.length / 3 * 2L));
    positions = Arrays.copyOf(positions, capacity * 3);
    normals = Arrays.copyOf(normals, capacity * 3);
    texCoords = Arrays.copyOf(texCoords, capacity * 2);
    lightmapCoords = Arrays.copyOf(lightmapCoords, capacity * 2);
    colors = Arrays.copyOf(colors, capacity * 4);
  }

  /** Makes room for {@code count} more indices; throws when the budget would be exceeded. */
  public void reserveIndices(int count) {
    long needed = (long) indexCount + count;
    if (needed > maxIndices)
      throw new IllegalArgumentException("Mesh exceeds " + maxIndices + " indices");
    if (needed <= indices.length) return;
    indices =
        Arrays.copyOf(indices, (int) Math.min(maxIndices, Math.max(needed, indices.length * 2L)));
  }

  /** Appends a vertex and returns its index. */
  public int addVertex(
      float px,
      float py,
      float pz,
      float nx,
      float ny,
      float nz,
      float s,
      float t,
      float ls,
      float lt,
      int rgba) {
    reserveVertices(1);
    int v = vertexCount++;
    positions[v * 3] = px;
    positions[v * 3 + 1] = py;
    positions[v * 3 + 2] = pz;
    normals[v * 3] = nx;
    normals[v * 3 + 1] = ny;
    normals[v * 3 + 2] = nz;
    texCoords[v * 2] = s;
    texCoords[v * 2 + 1] = t;
    lightmapCoords[v * 2] = ls;
    lightmapCoords[v * 2 + 1] = lt;
    VertexArrays.putRgba(colors, v, rgba);
    return v;
  }

  /** Appends one index. */
  public void addIndex(int vertex) {
    reserveIndices(1);
    indices[indexCount++] = vertex;
  }

  /** Appends a triangle. */
  public void addTriangle(int a, int b, int c) {
    reserveIndices(3);
    indices[indexCount++] = a;
    indices[indexCount++] = b;
    indices[indexCount++] = c;
  }

  /**
   * Copies vertices {@code [first, first + count)} of {@code source}; returns the new first index.
   */
  public int copyVertices(VertexArrays source, int first, int count) {
    reserveVertices(count);
    int base = vertexCount;
    System.arraycopy(source.positions(), first * 3, positions, base * 3, count * 3);
    System.arraycopy(source.normals(), first * 3, normals, base * 3, count * 3);
    System.arraycopy(source.texCoords(), first * 2, texCoords, base * 2, count * 2);
    System.arraycopy(source.lightmapCoords(), first * 2, lightmapCoords, base * 2, count * 2);
    System.arraycopy(source.colors(), first * 4, colors, base * 4, count * 4);
    vertexCount += count;
    return base;
  }

  /** Appends {@code count} indices from {@code source[from..]}, each shifted by {@code delta}. */
  public void copyIndices(int[] source, int from, int count, int delta) {
    reserveIndices(count);
    for (int i = 0; i < count; i++) indices[indexCount++] = source[from + i] + delta;
  }

  /** A view of the current storage; invalidated by the next growth. */
  public VertexArrays arrays() {
    return new VertexArrays(positions, normals, texCoords, lightmapCoords, colors);
  }

  /** The live position array (3 floats per vertex). */
  public float[] positions() {
    return positions;
  }

  public float[] normals() {
    return normals;
  }

  public float[] texCoords() {
    return texCoords;
  }

  public float[] lightmapCoords() {
    return lightmapCoords;
  }

  public byte[] colors() {
    return colors;
  }

  /** The live index array; entries past {@link #indexCount()} are stale. */
  public int[] indices() {
    return indices;
  }

  /** Writes the bounds of vertices {@code [first, first + count)} as min xyz, max xyz. */
  public void bounds(int first, int count, float[] out, int offset) {
    bounds(positions, first, count, out, offset);
  }

  /** Bounds of a position array range; an empty range gives an inverted infinite box. */
  public static void bounds(float[] positions, int first, int count, float[] out, int offset) {
    float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
    float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
    for (int v = first; v < first + count; v++) {
      float x = positions[v * 3], y = positions[v * 3 + 1], z = positions[v * 3 + 2];
      minX = Math.min(minX, x);
      minY = Math.min(minY, y);
      minZ = Math.min(minZ, z);
      maxX = Math.max(maxX, x);
      maxY = Math.max(maxY, y);
      maxZ = Math.max(maxZ, z);
    }
    out[offset] = minX;
    out[offset + 1] = minY;
    out[offset + 2] = minZ;
    out[offset + 3] = maxX;
    out[offset + 4] = maxY;
    out[offset + 5] = maxZ;
  }
}
