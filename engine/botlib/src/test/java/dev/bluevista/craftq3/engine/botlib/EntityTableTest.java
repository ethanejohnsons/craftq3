package dev.bluevista.craftq3.engine.botlib;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

final class EntityTableTest {
  private static final Vec3f MINS = new Vec3f(-1, -1, -1), MAXS = new Vec3f(1, 1, 1);
  private static final BiFunction<Integer, Vec3f, Optional<BotWorld.ModelBounds>> NO_MODELS =
      (model, angles) -> Optional.empty();

  /** Records link changes as "link n mins maxs" and "unlink n". */
  private static final class Recorder implements EntityTable.Links {
    final List<String> events = new ArrayList<>();

    @Override
    public void link(int entity, Vec3f absMins, Vec3f absMaxs) {
      events.add("link " + entity + " " + absMins + " " + absMaxs);
    }

    @Override
    public void unlink(int entity) {
      events.add("unlink " + entity);
    }

    List<String> drain() {
      var drained = List.copyOf(events);
      events.clear();
      return drained;
    }
  }

  @Test
  void boxesRelinkOnlyWhenOriginOrBoundsChangeAfterTheFirstFrame() {
    var table = new EntityTable(8);
    var links = new Recorder();
    var state = BotEntityState.box(1, new Vec3f(10, 0, 0), MINS, MAXS);
    assertTrue(table.update(3, state, 1, .1f, NO_MODELS, links));
    assertEquals(
        List.of("link 3 " + new Vec3f(9, -1, -1) + " " + new Vec3f(11, 1, 1)), links.drain());
    // The first frame always relinks, even for an unchanged state.
    table.update(3, state, 1, .1f, NO_MODELS, links);
    assertEquals(1, links.drain().size());
    table.update(3, state, 2, .2f, NO_MODELS, links);
    assertEquals(List.of(), links.drain());
    table.update(3, state.withBounds(MINS, new Vec3f(2, 1, 1)), 2, .2f, NO_MODELS, links);
    assertEquals(1, links.drain().size());
    var moved = BotEntityState.box(1, new Vec3f(-0f, 0, 0), MINS, new Vec3f(2, 1, 1));
    table.update(3, moved, 2, .3f, NO_MODELS, links);
    assertEquals(1, links.drain().size());
    // Positive and negative zero compare equal.
    table.update(
        3, BotEntityState.box(1, Vec3f.ZERO, MINS, new Vec3f(2, 1, 1)), 2, .4f, NO_MODELS, links);
    assertEquals(List.of(), links.drain());
    assertEquals(new Vec3f(-0f, 0, 0), table.lastVisibleOrigin(3));
    assertEquals(.4f, table.lastUpdate(3));
    assertFalse(table.update(8, state, 2, .4f, NO_MODELS, links));
    assertFalse(table.update(-1, state, 2, .4f, NO_MODELS, links));
  }

  @Test
  void aFrameWithoutAnUpdateInvalidatesAndUnlinksButKeepsTheLastState() {
    var table = new EntityTable(8);
    var links = new Recorder();
    var state = BotEntityState.box(1, new Vec3f(10, 0, 0), MINS, MAXS);
    table.update(2, state, 1, .1f, NO_MODELS, links);
    links.drain();
    assertTrue(table.valid(2, 1));
    assertEquals(1, table.validCount(1));
    table.startFrame(1, links);
    assertEquals(List.of(), links.drain());
    table.startFrame(2, links);
    assertEquals(List.of("unlink 2"), links.drain());
    assertFalse(table.valid(2, 2));
    assertEquals(Optional.of(state), table.state(2));
    assertEquals(new Vec3f(10, 0, 0), table.info(2, 2).origin());
    assertFalse(table.info(2, 2).valid());
    table.startFrame(3, links);
    assertEquals(List.of(), links.drain(), "already unlinked");
    assertEquals(EntityInfo.EMPTY, table.info(5, 3));
  }

