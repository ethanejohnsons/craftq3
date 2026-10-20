package dev.bluevista.craftq3.engine.botlib;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.botlib.aas.TravelFlags;
import dev.bluevista.craftq3.engine.botlib.goal.Goal;
import dev.bluevista.craftq3.engine.botlib.movement.MovementInit;
import dev.bluevista.craftq3.engine.botlib.testing.StubWorld;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.MemoryAssets;
import dev.bluevista.craftq3.engine.formats.aas.AasFixture;
import dev.bluevista.craftq3.engine.formats.aas.TravelType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The facade over the authored three-area AAS fixture of the format layer: x above 0 is area 1, x
 * in [-1, 0] the portal area 2, below -1 area 3. Area 3 walks back to area 1; the only way forward
 * from area 1 is a team-one-only reachability.
 */
final class BotLibTest {
  private static final String ENTITIES =
      """
      { "classname" "worldspawn" "message" "first" "message" "last" }
      { "classname" "info_camp" "origin" "0.5 0 0" }
      { "classname" "target_location" "origin" "-1.5 0 0" "message" "Red Base" }
      { "classname" "weapon_gun" "origin" "0.5 0 0" }
      """;

  private static MemoryAssets files() {
    return new MemoryAssets()
        .put("maps/test.aas", AasFixture.map(4))
        .put(
            "botfiles/weapons.c",
            "weaponinfo { number 1 name \"Gun\" projectile \"bullet\" }"
                + " projectileinfo { name \"bullet\" }")
        .put(
            "botfiles/items.c",
            "iteminfo \"weapon_gun\" { name \"Gun\" modelindex 1 type 1 index 1 respawntime 10"
                + " mins {-15,-15,-15} maxs {15,15,15} }")
        .put("botfiles/syn.c", "")
        .put("botfiles/rnd.c", "")
        .put("botfiles/match.c", "")
        .put("botfiles/rchat.c", "")
        .put("botfiles/weights.c", "weight \"weapon_gun\" { return balance(50, 40, 60); }")
        .put("botfiles/bots/test_t.c", "chat \"test\" { type \"hello\" { \"hi there\"; } }");
  }

  private static BotLib loaded(StubWorld world) {
    var bots = new BotLib(world);
    bots.setStrict(true);
    bots.libVarSet("maxclients", "4");
    assertEquals(BotLib.NO_ERROR, bots.setup());
    assertEquals(BotLib.NO_ERROR, bots.loadMap("maps/test.bsp"));
    return bots;
  }

  @Test
  void setupLoadsTheBotFilesAndReportsMissingOnes() {
    var world = new StubWorld(files(), ENTITIES, Map.of());
    try (var bots = loaded(world)) {
      assertTrue(bots.isSetup());
      assertTrue(bots.aasInitialized());
      assertEquals("test", bots.mapName().orElseThrow());
      assertEquals(BotLib.CANNOT_OPEN_AAS_FILE, bots.loadMap("missing"));
      assertFalse(bots.aasInitialized());
    }
    var empty = new BotLib(new StubWorld(new MemoryAssets()));
    assertEquals(BotLib.CANNOT_LOAD_WEAPON_CONFIG, empty.setup());
    assertFalse(empty.isSetup());
    assertEquals(BotLib.LIBRARY_NOT_SETUP, empty.startFrame(1));
  }

  @Test
  void libraryVariablesAreCaseInsensitiveAndTheMapChecksumIsChecked() {
    var world = new StubWorld(files(), ENTITIES, Map.of());
    try (var bots = loaded(world)) {
      bots.libVarSet("G_GameType", "4");
      assertEquals("4", bots.libVarGet("g_gametype"));
      assertEquals("", bots.libVarGet("unset"));
      bots.libVarSet("sv_mapChecksum", "1");
      assertEquals(BotLib.WRONG_AAS_FILE_ID, bots.loadMap("test"));
      bots.libVarSet("sv_mapChecksum", Integer.toString(AasFixture.CHECKSUM));
      assertEquals(BotLib.NO_ERROR, bots.loadMap("test"));
    }
  }

