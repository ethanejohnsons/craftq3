package dev.bluevista.craftq3.engine.render.frame;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.image.Q3Image;
import dev.bluevista.craftq3.engine.formats.md3.Md3Model;
import dev.bluevista.craftq3.engine.formats.md3.SkinParser;
import dev.bluevista.craftq3.engine.render.AreaMask;
import dev.bluevista.craftq3.engine.render.Camera;
import dev.bluevista.craftq3.engine.render.DynamicLight;
import dev.bluevista.craftq3.engine.render.PortalViews;
import dev.bluevista.craftq3.engine.render.ViewBasis;
import dev.bluevista.craftq3.engine.render.ViewDefinition;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One frame of cgame renderer traps, captured by value in submission order: scenes ({@code
 * clearScene} .. {@code renderScene}), 2D quads ({@code drawStretchPic}) and cinematic frames. It
 * holds no host or VM memory references; the cgame host produces it and the render thread consumes
 * it.
 */
public record CgameFrame(List<Command> commands, SceneAssets assets, int timeMillis) {
  public static final int MAX_COMMANDS = 16384;
  public static final int MAX_VIEWS = 32;
  public static final int MAX_CINEMATICS = 32;
  public static final long MAX_CINEMATIC_BYTES = 64L * 1024 * 1024;

  /** {@code refEntity_t.renderfx} bits. */
  public static final int RF_MINLIGHT = 1,
      RF_THIRD_PERSON = 2,
      RF_FIRST_PERSON = 4,
      RF_DEPTHHACK = 8,
      RF_CROSSHAIR = 16,
      RF_NOSHADOW = 64,
      RF_LIGHTING_ORIGIN = 128,
      RF_SHADOW_PLANE = 256,
      RF_WRAP_FRAMES = 512;

  public CgameFrame {
    commands = List.copyOf(commands);
    Objects.requireNonNull(assets, "assets");
    if (commands.size() > MAX_COMMANDS)
      throw new IllegalArgumentException("Cgame command budget exceeded");
    if (commands.stream().filter(View.class::isInstance).count() > MAX_VIEWS)
      throw new IllegalArgumentException("Cgame view budget exceeded");
    Map<Long, Q3Image> images = new HashMap<>();
    long bytes = 0;
    for (Command command : commands) {
      if (!(command instanceof Image image)) continue;
      Q3Image previous = images.putIfAbsent(image.stream(), image.pixels());
      if (previous != null && previous != image.pixels())
        throw new IllegalArgumentException("Conflicting cinematic snapshots for one stream");
      if (previous == null) bytes += (long) image.pixels().width() * image.pixels().height() * 4;
    }
    if (images.size() > MAX_CINEMATICS || bytes > MAX_CINEMATIC_BYTES)
      throw new IllegalArgumentException("Cinematic image budget exceeded");
  }

  /** A frame whose time is the first scene's refdef time (0 without scenes). */
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

  public sealed interface Command permits View, Quad, Image {}

  /**
   * Opaque movie pixels in framebuffer coordinates; one immutable snapshot per stream per frame.
   */
  public record Image(long stream, float x, float y, float width, float height, Q3Image pixels)
      implements Command {
    public Image {
      Objects.requireNonNull(pixels, "pixels");
      if (stream < 0 || !(width > 0) || !(height > 0))
        throw new IllegalArgumentException("Invalid cinematic image extent or stream");
      Quad.check(x, y, width, height, 0, 0, 1, 1);
    }

    /** The builtin texture name the backend binds the frame under. */
    public String texture() {
      return "$cinematic/" + stream;
    }
  }

  /** A {@code clearScene .. renderScene} sequence captured by value. */
  public record View(
      Refdef refdef, List<RefEntity> entities, List<Poly> polygons, List<DynamicLight> lights)
      implements Command {
    public static final int MAX_ENTITIES = 1024;
    public static final int MAX_POLYS = 4096;
    public static final int MAX_LIGHTS = 128;
    public static final long MAX_POLY_VERTICES = 1_000_000;

    public View {
      Objects.requireNonNull(refdef, "refdef");
      entities = List.copyOf(entities);
      polygons = List.copyOf(polygons);
      lights = List.copyOf(lights);
      if (entities.size() > MAX_ENTITIES
          || polygons.size() > MAX_POLYS
          || lights.size() > MAX_LIGHTS)
        throw new IllegalArgumentException("Cgame scene submission budget exceeded");
      long vertices = 0;
      for (Poly poly : polygons) vertices += poly.vertexCount();
      if (vertices > MAX_POLY_VERTICES)
        throw new IllegalArgumentException("Cgame polygon vertex budget exceeded");
    }

    /** The cgame's portal entities, for {@link PortalViews#collect}. */
    public List<PortalViews.PortalEntity> portalEntities() {
      return entities.stream()
          .filter(e -> e.type() == EntityType.PORTALSURFACE)
          .map(RefEntity::portalEntity)
          .toList();
    }
  }

