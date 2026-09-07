package dev.bluevista.craftq3.render;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.assets.md3.Md3Model;
import dev.bluevista.craftq3.assets.md3.SkinParser;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.render.material.PortalView;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Ordered, owned snapshots of cgame renderer traps; contains no host or VM memory references. */
public record CgameFrame(List<Command> commands, SceneAssets assets, int timeMillis) {
  public CgameFrame(List<Command> commands, SceneAssets assets) {
    this(
        commands,
        assets,
        commands.stream()
            .filter(View.class::isInstance)
            .map(View.class::cast)
            .mapToInt(view -> view.refdef().timeMillis())
            .findFirst()
            .orElse(0));
  }

  public static final int RF_MINLIGHT = 1,
      RF_THIRD_PERSON = 2,
      RF_FIRST_PERSON = 4,
      RF_DEPTHHACK = 8,
      RF_CROSSHAIR = 16,
      RF_NOSHADOW = 64,
      RF_LIGHTING_ORIGIN = 128,
      RF_SHADOW_PLANE = 256,
      RF_WRAP_FRAMES = 512;
  public static final int RDF_NOWORLDMODEL = 1, RDF_HYPERSPACE = 4;

  public CgameFrame {
    commands = List.copyOf(commands);
    Objects.requireNonNull(assets, "assets");
    if (commands.size() > 16384)
      throw new IllegalArgumentException("Cgame command budget exceeded");
    if (commands.stream().filter(View.class::isInstance).count() > 32)
      throw new IllegalArgumentException("Cgame view budget exceeded");
    var images = new java.util.HashMap<Long, dev.bluevista.craftq3.assets.image.Q3Image>();
    long bytes = 0;
    for (var command : commands) {
      if (!(command instanceof Image image)) continue;
      var previous = images.putIfAbsent(image.stream(), image.pixels());
      if (previous != null && previous != image.pixels())
        throw new IllegalArgumentException("Conflicting cinematic snapshots for one stream");
      if (previous == null) bytes += (long) image.pixels().width() * image.pixels().height() * 4;
    }
    if (images.size() > 32 || bytes > 64L * 1024 * 1024)
      throw new IllegalArgumentException("Cinematic image budget exceeded");
  }

  public sealed interface Command permits View, Quad, Image {}

  /**
   * Opaque movie pixels in framebuffer coordinates; one immutable snapshot per stream per frame.
   */
  public record Image(
      long stream,
      float x,
      float y,
      float width,
      float height,
      dev.bluevista.craftq3.assets.image.Q3Image pixels)
      implements Command {
    public Image {
      Objects.requireNonNull(pixels, "pixels");
      if (stream < 0 || width <= 0 || height <= 0)
        throw new IllegalArgumentException("Invalid cinematic image extent or stream");
      new Quad(x, y, width, height, 0, 0, 1, 1, "$cinematic/" + stream, -1);
    }

    public String texture() {
      return "$cinematic/" + stream;
    }
  }

  /** A renderer clearScene/addEntity/addPoly/addLight/renderScene sequence captured by value. */
  public record View(
      Refdef refdef,
      List<RefEntity> entities,
      List<Poly> polygons,
      List<RenderScene.DynamicLight> lights)
      implements Command {
    public View {
      Objects.requireNonNull(refdef, "refdef");
      entities = List.copyOf(entities);
      polygons = List.copyOf(polygons);
      lights = List.copyOf(lights);
      if (entities.size() > 1024 || polygons.size() > 4096 || lights.size() > 128)
        throw new IllegalArgumentException("Cgame scene submission budget exceeded");
      if (polygons.stream().mapToLong(p -> p.vertices().size()).sum() > 1_000_000)
        throw new IllegalArgumentException("Cgame polygon vertex budget exceeded");
    }
  }

  /** Viewport uses framebuffer pixels. Q3 axes are forward, left, up. Area bits set mean hidden. */
  public record Refdef(
      int x,
      int y,
      int width,
      int height,
      float fovX,
      float fovY,
      Vec3 origin,
      Vec3 axisX,
      Vec3 axisY,
      Vec3 axisZ,
      int timeMillis,
      int rdflags,
      BspMap.Bytes areaMask,
      List<String> text) {
    public Refdef {
      Objects.requireNonNull(origin, "origin");
      Objects.requireNonNull(axisX, "axisX");
      Objects.requireNonNull(axisY, "axisY");
      Objects.requireNonNull(axisZ, "axisZ");
      Objects.requireNonNull(areaMask, "areaMask");
      text = List.copyOf(text);
      if (width < 1
          || height < 1
          || width > 32768
          || height > 32768
          || Math.abs((long) x) > 32768
          || Math.abs((long) y) > 32768
          || !Float.isFinite(fovX)
          || !Float.isFinite(fovY)
          || fovX <= 1
          || fovX >= 179
          || fovY <= 1
          || fovY >= 179
          || areaMask.size() > 32
          || text.size() > 8
          || text.stream().anyMatch(s -> s.length() > 32))
        throw new IllegalArgumentException("Invalid cgame refdef");
      if (Math.abs(dot(axisX, cross(axisY, axisZ))) < 1e-8)
        throw new IllegalArgumentException("Singular cgame view axes");
    }

    public boolean worldModel() {
      return (rdflags & RDF_NOWORLDMODEL) == 0;
    }

    public boolean mirrored() {
      return dot(axisX, cross(axisY, axisZ)) < 0;
    }

    public PortalView.Basis basis() {
      return new PortalView.Basis(axisX, axisY.scale(-1), axisZ);
    }

    public RenderScene.Camera camera() {
      return new RenderScene.Camera(
          origin,
          (float) Math.toDegrees(Math.atan2(axisX.y(), axisX.x())),
          (float) Math.toDegrees(Math.atan2(-axisX.z(), Math.hypot(axisX.x(), axisX.y()))),
          fovX);
    }
  }

