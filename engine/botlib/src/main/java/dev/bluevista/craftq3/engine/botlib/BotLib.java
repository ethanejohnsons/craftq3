package dev.bluevista.craftq3.engine.botlib;

import dev.bluevista.craftq3.engine.botlib.aas.AasAlternativeRoutes;
import dev.bluevista.craftq3.engine.botlib.aas.AasLadders;
import dev.bluevista.craftq3.engine.botlib.aas.AasMovementRoutes;
import dev.bluevista.craftq3.engine.botlib.aas.AasNavigation;
import dev.bluevista.craftq3.engine.botlib.aas.AasReachabilityArea;
import dev.bluevista.craftq3.engine.botlib.aas.AasRoutePredictor;
import dev.bluevista.craftq3.engine.botlib.aas.AasRouteTimes;
import dev.bluevista.craftq3.engine.botlib.aas.AasWorld;
import dev.bluevista.craftq3.engine.botlib.aas.TravelPolicy;
import dev.bluevista.craftq3.engine.botlib.character.BotCharacters;
import dev.bluevista.craftq3.engine.botlib.chat.BotChat;
import dev.bluevista.craftq3.engine.botlib.chat.ChatMatcher;
import dev.bluevista.craftq3.engine.botlib.ea.ActionFlags;
import dev.bluevista.craftq3.engine.botlib.ea.BotInput;
import dev.bluevista.craftq3.engine.botlib.ea.ElementaryActions;
import dev.bluevista.craftq3.engine.botlib.goal.BotGoals;
import dev.bluevista.craftq3.engine.botlib.goal.BotItemSelector;
import dev.bluevista.craftq3.engine.botlib.goal.BotMapGoals;
import dev.bluevista.craftq3.engine.botlib.goal.Goal;
import dev.bluevista.craftq3.engine.botlib.goal.GoalQueries;
import dev.bluevista.craftq3.engine.botlib.item.ItemConfig;
import dev.bluevista.craftq3.engine.botlib.item.ItemPlacement;
import dev.bluevista.craftq3.engine.botlib.item.ItemRegistry;
import dev.bluevista.craftq3.engine.botlib.item.JumpPadItemAreas;
import dev.bluevista.craftq3.engine.botlib.movement.AasMovementPredictor;
import dev.bluevista.craftq3.engine.botlib.movement.AasMovementWorld;
import dev.bluevista.craftq3.engine.botlib.movement.BarrierReachMovement;
import dev.bluevista.craftq3.engine.botlib.movement.BobbingPlatformMovement;
import dev.bluevista.craftq3.engine.botlib.movement.BotDirectionalMovement;
import dev.bluevista.craftq3.engine.botlib.movement.BotMovement;
import dev.bluevista.craftq3.engine.botlib.movement.BotMovementView;
import dev.bluevista.craftq3.engine.botlib.movement.BotVisiblePosition;
import dev.bluevista.craftq3.engine.botlib.movement.ElevatorMovement;
import dev.bluevista.craftq3.engine.botlib.movement.GrappleMovement;
import dev.bluevista.craftq3.engine.botlib.movement.GroundMoveToGoal;
import dev.bluevista.craftq3.engine.botlib.movement.GroundReachMovement;
import dev.bluevista.craftq3.engine.botlib.movement.JumpPadMovement;
import dev.bluevista.craftq3.engine.botlib.movement.JumpReachMovement;
import dev.bluevista.craftq3.engine.botlib.movement.JumpRunStart;
import dev.bluevista.craftq3.engine.botlib.movement.LadderMovement;
import dev.bluevista.craftq3.engine.botlib.movement.LedgeReachMovement;
import dev.bluevista.craftq3.engine.botlib.movement.LiquidReachMovement;
import dev.bluevista.craftq3.engine.botlib.movement.MovementInit;
import dev.bluevista.craftq3.engine.botlib.movement.MovementObstacles;
import dev.bluevista.craftq3.engine.botlib.movement.MovementObstruction;
import dev.bluevista.craftq3.engine.botlib.movement.MovementResult;
import dev.bluevista.craftq3.engine.botlib.movement.MovementSettings;
import dev.bluevista.craftq3.engine.botlib.movement.MoverModels;
import dev.bluevista.craftq3.engine.botlib.movement.MoverQueries;
import dev.bluevista.craftq3.engine.botlib.movement.TeleportReachMovement;
import dev.bluevista.craftq3.engine.botlib.movement.WeaponJumpMovement;
import dev.bluevista.craftq3.engine.botlib.script.ScriptSources;
import dev.bluevista.craftq3.engine.botlib.script.ScriptToken;
import dev.bluevista.craftq3.engine.botlib.script.SourceLocation;
import dev.bluevista.craftq3.engine.botlib.weapon.BotWeapons;
import dev.bluevista.craftq3.engine.botlib.weapon.WeaponInfo;
import dev.bluevista.craftq3.engine.botlib.weight.GeneticSelection;
import dev.bluevista.craftq3.engine.botlib.weight.WeightConfig;
import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import dev.bluevista.craftq3.engine.formats.aas.AasFormatException;
import dev.bluevista.craftq3.engine.formats.aas.AasReader;
import dev.bluevista.craftq3.engine.formats.aas.TravelType;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The bot library, one method per entry of the original {@code botlib_export_t} (the game's {@code
 * trap_BotLib*}, {@code trap_AAS_*}, {@code trap_EA_*}, {@code trap_Bot*} and {@code trap_PC_*}
 * imports, numbered 200-581 in the game ABI), so the game host's adapter only marshals guest
 * memory. Botlib reaches the world only through its {@link BotWorld}.
 *
 * <p>Error model: integer results keep the original {@code BLERR_*} codes; a failure inside a
 * query, a movement or a load is logged once and answered with the original's neutral result (no
 * route, a failed move, no item), so a bot can degrade but never ends the match. {@link #setStrict}
 * rethrows instead, for tests.
 *
 * <p>Single-threaded: {@link #setup} binds the library to the calling thread and every entry point
 * checks it.
 */
public final class BotLib implements AutoCloseable {
  private static final Log LOG = Log.get(BotLib.class);

  // Error codes (BLERR_*).
  public static final int NO_ERROR = 0;
  public static final int LIBRARY_NOT_SETUP = 1;
  public static final int INVALID_ENTITY_NUMBER = 2;
  public static final int NO_AAS_FILE = 3;
  public static final int CANNOT_OPEN_AAS_FILE = 4;
  public static final int WRONG_AAS_FILE_ID = 5;
  public static final int WRONG_AAS_FILE_VERSION = 6;
  public static final int CANNOT_READ_AAS_LUMP = 7;
  public static final int CANNOT_LOAD_ICHAT = 8;
  public static final int CANNOT_LOAD_ITEM_WEIGHTS = 9;
  public static final int CANNOT_LOAD_ITEM_CONFIG = 10;
  public static final int CANNOT_LOAD_WEAPON_WEIGHTS = 11;
  public static final int CANNOT_LOAD_WEAPON_CONFIG = 12;

  // Chat destinations (CHAT_*).
  public static final int CHAT_ALL = 0;
  public static final int CHAT_TEAM = 1;
  public static final int CHAT_TELL = 2;

  /** The presence hull bounds ({@code AAS_PresenceTypeBoundingBox}). */
  public record PresenceBox(Vec3f mins, Vec3f maxs) {}

  /** {@code aas_areainfo_t}. */
  public record AreaInfo(
      int contents,
      int flags,
      int presenceType,
      int cluster,
      Vec3f mins,
      Vec3f maxs,
      Vec3f center) {}

  /** One area crossed by {@link #aasTraceAreas} and the point where the segment enters it. */
  public record AreaEntry(int area, Vec3f point) {}

  /** A level item goal and its number, the cursor for the next call. */
  public record ItemGoal(int number, Goal goal) {}

  /** A camp spot goal and the cursor for the next call. */
  public record CampGoal(int next, Goal goal) {}

  private final BotWorld world;
  private final BotLibVars vars = new BotLibVars();
  private final ThreadOwner owner = new ThreadOwner("botlib");
  private final Random random = new Random(0);
  private final ScriptSources scripts;
  private final Set<Integer> sourceHandles = new HashSet<>();
  private final Set<Integer> disabledAreas = new HashSet<>();
  private final Set<Integer> enabledAreas = new HashSet<>();
  private boolean strict, setup, closed;
  private float time;
  private int frame;

  private ElementaryActions actions;
  private BotCharacters characters;
  private BotGoals goals;
  private BotWeapons weapons;
  private BotMovement movement;
  private BotChat chat;
  private ItemConfig itemConfig;
  private EntityTable entities;

  // Per map.
  private String mapName;
  private BspEntities bspEntities = new BspEntities(List.of());
  private AasNavigation navigation;
  private AasRouteTimes routeTimes;
  private AasMovementRoutes movementRoutes;
  private AasMovementWorld movementWorld;
  private AasReachabilityArea reachabilityArea;
  private AasLadders ladders;
  private MoverModels moverModels = MoverModels.NONE;
  private ItemRegistry items;
  private ItemPlacement itemPlacement;
  private BotItemSelector itemSelector;
  private BotMapGoals mapGoals;
  private GroundMoveToGoal moveToGoal;
  private int moveToGoalVersion = -1;

  public BotLib(BotWorld world) {
    this.world = Objects.requireNonNull(world);
    scripts = new ScriptSources(world.files());
  }

  /** Rethrow failures instead of degrading (the host's {@code vm_strictSyscalls}). */
  public void setStrict(boolean value) {
    strict = value;
  }

  /** Seeds the library's random stream (chat choices, fuzzy weights, water jumps). */
  public void seed(long seed) {
    owner.check();
    random.setSeed(seed);
  }

  // ---------------------------------------------------------------------------------------------
  // Library (200-211)

  /** {@code BotLibSetup} (200). */
  public int setup() {
    owner.check();
    if (closed) throw new IllegalStateException("Bot library is closed");
    if (setup) shutdown();
    owner.claim();
    int clients = Math.clamp(vars.integer("maxclients", 128), 1, ElementaryActions.MAX_CLIENTS);
    int maxEntities = Math.clamp(vars.integer("maxentities", 1024), 1, 4096);
    entities = new EntityTable(maxEntities);
    actions = new ElementaryActions(clients, world::botClientCommand);
    characters = new BotCharacters(scripts, this::message);
    characters.setReloadCharacters(vars.flag("bot_reloadcharacters"));
    goals = new BotGoals(() -> time, this::automaticAvoidDuration, this::message);
    movement = new BotMovement(this::message);
    weapons = new BotWeapons(scripts, this::message);
    if (weapons.setup(vars.string("weaponconfig", "weapons.c")) != BotWeapons.SUCCESS)
      return failSetup(CANNOT_LOAD_WEAPON_CONFIG);
    try {
      itemConfig = ItemConfig.load(scripts, vars.string("itemconfig", "items.c"));
      itemConfig.diagnostics().forEach(this::message);
    } catch (IOException | RuntimeException failure) {
      message("Cannot load item config: " + failure.getMessage());
      return failSetup(CANNOT_LOAD_ITEM_CONFIG);
    }
    chat = new BotChat(scripts, () -> time, random);
    try {
      chat.setup();
    } catch (IOException | RuntimeException failure) {
      message("Cannot load chat files: " + failure.getMessage());
      return failSetup(CANNOT_LOAD_ICHAT);
    }
    setup = true;
    time = 0;
    frame = 0;
    return NO_ERROR;
  }

  private int failSetup(int error) {
    closeServices();
    return error;
  }

  /** {@code BotLibShutdown} (201). */
  public int shutdown() {
    owner.check();
    if (!setup) return LIBRARY_NOT_SETUP;
    for (int handle : sourceHandles) scripts.free(handle);
    sourceHandles.clear();
    closeServices();
    unloadMap();
    setup = false;
    time = 0;
    frame = 0;
    owner.release();
    return NO_ERROR;
  }

  private void closeServices() {
    if (actions != null) actions.close();
    if (characters != null) characters.close();
    if (goals != null) goals.close();
    if (weapons != null) weapons.close();
    if (movement != null) movement.close();
    if (chat != null) chat.close();
    actions = null;
    characters = null;
    goals = null;
    weapons = null;
    movement = null;
    chat = null;
    itemConfig = null;
  }

  /** {@code BotLibVarSet} (202). */
  public int libVarSet(String name, String value) {
    owner.check();
    if (!vars.set(name, value)) message("Cannot set library variable " + name);
    if ("bot_reloadcharacters".equalsIgnoreCase(name) && characters != null)
      characters.setReloadCharacters(vars.flag("bot_reloadcharacters"));
    return NO_ERROR;
  }

  /** {@code BotLibVarGet} (203): the value, empty when unset. */
  public String libVarGet(String name) {
    owner.check();
    return vars.string(name, "");
  }

  /** {@code PC_AddGlobalDefine} (204): true when the definition parsed. */
  public boolean pcAddGlobalDefine(String define) {
    owner.check();
    try {
      scripts.addGlobalDefine(define);
      return true;
    } catch (IOException failure) {
      message("Global define: " + failure.getMessage());
      return false;
    }
  }

  /** {@code BotLibStartFrame} (205). */
  public int startFrame(float frameTime) {
    owner.check();
    if (!setup) return LIBRARY_NOT_SETUP;
    if (!Float.isFinite(frameTime)) return NO_ERROR;
    entities.startFrame(frame, links());
    time = frameTime;
    frame++;
    return NO_ERROR;
  }

  /** {@code BotLibLoadMap} (206): loads {@code maps/<name>.aas} and initialises level items. */
  public int loadMap(String name) {
    owner.check();
    if (!setup) return LIBRARY_NOT_SETUP;
    if (name == null) return NO_ERROR;
    unloadMap();
    String base = mapBase(name);
    var data = world.files().read("maps/" + base + ".aas");
    if (data.isEmpty()) {
      message("can't open maps/" + base + ".aas");
      return CANNOT_OPEN_AAS_FILE;
    }
    AasWorld map;
    try {
      var parsed = AasReader.read(data.get(), "maps/" + base + ".aas");
      String checksum = vars.get("sv_mapChecksum");
      if (checksum != null
          && !checksum.isEmpty()
          && BspEntities.atoi(checksum) != parsed.bspChecksum()) {
        message("aas file maps/" + base + ".aas is out of date");
        return WRONG_AAS_FILE_ID;
      }
      map = AasWorld.of(parsed);
    } catch (AasFormatException malformed) {
      message("aas file maps/" + base + ".aas: " + malformed.getMessage());
      return CANNOT_READ_AAS_LUMP;
    }
    mapName = base;
    bspEntities = BspEntities.parse(Objects.requireNonNullElse(world.bspEntityData(), ""));
    navigation = new AasNavigation(map);
    routeTimes = new AasRouteTimes(map);
    movementRoutes = new AasMovementRoutes(routeTimes);
    movementWorld =
        new AasMovementWorld(
            navigation,
            (start, mins, maxs, end, entity, mask) ->
                world.entityTrace(start, mins, maxs, end, entity, mask),
            world::pointContents);
    ladders = new AasLadders(navigation);
    moverModels = MoverModels.of(bspEntities, map);
    reachabilityArea =
        new AasReachabilityArea(
            navigation,
            bspEntities.list(),
            world,
            number ->
                entities
                    .state(number)
                    .map(
                        state ->
                            new AasReachabilityArea.Entity(
                                Math.clamp(state.modelIndex(), 0, 255))));
    disabledAreas.clear();
    enabledAreas.clear();
    entities.clear();
    guard("init level items", null, this::initLevelItemsUnchecked);
    return NO_ERROR;
  }

  private void unloadMap() {
    mapName = null;
    bspEntities = new BspEntities(List.of());
    navigation = null;
    routeTimes = null;
    movementRoutes = null;
    movementWorld = null;
    reachabilityArea = null;
    ladders = null;
    moverModels = MoverModels.NONE;
    items = null;
    itemPlacement = null;
    itemSelector = null;
    mapGoals = null;
    moveToGoal = null;
    disabledAreas.clear();
    enabledAreas.clear();
  }

  /** {@code BotLibUpdateEntity} (207): a null state removes the entity's links. */
  public int updateEntity(int entity, BotEntityState state) {
    owner.check();
    if (!setup) return LIBRARY_NOT_SETUP;
    var updated =
        entities.update(
            entity,
            state,
            frame,
            time,
            (model, angles) -> world.bspModelMinsMaxsOrigin(model, angles),
            links());
    return updated ? NO_ERROR : INVALID_ENTITY_NUMBER;
  }

  /** {@code BotLibTest} (208): the original's debug hook, inert in release builds. */
  public int test(int parm0, String parm1, Vec3f parm2, Vec3f parm3) {
    owner.check();
    return NO_ERROR;
  }

  private EntityTable.Links links() {
    return new EntityTable.Links() {
      @Override
      public void link(int entity, Vec3f absMins, Vec3f absMaxs) {
        if (movementWorld != null) movementWorld.link(entity, absMins, absMaxs);
      }

      @Override
      public void unlink(int entity) {
        if (movementWorld != null) movementWorld.unlink(entity);
      }
    };
  }

  // ---------------------------------------------------------------------------------------------
  // AAS (300-318, 575-577)

  /**
   * {@code AAS_EnableRoutingArea} (300): the previous enabled state; {@code enable < 0} only reads.
   */
  public int aasEnableRoutingArea(int area, int enable) {
    owner.check();
    if (!loaded()) return 0;
    var map = navigation.map();
    if (area <= 0 || area >= map.areas().size()) return 0;
    var settings = map.areaSettings().get(area);
    boolean before = !policy(0).disabled(area, settings);
    if (enable < 0) return before ? 1 : 0;
    boolean fileDisabled = (settings.flags() & Contents.AREAFLAG_DISABLED) != 0;
    disabledAreas.remove(area);
    enabledAreas.remove(area);
    if (enable == 0 && !fileDisabled) disabledAreas.add(area);
    if (enable != 0 && fileDisabled) enabledAreas.add(area);
    return before ? 1 : 0;
  }

  /** {@code AAS_BBoxAreas} (301): areas touching the box, at most {@code maxAreas}. */
  public List<Integer> aasBBoxAreas(Vec3f absMins, Vec3f absMaxs, int maxAreas) {
    owner.check();
    if (!loaded() || maxAreas <= 0) return List.of();
    return guard(
        "bbox areas",
        List.<Integer>of(),
        () -> navigation.boxAreas(absMins, absMaxs, Math.min(maxAreas, 65536)).areas());
  }

  /** {@code AAS_AreaInfo} (302): empty for invalid areas. */
  public Optional<AreaInfo> aasAreaInfo(int area) {
    owner.check();
    if (!loaded()) return Optional.empty();
    var map = navigation.map();
    if (area <= 0 || area >= map.areas().size()) return Optional.empty();
    var settings = map.areaSettings().get(area);
    var geometry = map.areas().get(area);
    return Optional.of(
        new AreaInfo(
            settings.contents(),
            settings.flags(),
            settings.presenceType(),
            settings.cluster(),
            geometry.min(),
            geometry.max(),
            geometry.center()));
  }

  /** {@code AAS_EntityInfo} (303). */
  public EntityInfo aasEntityInfo(int entity) {
    owner.check();
    if (!setup || entity < 0 || entity >= entities.capacity()) return EntityInfo.EMPTY;
    return entities.info(entity, frame);
  }

  /** {@code AAS_Initialized} (304). */
  public boolean aasInitialized() {
    owner.check();
    return loaded();
  }

  /** {@code AAS_PresenceTypeBoundingBox} (305): the runtime hulls, not the file's boxes. */
  public PresenceBox aasPresenceTypeBoundingBox(int presenceType) {
    owner.check();
    float top = presenceType == Contents.PRESENCE_CROUCH ? 8 : 32;
    return new PresenceBox(new Vec3f(-15, -15, -24), new Vec3f(15, 15, top));
  }

  /** {@code AAS_Time} (306). */
  public float aasTime() {
    owner.check();
    return time;
  }

  /** {@code AAS_PointAreaNum} (307): zero in solid. */
  public int aasPointAreaNum(Vec3f point) {
    owner.check();
    if (!loaded()) return 0;
    return guard("point area", 0, () -> navigation.pointArea(point));
  }

  /**
   * {@code AAS_PointReachabilityAreaIndex} (577): the dense routable-area index of {@code point},
   * or the number of routable areas when {@code point} is null.
   */
  public int aasPointReachabilityAreaIndex(Vec3f point) {
    owner.check();
    if (!loaded()) return 0;
    if (point == null) return navigation.reachabilityAreaCount();
    return guard("reachability area index", 0, () -> navigation.pointReachabilityAreaIndex(point));
  }

  /** {@code AAS_TraceAreas} (308): areas along the segment with their entry points. */
  public List<AreaEntry> aasTraceAreas(Vec3f start, Vec3f end, int maxAreas) {
    owner.check();
    if (!loaded() || maxAreas <= 0) return List.of();
    return guard(
        "trace areas",
        List.<AreaEntry>of(),
        () ->
            navigation.traceAreas(start, end, Math.min(maxAreas, 65536)).spans().stream()
                .map(span -> new AreaEntry(span.area(), span.entry()))
                .toList());
  }

  /** {@code AAS_PointContents} (309). */
  public int aasPointContents(Vec3f point) {
    owner.check();
    return world.pointContents(point);
  }

  /** {@code AAS_NextBSPEntity} (310). */
  public int aasNextBspEntity(int entity) {
    owner.check();
    return bspEntities.next(entity);
  }

  /** {@code AAS_ValueForBSPEpairKey} (311). */
  public Optional<String> aasValueForBspEpairKey(int entity, String key) {
    owner.check();
    return bspEntities.value(entity, key);
  }

  /** {@code AAS_VectorForBSPEpairKey} (312). */
  public Optional<Vec3f> aasVectorForBspEpairKey(int entity, String key) {
    owner.check();
    return bspEntities.vectorValue(entity, key);
  }

  /** {@code AAS_FloatForBSPEpairKey} (313). */
  public Optional<Float> aasFloatForBspEpairKey(int entity, String key) {
    owner.check();
    return bspEntities.floatValue(entity, key);
  }

  /** {@code AAS_IntForBSPEpairKey} (314). */
  public Optional<Integer> aasIntForBspEpairKey(int entity, String key) {
    owner.check();
    return bspEntities.intValue(entity, key);
  }

  /** {@code AAS_AreaReachability} (315): the area's reachability count. */
  public int aasAreaReachability(int area) {
    owner.check();
    if (!loaded()) return 0;
    var map = navigation.map();
    return area > 0 && area < map.areaSettings().size()
        ? map.areaSettings().get(area).reachabilityCount()
        : 0;
  }

  /** {@code AAS_AreaTravelTimeToGoalArea} (316): hundredths of a second, zero without a route. */
  public int aasAreaTravelTimeToGoalArea(int area, Vec3f origin, int goalArea, int travelFlags) {
    owner.check();
    if (!loaded()) return 0;
    return guard(
        "travel time", 0, () -> routeTimes.travelTime(area, origin, goalArea, policy(travelFlags)));
  }

  /** {@code AAS_Swimming} (317): liquid two units below the origin. */
  public boolean aasSwimming(Vec3f origin) {
    owner.check();
    var sample = new Vec3f(origin.x(), origin.y(), origin.z() - 2);
    return (world.pointContents(sample) & Contents.LIQUID) != 0;
  }

  /**
   * {@code AAS_PredictClientMovement} (318). Empty when the prediction cannot complete; the
   * original's visualisation flag only draws debug lines and is not needed here.
   */
  public Optional<AasMovementPredictor.Prediction> aasPredictClientMovement(
      int entity,
      Vec3f origin,
      int presenceType,
      boolean onGround,
      Vec3f velocity,
      Vec3f commandMove,
      int commandFrames,
      int maxFrames,
      float frameTime,
      int stopEvents,
      int stopArea) {
    owner.check();
    if (!loaded() || entity >= entities.capacity()) return Optional.empty();
    return guard(
        "predict client movement",
        Optional.<AasMovementPredictor.Prediction>empty(),
        () ->
            predictor()
                .predict(
                    new AasMovementPredictor.Request(
                        entity,
                        origin,
                        presenceType,
                        onGround,
                        velocity,
                        commandMove,
                        commandFrames,
                        maxFrames,
                        frameTime,
                        stopEvents,
                        stopArea)));
  }

  /** {@code AAS_AlternativeRouteGoals} (575), used by the CTF AI. */
  public List<AasAlternativeRoutes.Goal> aasAlternativeRouteGoals(
      Vec3f start,
      int startArea,
      Vec3f goal,
      int goalArea,
      int travelFlags,
      int maxGoals,
      int type) {
    owner.check();
    if (!loaded()) return List.of();
    return guard(
        "alternative route goals",
        List.<AasAlternativeRoutes.Goal>of(),
        () ->
            new AasAlternativeRoutes(routeTimes)
                .goals(start, startArea, goal, goalArea, policy(travelFlags), maxGoals, type));
  }

  /** {@code AAS_PredictRoute} (576): where following the route stops, and why. */
  public AasRoutePredictor.Result aasPredictRoute(
      int area,
      Vec3f origin,
      int goalArea,
      int travelFlags,
      int maxAreas,
      int maxTime,
      int stopEvents,
      int stopContents,
      int stopTravelFlags,
      int stopArea) {
    owner.check();
    var none =
        new AasRoutePredictor.Result(
            false,
            new AasRoutePredictor.Prediction(origin, area, AasRoutePredictor.NO_ROUTE, 0, 0, 0));
    if (!loaded()) return none;
    return guard(
        "predict route",
        none,
        () ->
            new AasRoutePredictor(navigation, routeTimes)
                .predict(
                    new AasRoutePredictor.Request(
                        area,
                        origin,
                        goalArea,
                        policy(travelFlags),
                        maxAreas,
                        maxTime,
                        stopEvents,
                        stopContents,
                        stopTravelFlags,
                        stopArea)));
  }

  // ---------------------------------------------------------------------------------------------
  // Elementary actions (400-423)

  public void eaSay(int client, String text) {
    owner.check();
    actions().say(client, text);
  }

  public void eaSayTeam(int client, String text) {
    owner.check();
    actions().sayTeam(client, text);
  }

  public void eaCommand(int client, String command) {
    owner.check();
    actions().command(client, command);
  }

  public void eaAction(int client, int action) {
    owner.check();
    actions().action(client, action);
  }

  public void eaGesture(int client) {
    owner.check();
    actions().gesture(client);
  }

  public void eaTalk(int client) {
    owner.check();
    actions().talk(client);
  }

  public void eaAttack(int client) {
    owner.check();
    actions().attack(client);
  }

  public void eaUse(int client) {
    owner.check();
    actions().use(client);
  }

  public void eaRespawn(int client) {
    owner.check();
    actions().respawn(client);
  }

  public void eaCrouch(int client) {
    owner.check();
    actions().crouch(client);
  }

  public void eaMoveUp(int client) {
    owner.check();
    actions().moveUp(client);
  }

  public void eaMoveDown(int client) {
    owner.check();
    actions().moveDown(client);
  }

  public void eaMoveForward(int client) {
    owner.check();
    actions().moveForward(client);
  }

  public void eaMoveBack(int client) {
    owner.check();
    actions().moveBack(client);
  }

  public void eaMoveLeft(int client) {
    owner.check();
    actions().moveLeft(client);
  }

  public void eaMoveRight(int client) {
    owner.check();
    actions().moveRight(client);
  }

  public void eaSelectWeapon(int client, int weapon) {
    owner.check();
    actions().selectWeapon(client, weapon);
  }

  public void eaJump(int client) {
    owner.check();
    actions().jump(client);
  }

  public void eaDelayedJump(int client) {
    owner.check();
    actions().delayedJump(client);
  }

  public void eaMove(int client, Vec3f direction, float speed) {
    owner.check();
    actions().move(client, direction, speed);
  }

  public void eaView(int client, Vec3f viewAngles) {
    owner.check();
    actions().view(client, viewAngles);
  }

  public void eaEndRegular(int client, float thinkTime) {
    owner.check();
    actions().endRegular(client, thinkTime);
  }

  public BotInput eaGetInput(int client, float thinkTime) {
    owner.check();
    return actions().getInput(client, thinkTime);
  }

  public void eaResetInput(int client) {
    owner.check();
    actions().resetInput(client);
  }

  /** Clears a freed or reused client slot's held input (a host lifecycle operation). */
  public void clearClient(int client) {
    owner.check();
    if (actions != null) actions.clearClient(client);
  }

  // ---------------------------------------------------------------------------------------------
  // Characters (500-506)

  public int loadCharacter(String file, float skill) {
    owner.check();
    return characters().loadCharacter(file, skill);
  }

  public void freeCharacter(int character) {
    owner.check();
    characters().free(character);
  }

  public float characteristicFloat(int character, int index) {
    owner.check();
    return characters().characteristicFloat(character, index);
  }

  public float characteristicBFloat(int character, int index, float min, float max) {
    owner.check();
    return characters().characteristicBoundedFloat(character, index, min, max);
  }

  public int characteristicInteger(int character, int index) {
    owner.check();
    return characters().characteristicInteger(character, index);
  }

  public int characteristicBInteger(int character, int index, int min, int max) {
    owner.check();
    return characters().characteristicBoundedInteger(character, index, min, max);
  }

  /** {@code Characteristic_String}: at most {@code size - 1} characters. */
  public String characteristicString(int character, int index, int size) {
    owner.check();
    return characters().characteristicString(character, index, size);
  }

  // ---------------------------------------------------------------------------------------------
  // Chat (507-524, 569-570)

  public int allocChatState() {
    owner.check();
    return chat().allocate();
  }

  public void freeChatState(int state) {
    owner.check();
    chat().free(state);
  }

  public void queueConsoleMessage(int state, int type, String message) {
    owner.check();
    if (!chat().queue(state, type, message)) message("Bot console message queue is full");
  }

  public void removeConsoleMessage(int state, int handle) {
    owner.check();
    chat().remove(state, handle);
  }

  public Optional<BotChat.ConsoleMessage> nextConsoleMessage(int state) {
    owner.check();
    return chat().next(state);
  }

  public int numConsoleMessages(int state) {
    owner.check();
    return chat().queued(state);
  }

  /** {@code BotInitialChat}: up to eight variables; null entries are absent. */
  public void initialChat(int state, String type, int context, List<String> variables) {
    owner.check();
    guard(
        "initial chat",
        Boolean.FALSE,
        () -> chat().initial(state, type, context, variables == null ? List.of() : variables));
  }

  public int numInitialChats(int state, String type) {
    owner.check();
    return chat().initialCount(state, type);
  }

  public boolean replyChat(
      int state, String message, int messageContext, int variableContext, List<String> variables) {
    owner.check();
    return guard(
        "reply chat",
        Boolean.FALSE,
        () ->
            chat()
                .reply(
                    state,
                    message,
                    messageContext,
                    variableContext,
                    variables == null ? List.of() : variables));
  }

  public int chatLength(int state) {
    owner.check();
    return chat().length(state);
  }

  /**
   * {@code BotEnterChat}: sends the pending chat message from the state's client to everyone, the
   * team or {@code client}, then clears it. Nothing is sent when no message is pending.
   */
  public void enterChat(int state, int client, int sendTo) {
    owner.check();
    String text = chat().takeMessage(state);
    if (text.isEmpty()) return;
    String command =
        switch (sendTo) {
          case CHAT_TEAM -> "say_team " + text;
          case CHAT_TELL -> "tell " + client + " " + text;
          default -> "say " + text;
        };
    actions().command(chat().client(state), command);
  }

  /** {@code BotGetChatMessage}: takes the pending message, at most {@code size - 1} characters. */
  public String getChatMessage(int state, int size) {
    owner.check();
    String text = chat().takeMessage(state);
    return size <= 0 ? "" : text.substring(0, Math.min(text.length(), size - 1));
  }

  public int stringContains(String text, String needle, boolean caseSensitive) {
    owner.check();
    return BotChat.stringContains(text, needle, caseSensitive);
  }

  /**
   * {@code BotFindMatch}: matches {@code text} against the match templates of {@code context},
   * filling the {@value ChatMatcher#BYTE_SIZE}-byte {@code bot_match_t} in {@code match}.
   */
  public boolean findMatch(String text, byte[] match, int context) {
    owner.check();
    Objects.checkFromIndexSize(0, ChatMatcher.BYTE_SIZE, match.length);
    return guard(
        "find match",
        Boolean.FALSE,
        () -> chat().findMatch(text, context, ByteBuffer.wrap(match), 0));
  }

  /** {@code BotMatchVariable}: variable {@code variable} of a filled match. */
  public String matchVariable(byte[] match, int variable, int size) {
    owner.check();
    return guard(
        "match variable",
        "",
        () -> ChatMatcher.variable(ByteBuffer.wrap(match), 0, variable, size));
  }

  public String unifyWhiteSpaces(String text) {
    owner.check();
    return BotChat.unifyWhiteSpaces(text);
  }

  public String replaceSynonyms(String text, int context) {
    owner.check();
    return chat().replaceSynonyms(text, context);
  }

  /** {@code BotLoadChatFile}: {@link #NO_ERROR} or {@link #CANNOT_LOAD_ICHAT}. */
  public int loadChatFile(int state, String file, String chatName) {
    owner.check();
    try {
      return chat().load(state, file, chatName) ? NO_ERROR : CANNOT_LOAD_ICHAT;
    } catch (IOException | RuntimeException failure) {
      if (strict && failure instanceof RuntimeException runtime) throw runtime;
      message("Bot chat " + file + ": " + failure.getMessage());
      return CANNOT_LOAD_ICHAT;
    }
  }

  public void setChatGender(int state, int gender) {
    owner.check();
    chat().setGender(state, Math.clamp(gender, 0, 2));
  }

  public void setChatName(int state, String name, int client) {
    owner.check();
    chat().setName(state, name, client);
  }

  // ---------------------------------------------------------------------------------------------
  // Goals and items (525-547, 567-568, 571, 573)

  public void resetGoalState(int state) {
    owner.check();
    goals().reset(state);
  }

  public void resetAvoidGoals(int state) {
    owner.check();
    goals().resetAvoid(state);
  }

  public void removeFromAvoidGoals(int state, int number) {
    owner.check();
    goals().removeAvoid(state, number);
  }

  public void pushGoal(int state, Goal goal) {
    owner.check();
    goals().push(state, goal);
  }

  public void popGoal(int state) {
    owner.check();
    goals().pop(state);
  }

  public void emptyGoalStack(int state) {
    owner.check();
    goals().empty(state);
  }

  public void dumpAvoidGoals(int state) {
    owner.check();
    goals().snapshot(state).ifPresent(s -> message("Avoid goals: " + s.avoidGoals()));
  }

  public void dumpGoalStack(int state) {
    owner.check();
    goals().snapshot(state).ifPresent(s -> message("Goal stack: " + s.stack()));
  }

  /** {@code BotGoalName}: the level item's display name, empty when unknown. */
  public String goalName(int number) {
    owner.check();
    return items == null ? "" : items.byNumber(number).map(item -> item.info().name()).orElse("");
  }

  public Optional<Goal> getTopGoal(int state) {
    owner.check();
    return goals().top(state);
  }

  public Optional<Goal> getSecondGoal(int state) {
    owner.check();
    return goals().second(state);
  }

  /** {@code BotChooseLTGItem}: pushes the best long-term item goal. */
  public boolean chooseLtgItem(int state, Vec3f origin, int[] inventory, int travelFlags) {
    owner.check();
    if (!loaded() || items == null) return false;
    return guard(
        "choose long-term item",
        Boolean.FALSE,
        () -> itemSelector().chooseLongTerm(state, origin, inventory, travelFlags));
  }

  /** {@code BotChooseNBGItem}: pushes the best nearby item that does not delay {@code ltg}. */
  public boolean chooseNbgItem(
      int state, Vec3f origin, int[] inventory, int travelFlags, Goal ltg, float maxTime) {
    owner.check();
    if (!loaded() || items == null) return false;
    return guard(
        "choose nearby item",
        Boolean.FALSE,
        () -> itemSelector().chooseNearby(state, origin, inventory, travelFlags, ltg, maxTime));
  }

  public boolean touchingGoal(Vec3f origin, Goal goal) {
    owner.check();
    return GoalQueries.touching(origin, goal);
  }

  /** {@code BotItemGoalInVisButNotVisible}. */
  public boolean itemGoalInVisButNotVisible(int viewer, Vec3f eye, Vec3f viewAngles, Goal goal) {
    owner.check();
    return guard(
        "item goal visibility",
        Boolean.FALSE,
        () ->
            GoalQueries.visibleButMissing(
                viewer, eye, viewAngles, goal, time, world, entities::lastUpdate));
  }

  /**
   * {@code BotGetLevelItemGoal}: the next item after {@code index} with display name {@code name}.
   */
  public Optional<ItemGoal> getLevelItemGoal(int index, String name) {
    owner.check();
    if (items == null) return Optional.empty();
    return items
        .nextGoal(index, name, vars.integer("g_gametype", 0))
        .map(goal -> new ItemGoal(goal.number(), goal));
  }

  /** {@code BotGetNextCampSpotGoal}. */
  public Optional<CampGoal> getNextCampSpotGoal(int number) {
    owner.check();
    if (!loaded()) return Optional.empty();
    return mapGoals().nextCamp(number).map(camp -> new CampGoal(camp.nextCursor(), camp.goal()));
  }

  /** {@code BotGetMapLocationGoal}. */
  public Optional<Goal> getMapLocationGoal(String name) {
    owner.check();
    if (!loaded()) return Optional.empty();
    return mapGoals().location(name);
  }

  public float avoidGoalTime(int state, int number) {
    owner.check();
    return goals().avoidTime(state, number);
  }

  /** {@code BotSetAvoidGoalTime}: a negative time uses the item's respawn time. */
  public void setAvoidGoalTime(int state, int number, float avoidTime) {
    owner.check();
    goals().setAvoidTime(state, number, avoidTime);
  }

  /** {@code BotInitLevelItems} (541). */
  public void initLevelItems() {
    owner.check();
    guard("init level items", null, this::initLevelItemsUnchecked);
  }

  private Void initLevelItemsUnchecked() {
    if (!loaded() || itemConfig == null) return null;
    var placement =
        new ItemPlacement(
            navigation,
            world,
            new JumpPadItemAreas(
                navigation,
                bspEntities,
                model -> world.bspModelMinsMaxsOrigin(model, Vec3f.ZERO),
                movementWorld,
                () -> AasMovementPredictor.Settings.from(vars::get),
                this::message),
            this::message);
    var registry = new ItemRegistry(itemConfig, placement, this::message);
    registry.initialize(bspEntities.list());
    itemPlacement = placement;
    items = registry;
    itemSelector = null;
    return null;
  }

  /** {@code BotUpdateEntityItems} (542): follows dropped and respawning items. */
  public void updateEntityItems() {
    owner.check();
    if (!loaded() || items == null) return;
    guard(
        "update entity items",
        null,
        () -> {
          var visible = new ArrayList<ItemRegistry.WorldEntity>();
          for (int number = 1; number < Math.min(entities.capacity(), 1024); number++) {
            if (!entities.valid(number, frame)) continue;
            var state = entities.state(number).orElseThrow();
            visible.add(
                new ItemRegistry.WorldEntity(
                    number,
                    state.type(),
                    state.flags(),
                    state.modelIndex(),
                    state.origin(),
                    entities.lastVisibleOrigin(number)));
          }
          items.update(time, visible, itemPlacement);
          return null;
        });
  }

  /** {@code BotLoadItemWeights} (543): {@link #NO_ERROR} or {@link #CANNOT_LOAD_ITEM_WEIGHTS}. */
  public int loadItemWeights(int state, String file) {
    owner.check();
    if (goals().snapshot(state).isEmpty()) return CANNOT_LOAD_ITEM_WEIGHTS;
    try {
      goals.weights(state, WeightConfig.load(scripts, file));
      return NO_ERROR;
    } catch (IOException | RuntimeException failure) {
      if (strict && failure instanceof RuntimeException runtime) throw runtime;
      message("Item weights " + file + ": " + failure.getMessage());
      return CANNOT_LOAD_ITEM_WEIGHTS;
    }
  }

  public void freeItemWeights(int state) {
    owner.check();
    goals().freeWeights(state);
  }

  /** {@code BotInterbreedGoalFuzzyLogic} (565): the child gets the parents' averaged weights. */
  public void interbreedGoalFuzzyLogic(int parent1, int parent2, int child) {
    owner.check();
    var first = goals().weights(parent1);
    var second = goals().weights(parent2);
    var target = goals().weights(child);
    if (first.isEmpty() || second.isEmpty() || target.isEmpty()) return;
    var bred = target.get().interbreed(first.get(), second.get());
    if (bred.isEmpty()) message("cannot interbreed weight configs, unequal shapes");
    else goals().weights(child, bred.get());
  }

  /**
   * {@code BotSaveGoalFuzzyLogic} (545): writes the state's item weights to {@code file} through
   * {@link BotWorld#writeFile}. The 1.32 library compiled this out, so a host that refuses writes
   * (the default) reproduces it exactly. Returns whether the file was written.
   */
  public boolean saveGoalFuzzyLogic(int state, String file) {
    owner.check();
    var weights = goals().weights(state);
    if (weights.isEmpty() || file == null || file.isEmpty()) return false;
    return world.writeFile(file, weights.get().toSource().getBytes(StandardCharsets.ISO_8859_1));
  }

  /** {@code BotMutateGoalFuzzyLogic} (566): randomly perturbs the state's balanced weights. */
  public void mutateGoalFuzzyLogic(int state, float range) {
    owner.check();
    goals()
        .weights(state)
        .ifPresent(weights -> goals.weights(state, weights.mutate(random::nextInt)));
  }

  public int allocGoalState(int client) {
    owner.check();
    return goals().allocate(client);
  }

  public void freeGoalState(int state) {
    owner.check();
    goals().free(state);
  }

  /**
   * {@code GeneticParentsAndChildSelection} (564): empty, the original's false, with fewer than
   * three valid rankings.
   */
  public Optional<GeneticSelection.Selection> geneticParentsAndChildSelection(float[] ranks) {
    owner.check();
    var selection = GeneticSelection.select(ranks, random::nextInt);
    if (selection.isEmpty())
      message("GeneticParentsAndChildSelection: too few valid bots or too many rankings");
    return selection;
  }

  // ---------------------------------------------------------------------------------------------
  // Movement (548-557, 572, 574)

  public void resetMoveState(int state) {
    owner.check();
    movement().reset(state);
  }

  /**
   * {@code BotMoveToGoal} (549): one move towards {@code goal}, already applied to the bot's
   * elementary actions. Unsupported situations return a failed result instead of throwing.
   */
  public GroundMoveToGoal.Output moveToGoal(int state, Goal goal, int travelFlags) {
    owner.check();
    var failed =
        new GroundMoveToGoal.Output(
            MovementResult.failed(0),
            GroundMoveToGoal.PREFIX_BYTES,
            Optional.empty(),
            0,
            Optional.empty(),
            java.util.OptionalInt.empty(),
            List.of(),
            Optional.of("botlib not ready"));
    if (!loaded() || goal == null) return failed;
    var input = movement().snapshot(state).flatMap(BotMovement.Snapshot::input);
    if (input.isEmpty()) return failed;
    int client = input.get().client();
    if (client < 0 || client >= actions().maxClients()) return failed;
    var result =
        guard(
            "move to goal",
            failed,
            () -> moveToGoal().execute(state, goal, policy(travelFlags), time));
    applyMove(client, result);
    return result;
  }

  private void applyMove(int client, GroundMoveToGoal.Output result) {
    var ea = actions();
    ea.action(client, result.actionFlags() & ~(ActionFlags.JUMP | ActionFlags.DELAYED_JUMP));
    if ((result.actionFlags() & ActionFlags.JUMP) != 0) ea.jump(client);
    if ((result.actionFlags() & ActionFlags.DELAYED_JUMP) != 0) ea.delayedJump(client);
    result.view().ifPresent(view -> ea.view(client, view));
    result.weapon().ifPresent(weapon -> ea.selectWeapon(client, weapon));
    result.command().ifPresent(command -> ea.move(client, command.direction(), command.speed()));
    for (String command : result.commands()) ea.command(client, command);
  }

  /** {@code BotMoveInDirection} (550): true when the bot can move that way. */
  public boolean moveInDirection(int state, Vec3f direction, float speed, int type) {
    owner.check();
    if (!loaded()) return false;
    var snapshot = movement().snapshot(state);
    if (snapshot.isEmpty() || snapshot.get().input().isEmpty()) return false;
    var input = snapshot.get().input().get();
    int client = input.client();
    if (client < 0 || client >= actions().maxClients()) return false;
    return guard(
        "move in direction",
        Boolean.FALSE,
        () -> {
          var predictor = predictor();
          var settings = movementSettings();
          var obstacles =
              new MovementObstacles(movementWorld, settings.step(), settings.maxBarrier());
          var directional =
              new BotDirectionalMovement(
                  new BotDirectionalMovement.Services() {
                    @Override
                    public boolean swimming(Vec3f origin) {
                      return predictor.swimming(origin);
                    }

                    @Override
                    public boolean onGround(Vec3f origin, int presence, int entity) {
                      return predictor.onGround(origin, presence, entity);
                    }

                    @Override
                    public boolean barrierJump(
                        MovementInit move, Vec3f requested, float requestedSpeed) {
                      return obstacles.barrierJump(move, requested, requestedSpeed);
                    }

                    @Override
                    public float gapDistance(Vec3f origin, Vec3f requested, int entity) {
                      return obstacles.gapDistance(origin, requested, entity);
                    }

                    @Override
                    public Optional<AasMovementPredictor.Prediction> predict(
                        AasMovementPredictor.Request request) {
                      return predictor.predict(request);
                    }
                  });
          var result =
              directional.move(input, snapshot.get().movementFlags(), direction, speed, type);
          movement.updateMovementFlags(state, result.movementFlags());
          var ea = actions();
          if (result.crouch()) ea.crouch(client);
          if (result.jump()) ea.jump(client);
          result.motion().ifPresent(motion -> ea.move(client, motion.direction(), motion.speed()));
          return result.accepted();
        });
  }

  public void resetAvoidReach(int state) {
    owner.check();
    movement().resetAvoidReach(state);
  }

  public void resetLastAvoidReach(int state) {
    owner.check();
    movement().resetLastAvoidReach(state);
  }

  /** {@code BotReachabilityArea} (553): the routable area a bot at {@code origin} occupies. */
  public int reachabilityArea(Vec3f origin, int client) {
    owner.check();
    if (!loaded()) return 0;
    return guard("reachability area", 0, () -> reachabilityArea.reachableArea(origin, client));
  }

  /** {@code BotMovementViewTarget} (554): a point {@code lookAhead} units ahead on the route. */
  public Optional<Vec3f> movementViewTarget(
      int state, Goal goal, int travelFlags, float lookAhead) {
    owner.check();
    if (!loaded() || goal == null || !Float.isFinite(lookAhead)) return Optional.empty();
    var snapshot = movement().snapshot(state);
    if (snapshot.isEmpty() || snapshot.get().input().isEmpty()) return Optional.empty();
    var history = snapshot.get().history();
    if (history.lastReachability() == 0 || lookAhead <= 0) return Optional.empty();
    return guard(
        "movement view target",
        Optional.<Vec3f>empty(),
        () -> {
          var avoid = snapshot.get().reachAvoidance();
          var context =
              new AasMovementRoutes.Context(
                  history.lastGoalArea(),
                  history.lastArea(),
                  time,
                  avoid.reachability() == 0
                      ? List.of()
                      : List.of(
                          new AasMovementRoutes.AvoidReach(
                              avoid.reachability(), avoid.expiresAt(), avoid.tries())));
          var result =
              new BotMovementView(navigation.map(), movementRoutes)
                  .target(
                      snapshot.get().input().get().origin(),
                      history.lastReachability(),
                      goal,
                      policy(travelFlags),
                      lookAhead,
                      context);
          return result.success() ? result.target() : Optional.<Vec3f>empty();
        });
  }

  /** {@code BotPredictVisiblePosition} (572): the first route point visible from the goal. */
  public Optional<Vec3f> predictVisiblePosition(
      Vec3f origin, int area, Goal goal, int travelFlags) {
    owner.check();
    if (!loaded() || goal == null) return Optional.empty();
    return guard(
        "predict visible position",
        Optional.<Vec3f>empty(),
        () ->
            new BotVisiblePosition(navigation.map(), movementRoutes, world)
                .predict(origin, area, goal, policy(travelFlags)));
  }

  public int allocMoveState() {
    owner.check();
    return movement().allocate();
  }

  public void freeMoveState(int state) {
    owner.check();
    movement().free(state);
  }

  public void initMoveState(int state, MovementInit init) {
    owner.check();
    movement().initialize(state, init);
  }

  /** {@code BotAddAvoidSpot} (574): type {@link BotMovement#AVOID_CLEAR} clears all spots. */
  public void addAvoidSpot(int state, Vec3f origin, float radius, int type) {
    owner.check();
    if (type == BotMovement.AVOID_CLEAR) movement().clearAvoidSpots(state);
    else movement().addAvoidSpot(state, new BotMovement.AvoidSpot(origin, radius, type));
  }

  // ---------------------------------------------------------------------------------------------
  // Weapons (558-563)

  public int chooseBestFightWeapon(int state, int[] inventory) {
    owner.check();
    return weapons().chooseBestFightWeapon(state, inventory);
  }

  public WeaponInfo getWeaponInfo(int state, int weapon) {
    owner.check();
    return weapons().weaponInfo(state, weapon);
  }

  public int loadWeaponWeights(int state, String file) {
    owner.check();
    return weapons().loadWeaponWeights(state, file);
  }

  public int allocWeaponState() {
    owner.check();
    return weapons().allocate();
  }

  public void freeWeaponState(int state) {
    owner.check();
    weapons().free(state);
  }

  public void resetWeaponState(int state) {
    owner.check();
    weapons().reset(state);
  }

  // ---------------------------------------------------------------------------------------------
  // Script sources (578-581)

  /** {@code PC_LoadSourceHandle}: zero when the file cannot be read or preprocessed. */
  public int pcLoadSource(String file) {
    owner.check();
    try {
      int handle = scripts.load(file);
      sourceHandles.add(handle);
      return handle;
    } catch (IOException | RuntimeException failure) {
      if (strict && failure instanceof RuntimeException runtime) throw runtime;
      message("Botlib source " + file + ": " + failure.getMessage());
      return 0;
    }
  }

  public boolean pcFreeSource(int handle) {
    owner.check();
    sourceHandles.remove(handle);
    return scripts.free(handle);
  }

  /** {@code PC_ReadTokenHandle}: empty at the end of the source or for an unknown handle. */
  public Optional<ScriptToken> pcReadToken(int handle) {
    owner.check();
    if (!sourceHandles.contains(handle)) return Optional.empty();
    return scripts.read(handle);
  }

  public Optional<SourceLocation> pcSourceFileAndLine(int handle) {
    owner.check();
    if (!sourceHandles.contains(handle)) return Optional.empty();
    return Optional.of(scripts.location(handle));
  }

  // ---------------------------------------------------------------------------------------------
  // Host inspection

  public boolean isSetup() {
    return setup;
  }

  /** The loaded map's base name, if any. */
  public Optional<String> mapName() {
    return Optional.ofNullable(mapName);
  }

  public int frame() {
    return frame;
  }

  public BotLibVars vars() {
    return vars;
  }

  /** The loaded navigation, for host tooling and tests. */
  public Optional<AasNavigation> navigation() {
    return Optional.ofNullable(navigation);
  }

  public int validEntities() {
    return entities == null ? 0 : entities.validCount(frame);
  }

  public int openSources() {
    return sourceHandles.size();
  }

  @Override
  public void close() {
    if (closed) return;
    if (setup) shutdown();
    scripts.close();
    closed = true;
  }

  // ---------------------------------------------------------------------------------------------
  // Internals

  private boolean loaded() {
    return setup && navigation != null;
  }

  private TravelPolicy policy(int travelFlags) {
    return new TravelPolicy(
        travelFlags, 6, TravelPolicy.Team.ANY, Set.copyOf(disabledAreas), Set.copyOf(enabledAreas));
  }

  private MovementSettings movementSettings() {
    return MovementSettings.from(vars::get);
  }

  private AasMovementPredictor predictor() {
    return new AasMovementPredictor(movementWorld, AasMovementPredictor.Settings.from(vars::get));
  }

  private GroundMoveToGoal moveToGoal() {
    if (moveToGoal != null && moveToGoalVersion == vars.version()) return moveToGoal;
    var map = navigation.map();
    var settings = movementSettings();
    var physics = AasMovementPredictor.Settings.from(vars::get);
    var obstruction =
        new MovementObstruction(
            world, area -> map.areaSettings().get(area).reachabilityCount(), settings.step());
    var obstacles = new MovementObstacles(movementWorld, settings.step(), settings.maxBarrier());
    var ground =
        new GroundReachMovement(
            obstruction,
            area -> map.areaSettings().get(area).presenceType(),
            obstacles::gapDistance);
    var movers = moverQueries();
    var bobbing = new BobbingPlatformMovement(movers, obstruction, obstacles::barrierJump);
    var elevator =
        new ElevatorMovement(
            movers,
            model ->
                world
                    .bspModelMinsMaxsOrigin(model, Vec3f.ZERO)
                    .map(bounds -> new MoverQueries.ModelBounds(bounds.mins(), bounds.maxs()))
                    .orElseThrow(
                        () -> new UnsupportedOperationException("Missing elevator model " + model)),
            obstruction,
            obstacles::barrierJump);
    var jumpPad = new JumpPadMovement(obstruction, settings.gravity());
    var teleport = new TeleportReachMovement(obstruction);
    var barrier = new BarrierReachMovement(obstruction);
    var liquid = new LiquidReachMovement(obstruction, () -> (random.nextInt() & 32767) / 32767f);
    var ladder = new LadderMovement();
    var weaponJump = new WeaponJumpMovement(settings);
    var runStart =
        new JumpRunStart(
            request ->
                predictor()
                    .predict(request)
                    .orElseThrow(
                        () -> new UnsupportedOperationException("Jump run-up prediction failed")));
    var jump = new JumpReachMovement(runStart::calculate, navigation::pointArea);
    var ledge =
        new LedgeReachMovement(
            obstruction,
            physics.gravity(),
            vars.value("phys_maxvelocity", 320),
            settings.gravity());
    var predictor = predictor();
    var grapple =
        new GrappleMovement(
            world, obstruction, navigation::pointArea, this::grappleVisible, settings);
    moveToGoal =
        GroundMoveToGoal.Config.builder(
                movement,
                navigation,
                reachabilityArea::fuzzyArea,
                movementRoutes,
                world,
                predictor::onGround,
                ground::execute,
                ground::moveInGoalArea)
            .movers(
                moverModels,
                entity -> entities.state(entity).map(BotEntityState::modelIndex).orElse(0))
            .againstLadder(ladders::against)
            .liquidGoalTravel(liquid::moveInGoalArea)
            .ground(TravelType.BARRIER_JUMP, barrier::execute)
            .air(TravelType.BARRIER_JUMP, barrier::finish)
            .groundAndAir(TravelType.LADDER, ladder::execute)
            .ground(TravelType.WALK_OFF_LEDGE, ledge::execute)
            .air(TravelType.WALK_OFF_LEDGE, ledge::finish)
            .groundAndAir(TravelType.SWIM, liquid::execute)
            .ground(TravelType.WATER_JUMP, liquid::execute)
            .air(TravelType.WATER_JUMP, liquid::finish)
            .ground(TravelType.TELEPORT, teleport::execute)
            .ground(TravelType.ELEVATOR, elevator::execute)
            .air(TravelType.ELEVATOR, elevator::finish)
            .ground(TravelType.JUMP_PAD, jumpPad::execute)
            .air(TravelType.JUMP_PAD, jumpPad::finish)
            .ground(TravelType.FUNC_BOB, bobbing::execute)
            .air(TravelType.FUNC_BOB, bobbing::finish)
            .statefulGround(TravelType.JUMP, jump::execute)
            .statefulAir(TravelType.JUMP, jump::finish)
            .statefulGround(TravelType.ROCKET_JUMP, weaponJump::execute)
            .statefulAir(TravelType.ROCKET_JUMP, weaponJump::finish)
            .statefulGround(TravelType.BFG_JUMP, weaponJump::execute)
            .statefulAir(TravelType.BFG_JUMP, weaponJump::finish)
            .grapple(grapple)
            .settings(settings)
            .build();
    moveToGoalVersion = vars.version();
    return moveToGoal;
  }

  /** Whether a grapple missile is in the world this frame ({@code GrappleState}'s entity scan). */
  private boolean grappleVisible() {
    var settings = movementSettings();
    for (int number = 1; number < entities.capacity(); number++) {
      if (!entities.valid(number, frame)) continue;
      var state = entities.state(number).orElseThrow();
      if (state.type() == settings.missileType() && state.weapon() == settings.grapple())
        return true;
    }
    return false;
  }

  private MoverQueries moverQueries() {
    return new MoverQueries(
        world,
        model ->
            world
                .bspModelMinsMaxsOrigin(model, Vec3f.ZERO)
                .map(bounds -> new MoverQueries.ModelBounds(bounds.mins(), bounds.maxs()))
                .orElseThrow(
                    () -> new UnsupportedOperationException("Missing mover model " + model)),
        number ->
            entities
                .state(number)
                .map(
                    state ->
                        new MoverQueries.Entity(state.type(), state.modelIndex(), state.origin())),
        Math.min(entities.capacity(), 1024));
  }

  private BotItemSelector itemSelector() {
    if (itemSelector == null) {
      var registry = items;
      itemSelector =
          new BotItemSelector(
              goals(),
              registry::items,
              new BotItemSelector.Routing() {
                @Override
                public int reachableArea(Vec3f point, int client) {
                  return reachabilityArea.reachableArea(point, client);
                }

                @Override
                public boolean hasReachability(int area) {
                  var map = navigation.map();
                  return area > 0
                      && area < map.areaSettings().size()
                      && map.areaSettings().get(area).reachabilityCount() != 0;
                }

                @Override
                public int travelTime(int start, Vec3f point, int goal, int flags) {
                  return routeTimes.travelTime(start, point, goal, policy(flags));
                }
              },
              (config, index, values) -> config.evaluateUndecided(index, values, random::nextInt),
              () -> vars.integer("g_gametype", 0),
              () -> vars.value("droppedweight", 1000));
    }
    return itemSelector;
  }

  private BotMapGoals mapGoals() {
    if (mapGoals == null) {
      var loaded = new BotMapGoals(navigation::pointArea, this::message);
      loaded.initialize(bspEntities.list());
      mapGoals = loaded;
    }
    return mapGoals;
  }

  private OptionalDouble automaticAvoidDuration(int number) {
    return items == null ? OptionalDouble.empty() : items.automaticAvoidDuration(number);
  }

  private <T> T guard(String operation, T fallback, Supplier<T> body) {
    try {
      return body.get();
    } catch (RuntimeException failure) {
      if (strict) throw failure;
      LOG.warnOnce(
          operation + ":" + failure.getClass().getName(),
          "Botlib " + operation + " failed and degraded: " + failure);
      return fallback;
    }
  }

  private void message(String text) {
    world.print(BotWorld.PrintType.MESSAGE, text.endsWith("\n") ? text : text + "\n");
  }

  private ElementaryActions actions() {
    requireSetup();
    return actions;
  }

  private BotCharacters characters() {
    requireSetup();
    return characters;
  }

  private BotGoals goals() {
    requireSetup();
    return goals;
  }

  private BotWeapons weapons() {
    requireSetup();
    return weapons;
  }

  private BotMovement movement() {
    requireSetup();
    return movement;
  }

  private BotChat chat() {
    requireSetup();
    return chat;
  }

  private void requireSetup() {
    if (!setup) throw new IllegalStateException("Bot library is not set up");
  }

  private static String mapBase(String path) {
    String name = VirtualPath.parse(path).map(VirtualPath::key).orElse(path.toLowerCase());
    if (name.startsWith("maps/")) name = name.substring(5);
    if (name.endsWith(".bsp") || name.endsWith(".aas")) name = name.substring(0, name.length() - 4);
    return name;
  }
}