  @Test
  void aasQueriesFollowTheFixtureGeometry() {
    var world = new StubWorld(files(), ENTITIES, Map.of());
    try (var bots = loaded(world)) {
      assertEquals(1, bots.aasPointAreaNum(new Vec3f(.5f, 0, 0)));
      assertEquals(2, bots.aasPointAreaNum(new Vec3f(-.5f, 0, 0)));
      assertEquals(3, bots.aasPointAreaNum(new Vec3f(-1.5f, 0, 0)));
      // Dense routable-area index: clusters 1 and 2 hold two areas each; the portal counts in
      // its front cluster.
      assertEquals(4, bots.aasPointReachabilityAreaIndex(null));
      assertEquals(0, bots.aasPointReachabilityAreaIndex(new Vec3f(.5f, 0, 0)));
      assertEquals(1, bots.aasPointReachabilityAreaIndex(new Vec3f(-.5f, 0, 0)));
      assertEquals(2, bots.aasPointReachabilityAreaIndex(new Vec3f(-1.5f, 0, 0)));
      assertEquals(1, bots.aasAreaReachability(1));
      assertEquals(8, bots.aasAreaInfo(2).orElseThrow().contents());
      assertTrue(bots.aasAreaInfo(99).isEmpty());
      assertEquals(
          List.of(1, 2), bots.aasBBoxAreas(new Vec3f(-.5f, 0, 0), new Vec3f(.5f, 1, 1), 8));
      var crossed = bots.aasTraceAreas(new Vec3f(.5f, 0, 0), new Vec3f(-1.5f, 0, 0), 8);
      assertEquals(List.of(1, 2, 3), crossed.stream().map(BotLib.AreaEntry::area).toList());
      var box = bots.aasPresenceTypeBoundingBox(Contents.PRESENCE_CROUCH);
      assertEquals(new Vec3f(15, 15, 8), box.maxs());
    }
  }

  @Test
  void travelTimesRespectTravelFlagsAndRoutingAreaOverrides() {
    var world = new StubWorld(files(), ENTITIES, Map.of());
    try (var bots = loaded(world)) {
      // The portal area 2 rides a bobbing platform (time 300) into area 3.
      Vec3f origin = new Vec3f(-.5f, 0, 0);
      int ride = bots.aasAreaTravelTimeToGoalArea(2, origin, 3, TravelFlags.DEFAULT);
      assertTrue(ride > 300, () -> "ride " + ride);
      assertEquals(0, bots.aasAreaTravelTimeToGoalArea(2, origin, 3, TravelFlags.WALK));
      // Disabling the goal area removes the route; enabling restores it.
      assertEquals(1, bots.aasEnableRoutingArea(3, 0));
      assertEquals(0, bots.aasEnableRoutingArea(3, -1));
      assertEquals(0, bots.aasAreaTravelTimeToGoalArea(2, origin, 3, TravelFlags.DEFAULT));
      assertEquals(0, bots.aasEnableRoutingArea(3, 1));
      assertEquals(ride, bots.aasAreaTravelTimeToGoalArea(2, origin, 3, TravelFlags.DEFAULT));
      assertEquals(0, bots.aasEnableRoutingArea(99, 0));
      var route = bots.aasPredictRoute(2, origin, 3, TravelFlags.DEFAULT, 0, 0, 0, 0, 0, 0);
      assertTrue(route.success());
      assertEquals(3, route.prediction().endArea());
    }
  }