  public enum EntityType {
    MODEL,
    POLY,
    SPRITE,
    BEAM,
    RAIL_CORE,
    RAIL_RINGS,
    LIGHTNING,
    PORTALSURFACE
  }

  /** Model may be null for non-MD3 entities; inlineModel >= 0 identifies a BSP model instead. */
  public record RefEntity(
      EntityType type,
      int renderFx,
      Md3Model model,
      int inlineModel,
      Md3Model.Tag transform,
      Vec3 oldOrigin,
      int frame,
      int oldFrame,
      float backlerp,
      Vec3 lightingOrigin,
      float shadowPlane,
      int skinNumber,
      Optional<SkinParser.Skin> skin,
      Optional<String> customShader,
      int rgba,
      BspMap.Uv shaderTexCoord,
      float shaderTime,
      float radius,
      float rotation) {
    public RefEntity {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(transform, "transform");
      Objects.requireNonNull(oldOrigin, "oldOrigin");
      Objects.requireNonNull(lightingOrigin, "lightingOrigin");
      Objects.requireNonNull(skin, "skin");
      Objects.requireNonNull(customShader, "customShader");
      Objects.requireNonNull(shaderTexCoord, "shaderTexCoord");
      Objects.requireNonNull(transform.origin(), "transform.origin");
      Objects.requireNonNull(transform.axisX(), "transform.axisX");
      Objects.requireNonNull(transform.axisY(), "transform.axisY");
      Objects.requireNonNull(transform.axisZ(), "transform.axisZ");
      if (!Float.isFinite(backlerp)
          || backlerp < 0
          || backlerp > 1
          || !Float.isFinite(shadowPlane)
          || !Float.isFinite(shaderTime)
          || !Float.isFinite(radius)
          || radius < 0
          || radius > 131072
          || !Float.isFinite(rotation)
          || !Float.isFinite(shaderTexCoord.u())
          || !Float.isFinite(shaderTexCoord.v()))
        throw new IllegalArgumentException("Invalid cgame refEntity");
    }

    public boolean visible(boolean portalView) {
      return portalView ? (renderFx & RF_FIRST_PERSON) == 0 : (renderFx & RF_THIRD_PERSON) == 0;
    }

    public Vec3 lightOrigin() {
      return (renderFx & RF_LIGHTING_ORIGIN) != 0 ? lightingOrigin : transform.origin();
    }

    public boolean depthHack() {
      return (renderFx & RF_DEPTHHACK) != 0;
    }
  }

  /** Quake polyVert attributes; polygons are convex and are triangulated as a fan. */
  public record PolyVertex(Vec3 position, BspMap.Uv uv, int rgba) {
    public PolyVertex {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(uv, "uv");
      if (!Float.isFinite(uv.u()) || !Float.isFinite(uv.v()))
        throw new IllegalArgumentException("Invalid poly UV");
    }
  }

  public record Poly(String shader, List<PolyVertex> vertices) {
    public Poly {
      Objects.requireNonNull(shader, "shader");
      vertices = List.copyOf(vertices);
      if (vertices.size() < 3 || vertices.size() > 65536)
        throw new IllegalArgumentException("Invalid polygon size");
    }
  }

  /**
   * drawStretchPic coordinates and UVs are already scaled by cgame; rgba captures current setColor.
   */
  public record Quad(
      float x,
      float y,
      float width,
      float height,
      float s1,
      float t1,
      float s2,
      float t2,
      String shader,
      int rgba)
      implements Command {
    public Quad {
      Objects.requireNonNull(shader, "shader");
      for (float value : new float[] {x, y, width, height, s1, t1, s2, t2})
        if (!Float.isFinite(value) || Math.abs(value) > 1e8)
          throw new IllegalArgumentException("Invalid HUD quad");
    }
  }

  private static double dot(Vec3 a, Vec3 b) {
    return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
  }

  private static Vec3 cross(Vec3 a, Vec3 b) {
    return new Vec3(
        a.y() * b.z() - a.z() * b.y(),
        a.z() * b.x() - a.x() * b.z(),
        a.x() * b.y() - a.y() * b.x());
  }
}