  /**
   * {@code refdef_t}. The viewport is in framebuffer pixels; axes are Q3's forward, left and up.
   * Area-mask bits set mean hidden.
   */
  public record Refdef(
      int x,
      int y,
      int width,
      int height,
      float fovX,
      float fovY,
      Vec3f origin,
      Vec3f axisX,
      Vec3f axisY,
      Vec3f axisZ,
      int timeMillis,
      int rdflags,
      AreaMask areaMask,
      List<String> text) {
    public static final int MAX_EXTENT = 32768;
    public static final int MAX_TEXT = 8;
    public static final int MAX_TEXT_CHARS = 32;

    public Refdef {
      Objects.requireNonNull(origin, "origin");
      Objects.requireNonNull(axisX, "axisX");
      Objects.requireNonNull(axisY, "axisY");
      Objects.requireNonNull(axisZ, "axisZ");
      Objects.requireNonNull(areaMask, "areaMask");
      text = List.copyOf(text);
      if (width < 1
          || height < 1
          || width > MAX_EXTENT
          || height > MAX_EXTENT
          || Math.abs((long) x) > MAX_EXTENT
          || Math.abs((long) y) > MAX_EXTENT
          || !(fovX > 1 && fovX < 179)
          || !(fovY > 1 && fovY < 179)
          || !origin.isFinite()
          || text.size() > MAX_TEXT
          || text.stream().anyMatch(s -> s.length() > MAX_TEXT_CHARS))
        throw new IllegalArgumentException("Invalid cgame refdef");
      if (Math.abs(axisX.dot(axisY.cross(axisZ))) < 1e-8f)
        throw new IllegalArgumentException("Singular cgame view axes");
    }

    public boolean worldModel() {
      return (rdflags & ViewDefinition.RDF_NOWORLDMODEL) == 0;
    }

    public boolean mirrored() {
      return axisX.dot(axisY.cross(axisZ)) < 0;
    }

    /** Screen-space axes: right is Q3's left negated. */
    public ViewBasis basis() {
      return ViewBasis.fromQuake(axisX, axisY, axisZ);
    }

    public Camera camera() {
      return new Camera(
          origin,
          (float) Math.toDegrees(Math.atan2(axisX.y(), axisX.x())),
          (float) Math.toDegrees(Math.atan2(-axisX.z(), Math.hypot(axisX.x(), axisX.y()))),
          fovX);
    }

    /** The main view this refdef describes. */
    public ViewDefinition view() {
      return new ViewDefinition(
          ViewDefinition.Kind.MAIN,
          origin,
          basis(),
          fovX,
          fovY,
          x,
          y,
          width,
          height,
          timeMillis,
          rdflags,
          areaMask,
          Optional.empty(),
          origin,
          -1,
          0);
    }
  }

  /** {@code refEntityType_t}. */
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

