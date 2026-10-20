package dev.bluevista.craftq3.engine.formats.md3;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable MD3 (IDP3 version 15) model in Quake III coordinates and units. Per-frame vertex
 * data is held in packed float arrays: {@code [frame][vertex][xyz]}.
 */
public final class Md3Model {
  private final String name;
  private final int flags;
  private final List<Frame> frames;
  private final int tagCount;
  private final String[] tagNames; // [frame * tagCount + tag]
  private final float[] tagData; // 12 floats per tag: origin, axis x, axis y, axis z
  private final List<Surface> surfaces;
  private final List<String> diagnostics;

  Md3Model(
      String name,
      int flags,
      List<Frame> frames,
      int tagCount,
      String[] tagNames,
      float[] tagData,
      List<Surface> surfaces,
      List<String> diagnostics) {
    this.name = Objects.requireNonNull(name);
    this.flags = flags;
    this.frames = List.copyOf(frames);
    this.tagCount = tagCount;
    this.tagNames = tagNames;
    this.tagData = tagData;
    this.surfaces = List.copyOf(surfaces);
    this.diagnostics = List.copyOf(diagnostics);
    if (this.frames.isEmpty() || tagNames.length != this.frames.size() * tagCount)
      throw new IllegalArgumentException("MD3 frame and tag-frame counts must agree");
  }

  public String name() {
    return name;
  }

  public int flags() {
    return flags;
  }

  public List<Frame> frames() {
    return frames;
  }

  public int frameCount() {
    return frames.size();
  }

  public int tagCount() {
    return tagCount;
  }

  public String tagName(int frame, int tag) {
    Objects.checkIndex(tag, tagCount);
    return tagNames[frame * tagCount + tag];
  }

  public List<Surface> surfaces() {
    return surfaces;
  }

  /** Notes from lenient reading (for example inverted frame bounds). */
  public List<String> diagnostics() {
    return diagnostics;
  }

  /** The first tag of {@code frame} named {@code tagName}, as the original tag lookup. */
  public Optional<Tag> tag(int frame, String tagName) {
    Objects.checkIndex(frame, frames.size());
    for (int t = 0; t < tagCount; t++) {
      int index = frame * tagCount + t;
      if (tagNames[index].equals(tagName)) {
        int base = index * 12;
        return Optional.of(
            new Tag(
                tagName,
                vector(tagData, base),
                vector(tagData, base + 3),
                vector(tagData, base + 6),
                vector(tagData, base + 9)));
      }
    }
    return Optional.empty();
  }

  private static Vec3f vector(float[] data, int base) {
    return new Vec3f(data[base], data[base + 1], data[base + 2]);
  }

  /** Tag-only exports can have inverted empty bounds; check {@link #hasBounds} before use. */
  public record Frame(Vec3f mins, Vec3f maxs, Vec3f origin, float radius, String name) {
    public boolean hasBounds() {
      return mins.x() <= maxs.x() && mins.y() <= maxs.y() && mins.z() <= maxs.z();
    }
  }

  /** An attachment point; the axes transform local coordinates into parent-model coordinates. */
  public record Tag(String name, Vec3f origin, Vec3f axisX, Vec3f axisY, Vec3f axisZ) {
    public Vec3f transformPoint(Vec3f point) {
      return origin.add(transformDirection(point));
    }

    public Vec3f transformDirection(Vec3f d) {
      return axisX.scale(d.x()).add(axisY.scale(d.y())).add(axisZ.scale(d.z()));
    }

    /** Attaches a child tag to this parent tag. */
    public Tag compose(Tag child) {
      return new Tag(
          child.name(),
          transformPoint(child.origin()),
          transformDirection(child.axisX()),
          transformDirection(child.axisY()),
          transformDirection(child.axisZ()));
    }
  }

  public record Shader(String name, int index) {}

  /**
   * One surface. The name is lower case as stored; {@link SkinParser.Skin} also matches it without
   * a trailing {@code _N} level-of-detail suffix.
   */
  public static final class Surface {
    private final String name;
    private final int flags;
    private final List<Shader> shaders;
    private final int[] triangles;
    private final float[] texCoords;
    private final float[] positions;
    private final float[] normals;
    private final int frameCount;

    Surface(
        String name,
        int flags,
        List<Shader> shaders,
        int[] triangles,
        float[] texCoords,
        float[] positions,
        float[] normals,
        int frameCount) {
      this.name = name;
      this.flags = flags;
      this.shaders = List.copyOf(shaders);
      this.triangles = triangles;
      this.texCoords = texCoords;
      this.positions = positions;
      this.normals = normals;
      this.frameCount = frameCount;
    }

    public String name() {
      return name;
    }

    public int flags() {
      return flags;
    }

    public List<Shader> shaders() {
      return shaders;
    }

    public int frameCount() {
      return frameCount;
    }

    public int vertexCount() {
      return texCoords.length / 2;
    }

    public int triangleCount() {
      return triangles.length / 3;
    }

    /** Vertex index of corner {@code corner} (0..2) of triangle {@code triangle}. */
    public int triangle(int triangle, int corner) {
      Objects.checkIndex(corner, 3);
      return triangles[triangle * 3 + corner];
    }

    public float texCoord(int vertex, int component) {
      Objects.checkIndex(component, 2);
      return texCoords[vertex * 2 + component];
    }

    public float position(int frame, int vertex, int axis) {
      return positions[offset(frame, vertex, axis)];
    }

    public float normal(int frame, int vertex, int axis) {
      return normals[offset(frame, vertex, axis)];
    }

    public Vec3f position(int frame, int vertex) {
      int base = offset(frame, vertex, 0);
      return new Vec3f(positions[base], positions[base + 1], positions[base + 2]);
    }

    public Vec3f normal(int frame, int vertex) {
      int base = offset(frame, vertex, 0);
      return new Vec3f(normals[base], normals[base + 1], normals[base + 2]);
    }

    public int[] trianglesCopy() {
      return triangles.clone();
    }

    public float[] texCoordsCopy() {
      return texCoords.clone();
    }

    /** Copies frame {@code frame}'s positions ({@code 3 * vertexCount} floats) into {@code out}. */
    public void copyPositions(int frame, float[] out, int outOffset) {
      Objects.checkIndex(frame, frameCount);
      int length = vertexCount() * 3;
      System.arraycopy(positions, frame * length, out, outOffset, length);
    }

    /** Copies frame {@code frame}'s unit normals into {@code out}. */
    public void copyNormals(int frame, float[] out, int outOffset) {
      Objects.checkIndex(frame, frameCount);
      int length = vertexCount() * 3;
      System.arraycopy(normals, frame * length, out, outOffset, length);
    }

    private int offset(int frame, int vertex, int axis) {
      Objects.checkIndex(frame, frameCount);
      Objects.checkIndex(vertex, vertexCount());
      Objects.checkIndex(axis, 3);
      return (frame * vertexCount() + vertex) * 3 + axis;
    }
  }
}
