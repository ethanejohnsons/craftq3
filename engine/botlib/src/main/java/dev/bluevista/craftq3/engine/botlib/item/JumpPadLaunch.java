package dev.bluevista.craftq3.engine.botlib.item;

import dev.bluevista.craftq3.engine.botlib.BotWorld;
import dev.bluevista.craftq3.engine.botlib.BspEntities;
import dev.bluevista.craftq3.engine.botlib.Contents;
import dev.bluevista.craftq3.engine.botlib.movement.AasMovementPredictor;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/** Launch metadata derived from a BSP trigger/target and the AAS presence hull. */
public final class JumpPadLaunch {
  public record Launch(Vec3f origin, Vec3f minimum, Vec3f maximum, Vec3f velocity) {}

  private final BspEntities entities;
  private final IntFunction<Optional<BotWorld.ModelBounds>> models;
  private final AasMovementPredictor.World world;
  private final Consumer<String> diagnostics;

  public JumpPadLaunch(
      BspEntities entities,
      IntFunction<Optional<BotWorld.ModelBounds>> models,
      AasMovementPredictor.World world,
      Consumer<String> diagnostics) {
    this.entities = Objects.requireNonNull(entities);
    this.models = Objects.requireNonNull(models);
    this.world = Objects.requireNonNull(world);
    this.diagnostics = Objects.requireNonNull(diagnostics);
  }

  /**
   * Entity indices are zero-based indices into the BSP entity list. Unusable metadata (no inline
   * model, no target, a target that is not above the pad) is reported and yields no launch.
   */
  public Optional<Launch> resolve(int entityIndex, float gravity) {
    if (entityIndex < 0 || entityIndex >= entities.count())
      throw new IllegalArgumentException("Invalid BSP jump-pad entity index");
    if (!Float.isFinite(gravity) || gravity < 0 || gravity > 100_000)
      throw new IllegalArgumentException("Invalid jump-pad gravity");
    var entity = entities.list().get(entityIndex);
    String modelText = entity.getOrDefault("model", "");
    if (!modelText.matches("\\*[0-9]{1,9}")) {
      diagnostics.accept("Jump-pad entity " + entityIndex + " has no valid inline model");
      return Optional.empty();
    }
    int model = Integer.parseInt(modelText.substring(1));
    var bounds = models.apply(model);
    if (bounds.isEmpty()) {
      diagnostics.accept("Jump-pad entity " + entityIndex + " has an unavailable inline model");
      return Optional.empty();
    }
    // The model callback supplies origin zero; native absolute bounds include that addition.
    Vec3f minimum = bounds.get().mins().add(bounds.get().origin());
    Vec3f maximum = bounds.get().maxs().add(bounds.get().origin());
    Vec3f center =
        new Vec3f(
            (minimum.x() + maximum.x()) * .5f,
            (minimum.y() + maximum.y()) * .5f,
            (minimum.z() + maximum.z()) * .5f);
    var trace =
        world.trace(
            new Vec3f(center.x(), center.y(), center.z() + 64),
            center,
            Contents.PRESENCE_CROUCH,
            -1);
    Vec3f landing = trace.startSolid() ? center : trace.endPosition();
    Vec3f start = new Vec3f(landing.x(), landing.y(), landing.z() + .125f);
    if (trace.startSolid()) diagnostics.accept("Jump-pad entity " + entityIndex + " starts solid");

    String target = entity.get("target");
    if (target == null || target.isEmpty()) return Optional.empty();
    for (var candidate : entities.list()) {
      if (!target.equals(candidate.get("targetname"))) continue;
      Vec3f destination = BspEntities.vector(candidate.getOrDefault("origin", "0 0 0"));
      float x = destination.x() - center.x();
      float y = destination.y() - center.y();
      float height = destination.z() - center.z();
      if (height <= 0 || gravity == 0) {
        // The original arithmetic becomes nonfinite here; such a pad cannot launch a bot.
        if (height != 0)
          diagnostics.accept(
              "Jump-pad entity " + entityIndex + " needs a target above it and gravity");
        return Optional.empty();
      }
      float time = (float) Math.sqrt(height / (.5 * gravity));
      float squared = x * x + y * y + height * height;
      float inverseLength = 1 / (float) Math.sqrt(squared);
      float distance = squared * inverseLength;
      float speed = (distance / time) * 1.1f;
      Vec3f velocity =
          new Vec3f((x * inverseLength) * speed, (y * inverseLength) * speed, time * gravity);
      return Optional.of(new Launch(start, minimum, maximum, velocity));
    }
    return Optional.empty();
  }
}