  @Test
  void aNullUpdateUnlinksAtOnceAndKeepsValidityAndState() {
    var table = new EntityTable(8);
    var links = new Recorder();
    var state = BotEntityState.box(1, new Vec3f(10, 0, 0), MINS, MAXS);
    table.update(4, state, 1, .1f, NO_MODELS, links);
    links.drain();
    assertTrue(table.update(4, null, 1, .1f, NO_MODELS, links));
    assertEquals(List.of("unlink 4"), links.drain());
    assertTrue(table.valid(4, 1));
    assertEquals(Optional.of(state), table.state(4));
    table.startFrame(2, links);
    assertEquals(List.of(), links.drain());
  }

  @Test
  void modelsTakeTheirBoundsFromTheWorldAndRelinkOnRotation() {
    var table = new EntityTable(8);
    var links = new Recorder();
    BiFunction<Integer, Vec3f, Optional<BotWorld.ModelBounds>> models =
        (model, angles) ->
            model == 7
                ? Optional.of(BotWorld.ModelBounds.forAngles(MINS, MAXS, angles))
                : Optional.empty();
    var door = solid(BotEntityState.box(1, Vec3f.ZERO, Vec3f.ZERO, Vec3f.ZERO), 3, 7);
    table.update(1, door, 1, .1f, models, links);
    assertEquals(List.of("link 1 " + MINS + " " + MAXS), links.drain());
    assertEquals(MAXS, table.state(1).orElseThrow().maxs());
    table.update(1, door, 2, .2f, models, links);
    assertEquals(List.of(), links.drain());
    table.update(1, door.withAngles(new Vec3f(0, 90, 0)), 2, .3f, models, links);
    assertEquals(1, links.drain().size());
    // An unknown model links as a point.
    table.update(2, solid(door, 3, 9), 1, .1f, models, links);
    assertEquals(List.of("link 2 " + Vec3f.ZERO + " " + Vec3f.ZERO), links.drain());
  }

  @Test
  void nonSolidEntitiesKeepTheirPreviousBoundsAndAngles() {
    var table = new EntityTable(8);
    var links = new Recorder();
    var box = BotEntityState.box(1, Vec3f.ZERO, MINS, MAXS).withAngles(new Vec3f(0, 45, 0));
    table.update(1, box, 1, .1f, NO_MODELS, links);
    var ghost = solid(box.withAngles(Vec3f.ZERO).withBounds(Vec3f.ZERO, Vec3f.ZERO), 0, 0);
    table.update(1, ghost, 2, .2f, NO_MODELS, links);
    var kept = table.state(1).orElseThrow();
    assertEquals(MAXS, kept.maxs());
    assertEquals(new Vec3f(0, 45, 0), kept.angles());
    var fresh = solid(BotEntityState.box(1, Vec3f.ZERO, MINS, MAXS), 1, 0);
    table.update(2, fresh, 2, .2f, NO_MODELS, links);
    assertEquals(Vec3f.ZERO, table.state(2).orElseThrow().maxs());
  }

  @Test
  void theWorldEntityAndInvertedBoundsAreNeverLinked() {
    var table = new EntityTable(1024);
    var links = new Recorder();
    var state = BotEntityState.box(1, Vec3f.ZERO, MINS, MAXS);
    table.update(BspTrace.ENTITY_WORLD, state, 1, .1f, NO_MODELS, links);
    table.update(6, state.withBounds(MAXS, MINS), 1, .1f, NO_MODELS, links);
    assertEquals(List.of(), links.drain());
    assertTrue(table.valid(BspTrace.ENTITY_WORLD, 1));
    assertThrows(IllegalArgumentException.class, () -> new EntityTable(0));
    assertThrows(IllegalArgumentException.class, () -> new EntityTable(4097));
  }

  private static BotEntityState solid(BotEntityState state, int solid, int model) {
    return new BotEntityState(
        state.type(),
        state.flags(),
        state.origin(),
        state.angles(),
        state.oldOrigin(),
        state.mins(),
        state.maxs(),
        state.groundEntity(),
        solid,
        model,
        state.modelIndex2(),
        state.frame(),
        state.event(),
        state.eventParm(),
        state.powerups(),
        state.weapon(),
        state.legsAnim(),
        state.torsoAnim());
  }
}
