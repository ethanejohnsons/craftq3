package dev.bluevista.craftq3.engine.game;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.game.world.Contents;
import dev.bluevista.craftq3.engine.game.world.EntityWorld;
import dev.bluevista.craftq3.engine.game.world.GameEntities;
import dev.bluevista.craftq3.engine.game.world.GameTrace;
import dev.bluevista.craftq3.engine.vmabi.QagameImport;
import dev.bluevista.craftq3.engine.vmabi.layout.EntityStateLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.SharedEntityLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.TraceLayout;
import org.junit.jupiter.api.Test;

final class EntityWorldTest {
  private static final float[] MINS = {-15, -15, -24}, MAXS = {15, 15, 32};
  private static final SharedEntityLayout L = SharedEntityLayout.Q3_132;

  private static float[] v(float x, float y, float z) {
    return new float[] {x, y, z};
  }

  @Test
  void linkEncodesSolidAbsBoundsAreasClustersAndLinkCount() {
    Harness h = new Harness().spawn();
    EntityWorld world = h.server.world();
    GameEntities g = world.entities();
    h.game.spawn(8, v(-100, 0, 24), MINS, MAXS, Contents.BODY, 0);
    assertTrue(g.linked(8));
    assertEquals(1, g.linkCount(8));
    float[] absmin = new float[3], absmax = new float[3];
    g.absmin(8, absmin);
    g.absmax(8, absmax);
    assertArrayEquals(v(-116, -16, -1), absmin);
    assertArrayEquals(v(-84, 16, 57), absmax);
    assertEquals(
        (32 + 32) << 16 | 24 << 8 | 15,
        h.game.memory().readInt(FakeGame.entity(8) + EntityStateLayout.SOLID));
    assertEquals(0, world.areaNum(8));
    assertEquals(-1, world.areaNum2(8));
    assertEquals(1, world.clusterCount(8));
    // The door straddles areas 1 and 0 (front leaves first) and is a brush model with unknown
    // contents.
    assertEquals(1, world.areaNum(14));
    assertEquals(0, world.areaNum2(14));
    assertEquals(-1, g.contents(14));
    assertEquals(
        Contents.SOLID_BMODEL,
        h.game.memory().readInt(FakeGame.entity(14) + EntityStateLayout.SOLID));
    h.game.spawn(8, v(-100, 0, 24), MINS, MAXS, 0, 0);
    assertEquals(2, g.linkCount(8));
    assertEquals(0, h.game.memory().readInt(FakeGame.entity(8) + EntityStateLayout.SOLID));
    h.game.unlink(8);
    assertFalse(g.linked(8));
    assertFalse(world.inSector(8));
  }

  @Test
  void entitiesInBoxUsesAbsoluteBounds() {
    Harness h = new Harness().spawn();
    h.game.spawn(8, v(-100, 0, 24), MINS, MAXS, Contents.BODY, 0);
    h.game.spawn(9, v(300, 0, 24), MINS, MAXS, Contents.BODY, 0);
    int list = h.game.string(" ".repeat(64));
    int count =
        h.game.sys(
            QagameImport.G_ENTITIES_IN_BOX,
            h.game.vec(-200, -50, 0),
            h.game.vec(-90, 50, 50),
            list,
            16);
    assertEquals(1, count);
    assertEquals(8, h.game.memory().readInt(list));
    count =
        h.game.sys(
            QagameImport.G_ENTITIES_IN_BOX,
            h.game.vec(-1000, -500, -100),
            h.game.vec(1000, 500, 100),
            list,
            2);
    assertEquals(2, count, "the list is cut at the caller's capacity");
  }

  @Test
  void tracesHitTheFloorEntitiesAndHonourPassEntityAndOwners() {
    Harness h = new Harness().spawn();
    EntityWorld world = h.server.world();
    GameTrace t = new GameTrace();
    world.trace(
        t,
        v(-300, 0, 100),
        null,
        null,
        v(-300, 0, -100),
        TraceLayout.ENTITYNUM_NONE,
        Contents.SOLID,
        false);
    assertEquals(TraceLayout.ENTITYNUM_WORLD, t.entityNum);
    assertEquals(0, t.endPos[2], 0.2f);
    assertEquals(1, t.planeNormal[2]);

    h.game.spawn(8, v(-100, 0, 24), MINS, MAXS, Contents.BODY, 0);
    world.trace(
        t,
        v(-300, 0, 30),
        null,
        null,
        v(100, 0, 30),
        TraceLayout.ENTITYNUM_NONE,
        Contents.BODY,
        false);
    assertEquals(8, t.entityNum);
    assertEquals(-115, t.endPos[0], 0.2f);
    assertEquals(Contents.BODY, t.contents);
    // The pass entity is skipped, and so are its missiles.
    world.trace(t, v(-300, 0, 30), null, null, v(100, 0, 30), 8, Contents.BODY, false);
    assertEquals(TraceLayout.ENTITYNUM_NONE, t.entityNum);
    h.game.memory().writeInt(FakeGame.entity(8) + L.ownerNum(), 5);
    world.trace(t, v(-300, 0, 30), null, null, v(100, 0, 30), 5, Contents.BODY, false);
    assertEquals(TraceLayout.ENTITYNUM_NONE, t.entityNum, "own missile");
    // A mask without the entity's contents ignores it.
    world.trace(
        t,
        v(-300, 0, 30),
        null,
        null,
        v(100, 0, 30),
        TraceLayout.ENTITYNUM_NONE,
        Contents.WATER,
        false);
    assertEquals(1, t.fraction);
    // Brush models trace their inline model.
    world.trace(
        t,
        v(-100, 30, 64),
        null,
        null,
        v(100, 30, 64),
        TraceLayout.ENTITYNUM_NONE,
        Contents.SOLID,
        false);
    assertEquals(14, t.entityNum);
    assertEquals(-8, t.endPos[0], 0.2f);
  }