  /**
   * {@code refEntity_t}. {@code model} is null for non-MD3 entities; {@code inlineModel >= 0} names
   * a BSP model instead. {@code transform} carries the origin and the (possibly scaled) axes.
   *
   * @param shaderS entity shader texture offset (s), read by {@code tcMod entityTranslate}
   * @param shaderTime seconds subtracted from the refdef time for this entity's shaders
   */
  public record RefEntity(
      EntityType type,
      int renderFx,
      Md3Model model,
      int inlineModel,
      Md3Model.Tag transform,
      Vec3f oldOrigin,
      int frame,
      int oldFrame,
      float backlerp,
      Vec3f lightingOrigin,
      float shadowPlane,
      int skinNumber,
      Optional<SkinParser.Skin> skin,
      Optional<String> customShader,
      int rgba,
      float shaderS,
      float shaderT,
      float shaderTime,
      float radius,
      float rotation) {
    public static final float MAX_RADIUS = 131072;

    public RefEntity {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(transform, "transform");
      Objects.requireNonNull(oldOrigin, "oldOrigin");
      Objects.requireNonNull(lightingOrigin, "lightingOrigin");
      Objects.requireNonNull(skin, "skin");
      Objects.requireNonNull(customShader, "customShader");
      Objects.requireNonNull(transform.origin(), "transform.origin");
      Objects.requireNonNull(transform.axisX(), "transform.axisX");
      Objects.requireNonNull(transform.axisY(), "transform.axisY");
      Objects.requireNonNull(transform.axisZ(), "transform.axisZ");
      if (!(backlerp >= 0 && backlerp <= 1)
          || !Float.isFinite(shadowPlane)
          || !Float.isFinite(shaderTime)
          || !(radius >= 0 && radius <= MAX_RADIUS)
          || !Float.isFinite(rotation)
          || !Float.isFinite(shaderS)
          || !Float.isFinite(shaderT))
        throw new IllegalArgumentException("Invalid cgame refEntity");
    }

    /** First-person models are hidden in portal views, third-person ones in the main view. */
    public boolean visible(boolean portalView) {
      return portalView ? (renderFx & RF_FIRST_PERSON) == 0 : (renderFx & RF_THIRD_PERSON) == 0;
    }

    public Vec3f lightOrigin() {
      return (renderFx & RF_LIGHTING_ORIGIN) != 0 ? lightingOrigin : transform.origin();
    }

    public boolean depthHack() {
      return (renderFx & RF_DEPTHHACK) != 0;
    }

    public PortalViews.PortalEntity portalEntity() {
      return new PortalViews.PortalEntity(
          transform.origin(),
          oldOrigin,
          transform.axisX(),
          transform.axisY(),
          transform.axisZ(),
          frame,
          oldFrame,
          skinNumber);
    }
  }

  /**
   * A convex {@code polyVert_t} polygon, triangulated as a fan: positions (3 floats), texture
   * coordinates (2 floats) and colours ({@code 0xRRGGBBAA}) per vertex.
   */
  public record Poly(String shader, float[] positions, float[] texCoords, int[] colors) {
    public static final int MAX_VERTICES = 65536;

    public Poly {
      Objects.requireNonNull(shader, "shader");
      positions = positions.clone();
      texCoords = texCoords.clone();
      colors = colors.clone();
      int count = colors.length;
      if (count < 3
          || count > MAX_VERTICES
          || positions.length != count * 3
          || texCoords.length != count * 2)
        throw new IllegalArgumentException("Invalid polygon size");
      for (float value : positions)
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Invalid poly position");
      for (float value : texCoords)
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Invalid poly UV");
    }

    public int vertexCount() {
      return colors.length;
    }

    public Vec3f position(int vertex) {
      return new Vec3f(positions[vertex * 3], positions[vertex * 3 + 1], positions[vertex * 3 + 2]);
    }

    @Override
    public float[] positions() {
      return positions.clone();
    }

    @Override
    public float[] texCoords() {
      return texCoords.clone();
    }

    @Override
    public int[] colors() {
      return colors.clone();
    }

    float positionAt(int index) {
      return positions[index];
    }

    float texCoordAt(int index) {
      return texCoords[index];
    }

    int colorAt(int vertex) {
      return colors[vertex];
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Poly p
          && shader.equals(p.shader)
          && Arrays.equals(positions, p.positions)
          && Arrays.equals(texCoords, p.texCoords)
          && Arrays.equals(colors, p.colors);
    }

    @Override
    public int hashCode() {
      return shader.hashCode() * 31 + Arrays.hashCode(positions);
    }

    @Override
    public String toString() {
      return "Poly[" + shader + ", " + vertexCount() + " vertices]";
    }
  }

  /** {@code drawStretchPic}: coordinates and UVs are already scaled; rgba is the current colour. */
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
    public static final float MAX_COORDINATE = 1e8f;

    public Quad {
      Objects.requireNonNull(shader, "shader");
      check(x, y, width, height, s1, t1, s2, t2);
    }

    static void check(float... values) {
      for (float value : values)
        if (!Float.isFinite(value) || Math.abs(value) > MAX_COORDINATE)
          throw new IllegalArgumentException("Invalid HUD quad");
    }
  }
}