  @Test
  void bspEntityQueriesKeepTheLastDuplicateAndParseLeniently() {
    var world = new StubWorld(files(), ENTITIES, Map.of());
    try (var bots = loaded(world)) {
      assertEquals(1, bots.aasNextBspEntity(0));
      assertEquals(0, bots.aasNextBspEntity(4));
      assertEquals("last", bots.aasValueForBspEpairKey(1, "message").orElseThrow());
      assertTrue(bots.aasValueForBspEpairKey(1, "Message").isEmpty());
      assertEquals(new Vec3f(.5f, 0, 0), bots.aasVectorForBspEpairKey(2, "origin").orElseThrow());
      assertEquals(.5f, bots.aasFloatForBspEpairKey(2, "origin").orElseThrow());
      assertEquals(0, bots.aasIntForBspEpairKey(2, "origin").orElseThrow());
      assertTrue(bots.aasValueForBspEpairKey(9, "origin").isEmpty());
      assertEquals(1, bots.getNextCampSpotGoal(0).orElseThrow().next());
      assertEquals(3, bots.getMapLocationGoal("red base").orElseThrow().area());
    }
  }

  @Test
  void entityUpdatesFollowFrameValidityAndLinkLifetime() {
    var world = new StubWorld(files(), ENTITIES, Map.of());
    try (var bots = loaded(world)) {
      bots.startFrame(.1f);
      var state =
          BotEntityState.box(1, new Vec3f(.5f, 0, 0), new Vec3f(-1, -1, -1), new Vec3f(1, 1, 1));
      assertEquals(BotLib.NO_ERROR, bots.updateEntity(5, state));
      assertEquals(BotLib.INVALID_ENTITY_NUMBER, bots.updateEntity(4096, state));
      var info = bots.aasEntityInfo(5);
      assertTrue(info.valid());
      assertEquals(5, info.number());
      assertEquals(.1f, info.lastUpdateTime());
      assertEquals(new Vec3f(1, 1, 1), info.maxs());
      bots.startFrame(.2f);
      assertFalse(bots.aasEntityInfo(5).valid());
      assertEquals(state.origin(), bots.aasEntityInfo(5).origin());
      bots.updateEntity(5, null);
      assertEquals(state.origin(), bots.aasEntityInfo(5).origin());
      assertEquals(EntityInfo.EMPTY, bots.aasEntityInfo(6));
      assertEquals(.2f, bots.aasTime());
    }
  }

  @Test
  void levelItemsAndGoalWeightsDriveItemChoice() {
    var world = new StubWorld(files(), ENTITIES, Map.of());
    try (var bots = loaded(world)) {
      assertEquals("Gun", bots.goalName(1));
      var item = bots.getLevelItemGoal(-1, "gun").orElseThrow();
      assertEquals(1, item.number());
      int goals = bots.allocGoalState(0);
      assertEquals(BotLib.NO_ERROR, bots.loadItemWeights(goals, "weights.c"));
      assertEquals(BotLib.CANNOT_LOAD_ITEM_WEIGHTS, bots.loadItemWeights(goals, "missing.c"));
      assertEquals(BotLib.NO_ERROR, bots.loadItemWeights(goals, "weights.c"));
      bots.pushGoal(goals, Goal.at(new Vec3f(.5f, 0, 0), 1));
      assertEquals(1, bots.getTopGoal(goals).orElseThrow().area());
      bots.popGoal(goals);
      assertTrue(bots.getTopGoal(goals).isEmpty());
      // No slot has expired at time zero, so the original stores nothing yet.
      bots.setAvoidGoalTime(goals, 1, -1);
      assertEquals(0, bots.avoidGoalTime(goals, 1));
      bots.startFrame(1);
      bots.setAvoidGoalTime(goals, 1, -1);
      assertEquals(10, bots.avoidGoalTime(goals, 1));
    }
  }

