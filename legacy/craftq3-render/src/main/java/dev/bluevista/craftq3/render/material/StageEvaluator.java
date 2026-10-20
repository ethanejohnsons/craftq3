package dev.bluevista.craftq3.render.material;

import static dev.bluevista.craftq3.render.material.ShaderMath.*;

import dev.bluevista.craftq3.assets.bsp.BspMap.Uv;
import dev.bluevista.craftq3.assets.bsp.BspMap.Vertex;
import dev.bluevista.craftq3.assets.shader.ShaderDefinition.*;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.render.RenderScene.Camera;
import java.util.Objects;

/**
 * Evaluates a Q3 stage's vertex color and texture coordinates without depending on a graphics API.
 */
public final class StageEvaluator {
  private StageEvaluator() {}

  /**
   * Colors and lighting are normalized to 0..1; direction points from the surface toward the light.
   */
  public record Context(
      Camera camera,
      double seconds,
      Vec3 entityColor,
      float entityAlpha,
      Vec3 lightDirection,
      Vec3 ambientLight,
      Vec3 directedLight,
      Vec3 fogColor,
      Vec3 entityTexOffset,
      float identityLight) {
    public Context {
      Objects.requireNonNull(camera, "camera");
      Objects.requireNonNull(entityColor, "entityColor");
      Objects.requireNonNull(lightDirection, "lightDirection");
      Objects.requireNonNull(ambientLight, "ambientLight");
      Objects.requireNonNull(directedLight, "directedLight");
      Objects.requireNonNull(fogColor, "fogColor");
      Objects.requireNonNull(entityTexOffset, "entityTexOffset");
      if (!Double.isFinite(seconds)
          || !Float.isFinite(entityAlpha)
          || !Float.isFinite(identityLight)) {
        throw new IllegalArgumentException("Non-finite shader evaluation context");
      }
    }

    public static Context defaults(Camera camera, double seconds) {
      return new Context(
          camera,
          seconds,
          new Vec3(1, 1, 1),
          1,
          new Vec3(0, 0, 1),
          new Vec3(0.25, 0.25, 0.25),
          new Vec3(0.75, 0.75, 0.75),
          ZERO,
          ZERO,
          1);
    }
  }

  /** The returned textureUv is the final stage UV; lightmapUv retains the original source data. */
  public static Vertex evaluate(Vertex vertex, Stage stage, Context context) {
    Uv coordinates = coordinates(vertex, stage, context);
    Vec3 rgb = color(vertex, stage, context);
    double alpha = alpha(vertex, stage, context);
    int rgba =
        channel(rgb.x()) << 24 | channel(rgb.y()) << 16 | channel(rgb.z()) << 8 | channel(alpha);
    return new Vertex(vertex.position(), coordinates, vertex.lightmapUv(), vertex.normal(), rgba);
  }

  /** Texture frame selection is independent; this reports changes to vertex attributes only. */
  public static boolean isDynamic(Stage stage) {
    boolean coordinates =
        switch (stage.tcGen().type()) {
          case ENVIRONMENT, FOG -> true;
          default -> false;
        };
    boolean color =
        switch (stage.rgbGen().type()) {
          case ENTITY, ONE_MINUS_ENTITY, LIGHTING_DIFFUSE, FOG -> true;
          case WAVE -> animated(stage.rgbGen().wave());
          default -> false;
        };
    boolean alpha =
        switch (stage.alphaGen().type()) {
          case ENTITY, ONE_MINUS_ENTITY, LIGHTING_SPECULAR, PORTAL -> true;
          case WAVE -> animated(stage.alphaGen().wave());
          default -> false;
        };
    return coordinates
        || color
        || alpha
        || stage.tcMods().stream().anyMatch(StageEvaluator::animated);
  }

  /**
   * Restores BSP lighting before stage evaluation; ratio normalization preserves hue at saturation.
   */
  public static int restoreMapLightColor(int rgba, int overbrightBits) {
    if (overbrightBits < 0 || overbrightBits > 8) {
      throw new IllegalArgumentException("Overbright shift must be 0..8");
    }
    double r = (rgba >>> 24) * (1 << overbrightBits);
    double g = ((rgba >>> 16) & 255) * (1 << overbrightBits);
    double b = ((rgba >>> 8) & 255) * (1 << overbrightBits);
    double divisor = Math.max(255, Math.max(r, Math.max(g, b)));
    return channel(r / divisor) << 24
        | channel(g / divisor) << 16
        | channel(b / divisor) << 8
        | (rgba & 255);
  }

  public static double wave(Wave waveform, double seconds) {
    return ShaderMath.wave(waveform, seconds, 0);
  }

  private static boolean animated(Wave wave) {
    return wave.frequency() != 0 && wave.amplitude() != 0;
  }

  private static boolean animated(TcMod mod) {
    return switch (mod) {
      case Scroll scroll -> scroll.s() != 0 || scroll.t() != 0;
      case Rotate rotate -> rotate.degreesPerSecond() != 0;
      case Stretch stretch -> animated(stretch.wave());
      case Turbulence turbulence -> turbulence.frequency() != 0 && turbulence.amplitude() != 0;
      case EntityTranslate ignored -> true;
      case Scale ignored -> false;
      case Transform ignored -> false;
    };
  }

