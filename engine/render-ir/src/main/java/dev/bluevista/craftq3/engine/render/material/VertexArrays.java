package dev.bluevista.craftq3.engine.render.material;

import java.util.Objects;

/**
 * A view of structure-of-arrays vertex data: positions and normals (3 floats per vertex), base and
 * lightmap texture coordinates (2 floats), colours (4 bytes in R, G, B, A memory order). The arrays
 * are shared, not copied, and may be longer than the vertices in use.
 */
public record VertexArrays(
    float[] positions, float[] normals, float[] texCoords, float[] lightmapCoords, byte[] colors) {
  public VertexArrays {
    Objects.requireNonNull(positions, "positions");
    Objects.requireNonNull(normals, "normals");
    Objects.requireNonNull(texCoords, "texCoords");
    Objects.requireNonNull(lightmapCoords, "lightmapCoords");
    Objects.requireNonNull(colors, "colors");
  }

  /** Vertices every array can hold. */
  public int capacity() {
    return Math.min(
        Math.min(positions.length / 3, normals.length / 3),
        Math.min(Math.min(texCoords.length / 2, lightmapCoords.length / 2), colors.length / 4));
  }

  /** Colour of {@code vertex} as {@code 0xRRGGBBAA}. */
  public int rgba(int vertex) {
    int at = vertex * 4;
    return (colors[at] & 255) << 24
        | (colors[at + 1] & 255) << 16
        | (colors[at + 2] & 255) << 8
        | (colors[at + 3] & 255);
  }

  /** Stores {@code rgba} ({@code 0xRRGGBBAA}) at {@code colors[vertex * 4..]}. */
  public static void putRgba(byte[] colors, int vertex, int rgba) {
    int at = vertex * 4;
    colors[at] = (byte) (rgba >>> 24);
    colors[at + 1] = (byte) (rgba >>> 16);
    colors[at + 2] = (byte) (rgba >>> 8);
    colors[at + 3] = (byte) rgba;
  }

  // Arrays are compared by identity: a view equals another view of the same storage.
  @Override
  public boolean equals(Object other) {
    return other instanceof VertexArrays o
        && positions == o.positions
        && normals == o.normals
        && texCoords == o.texCoords
        && lightmapCoords == o.lightmapCoords
        && colors == o.colors;
  }

  @Override
  public int hashCode() {
    return System.identityHashCode(positions) * 31 + System.identityHashCode(colors);
  }

  @Override
  public String toString() {
    return "VertexArrays[capacity=" + capacity() + "]";
  }
}
