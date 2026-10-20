package dev.bluevista.craftq3.engine.render.frame;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.md3.Md3Animation;
import dev.bluevista.craftq3.engine.formats.md3.Md3Model;
import dev.bluevista.craftq3.engine.formats.md3.SkinParser;
import dev.bluevista.craftq3.engine.render.MeshBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MD3 submission: blends the cgame's frame pair, places the vertices with the entity transform and
 * appends indexed triangles to a {@link MeshBuffer}. Frames and backlerp come from the cgame; this
 * never runs an animation state machine of its own.
 */
public final class ModelGeometry {
  public static final Md3Model.Tag IDENTITY =
      new Md3Model.Tag("", Vec3f.ZERO, new Vec3f(1, 0, 0), new Vec3f(0, 1, 0), new Vec3f(0, 0, 1));
  public static final String MISSING_SHADER = "$missing";
  private static final Log LOG = Log.get(ModelGeometry.class);

  private ModelGeometry() {}

  /** One appended surface: its MD3 name, chosen shader and mesh ranges. */
  public record Surface(
      String name,
      String shader,
      int firstVertex,
      int vertexCount,
      int firstIndex,
      int indexCount) {}

  /** Per-thread blending scratch, grown to the largest surface seen. */
  public static final class Scratch {
    private float[] positions = new float[0];
    private float[] normals = new float[0];

    void ensure(int vertices) {
      if (positions.length < vertices * 3) {
        positions = new float[vertices * 3];
        normals = new float[vertices * 3];
      }
    }
  }

  /**
   * Appends every surface of {@code model}. Out-of-range frames draw frame 0, as the original
   * renderer does after its warning; {@code wrapFrames} ({@code RF_WRAP_FRAMES}) wraps them first.
   * Vertex colours are {@code rgba}; normals use the transform's cofactor matrix, so scaled and
   * singular (flattened) transforms keep usable normals, and reflected transforms keep the Q3
   * winding.
   */
  public static List<Surface> build(
      Md3Model model,
      int oldFrame,
      int frame,
      float backlerp,
      boolean wrapFrames,
      Md3Model.Tag transform,
      Optional<SkinParser.Skin> skin,
      Optional<String> customShader,
      int skinNumber,
      int rgba,
      MeshBuffer out,
      Scratch scratch) {
    if (!(backlerp >= 0 && backlerp <= 1))
      throw new IllegalArgumentException("Invalid model backlerp");
    int frames = model.frameCount();
    if (wrapFrames) {
      oldFrame = Math.floorMod(oldFrame, frames);
      frame = Math.floorMod(frame, frames);
    }
    if (oldFrame < 0 || oldFrame >= frames || frame < 0 || frame >= frames) {
      LOG.warnOnce(
          "md3-frame:" + model.name(),
          "No such frame " + oldFrame + " to " + frame + " for " + model.name());
      oldFrame = 0;
      frame = 0;
    }
    Vec3f ax = transform.axisX(), ay = transform.axisY(), az = transform.axisZ();
    Vec3f yz = ay.cross(az), zx = az.cross(ax), xy = ax.cross(ay);
    boolean mirrored = ax.dot(yz) < 0;
    float orientation = mirrored ? -1 : 1;
    Vec3f o = transform.origin();
    List<Surface> result = new ArrayList<>(model.surfaces().size());
    for (Md3Model.Surface source : model.surfaces()) {
      if (source.triangleCount() == 0) continue;
      int vertices = source.vertexCount();
      scratch.ensure(vertices);
      Md3Animation.interpolateSurface(
          source, oldFrame, frame, 1 - backlerp, scratch.positions, scratch.normals);
      out.reserveVertices(vertices);
      out.reserveIndices(source.triangleCount() * 3);
      int firstVertex = out.vertexCount(), firstIndex = out.indexCount();
      float[] p = scratch.positions, n = scratch.normals;
      for (int v = 0; v < vertices; v++) {
        float x = p[v * 3], y = p[v * 3 + 1], z = p[v * 3 + 2];
        float nx = n[v * 3], ny = n[v * 3 + 1], nz = n[v * 3 + 2];
        float tx = (yz.x() * nx + zx.x() * ny + xy.x() * nz) * orientation;
        float ty = (yz.y() * nx + zx.y() * ny + xy.y() * nz) * orientation;
        float tz = (yz.z() * nx + zx.z() * ny + xy.z() * nz) * orientation;
        double length = Math.sqrt((double) tx * tx + (double) ty * ty + (double) tz * tz);
        float inverse = length == 0 ? 0 : (float) (1 / length);
        out.addVertex(
            o.x() + ax.x() * x + ay.x() * y + az.x() * z,
            o.y() + ax.y() * x + ay.y() * y + az.y() * z,
            o.z() + ax.z() * x + ay.z() * y + az.z() * z,
            tx * inverse,
            ty * inverse,
            tz * inverse,
            source.texCoord(v, 0),
            source.texCoord(v, 1),
            0,
            0,
            rgba);
      }
      for (int t = 0; t < source.triangleCount(); t++) {
        int a = source.triangle(t, 0), b = source.triangle(t, 1), c = source.triangle(t, 2);
        if (mirrored) out.addTriangle(firstVertex + a, firstVertex + c, firstVertex + b);
        else out.addTriangle(firstVertex + a, firstVertex + b, firstVertex + c);
      }
      result.add(
          new Surface(
              source.name(),
              shader(source, skin, customShader, skinNumber),
              firstVertex,
              vertices,
              firstIndex,
              out.indexCount() - firstIndex));
    }
    return result;
  }

  /** The custom shader, else the skin's choice, else the surface's own shader by skin number. */
  static String shader(
      Md3Model.Surface source,
      Optional<SkinParser.Skin> skin,
      Optional<String> customShader,
      int skinNumber) {
    if (customShader.isPresent()) return customShader.get();
    if (skin.isPresent()) return skin.get().shaderForSurface(source.name()).orElse(MISSING_SHADER);
    if (source.shaders().isEmpty()) return MISSING_SHADER;
    return source.shaders().get(Math.floorMod(skinNumber, source.shaders().size())).name();
  }
}