  private static Uv coordinates(Vertex vertex, Stage stage, Context context) {
    Vec3 position = vertex.position();
    Uv start =
        switch (stage.tcGen().type()) {
          case BASE -> vertex.textureUv();
          case LIGHTMAP -> vertex.lightmapUv();
          case IDENTITY -> new Uv(0, 0);
          case VECTOR ->
              new Uv(
                  (float) dot(position, stage.tcGen().s()),
                  (float) dot(position, stage.tcGen().t()));
          case ENVIRONMENT -> environment(vertex, context.camera());
          // Actual brush fog coordinates are supplied by the world fog pass. Standalone tcGen fog
          // uses the original default fog lookup width, useful when no BSP volume is attached.
          case FOG ->
              new Uv(
                  (float)
                      (Math.sqrt(
                              dot(
                                  subtract(position, context.camera().origin()),
                                  subtract(position, context.camera().origin())))
                          / 256),
                  0.5f);
        };
    double s = start.u();
    double t = start.v();
    for (TcMod mod : stage.tcMods()) {
      double oldS = s;
      double oldT = t;
      switch (mod) {
        case Scroll scroll -> {
          s += fraction(context.seconds() * scroll.s());
          t += fraction(context.seconds() * scroll.t());
        }
        case Scale scale -> {
          s *= scale.s();
          t *= scale.t();
        }
        case Rotate rotate -> {
          double angle = Math.toRadians(-rotate.degreesPerSecond() * context.seconds());
          double sin = Math.sin(angle);
          double cos = Math.cos(angle);
          s = (oldS - 0.5) * cos - (oldT - 0.5) * sin + 0.5;
          t = (oldS - 0.5) * sin + (oldT - 0.5) * cos + 0.5;
        }
        case Stretch stretch -> {
          double value = wave(stretch.wave(), context.seconds());
          // A zero stretch has no finite inverse; keep this degenerate stage bounded.
          double inverse = 1 / (Math.abs(value) < 1e-6 ? Math.copySign(1e-6, value) : value);
          s = (s - 0.5) * inverse + 0.5;
          t = (t - 0.5) * inverse + 0.5;
        }
        case Transform transform -> {
          s = oldS * transform.m00() + oldT * transform.m10() + transform.t0();
          t = oldS * transform.m01() + oldT * transform.m11() + transform.t1();
        }
        case Turbulence turbulence -> {
          double cycles = turbulence.phase() + context.seconds() * turbulence.frequency();
          s +=
              Math.sin(((position.x() + position.z()) / 1024 + cycles) * Math.TAU)
                  * turbulence.amplitude();
          t += Math.sin((position.y() / 1024 + cycles) * Math.TAU) * turbulence.amplitude();
        }
        case EntityTranslate ignored -> {
          s += context.entityTexOffset().x();
          t += context.entityTexOffset().y();
        }
      }
    }
    return new Uv((float) s, (float) t);
  }

  private static Uv environment(Vertex vertex, Camera camera) {
    Vec3 view = normalize(subtract(camera.origin(), vertex.position()));
    Vec3 normal = normalize(vertex.normal());
    Vec3 reflection = subtract(normal.scale(2 * dot(normal, view)), view);
    return new Uv((float) (0.5 + 0.5 * reflection.y()), (float) (0.5 - 0.5 * reflection.z()));
  }

  private static Vec3 color(Vertex vertex, Stage stage, Context context) {
    Vec3 vertexColor =
        new Vec3(
            (vertex.rgba() >>> 24) / 255.0,
            ((vertex.rgba() >>> 16) & 255) / 255.0,
            ((vertex.rgba() >>> 8) & 255) / 255.0);
    return switch (stage.rgbGen().type()) {
      case IDENTITY -> new Vec3(1, 1, 1);
      case IDENTITY_LIGHTING -> new Vec3(1, 1, 1).scale(context.identityLight());
      case VERTEX -> vertexColor.scale(context.identityLight());
      case EXACT_VERTEX -> vertexColor;
      case ONE_MINUS_VERTEX ->
          subtract(new Vec3(1, 1, 1), vertexColor).scale(context.identityLight());
      case ENTITY -> context.entityColor();
      case ONE_MINUS_ENTITY -> subtract(new Vec3(1, 1, 1), context.entityColor());
      case LIGHTING_DIFFUSE ->
          context
              .ambientLight()
              .add(
                  context
                      .directedLight()
                      .scale(
                          Math.max(
                              0,
                              dot(
                                  normalize(vertex.normal()),
                                  normalize(context.lightDirection())))));
      case WAVE ->
          new Vec3(1, 1, 1)
              .scale(wave(stage.rgbGen().wave(), context.seconds()) * context.identityLight());
      case CONSTANT -> stage.rgbGen().constant();
      case FOG -> context.fogColor();
    };
  }

  private static double alpha(Vertex vertex, Stage stage, Context context) {
    return switch (stage.alphaGen().type()) {
      case IDENTITY -> 1;
      case VERTEX -> (vertex.rgba() & 255) / 255.0;
      case ONE_MINUS_VERTEX -> 1 - (vertex.rgba() & 255) / 255.0;
      case ENTITY -> context.entityAlpha();
      case ONE_MINUS_ENTITY -> 1 - context.entityAlpha();
      case LIGHTING_SPECULAR -> {
        Vec3 normal = normalize(vertex.normal());
        Vec3 light = normalize(context.lightDirection());
        Vec3 reflection = subtract(normal.scale(2 * dot(normal, light)), light);
        Vec3 view = normalize(subtract(context.camera().origin(), vertex.position()));
        yield Math.pow(Math.max(0, dot(reflection, view)), 4);
      }
      case WAVE -> wave(stage.alphaGen().wave(), context.seconds());
      case CONSTANT -> stage.alphaGen().constant();
      case PORTAL -> {
        Vec3 delta = subtract(vertex.position(), context.camera().origin());
        yield Math.sqrt(dot(delta, delta)) / Math.max(1e-6, stage.alphaGen().portalRange());
      }
    };
  }
}