  @Test
  void traceSyscallWritesTheGuestTraceAndCapsulesWork() {
    Harness h = new Harness().spawn();
    h.game.spawn(8, v(-100, 0, 24), MINS, MAXS, Contents.BODY, Contents.SVF_CAPSULE);
    int trace = h.game.string(" ".repeat(TraceLayout.BYTES));
    for (QagameImport kind :
        new QagameImport[] {QagameImport.G_TRACE, QagameImport.G_TRACECAPSULE}) {
      h.game.sys(
          kind,
          trace,
          h.game.vec(-300, 0, 30),
          h.game.vec(-4, -4, -4),
          h.game.vec(4, 4, 4),
          h.game.vec(100, 0, 30),
          1023,
          Contents.BODY | Contents.SOLID);
      TraceLayout.VmTrace result = TraceLayout.read(h.game.memory(), trace);
      assertEquals(8, result.entityNum(), kind.name());
      assertTrue(result.fraction() > 0 && result.fraction() < 1);
    }
    assertEquals(0, h.server.degradationTotal());
  }

  @Test
  void pointContentsIncludeEntitiesAsBodyAndContactTestsOverlap() {
    Harness h = new Harness().spawn();
    EntityWorld world = h.server.world();
    assertEquals(Contents.SOLID, world.pointContents(v(-300, 0, -10), TraceLayout.ENTITYNUM_NONE));
    h.game.spawn(8, v(-100, 0, 24), MINS, MAXS, Contents.TRIGGER, 0);
    assertEquals(Contents.BODY, world.pointContents(v(-100, 0, 24), TraceLayout.ENTITYNUM_NONE));
    assertEquals(0, world.pointContents(v(-100, 0, 24), 8));
    assertTrue(world.entityContact(v(-110, -5, 0), v(-90, 5, 10), 8, false));
    assertFalse(world.entityContact(v(-300, -5, 0), v(-290, 5, 10), 8, false));
    assertEquals(
        1,
        h.game.sys(
            QagameImport.G_ENTITY_CONTACTCAPSULE,
            h.game.vec(-110, -5, 0),
            h.game.vec(-90, 5, 10),
            FakeGame.entity(8)));
  }

  @Test
  void pvsAndAreaPortals() {
    Harness h = new Harness().spawn();
    EntityWorld world = h.server.world();
    assertTrue(world.inPvs(v(-100, 0, 10), v(300, 0, 10), true));
    assertFalse(world.inPvs(v(-100, 0, 10), v(300, 0, 10), false), "door closed");
    assertFalse(world.inPvs(v(-100, 0, 10), v(1000, 0, 10), true));
    assertEquals(0, h.game.sys(QagameImport.G_AREAS_CONNECTED, 0, 1));
    h.game.sys(QagameImport.G_ADJUST_AREA_PORTAL_STATE, FakeGame.entity(14), 1);
    h.game.sys(QagameImport.G_ADJUST_AREA_PORTAL_STATE, FakeGame.entity(14), 1);
    assertEquals(1, h.game.sys(QagameImport.G_AREAS_CONNECTED, 1, 0));
    assertEquals(
        1, h.game.sys(QagameImport.G_IN_PVS, h.game.vec(-100, 0, 10), h.game.vec(300, 0, 10)));
    h.game.sys(QagameImport.G_ADJUST_AREA_PORTAL_STATE, FakeGame.entity(14), 0);
    assertTrue(world.visibility().areasConnected(0, 1), "reference counted");
    h.game.sys(QagameImport.G_ADJUST_AREA_PORTAL_STATE, FakeGame.entity(14), 0);
    assertFalse(world.visibility().areasConnected(0, 1));
    assertFalse(world.visibility().areasConnected(0, -1));
  }

  @Test
  void locateGameDataChecksSizesAgainstTheProfile() {
    Harness h = new Harness().spawn();
    h.game.entities(32);
    assertEquals(32, h.server.world().entities().count());
    h.server.cvars().set("vm_strictSyscalls", "0");
    // An entity smaller than sharedEntity_t is refused (LocateGameData is critical: it faults).
    var failure =
        assertThrows(
            RuntimeException.class,
            () ->
                h.game.sys(
                    QagameImport.G_LOCATE_GAME_DATA,
                    FakeGame.ENTITIES,
                    16,
                    400,
                    FakeGame.CLIENTS,
                    512));
    assertTrue(failure.getMessage().contains("sharedEntity_t"), failure.getMessage());
  }
}
