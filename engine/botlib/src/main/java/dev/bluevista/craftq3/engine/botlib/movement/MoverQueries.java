package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.botlib.BotCollision;
import dev.bluevista.craftq3.engine.botlib.BspTrace;
import dev.bluevista.craftq3.engine.botlib.Contents;
import dev.bluevista.craftq3.engine.botlib.aas.AasWorld.Reachability;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.aas.TravelType;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;

/** Bounded native-observed mover metadata and platform contact queries. */
public final class MoverQueries {
  public record Entity(int type, int modelIndex, Vec3f origin) {
    public Entity {
      origin = Objects.requireNonNull(origin);
    }
  }

  public record ModelBounds(Vec3f min, Vec3f max) {
    public ModelBounds {
      min = Objects.requireNonNull(min);
      max = Objects.requireNonNull(max);
      if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z())
        throw new IllegalArgumentException("Reversed mover model bounds");
    }
  }

  public record BobbingGeometry(int modelIndex, int axis, Vec3f start, Vec3f end, Vec3f current) {
    public BobbingGeometry {
      if (modelIndex < 0 || modelIndex > 65535 || axis < 0 || axis > 2)
        throw new IllegalArgumentException("Invalid bobbing metadata");
      start = Objects.requireNonNull(start);
      end = Objects.requireNonNull(end);
      current = Objects.requireNonNull(current);
    }
  }

  private final BotCollision bsp;
  private final IntFunction<ModelBounds> models;
  private final IntFunction<Optional<Entity>> entities;
  private final int maxEntities;

  public MoverQueries(
      BotCollision bsp,
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
  public Optional<Vec3f> originForModel(int modelIndex) {
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
    if (!reach.is(TravelType.FUNC_BOB))
      throw new IllegalArgumentException("Non-bobbing reach metadata");
    int model = reach.face() & 65535;
    var offset = originForModel(model);
    if (offset.isEmpty()) return Optional.empty();
    var bounds = Objects.requireNonNull(models.apply(model));
    int axis = (reach.face() & 65536) != 0 ? 0 : (reach.face() & 131072) != 0 ? 1 : 2;
    float[] center = {
      (bounds.min.x() + bounds.max.x()) * .5f,
      (bounds.min.y() + bounds.max.y()) * .5f,
      (bounds.min.z() + bounds.max.z()) * .5f
    };
    float[] start = center.clone(), end = center.clone(), current = center.clone();
    start[axis] = (short) (reach.edge() >>> 16);
    end[axis] = (short) reach.edge();
    current[axis] += component(offset.orElseThrow(), axis);
    return Optional.of(
        new BobbingGeometry(model, axis, vector(start), vector(end), vector(current)));
  }

  /** Passes the caller entity as the trace's ignored entity; the hit model determines contact. */
  public boolean onMover(Vec3f origin, int entity, Reachability reach) {
    origin = Objects.requireNonNull(origin);
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
            new Vec3f(origin.x(), origin.y(), origin.z() + 24),
            new Vec3f(-16, -16, -8),
            new Vec3f(16, 16, 8),
            new Vec3f(origin.x(), origin.y(), origin.z() - 48),
            entity,
            Contents.PLAYER_SOLID);
    if (result.startSolid() || result.allSolid() || result.entity() == BspTrace.ENTITY_NONE)
      return false;
    int hit = result.entity();
    if (hit < 0 || hit >= maxEntities) return false;
    return Objects.requireNonNull(entities.apply(hit)).map(Entity::modelIndex).orElse(0) == model;
  }

  private static float component(Vec3f value, int axis) {
    return (axis == 0 ? value.x() : axis == 1 ? value.y() : value.z());
  }

  private static Vec3f vector(float[] value) {
    return new Vec3f(value[0], value[1], value[2]);
  }
}
