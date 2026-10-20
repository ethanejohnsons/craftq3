package dev.bluevista.craftq3.engine.formats.shader;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.QPath;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable Q3 material description, as parsed from a shader script (or synthesised for a name
 * without one). Values retain Q3 units and texture-coordinate conventions.
 */
public record ShaderDefinition(
    String name,
    List<Stage> stages,
    Cull cull,
    float sort,
    boolean polygonOffset,
    boolean noMipmaps,
    boolean noPicmip,
    Set<String> surfaceParms,
    Optional<Sky> sky,
    Optional<Fog> fog,
    List<Deform> deforms,
    boolean portal,
    float clampTime) {

  public ShaderDefinition {
    name = canonicalName(name);
    stages = List.copyOf(stages);
    surfaceParms = Set.copyOf(surfaceParms);
    deforms = List.copyOf(deforms);
  }

  /**
   * The lookup key the renderer uses: lower case, {@code /} separators, extension stripped. Names
   * are compared this way whether they come from scripts, BSP faces, MD3 surfaces or skins.
   */
  public static String canonicalName(String name) {
    return QPath.stripExtension(QPath.normalize(name));
  }

  public boolean skySurface() {
    return sky.isPresent() || surfaceParms.contains("sky");
  }

  public boolean noDraw() {
    return surfaceParms.contains("nodraw");
  }

  /** The script-side spelling is preserved: FRONT is Q3's normal one-sided default. */
  public enum Cull {
    FRONT,
    BACK,
    NONE
  }

  public enum BlendFactor {
    ZERO,
    ONE,
    SRC_COLOR,
    ONE_MINUS_SRC_COLOR,
    DST_COLOR,
    ONE_MINUS_DST_COLOR,
    SRC_ALPHA,
    ONE_MINUS_SRC_ALPHA,
    DST_ALPHA,
    ONE_MINUS_DST_ALPHA,
    SRC_ALPHA_SATURATE
  }

  public record Blend(BlendFactor source, BlendFactor destination) {
    public static final Blend OPAQUE = new Blend(BlendFactor.ONE, BlendFactor.ZERO);
    public static final Blend ADD = new Blend(BlendFactor.ONE, BlendFactor.ONE);
    public static final Blend FILTER = new Blend(BlendFactor.DST_COLOR, BlendFactor.ZERO);
    public static final Blend ALPHA =
        new Blend(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);

    public boolean opaque() {
      return equals(OPAQUE);
    }
  }

  public enum AlphaFunc {
    NONE,
    GT0,
    LT128,
    GE128
  }

  public enum DepthFunc {
    LEQUAL,
    EQUAL,
    DISABLE
  }

  public enum WaveFunction {
    SIN,
    TRIANGLE,
    SQUARE,
    SAWTOOTH,
    INVERSE_SAWTOOTH,
    NOISE
  }

  public record Wave(
      WaveFunction function, float base, float amplitude, float phase, float frequency) {
    public static final Wave ONE = new Wave(WaveFunction.SIN, 1, 0, 0, 0);
  }

  public enum RgbGenType {
    IDENTITY,
    IDENTITY_LIGHTING,
    VERTEX,
    EXACT_VERTEX,
    ONE_MINUS_VERTEX,
    ENTITY,
    ONE_MINUS_ENTITY,
    LIGHTING_DIFFUSE,
    WAVE,
    CONSTANT,
    FOG
  }

  public record RgbGen(RgbGenType type, Wave wave, Vec3f constant) {
    public static RgbGen of(RgbGenType type) {
      return new RgbGen(type, Wave.ONE, new Vec3f(1, 1, 1));
    }
  }

  public enum AlphaGenType {
    IDENTITY,
    VERTEX,
    ONE_MINUS_VERTEX,
    ENTITY,
    ONE_MINUS_ENTITY,
    LIGHTING_SPECULAR,
    WAVE,
    CONSTANT,
    PORTAL
  }

  public record AlphaGen(AlphaGenType type, Wave wave, float constant, float portalRange) {
    public static AlphaGen of(AlphaGenType type) {
      return new AlphaGen(type, Wave.ONE, 1, 256);
    }
  }

  public enum TcGenType {
    BASE,
    LIGHTMAP,
    ENVIRONMENT,
    VECTOR,
    IDENTITY,
    FOG
  }

  public record TcGen(TcGenType type, Vec3f s, Vec3f t) {
    public static TcGen of(TcGenType type) {
      return new TcGen(type, new Vec3f(1, 0, 0), new Vec3f(0, 1, 0));
    }
  }

  public sealed interface TcMod
      permits Scroll, Scale, Rotate, Stretch, Transform, Turbulence, EntityTranslate {}

  public record Scroll(float s, float t) implements TcMod {}

  public record Scale(float s, float t) implements TcMod {}

  public record Rotate(float degreesPerSecond) implements TcMod {}

  public record Stretch(Wave wave) implements TcMod {}

  public record Transform(float m00, float m01, float m10, float m11, float t0, float t1)
      implements TcMod {}

  public record Turbulence(float base, float amplitude, float phase, float frequency)
      implements TcMod {}

  public record EntityTranslate() implements TcMod {}

  public sealed interface Deform
      permits WaveDeform,
          NormalDeform,
          BulgeDeform,
          MoveDeform,
          AutoSprite,
          ProjectionShadow,
          TextDeform {}

  public record WaveDeform(float divisor, Wave wave) implements Deform {}

  public record NormalDeform(float amplitude, float frequency) implements Deform {}

  public record BulgeDeform(float width, float height, float speed) implements Deform {}

  public record MoveDeform(Vec3f direction, Wave wave) implements Deform {}

  public record AutoSprite(boolean axial) implements Deform {}

  public record ProjectionShadow() implements Deform {}

  public record TextDeform(int index) implements Deform {}

  public record Sky(String farBox, float cloudHeight, String nearBox) {}

  public record Fog(Vec3f color, float depthForOpaque) {}

  /**
   * A stage's image source: one image ({@code map}, {@code clampMap}), animation frames ({@code
   * animMap}) or, with {@code video}, the RoQ file a {@code videoMap} plays (looping, silent).
   */
  public record TextureMap(List<String> frames, float fps, boolean clamp, boolean video) {
    public TextureMap {
      frames = frames.stream().map(ShaderDefinition::textureName).toList();
      if (frames.isEmpty() || frames.size() > 64 || !Float.isFinite(fps) || fps < 0) {
        throw new IllegalArgumentException("Invalid shader texture animation");
      }
      if (video && frames.size() != 1)
        throw new IllegalArgumentException("A video map plays exactly one file");
    }

    public TextureMap(List<String> frames, float fps, boolean clamp) {
      this(frames, fps, clamp, false);
    }

    /**
     * {@code videoMap name}: a bare file name is looked up in {@code video/}, as the original
     * cinematic player does; a name with a directory is used as given.
     */
    public static TextureMap video(String name) {
      boolean bare = name.indexOf('/') < 0 && name.indexOf('\\') < 0;
      return new TextureMap(List.of(bare ? "video/" + name : name), 0, false, true);
    }

    public String atTime(double seconds) {
      if (!Double.isFinite(seconds) || fps == 0) return frames.getFirst();
      double frame = Math.floor(Math.max(0, seconds) * fps);
      return frames.get((int) (frame % frames.size()));
    }
  }

  /** Image names are normalised; implausible paths are refused so the stage uses a default. */
  private static String textureName(String value) {
    if (value.startsWith("$")) return value.toLowerCase(Locale.ROOT);
    if (!QPath.isPlausible(value))
      throw new IllegalArgumentException("Invalid texture path: " + value);
    return QPath.normalize(value);
  }

  public record Stage(
      TextureMap texture,
      Blend blend,
      AlphaFunc alphaFunc,
      RgbGen rgbGen,
      AlphaGen alphaGen,
      TcGen tcGen,
      List<TcMod> tcMods,
      DepthFunc depthFunc,
      boolean depthWrite,
      boolean detail) {
    public Stage {
      tcMods = List.copyOf(tcMods);
    }
  }

  public static ShaderDefinition implicit(String name, boolean lightmapped) {
    String canonical = canonicalName(name);
    Stage base =
        new Stage(
            new TextureMap(List.of(canonical), 0, false),
            Blend.OPAQUE,
            AlphaFunc.NONE,
            RgbGen.of(lightmapped ? RgbGenType.IDENTITY : RgbGenType.VERTEX),
            AlphaGen.of(AlphaGenType.IDENTITY),
            TcGen.of(TcGenType.BASE),
            List.of(),
            DepthFunc.LEQUAL,
            true,
            false);
    Stage light =
        new Stage(
            new TextureMap(List.of("$lightmap"), 0, false),
            Blend.FILTER,
            AlphaFunc.NONE,
            RgbGen.of(RgbGenType.IDENTITY),
            AlphaGen.of(AlphaGenType.IDENTITY),
            TcGen.of(TcGenType.LIGHTMAP),
            List.of(),
            DepthFunc.EQUAL,
            false,
            false);
    return new ShaderDefinition(
        canonical,
        lightmapped ? List.of(base, light) : List.of(base),
        Cull.FRONT,
        3,
        false,
        false,
        false,
        Set.of(),
        Optional.empty(),
        Optional.empty(),
        List.of(),
        false,
        0);
  }
}
