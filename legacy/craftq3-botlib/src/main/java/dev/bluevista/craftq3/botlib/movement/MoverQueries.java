package dev.bluevista.craftq3.botlib.movement;

import dev.bluevista.craftq3.assets.aas.AasMap.Reachability;
import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.collision.TraceWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;

/** Bounded native-observed mover metadata and platform contact queries. */
public final class MoverQueries {
  public record Entity(int type, int modelIndex, Vec3 origin) {
    public Entity {
      origin = MovementAbi.vector(origin);
    }
  }

  public record ModelBounds(Vec3 min, Vec3 max) {
    public ModelBounds {
      min = MovementAbi.vector(min);
      max = MovementAbi.vector(max);
      if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z())
        throw new IllegalArgumentException("Reversed mover model bounds");
    }
  }

  public record BobbingGeometry(int modelIndex, int axis, Vec3 start, Vec3 end, Vec3 current) {
    public BobbingGeometry {
      if (modelIndex < 0 || modelIndex > 65535 || axis < 0 || axis > 2)
        throw new IllegalArgumentException("Invalid bobbing metadata");
      start = MovementAbi.vector(start);
      end = MovementAbi.vector(end);
      current = MovementAbi.vector(current);
    }
  }

  private final TraceWorld bsp;
  private final IntFunction<ModelBounds> models;
  private final IntFunction<Optional<Entity>> entities;
  private final int maxEntities;

  public MoverQueries(
      TraceWorld bsp,
      IntFunction<ModelBounds> models,
      IntFunction<Optional<Entity>> entities,
      int maxEntities) {
    this.bsp = Objects.requireNonNull(bsp);
    this.models = Objects.requireNonNull(models);
    this.entities = Objects.requireNonNull(entities);
    if (maxEntities < 1 || maxEntities > 1024)
      throw new IllegalArgumentException("Invalid mover entity limit");
    this.maxEntities = maxEntities;
  }

  /**
   * The caller supplies retained entity metadata, including records invalidated or unlinked later.
   */
  public Optional<Vec3> originForModel(int modelIndex) {
    for (int i = 0; i < maxEntities; i++) {
      var entity = Objects.requireNonNull(entities.apply(i));
      if (entity.isPresent()
          && entity.orElseThrow().type() == 4
          && entity.orElseThrow().modelIndex() == modelIndex)
        return Optional.of(entity.orElseThrow().origin());
    }
    return Optional.empty();
  }

  public Optional<BobbingGeometry> bobbing(Reachability reach) {
    Objects.requireNonNull(reach);
    if (reach.baseTravelType() != 19)
      throw new IllegalArgumentException("Non-bobbing reach metadata");
    int model = reach.face() & 65535;
    var offset = originForModel(model);
    if (offset.isEmpty()) return Optional.empty();
    var bounds = Objects.requireNonNull(models.apply(model));
    int axis = (reach.face() & 65536) != 0 ? 0 : (reach.face() & 131072) != 0 ? 1 : 2;
    float[] center = {
      ((float) bounds.min.x() + (float) bounds.max.x()) * .5f,
      ((float) bounds.min.y() + (float) bounds.max.y()) * .5f,
      ((float) bounds.min.z() + (float) bounds.max.z()) * .5f
    };
    float[] start = center.clone(), end = center.clone(), current = center.clone();
    start[axis] = (short) (reach.edge() >>> 16);
    end[axis] = (short) reach.edge();
    current[axis] += component(offset.orElseThrow(), axis);
    return Optional.of(
        new BobbingGeometry(model, axis, vector(start), vector(end), vector(current)));
  }

  /** Passes the caller entity as the trace's ignored entity; the hit model determines contact. */
  public boolean onMover(Vec3 origin, int entity, Reachability reach) {
    origin = MovementAbi.vector(origin);
    Objects.requireNonNull(reach);
    if (entity < 0 || entity >= maxEntities)
      throw new IllegalArgumentException("Invalid mover contact entity");
    int model = reach.face() & 65535;
    var offset = originForModel(model);
    if (offset.isEmpty()) return false;
    var bounds = Objects.requireNonNull(models.apply(model));
    for (int axis = 0; axis < 2; axis++) {
      float low = component(bounds.min, axis) + component(offset.orElseThrow(), axis);
      float high = component(bounds.max, axis) + component(offset.orElseThrow(), axis);
      if (!Float.isFinite(low) || !Float.isFinite(high))
        throw new IllegalArgumentException("Translated mover bounds exceed float range");
      float value = component(origin, axis);
      if (value < low - 16 || value > high + 16) return false;
    }
    var result =
        bsp.trace(
            new TraceRequest(
                new Vec3(origin.x(), origin.y(), (float) origin.z() + 24),
                new Vec3(origin.x(), origin.y(), (float) origin.z() - 48),
                new Vec3(-16, -16, -8),
                new Vec3(16, 16, 8),
                65537,
                entity));
    if (result.startSolid() || result.allSolid() || result.hit().isEmpty()) return false;
    int hit = result.hit().orElseThrow().entity();
    if (hit < 0 || hit >= maxEntities) return false;
    return Objects.requireNonNull(entities.apply(hit)).map(Entity::modelIndex).orElse(0) == model;
  }

  private static float component(Vec3 value, int axis) {
    return (float) (axis == 0 ? value.x() : axis == 1 ? value.y() : value.z());
  }

  private static Vec3 vector(float[] value) {
    return new Vec3(value[0], value[1], value[2]);
  }
}
