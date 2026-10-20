package dev.bluevista.craftq3.engine.formats.md3;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.md3.AnimationConfig.Animation;
import java.util.Optional;

/**
 * Host-independent frame blending; fractions weight the destination frame ({@code 1 - backlerp}).
 * These are utilities for previews and asset consumers: the original cgame supplies its own
 * frame/oldframe/backlerp timing.
 */
public final class Md3Animation {
  private Md3Animation() {}

  public record FrameBlend(int from, int to, float fraction, boolean finished) {}

  /**
   * Blends two frames of {@code surface} into {@code positions} and {@code normals} (each {@code 3
   * * vertexCount} floats); blended normals are renormalised.
   */
  public static void interpolateSurface(
      Md3Model.Surface surface,
      int from,
      int to,
      float fraction,
      float[] positions,
      float[] normals) {
    checkFraction(fraction);
    int vertices = surface.vertexCount();
    for (int v = 0; v < vertices; v++) {
      for (int axis = 0; axis < 3; axis++) {
        float a = surface.position(from, v, axis);
        positions[v * 3 + axis] = a + (surface.position(to, v, axis) - a) * fraction;
      }
      Vec3f normal = normalLerp(surface.normal(from, v), surface.normal(to, v), fraction);
      normals[v * 3] = normal.x();
      normals[v * 3 + 1] = normal.y();
      normals[v * 3 + 2] = normal.z();
    }
  }

  /** Blends tag origin and normalises each blended basis axis, as Q3 attachments expect. */
  public static Optional<Md3Model.Tag> interpolateTag(
      Md3Model model, String name, int from, int to, float fraction) {
    checkFraction(fraction);
    Optional<Md3Model.Tag> a = model.tag(from, name);
    Optional<Md3Model.Tag> b = model.tag(to, name);
    if (a.isEmpty() || b.isEmpty()) return Optional.empty();
    Md3Model.Tag first = a.get();
    Md3Model.Tag second = b.get();
    return Optional.of(
        new Md3Model.Tag(
            name,
            first.origin().lerp(second.origin(), fraction),
            normalLerp(first.axisX(), second.axisX(), fraction),
            normalLerp(first.axisY(), second.axisY(), fraction),
            normalLerp(first.axisZ(), second.axisZ(), fraction)));
  }

  /**
   * Samples a clip starting at time zero, looping its tail or holding its last frame. Flip-flop
   * clips play forward then backward, including both endpoint frames.
   */
  public static FrameBlend sample(Animation animation, double seconds) {
    if (!Double.isFinite(seconds) || seconds < 0)
      throw new IllegalArgumentException("Animation time must be finite and nonnegative");
    if (animation.frameCount() < 1 || !(animation.framesPerSecond() > 0))
      throw new IllegalArgumentException("Animation needs a frame and a positive frame rate");
    if (animation.loopFrames() < 0 || animation.loopFrames() > animation.frameCount())
      throw new IllegalArgumentException("Animation loop exceeds its frames");
    long length = (long) animation.frameCount() * (animation.flipFlop() ? 2 : 1);
    double tick = seconds * animation.framesPerSecond();
    boolean finished = animation.loopFrames() == 0 && tick >= length;
    if (tick >= length) {
      if (animation.loopFrames() == 0) {
        int last = frame(animation, length - 1);
        return new FrameBlend(last, last, 0, true);
      }
      tick = loopTick(animation, seconds, tick, length);
    }
    long start = (long) Math.floor(tick);
    long next = start + 1;
    if (next >= length) next = animation.loopFrames() > 0 ? length - animation.loopFrames() : start;
    float fraction = next == start ? 0 : (float) (tick - start);
    return new FrameBlend(frame(animation, start), frame(animation, next), fraction, finished);
  }

  private static double loopTick(Animation animation, double seconds, double tick, long length) {
    int loop = animation.loopFrames();
    double tail =
        Double.isFinite(tick)
            ? (tick - length) % loop
            : ((seconds % (loop / (double) animation.framesPerSecond()))
                        * animation.framesPerSecond()
                    - length % loop
                    + loop)
                % loop;
    double result = length - loop + tail;
    // Floating-point remainder can round up by one ULP at a loop boundary.
    return result >= length ? length - loop : result;
  }

  private static int frame(Animation animation, long index) {
    if (animation.flipFlop() && index >= animation.frameCount())
      index = 2L * animation.frameCount() - 1 - index;
    if (animation.reversed()) index = animation.frameCount() - 1L - index;
    return animation.firstFrame() + (int) index;
  }

  private static Vec3f normalLerp(Vec3f a, Vec3f b, float fraction) {
    Vec3f value = a.lerp(b, fraction);
    if (value.length() < 1e-6f) value = fraction < 0.5f ? a : b; // antipodal input
    return value.normalize();
  }

  private static void checkFraction(float fraction) {
    if (!Float.isFinite(fraction) || fraction < 0 || fraction > 1)
      throw new IllegalArgumentException("MD3 interpolation fraction must be between 0 and 1");
  }
}