  @Test
  void fuzzyLogicSaveMutateAndInterbreedOperateOnGoalStates() {
    var world = new StubWorld(files(), ENTITIES, Map.of());
    try (var bots = loaded(world)) {
      int a = bots.allocGoalState(0), b = bots.allocGoalState(1), child = bots.allocGoalState(2);
      for (int state : new int[] {a, b, child}) bots.loadItemWeights(state, "weights.c");
      assertFalse(bots.saveGoalFuzzyLogic(a, "saved.c"));
      world.allowWrites = true;
      assertTrue(bots.saveGoalFuzzyLogic(a, "saved.c"));
      String saved = new String(world.written.get("saved.c"), StandardCharsets.ISO_8859_1);
      assertTrue(saved.contains("weight \"weapon_gun\""), saved);
      assertTrue(saved.contains("balance(50.0, 40.0, 60.0)"), saved);
      bots.seed(7);
      bots.mutateGoalFuzzyLogic(a, 1);
      bots.interbreedGoalFuzzyLogic(a, b, child);
      assertTrue(world.printed.stream().noneMatch(line -> line.contains("cannot interbreed")));
      var selection = bots.geneticParentsAndChildSelection(new float[] {1, 2, 3, -1});
      var chosen = selection.orElseThrow();
      assertNotEquals(chosen.parent1(), chosen.parent2());
      assertTrue(bots.geneticParentsAndChildSelection(new float[] {1, -1, 2}).isEmpty());
    }
  }

  @Test
  void enteredChatsBecomeClientCommandsOnlyWhenAMessageIsPending() {
    var world = new StubWorld(files(), ENTITIES, Map.of());
    try (var bots = loaded(world)) {
      int chat = bots.allocChatState();
      bots.setChatName(chat, "Bot", 2);
      assertEquals(BotLib.NO_ERROR, bots.loadChatFile(chat, "bots/test_t.c", "test"));
      assertEquals(BotLib.CANNOT_LOAD_ICHAT, bots.loadChatFile(chat, "bots/test_t.c", "other"));
      bots.enterChat(chat, 0, BotLib.CHAT_ALL);
      assertTrue(world.commands.isEmpty());
      assertEquals(1, bots.numInitialChats(chat, "hello"));
      bots.initialChat(chat, "hello", 0, List.of());
      bots.enterChat(chat, 3, BotLib.CHAT_TELL);
      assertEquals(List.of("2:tell 3 hi there"), world.commands);
    }
  }

  @Test
  void moveToGoalDegradesInsteadOfThrowingAndAppliesElementaryActions() {
    var world = new StubWorld(files(), ENTITIES, Map.of());
    try (var bots = loaded(world)) {
      bots.startFrame(1);
      int move = bots.allocMoveState();
      bots.initMoveState(
          move,
          new MovementInit(
              new Vec3f(-1.5f, 0, 0), Vec3f.ZERO, Vec3f.ZERO, 1, 1, .1f, 2, Vec3f.ZERO, 2));
      // The state says the bot stands on ground, so it walks and the facade feeds the command
      // to the elementary actions of the move state's client.
      var output = bots.moveToGoal(move, Goal.at(new Vec3f(.5f, 0, 0), 1), TravelFlags.DEFAULT);
      assertTrue(output.degraded().isEmpty(), output.toString());
      assertEquals(0, output.result().failure());
      assertEquals(TravelType.WALK.id(), output.result().travelType());
      var command = output.command().orElseThrow();
      var input = bots.eaGetInput(1, .1f);
      assertEquals(command.speed(), input.speed());
      assertEquals(command.direction(), input.direction());
      bots.eaResetInput(1);
      // A state that was never initialised fails softly.
      int bare = bots.allocMoveState();
      var failed = bots.moveToGoal(bare, Goal.at(Vec3f.ZERO, 1), TravelFlags.DEFAULT);
      assertEquals(1, failed.result().failure());
      assertEquals(0, bots.eaGetInput(1, .1f).actionFlags());
    }
  }

  @Test
  void setupBindsTheLibraryToItsThread() throws Exception {
    var world = new StubWorld(files(), ENTITIES, Map.of());
    try (var bots = loaded(world)) {
      var failure = new AtomicReference<Throwable>();
      var other =
          new Thread(
              () -> {
                try {
                  bots.aasTime();
                } catch (Throwable thrown) {
                  failure.set(thrown);
                }
              });
      other.start();
      other.join();
      assertInstanceOf(IllegalStateException.class, failure.get());
    }
  }
}
