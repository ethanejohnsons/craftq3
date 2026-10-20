package dev.bluevista.craftq3.botlib.item;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.botlib.movement.AasMovementPredictor;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Launch metadata derived from a BSP trigger/target and the AAS presence hull. */
public final class JumpPadLaunch {
  public record Launch(Vec3 origin, Vec3 minimum, Vec3 maximum, Vec3 velocity) {}

  private final BspMap bsp;
  private final AasMovementPredictor.World world;
  private final Consumer<String> diagnostics;

  public JumpPadLaunch(BspMap bsp, AasMovementPredictor.World world, Consumer<String> diagnostics) {
    this.bsp = Objects.requireNonNull(bsp);
    this.world = Objects.requireNonNull(world);
    this.diagnostics = Objects.requireNonNull(diagnostics);
  }

  /** Entity indices are zero-based indices into the immutable BSP entity list. */
  public Optional<Launch> resolve(int entityIndex, float gravity) {
    if (entityIndex < 0 || entityIndex >= bsp.entities().size())
      throw new IllegalArgumentException("Invalid BSP jump-pad entity index");
    if (!Float.isFinite(gravity) || gravity < 0 || gravity > 100_000)
      throw new IllegalArgumentException("Invalid jump-pad gravity");
    var entity = bsp.entities().get(entityIndex);
    String modelText = entity.getOrDefault("model", "");
    if (!modelText.matches("\\*[0-9]{1,9}")) {
      diagnostics.accept("Jump-pad entity " + entityIndex + " has no valid inline model");
      return Optional.empty();
    }
    int model = Integer.parseInt(modelText.substring(1));
    if (model >= bsp.models().size()) {
      diagnostics.accept("Jump-pad entity " + entityIndex + " has an unavailable inline model");
      return Optional.empty();
    }
    var bounds = bsp.models().get(model).bounds();
    // The model callback supplies origin zero; native absolute bounds include that addition.
    Vec3 minimum = absoluteBounds(bounds.min()), maximum = absoluteBounds(bounds.max());
    Vec3 center =
        new Vec3(
            ((float) minimum.x() + (float) maximum.x()) * .5f,
            ((float) minimum.y() + (float) maximum.y()) * .5f,
            ((float) minimum.z() + (float) maximum.z()) * .5f);
    var trace =
        world.trace(new Vec3(center.x(), center.y(), (float) center.z() + 64), center, 4, -1);
    Vec3 landing = trace.startSolid() ? center : point(trace.endPosition());
    Vec3 start = new Vec3(landing.x(), landing.y(), (float) landing.z() + .125f);
    if (trace.startSolid()) diagnostics.accept("Jump-pad entity " + entityIndex + " starts solid");

    String target = entity.get("target");
    if (target == null || target.isEmpty()) return Optional.empty();
    for (var candidate : bsp.entities()) {
      if (!target.equals(candidate.get("targetname"))) continue;
      Vec3 destination;
      try {
        String[] words = candidate.getOrDefault("origin", "0 0 0").trim().split("\\s+");
        if (words.length != 3) throw new IllegalArgumentException("Malformed target origin");
        destination =
            new Vec3(
                Float.parseFloat(words[0]), Float.parseFloat(words[1]), Float.parseFloat(words[2]));
      } catch (IllegalArgumentException malformed) {
        diagnostics.accept("Jump-pad entity " + entityIndex + " has an invalid target origin");
        return Optional.empty();
      }
      float x = (float) destination.x() - (float) center.x();
      float y = (float) destination.y() - (float) center.y();
      float height = (float) destination.z() - (float) center.z();
      if (height == 0) return Optional.empty();
      if (height < 0 || gravity == 0) {
        // Native arithmetic becomes nonfinite for these metadata inputs.
        throw new IllegalArgumentException(
            "Jump-pad launch needs positive target height and gravity");
      }
      float time = (float) Math.sqrt(height / (.5 * gravity));
      float squared = x * x + y * y + height * height;
      float inverseLength = 1 / (float) Math.sqrt(squared);
      float distance = squared * inverseLength;
      float speed = (distance / time) * 1.1f;
      Vec3 velocity =
          new Vec3((x * inverseLength) * speed, (y * inverseLength) * speed, time * gravity);
      return Optional.of(new Launch(start, minimum, maximum, velocity));
    }
    return Optional.empty();
  }

  private static Vec3 point(Vec3 value) {
    return new Vec3((float) value.x(), (float) value.y(), (float) value.z());
  }

  private static Vec3 absoluteBounds(Vec3 value) {
    return new Vec3((float) value.x() + 0f, (float) value.y() + 0f, (float) value.z() + 0f);
  }
}
