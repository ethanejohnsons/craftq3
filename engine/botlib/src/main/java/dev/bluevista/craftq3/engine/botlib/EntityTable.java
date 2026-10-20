package dev.bluevista.craftq3.engine.botlib;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Botlib's entity table ({@code AAS_UpdateEntity} and the frame bookkeeping around it). It keeps
 * the last reported state of each entity, resolves its collision bounds and decides when its AAS
 * area links must be rebuilt:
 *
 * <ul>
 *   <li>An update makes the entity valid for the current frame; the next {@link #startFrame}
 *       invalidates it, and a second frame without an update drops its links. Its last state stays
 *       readable afterwards.
 *   <li>A null update drops the links at once but keeps the validity and state.
 *   <li>An update relinks when the origin changes, when a box's mins/maxs change, when a BSP
 *       model's angles change, and always during the first frame. Not-solid and trigger entities
 *       keep their previous bounds and angles.
 * </ul>
 *
 * These rules were measured against the native library (see {@code BOTLIB_ENTITY_LIFETIME.md} on
 * {@code main}).
 */
public final class EntityTable {
  private static final Log LOG = Log.get(EntityTable.class);

  /** Where entity area links live (the AAS movement world). */
  public interface Links {
    void link(int entity, Vec3f absMins, Vec3f absMaxs);

    void unlink(int entity);
  }

  private record Slot(
      BotEntityState state,
      float updated,
      float interval,
      int frame,
      Vec3f lastVisibleOrigin,
      boolean linked) {
    Slot unlinked() {
      return new Slot(state, updated, interval, frame, lastVisibleOrigin, false);
    }
  }

  private final Slot[] slots;

  public EntityTable(int maxEntities) {
    if (maxEntities < 1 || maxEntities > 4096)
      throw new IllegalArgumentException("Invalid botlib entity count");
    slots = new Slot[maxEntities];
  }

  public int capacity() {
    return slots.length;
  }

  public void clear() {
    Arrays.fill(slots, null);
  }

  /**
   * Advances to {@code frame}: entities not updated during the frame that just ended lose their
   * links. Call before incrementing the frame counter.
   */
  public void startFrame(int currentFrame, Links links) {
    for (int number = 0; number < slots.length; number++) {
      var slot = slots[number];
      if (slot != null && slot.linked() && slot.frame() != currentFrame) {
        links.unlink(number);
        slots[number] = slot.unlinked();
      }
    }
  }

  /**
   * Applies one entity update. {@code models} resolves an inline model's bounds for its angles
   * ({@link BotWorld#bspModelMinsMaxsOrigin}). Returns false for an entity number outside the
   * table.
   */
  public boolean update(
      int number,
      BotEntityState state,
      int frame,
      float time,
      BiFunction<Integer, Vec3f, Optional<BotWorld.ModelBounds>> models,
      Links links) {
    if (number < 0 || number >= slots.length) return false;
    var old = slots[number];
    if (state == null) {
      links.unlink(number);
      if (old != null) slots[number] = old.unlinked();
      return true;
    }
    BotEntityState before = old == null ? null : old.state();
    Vec3f previousOrigin = before == null ? Vec3f.ZERO : before.origin();
    boolean relink =
        frame == 1
            || changed(state.origin(), previousOrigin)
            || (state.solid() == BotEntityState.SOLID_BBOX
                && (changed(state.mins(), before == null ? Vec3f.ZERO : before.mins())
                    || changed(state.maxs(), before == null ? Vec3f.ZERO : before.maxs())))
            || (state.solid() == BotEntityState.SOLID_BSP
                && changed(state.angles(), before == null ? Vec3f.ZERO : before.angles()));
    var resolved = resolveBounds(state, before, models);
    boolean usable =
        resolved.mins().x() <= resolved.maxs().x()
            && resolved.mins().y() <= resolved.maxs().y()
            && resolved.mins().z() <= resolved.maxs().z();
    if (!usable)
      LOG.warnOnce(
          "inverted:" + number, "Bot entity " + number + " has inverted bounds; not linked");
    // The world entity is never linked into AAS areas.
    if (relink && usable && number != BspTrace.ENTITY_WORLD)
      links.link(
          number, resolved.origin().add(resolved.mins()), resolved.origin().add(resolved.maxs()));
    slots[number] =
        new Slot(
            resolved,
            time,
            time - (old == null ? 0 : old.updated()),
            frame,
            previousOrigin,
            relink || (old != null && old.linked()));
    return true;
  }

  private static BotEntityState resolveBounds(
      BotEntityState state,
      BotEntityState previous,
      BiFunction<Integer, Vec3f, Optional<BotWorld.ModelBounds>> models) {
    return switch (state.solid()) {
      case BotEntityState.SOLID_BBOX -> state;
      case BotEntityState.SOLID_BSP -> {
        var bounds = models.apply(state.modelIndex(), state.angles());
        if (bounds.isEmpty()) {
          LOG.warnOnce(
              "model:" + state.modelIndex(),
              "Bot entity uses unknown inline model " + state.modelIndex());
          yield state.withBounds(Vec3f.ZERO, Vec3f.ZERO);
        }
        yield state.withBounds(bounds.get().mins(), bounds.get().maxs());
      }
      // Not-solid, trigger and unknown solids keep their previous bounds and angles.
      default ->
          previous == null
              ? state.withAngles(Vec3f.ZERO).withBounds(Vec3f.ZERO, Vec3f.ZERO)
              : state.withAngles(previous.angles()).withBounds(previous.mins(), previous.maxs());
    };
  }

  private static boolean changed(Vec3f current, Vec3f previous) {
    // Float comparison: positive and negative zero are equal.
    return current.x() != previous.x()
        || current.y() != previous.y()
        || current.z() != previous.z();
  }

  /** The {@code aas_entityinfo_t} view; an entity never updated reads as all zero. */
  public EntityInfo info(int number, int currentFrame) {
    Objects.checkIndex(number, slots.length);
    var slot = slots[number];
    if (slot == null) return EntityInfo.EMPTY;
    var s = slot.state();
    return new EntityInfo(
        slot.frame() == currentFrame,
        s.type(),
        s.flags(),
        slot.updated(),
        slot.interval(),
        number,
        s.origin(),
        s.angles(),
        s.oldOrigin(),
        slot.lastVisibleOrigin(),
        s.mins(),
        s.maxs(),
        s.groundEntity(),
        s.solid(),
        s.modelIndex(),
        s.modelIndex2(),
        s.frame(),
        s.event(),
        s.eventParm(),
        s.powerups(),
        s.weapon(),
        s.legsAnim(),
        s.torsoAnim());
  }

  /** The last state reported for {@code number}, kept after invalidation and unlinking. */
  public Optional<BotEntityState> state(int number) {
    if (number < 0 || number >= slots.length || slots[number] == null) return Optional.empty();
    return Optional.of(slots[number].state());
  }

  public boolean valid(int number, int currentFrame) {
    return number >= 0
        && number < slots.length
        && slots[number] != null
        && slots[number].frame() == currentFrame;
  }

  /** When {@code number} was last updated, or zero. */
  public float lastUpdate(int number) {
    if (number < 0 || number >= slots.length || slots[number] == null) return 0;
    return slots[number].updated();
  }

  /** The origin before the latest update ({@code lastvisorigin}). */
  public Vec3f lastVisibleOrigin(int number) {
    if (number < 0 || number >= slots.length || slots[number] == null) return Vec3f.ZERO;
    return slots[number].lastVisibleOrigin();
  }

  public int validCount(int currentFrame) {
    int count = 0;
    for (var slot : slots) if (slot != null && slot.frame() == currentFrame) count++;
    return count;
  }
}
