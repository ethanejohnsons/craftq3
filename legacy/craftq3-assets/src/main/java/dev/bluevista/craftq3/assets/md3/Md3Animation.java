package dev.bluevista.craftq3.assets.md3;

import dev.bluevista.craftq3.assets.md3.AnimationConfig.Animation;
import dev.bluevista.craftq3.assets.md3.Md3Model.Surface;
import dev.bluevista.craftq3.assets.md3.Md3Model.Tag;
import dev.bluevista.craftq3.assets.md3.Md3Model.Vertex;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Host-independent frame blending; fractions weight the destination frame (1 - Q3 backlerp). */
public final class Md3Animation {
  private Md3Animation() {}

  public record FrameBlend(int from, int to, float fraction, boolean finished) {}

  public static List<Vertex> interpolateSurface(Surface surface, int from, int to, float fraction) {
    fraction(fraction);
    List<Vertex> a = surface.frameVertices().get(from);
    List<Vertex> b = surface.frameVertices().get(to);
    if (a.size() != b.size()) throw new IllegalArgumentException("MD3 vertex counts differ");
    List<Vertex> result = new ArrayList<>(a.size());
    for (int i = 0; i < a.size(); i++) {
      result.add(
          new Vertex(
              lerp(a.get(i).position(), b.get(i).position(), fraction),
              normalLerp(a.get(i).normal(), b.get(i).normal(), fraction)));
    }
    return List.copyOf(result);
  }

  /** Blends tag origin and normalizes each blended basis axis, as Q3 attachments expect. */
  public static Optional<Tag> interpolateTag(
      Md3Model model, String name, int from, int to, float fraction) {
    fraction(fraction);
    Optional<Tag> a = model.tag(from, name);
    Optional<Tag> b = model.tag(to, name);
    if (a.isEmpty() || b.isEmpty()) return Optional.empty();
    Tag first = a.get(), second = b.get();
    return Optional.of(
        new Tag(
            name,
            lerp(first.origin(), second.origin(), fraction),
            normalLerp(first.axisX(), second.axisX(), fraction),
            normalLerp(first.axisY(), second.axisY(), fraction),
            normalLerp(first.axisZ(), second.axisZ(), fraction)));
  }

  /**
   * Samples a clip starting at time zero, looping its tail or holding its last frame. Flip-flop
   * clips play forward then backward, including both endpoint frames. This utility is for previews
   * and asset consumers: original cgame supplies its own frame/oldframe/backlerp timing.
   */
  public static FrameBlend sample(Animation animation, double seconds) {
    if (!Double.isFinite(seconds) || seconds < 0) {
      throw new IllegalArgumentException("Animation time must be finite and nonnegative");
    }
    long length = (long) animation.frameCount() * (animation.flipFlop() ? 2 : 1);
    double tick = seconds * animation.framesPerSecond();
    boolean finished = animation.loopFrames() == 0 && tick >= length;
    if (tick >= length) {
      if (animation.loopFrames() == 0) {
        int last = frame(animation, length - 1);
        return new FrameBlend(last, last, 0, true);
      }
      double tail =
          Double.isFinite(tick)
              ? (tick - length) % animation.loopFrames()
              : ((seconds % (animation.loopFrames() / (double) animation.framesPerSecond()))
                          * animation.framesPerSecond()
                      - length % animation.loopFrames()
                      + animation.loopFrames())
                  % animation.loopFrames();
      tick = length - animation.loopFrames() + tail;
      // Floating-point remainder can round up by one ULP at a loop boundary.
      if (tick >= length) tick = length - animation.loopFrames();
    }
    long start = (long) Math.floor(tick);
    long next = start + 1;
    if (next >= length) next = animation.loopFrames() > 0 ? length - animation.loopFrames() : start;
    float fraction = next == start ? 0 : (float) (tick - start);
    return new FrameBlend(frame(animation, start), frame(animation, next), fraction, finished);
  }

  private static int frame(Animation animation, long index) {
    if (animation.flipFlop() && index >= animation.frameCount()) {
      index = 2L * animation.frameCount() - 1 - index;
    }
    if (animation.reversed()) index = animation.frameCount() - 1L - index;
    return animation.firstFrame() + (int) index;
  }

  private static Vec3 lerp(Vec3 a, Vec3 b, float fraction) {
    return a.scale(1.0 - fraction).add(b.scale(fraction));
  }

  private static Vec3 normalLerp(Vec3 a, Vec3 b, float fraction) {
    Vec3 value = lerp(a, b, fraction);
    double length = Math.hypot(Math.hypot(value.x(), value.y()), value.z());
    if (length < 1e-12) {
      // Antipodal or degenerate input has no unique interpolation direction.
      value = fraction < 0.5 ? a : b;
      length = Math.hypot(Math.hypot(value.x(), value.y()), value.z());
    }
    return length < 1e-12 ? new Vec3(0, 0, 0) : value.scale(1 / length);
  }

  private static void fraction(float fraction) {
    if (!Float.isFinite(fraction) || fraction < 0 || fraction > 1) {
      throw new IllegalArgumentException("MD3 interpolation fraction must be between zero and one");
    }
  }
}
