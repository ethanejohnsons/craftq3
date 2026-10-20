package dev.bluevista.craftq3.assets.md3;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Immutable MD3 v15 data in Q3's right-handed, Z-up coordinates and game-unit scale. */
public record Md3Model(
    String name, int flags, List<Frame> frames, List<List<Tag>> tagFrames, List<Surface> surfaces) {
  public Md3Model {
    Objects.requireNonNull(name, "name");
    frames = List.copyOf(frames);
    tagFrames = tagFrames.stream().map(List::copyOf).toList();
    surfaces = List.copyOf(surfaces);
    if (frames.isEmpty() || tagFrames.size() != frames.size()) {
      throw new IllegalArgumentException("MD3 frame and tag-frame counts must agree");
    }
  }

  /** Tag-only exports can have inverted empty bounds; check hasBounds before using their box. */
  public record Frame(Vec3 min, Vec3 max, Vec3 origin, float radius, String name) {
    public boolean hasBounds() {
      return min.x() <= max.x() && min.y() <= max.y() && min.z() <= max.z();
    }
  }

  /** Basis vectors transform local coordinates into parent-model coordinates. */
  public record Tag(String name, Vec3 origin, Vec3 axisX, Vec3 axisY, Vec3 axisZ) {
    public Vec3 transformPoint(Vec3 point) {
      return origin.add(transformDirection(point));
    }

    public Vec3 transformDirection(Vec3 direction) {
      return axisX
          .scale(direction.x())
          .add(axisY.scale(direction.y()))
          .add(axisZ.scale(direction.z()));
    }

    /** Attaches a child tag to this parent tag without depending on a host matrix library. */
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

  public record Triangle(int a, int b, int c) {}

  public record TexCoord(float s, float t) {}

  public record Vertex(Vec3 position, Vec3 normal) {}

  public record Surface(
      String name,
      int flags,
      List<Shader> shaders,
      List<Triangle> triangles,
      List<TexCoord> texCoords,
      List<List<Vertex>> frameVertices) {
    public Surface {
      name = Objects.requireNonNull(name, "name").toLowerCase(Locale.ROOT);
      shaders = List.copyOf(shaders);
      triangles = List.copyOf(triangles);
      texCoords = List.copyOf(texCoords);
      frameVertices = frameVertices.stream().map(List::copyOf).toList();
    }

    public int vertexCount() {
      return texCoords.size();
    }
  }

  public Optional<Tag> tag(int frame, String tagName) {
    return tagFrames.get(frame).stream().filter(tag -> tag.name().equals(tagName)).findFirst();
  }
}
