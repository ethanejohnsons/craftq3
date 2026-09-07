import dev.bluevista.craftq3.assets.aas.*;
import dev.bluevista.craftq3.assets.bsp.*;
import dev.bluevista.craftq3.botlib.aas.*;
import dev.bluevista.craftq3.botlib.ea.*;
import dev.bluevista.craftq3.botlib.goal.Goal;
import dev.bluevista.craftq3.botlib.movement.*;
import dev.bluevista.craftq3.collision.*;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

/** Original-map full bobbing-platform dispatch comparison. All collision replies are authored. */
class AuditBobbingGoals {
  static final Vec3 ZERO = new Vec3(0, 0, 0);
  static final String[] SCENARIOS = {
    "fresh",
    "cached",
    "expired",
    "different-area",
    "different-goal",
    "same-area",
    "no-flags",
    "air-no-reach",
    "entity-ground",
    "solid-area",
    "avoided-reach",
    "cached-spot",
    "crouch-spot",
    "solid-trace",
    "deadline-equal",
    "cached-no-flags",
    "stale-contact-flags",
    "crouch-world-floor",
    "effective-input-flags",
    "avoid-equal",
    "avoid-expired",
    "zero-goal"
  };

  record Expected(
      String commands,
      String scenario,
      byte[] result,
      BotMovement.Snapshot state,
      GroundMoveToGoal.Output output,
      BotInput actions,
      int traces) {}

  record Native(String result, String state, String action) {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 4)
      throw new IllegalArgumentException(
          "AuditBobbingGoals <PK3> <oracle> [count] [ground|air|standing|capture]");
    int count = args.length > 2 ? Integer.parseInt(args[2]) : 1000;
    if (count < 1 || count > 30000) throw new IllegalArgumentException("Invalid count");
    boolean airborne = args.length > 3 && args[3].equals("air");
    boolean standing = args.length > 3 && args[3].equals("standing");
    boolean capture = args.length > 3 && args[3].equals("capture");
    if (args.length > 3 && !Set.of("ground", "air", "standing", "capture").contains(args[3]))
      throw new IllegalArgumentException("Unknown mode");
    try (var zip = new ZipFile(args[0])) {
      var map = AasReader.read(zip.getInputStream(zip.getEntry("maps/q3dm19.aas")).readAllBytes());
      var bsp = BspReader.read(zip.getInputStream(zip.getEntry("maps/q3dm19.bsp")).readAllBytes());
      var nav = new AasNavigation(map);
      var world = new World();
      var aasWorld = new AasMovementWorld(nav, (e, r) -> world.trace(r, true), p -> world.contents);
      var predictor = new AasMovementPredictor(aasWorld, AasMovementPredictor.Settings.defaults());
      var obstacles = new MovementObstacles(aasWorld);
      var locator = new AasReachabilityArea(nav, List.of(), world, e -> Optional.empty());
      var routes = new AasMovementRoutes(new AasRouteTimes(map));
      var obstruction =
          new MovementObstruction(
              world, a -> a <= 0 ? 0 : map.areaSettings().get(a).reachabilityCount());
      var movers =
          new MoverQueries(
              world,
              m -> {
                var b = bsp.models().get(m).bounds();
                return new MoverQueries.ModelBounds(b.min(), b.max());
              },
              e -> Optional.ofNullable(world.entities.get(e)),
              1024);
      var bobbing = new BobbingPlatformMovement(movers, obstruction, obstacles::barrierJump);
      var ground =
          new GroundReachMovement(
              world,
              a -> a <= 0 ? 0 : map.areaSettings().get(a).presenceType(),
              a -> a <= 0 ? 0 : map.areaSettings().get(a).reachabilityCount(),
              obstacles::gapDistance);
      var pad = new JumpPadMovement(obstruction);
      var ledge = new LedgeReachMovement(obstruction);
      var barrier = new BarrierReachMovement(obstruction);
      var teleport = new TeleportReachMovement(obstruction);
      List<Integer> links = new ArrayList<>();
      Map<Integer, Integer> sourceAreas = new HashMap<>();
      Set<Integer> models = new TreeSet<>();
      for (int a = 1; a < map.areas().size(); a++) {
        var s = map.areaSettings().get(a);
        for (int r = s.firstReachability(); r < s.firstReachability() + s.reachabilityCount(); r++)
          if (map.reachabilities().get(r).baseTravelType() == 19) {
            links.add(r);
            sourceAreas.put(r, a);
            models.add(map.reachabilities().get(r).face() & 65535);
          }
      }
      System.out.println("Original bobbing links=" + links.size() + " models=" + models);
      var expected = new ArrayList<Expected>();
      var allCommands = new StringBuilder();
      for (int m : models)
        allCommands.append("mover ").append(180 + m).append(' ').append(m).append(" 1 1 1 4 0\n");
      Map<String, Integer> exclusions = new TreeMap<>(),
          scenarios = new TreeMap<>(),
          differences = new TreeMap<>(),
          outputs = new TreeMap<>();
      Random random = new Random(190068);
      int skipped = 0;
      try (var states =
          new BotMovement(
              s -> {
                throw new AssertionError(s);
              })) {
        int handle = states.allocate();
        var move =
            new GroundMoveToGoal(
                states,
                nav,
                locator::fuzzyArea,
                routes,
                world,
                predictor::onGround,
                world.entities::containsKey,
                ground::execute,
                ground::moveInGoalArea,
                Map.of(
                    4,
                    barrier::execute,
                    7,
                    ledge::execute,
                    10,
                    teleport::execute,
                    18,
                    pad::execute,
                    19,
                    bobbing::execute),
                Map.of(4, barrier::finish, 7, ledge::finish, 18, pad::finish, 19, bobbing::finish),
                null,
                Map.of(),
                Map.of(),
                (entity, previous) -> {
                  int model = world.entities.get(entity).modelIndex();
                  if (previous > 0
                      && map.reachabilities().get(previous).baseTravelType() == 19
                      && (map.reachabilities().get(previous).face() & 65535) == model)
                    return previous;
                  return links.stream()
                      .filter(r -> (map.reachabilities().get(r).face() & 65535) == model)
                      .min(Integer::compare)
                      .orElse(0);
                });
        if (capture) {
          capture(args, states, handle, move, aasWorld, bsp, world);
          return;
        }
        for (int i = 0; i < count; i++) {
          int mode = i % 22, link = links.get(random.nextInt(links.size()));
          var reach = map.reachabilities().get(link);
          int source = sourceAreas.get(link), model = reach.face() & 65535;
          world.mode = 0;
          world.contents = 0;
          world.entity = 1022;
          world.forced = 0;
          world.groundReply = false;
          for (int m : models) world.entities.put(180 + m, new MoverQueries.Entity(4, m, ZERO));
          var b = bsp.models().get(model).bounds();
          int axis = (reach.face() & 65536) != 0 ? 0 : (reach.face() & 131072) != 0 ? 1 : 2;
          float[] center = {
            ((float) b.min().x() + (float) b.max().x()) * .5f,
            ((float) b.min().y() + (float) b.max().y()) * .5f,
            ((float) b.min().z() + (float) b.max().z()) * .5f
          };
          float start = (short) (reach.edge() >>> 16), end = (short) reach.edge();
          float phase =
              switch ((i / 22) % 6) {
                case 0 -> start;
                case 1 -> end;
                case 2 -> start + 16;
                case 3 -> end + 24;
                default -> start + (end - start) * random.nextFloat();
              };
          float[] offset = {0, 0, 0};
          offset[axis] = phase - center[axis];
          world.entities.put(
              180 + model,
              new MoverQueries.Entity(4, model, new Vec3(offset[0], offset[1], offset[2])));
          aasWorld.clear();
          for (var entry : world.entities.entrySet())
            aasWorld.link(entry.getKey(), entry.getValue().origin(), entry.getValue().origin());
          Vec3 origin = map.areas().get(source).center();
          if (i % 4 == 1)
            origin =
                new Vec3(
                    (float) reach.start().x() + random.nextFloat() * 24 - 12,
                    (float) reach.start().y() + random.nextFloat() * 24 - 12,
                    (float) reach.start().z() + .125f);
          if (i % 4 == 2)
            origin =
                new Vec3(
                    (float) reach.end().x() + random.nextFloat() * 80 - 40,
                    (float) reach.end().y() + random.nextFloat() * 80 - 40,
                    (float) reach.end().z() + .125f);
          if (i % 4 == 3) {
            var g = movers.bobbing(reach).orElseThrow();
            origin = new Vec3(g.current().x(), g.current().y(), (float) g.current().z() + 24.125f);
          }
          int goalArea = reach.area();
          Vec3 goalPoint = reach.end();
          int flags = TravelFlags.DEFAULT, effective = mode == 7 ? 0 : mode % 3 == 0 ? 514 : 2;
          if (mode == 9) origin = new Vec3(-1000000, -1000000, -1000000);
          int current = locator.fuzzyArea(origin);
          if (mode == 4 && !airborne) {
            int beyond =
                links.stream()
                    .filter(
                        r ->
                            (map.reachabilities().get(r).face() & 65535) == model
                                && map.reachabilities().get(r).area() != reach.area())
                    .min(Integer::compare)
                    .orElseThrow();
            goalArea = map.reachabilities().get(beyond).area();
            goalPoint = map.reachabilities().get(beyond).end();
          }
          if (mode == 5) {
            goalArea = current;
            goalPoint = origin.add(new Vec3(3, 4, 30));
          }
          if (mode == 6 || mode == 15) flags = 0;
          if (mode == 21) {
            goalArea = 0;
            goalPoint = ZERO;
          }
          if (mode == 16) effective = 2 | 4 | 8 | 128;
          var history = new BotMovement.History(0, 0, 0, 0, 0, 0, 0, new Vec3(7, 8, 9));
          if (Set.of(1, 2, 3, 4, 11, 14, 15).contains(mode))
            history =
                new BotMovement.History(
                    current,
                    mode == 3 ? 0 : current,
                    mode == 4 ? 0 : goalArea,
                    link,
                    source,
                    0,
                    mode == 2 ? 9 : mode == 14 ? 10 : 15,
                    new Vec3(7, 8, 9));
          if (mode >= 5 && mode <= 9)
            history =
                new BotMovement.History(source, source, source, 0, source, 0, 7, new Vec3(7, 8, 9));
          int presence = mode == 12 || mode == 17 ? 4 : 2;
          if (airborne) {
            origin =
                new Vec3(
                    (float) reach.start().x() + random.nextFloat() * 100 - 50,
                    (float) reach.start().y() + random.nextFloat() * 100 - 50,
                    (float) reach.start().z() + random.nextFloat() * 160 + .5f);
            if (i % 5 == 1) {
              var g = movers.bobbing(reach).orElseThrow();
              origin =
                  new Vec3(
                      (float) g.current().x() + random.nextFloat() * 8 - 4,
                      (float) g.current().y() + random.nextFloat() * 8 - 4,
                      (float) reach.start().z() + random.nextFloat() * 4 - 2);
            }
            if (i % 5 == 2) origin = reach.end();
            if (predictor.onGround(origin, presence, 1)) {
              skipped++;
              exclusions.merge("AAS grounded sample", 1, Integer::sum);
              continue;
            }
            effective = mode % 3 == 0 ? 512 : mode % 3 == 1 ? 0 : 4 | 8 | 128 | 1;
            history =
                new BotMovement.History(
                    mode % 3 == 0 ? 0 : source,
                    source,
                    source,
                    link,
                    source,
                    i % 3 == 0 ? 0 : link,
                    mode % 2 == 0 ? -5 : 99,
                    new Vec3(7, 8, 9));
            if (mode % 2 == 0) flags = 0;
          }
          if (standing && mode == 3) {
            int other =
                links.stream()
                    .filter(r -> (map.reachabilities().get(r).face() & 65535) != model)
                    .min(Integer::compare)
                    .orElseThrow();
            history =
                new BotMovement.History(
                    history.area(),
                    history.lastArea(),
                    history.lastGoalArea(),
                    other,
                    history.reachArea(),
                    7,
                    history.reachDeadline(),
                    history.lastOrigin());
          }
          world.floor = (float) origin.z() - 25;
          world.mode = mode == 8 || mode == 17 ? 1 : mode == 13 ? 2 : 0;
          world.entity = mode == 8 ? 15 : 1022;
          if (mode == 18 || mode == 20) {
            world.mode = 0;
            world.forced = 2;
            world.fraction = .5f;
            world.hitEntity = 180 + model;
            world.solids = mode == 20 ? 1 : 0;
          }
          if (standing) {
            world.groundReply = true;
            world.groundEntity = 180 + model;
            world.groundFraction = i % 7 == 0 ? 1 : .5f;
            world.groundSolids = i % 13 == 0 ? 1 : i % 13 == 1 ? 3 : 0;
          }
          var input =
              new MovementInit(
                  origin,
                  new Vec3(
                      random.nextFloat() * 800 - 400,
                      random.nextFloat() * 800 - 400,
                      random.nextFloat() * 800 - 400),
                  new Vec3(0, 0, 26),
                  1,
                  1,
                  .1f,
                  presence,
                  ZERO,
                  mode == 18 ? 0 : effective);
          states.reset(handle);
          states.initialize(handle, input);
          states.updateHistory(handle, history);
          states.updateMovementFlags(handle, effective);
          if (mode == 10 || mode == 19 || mode == 20)
            for (int t = 0; t < 5; t++)
              states.recordReachAttempt(handle, link, mode == 19 ? 9 : mode == 20 ? 8 : 10, 1);
          if (mode == 11 || mode == 12)
            states.addAvoidSpot(
                handle, new BotMovement.AvoidSpot(reach.start(), 64, mode == 11 ? 1 : 2));
          var goal =
              new Goal(
                  goalPoint,
                  goalArea,
                  new Vec3(-15, -15, -15),
                  new Vec3(15, 15, 15),
                  149,
                  9,
                  1,
                  11);
          var before = states.snapshot(handle).orElseThrow();
          var seed =
              new BotInput(
                  0,
                  new Vec3(.25f + i % 8 * .03125f, -.5, .125),
                  123,
                  ZERO,
                  switch (i % 5) {
                    case 0 -> 8192;
                    case 1 -> 16;
                    case 2 -> 268435456;
                    case 3 -> 32768;
                    default -> 0;
                  },
                  0);
          String replay = commands(input, before, goal, flags, world, seed);
          world.calls = 0;
          GroundMoveToGoal.Output output;
          try {
            output = move.execute(handle, goal, TravelPolicy.ofFlags(flags), 10);
          } catch (UnsupportedOperationException failure) {
            if (!before.equals(states.snapshot(handle).orElseThrow()))
              throw new AssertionError("Unsupported mutated state");
            skipped++;
            exclusions.merge(
                failure instanceof GroundMoveToGoal.UnsupportedMovement d
                    ? d.reason()
                    : failure.getMessage(),
                1,
                Integer::sum);
            continue;
          }
          byte[] result = new byte[52];
          Arrays.fill(result, (byte) 0x7f);
          output.writeTo(ByteBuffer.wrap(result), 0);
          String scenario =
              (airborne ? "air-" : standing ? "standing-" : "ground-") + SCENARIOS[mode];
          expected.add(
              new Expected(
                  replay,
                  scenario,
                  result,
                  states.snapshot(handle).orElseThrow(),
                  output,
                  applyActions(seed, output),
                  world.calls));
          allCommands.append(replay);
          scenarios.merge(scenario, 1, Integer::sum);
          outputs.merge(
              output.result().travelType()
                  + ":"
                  + output.result().type()
                  + ":"
                  + output.result().flags()
                  + ":"
                  + (output.command().isPresent() ? "move" : "absent"),
              1,
              Integer::sum);
        }
      }
      Files.writeString(
          Path.of(
              ".tools/bobbing-goal-oracle/audit-"
                  + (airborne ? "air" : standing ? "standing" : "ground")
                  + ".commands"),
          allCommands.toString());
      var actual = oracle(args[1], args[0], "q3dm19", allCommands.toString(), null);
      if (actual.size() != expected.size())
        throw new AssertionError("Native count " + actual.size() + "/" + expected.size());
      int diffCount = 0;
      Set<String> reported = new HashSet<>();
      for (int i = 0; i < expected.size(); i++) {
        String diff = compare(expected.get(i), actual.get(i));
        if (!diff.isEmpty()) {
          diffCount++;
          differences.merge(expected.get(i).scenario() + ":" + diff, 1, Integer::sum);
          if (reported.add(expected.get(i).scenario() + ":" + diff) && reported.size() <= 40) {
            System.out.println(
                "DIFF #"
                    + i
                    + " "
                    + expected.get(i).scenario()
                    + " "
                    + diff
                    + "\nREPRO\n"
                    + expected.get(i).commands()
                    + "JAVA "
                    + expected.get(i).output()
                    + " STATE "
                    + expected.get(i).state()
                    + "\nNATIVE "
                    + actual.get(i));
          }
        }
      }
      System.out.println(
          "Bobbing goals "
              + (airborne ? "air" : standing ? "standing" : "ground")
              + " compared="
              + expected.size()
              + " skipped="
              + skipped
              + " differences="
              + diffCount);
      System.out.println("SCENARIOS " + scenarios);
      System.out.println("EXCLUSIONS " + exclusions);
      System.out.println("DIFFERENCES " + differences);
      System.out.println("OUTPUTS " + outputs);
      if (diffCount != 0) throw new AssertionError("Bobbing goal differences " + diffCount);
    }
  }

  private static void capture(
      String[] args,
      BotMovement states,
      int handle,
      GroundMoveToGoal move,
      AasMovementWorld aasWorld,
      BspMap bsp,
      World world)
      throws Exception {
    for (String profile : List.of("retail", "modern", "matching-modern")) {
      String replay =
          Files.readString(Path.of(".tools/bobbing-goal-oracle", profile + "-live-input.txt"));
      world.entities.clear();
      aasWorld.clear();
      world.mode = 0;
      world.forced = 0;
      world.contents = 0;
      world.groundReply = false;
      world.platformReply = false;
      float time = 0;
      MovementInit input = null;
      BotMovement.History history = null;
      int flags = 0, policy = 0;
      Goal goal = null;
      BotInput seed = null;
      int avoid = 0, tries = 0;
      float expiry = 0;
      for (String line : replay.lines().toList()) {
        String[] f = line.strip().split(" +");
        switch (f[0]) {
          case "frame" -> time = Float.parseFloat(f[1]);
          case "mover" -> {
            int id = Integer.parseInt(f[1]), model = Integer.parseInt(f[2]);
            Vec3 origin = vector(f, 3);
            world.entities.put(id, new MoverQueries.Entity(Integer.parseInt(f[6]), model, origin));
            var b = bsp.models().get(model).bounds();
            aasWorld.link(id, add(b.min(), origin), add(b.max(), origin));
          }
          case "init" ->
              input =
                  new MovementInit(
                      vector(f, 1),
                      vector(f, 4),
                      vector(f, 7),
                      Integer.parseInt(f[10]),
                      Integer.parseInt(f[11]),
                      Float.parseFloat(f[12]),
                      Integer.parseInt(f[13]),
                      vector(f, 14),
                      Integer.parseInt(f[17]));
          case "history" -> {
            history =
                new BotMovement.History(
                    Integer.parseInt(f[1]),
                    Integer.parseInt(f[2]),
                    Integer.parseInt(f[3]),
                    Integer.parseInt(f[4]),
                    Integer.parseInt(f[5]),
                    Integer.parseInt(f[7]),
                    Float.parseFloat(f[8]),
                    vector(f, 9));
            flags = Integer.parseInt(f[6]);
          }
          case "avoid" -> {
            avoid = Integer.parseInt(f[1]);
            expiry = Float.parseFloat(f[2]);
            tries = Integer.parseInt(f[3]);
          }
          case "groundhit" -> {
            world.groundReply = true;
            world.groundEntity = Integer.parseInt(f[1]);
            world.groundFraction = Float.parseFloat(f[2]);
            world.groundSolids = Integer.parseInt(f[3]);
          }
          case "platformhit" -> {
            world.platformReply = true;
            world.platformEntity = Integer.parseInt(f[1]);
            world.platformFraction = Float.parseFloat(f[2]);
            world.platformSolids = Integer.parseInt(f[3]);
          }
          case "goal" -> {
            goal =
                new Goal(
                    vector(f, 1),
                    Integer.parseInt(f[4]),
                    new Vec3(-15, -15, -15),
                    new Vec3(15, 15, 15),
                    149,
                    9,
                    1,
                    11);
            policy = Integer.parseInt(f[5]);
            seed =
                new BotInput(
                    0, vector(f, 6), Float.parseFloat(f[9]), ZERO, Integer.parseInt(f[10]), 0);
          }
          case "traceverbose" -> {}
          default -> throw new IllegalArgumentException("Unexpected capture command " + f[0]);
        }
      }
      states.reset(handle);
      states.initialize(handle, Objects.requireNonNull(input));
      states.updateHistory(handle, Objects.requireNonNull(history));
      states.updateMovementFlags(handle, flags);
      for (int i = 0; i < tries; i++) states.recordReachAttempt(handle, avoid, 1, expiry - 1);
      world.calls = 0;
      var output =
          move.execute(handle, Objects.requireNonNull(goal), TravelPolicy.ofFlags(policy), time);
      byte[] result = new byte[52];
      Arrays.fill(result, (byte) 0x7f);
      output.writeTo(ByteBuffer.wrap(result), 0);
      var expected =
          new Expected(
              replay,
              profile,
              result,
              states.snapshot(handle).orElseThrow(),
              output,
              applyActions(Objects.requireNonNull(seed), output),
              world.calls);
      var nativeRows = oracle(args[1], args[0], "q3dm19", replay, null);
      if (nativeRows.size() != 1) throw new AssertionError("Capture native row count");
      String diff = compare(expected, nativeRows.getFirst());
      System.out.println(
          "CAPTURE "
              + profile
              + " "
              + (diff.isEmpty() ? "PASS" : diff)
              + " JAVA "
              + output
              + " STATE "
              + expected.state()
              + " NATIVE "
              + nativeRows.getFirst());
      if (!diff.isEmpty()) throw new AssertionError("Capture differs " + profile + ": " + diff);
    }
  }

  private static Vec3 vector(String[] values, int offset) {
    return new Vec3(
        Float.parseFloat(values[offset]),
        Float.parseFloat(values[offset + 1]),
        Float.parseFloat(values[offset + 2]));
  }

  private static Vec3 add(Vec3 a, Vec3 b) {
    return new Vec3(
        (float) a.x() + (float) b.x(),
        (float) a.y() + (float) b.y(),
        (float) a.z() + (float) b.z());
  }

  private static String commands(
      MovementInit in,
      BotMovement.Snapshot state,
      Goal goal,
      int flags,
      World world,
      BotInput seed) {
    var s = new StringBuilder("reset\nframe 10\ncontents ").append(world.contents).append("\n");
    s.append(
            world.mode == 0
                ? "clear\n"
                : world.mode == 2 ? "solid\n" : "floor " + world.floor + "\n")
        .append("hitentity ")
        .append(world.entity)
        .append('\n');
    for (var entry : world.entities.entrySet()) {
      s.append("mover ")
          .append(entry.getKey())
          .append(' ')
          .append(entry.getValue().modelIndex())
          .append(' ');
      point(s, entry.getValue().origin());
      s.append("4 0\n");
    }
    if (world.forced > 0)
      s.append("repliesclear\nreply ")
          .append(world.forced)
          .append(' ')
          .append(world.fraction)
          .append(' ')
          .append(world.hitEntity)
          .append(' ')
          .append(world.solids)
          .append('\n');
    s.append("groundclear\n");
    if (world.groundReply)
      s.append("groundhit ")
          .append(world.groundEntity)
          .append(' ')
          .append(world.groundFraction)
          .append(' ')
          .append(world.groundSolids)
          .append('\n');
    s.append("init ");
    point(s, in.origin());
    point(s, in.velocity());
    point(s, in.viewOffset());
    s.append(in.entity())
        .append(' ')
        .append(in.client())
        .append(' ')
        .append(in.thinkTime())
        .append(' ')
        .append(in.presenceType())
        .append(' ');
    point(s, in.viewAngles());
    s.append(in.moveFlags()).append("\neareset\n");
    var h = state.history();
    s.append("history ")
        .append(h.area())
        .append(' ')
        .append(h.lastArea())
        .append(' ')
        .append(h.lastGoalArea())
        .append(' ')
        .append(h.lastReachability())
        .append(' ')
        .append(h.reachArea())
        .append(' ')
        .append(state.movementFlags())
        .append(' ')
        .append(h.jumpReachability())
        .append(' ')
        .append(h.reachDeadline())
        .append(' ');
    point(s, h.lastOrigin());
    s.append('\n');
    var a = state.reachAvoidance();
    s.append("avoid ")
        .append(a.reachability())
        .append(' ')
        .append(a.expiresAt())
        .append(' ')
        .append(a.tries())
        .append('\n');
    for (var spot : state.avoidSpots()) {
      s.append("spot ");
      point(s, spot.origin());
      s.append(spot.radius()).append(' ').append(spot.type()).append('\n');
    }
    s.append("goal ");
    point(s, goal.origin());
    s.append(goal.area()).append(' ').append(flags).append(' ');
    point(s, seed.direction());
    s.append(seed.speed()).append(' ').append(seed.actionFlags()).append('\n');
    return s.toString();
  }

  private static String compare(Expected e, Native n) {
    var result = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN);
    String[] f = n.result().split(" ");
    for (int i = 0; i < 7; i++) result.putInt(Integer.parseInt(f[i + 1]));
    for (int i = 0; i < 6; i++) result.putFloat(Float.parseFloat(f[i + 8]));
    StringBuilder diff = new StringBuilder();
    if (!Arrays.equals(e.result(), result.array())) diff.append("result ");
    if (e.traces() != Integer.parseInt(f[14].substring(7)))
      diff.append("traces(").append(e.traces()).append('/').append(f[14]).append(") ");
    String[] st = n.state().split(" ");
    var h = e.state().history();
    int[] ints = {
      h.area(),
      h.lastArea(),
      h.lastGoalArea(),
      h.lastReachability(),
      h.reachArea(),
      e.state().movementFlags(),
      h.jumpReachability()
    };
    for (int i = 0; i < ints.length; i++)
      if (ints[i] != Integer.parseInt(st[i + 1])) diff.append("state").append(i).append(' ');
    float[] floats = {
      h.reachDeadline(),
      (float) h.lastOrigin().x(),
      (float) h.lastOrigin().y(),
      (float) h.lastOrigin().z()
    };
    for (int i = 0; i < floats.length; i++)
      if (!same(floats[i], Float.parseFloat(st[i + 8])))
        diff.append("stateFloat").append(i).append(' ');
    String[] av = st[12].substring(6).split(",");
    var a = e.state().reachAvoidance();
    if (a.reachability() != Integer.parseInt(av[0])
        || !same(a.expiresAt(), Float.parseFloat(av[1]))
        || a.tries() != Integer.parseInt(av[2])) diff.append("avoid ");
    String[] ea = n.action().split(" ");
    var actions = e.actions();
    float[] values = {
      (float) actions.direction().x(),
      (float) actions.direction().y(),
      (float) actions.direction().z(),
      actions.speed()
    };
    for (int i = 0; i < 4; i++)
      if (!same(values[i], Float.parseFloat(ea[i + 1]))) diff.append("EA").append(i).append(' ');
    if (actions.actionFlags() != Integer.parseInt(ea[5])) diff.append("actions ");
    if (actions.weapon() != Integer.parseInt(ea[11])) diff.append("weapon ");
    for (int j = 0; j < 3; j++)
      if (!same(
          (float)
              (j == 0
                  ? actions.viewAngles().x()
                  : j == 1 ? actions.viewAngles().y() : actions.viewAngles().z()),
          Float.parseFloat(ea[7 + j]))) diff.append("view").append(j).append(' ');
    return diff.toString();
  }

  private static BotInput applyActions(BotInput seed, GroundMoveToGoal.Output output) {
    try (var actions = new ElementaryActions(1, (client, text) -> {})) {
      actions.move(0, seed.direction(), seed.speed());
      actions.action(0, seed.actionFlags());
      actions.selectWeapon(0, seed.weapon());
      actions.view(0, seed.viewAngles());
      actions.action(0, output.actionFlags() & ~(ActionFlags.JUMP | ActionFlags.DELAYED_JUMP));
      if ((output.actionFlags() & ActionFlags.JUMP) != 0) actions.jump(0);
      if ((output.actionFlags() & ActionFlags.DELAYED_JUMP) != 0) actions.delayedJump(0);
      output.command().ifPresent(c -> actions.move(0, c.direction(), c.speed()));
      output.view().ifPresent(v -> actions.view(0, v));
      output.weapon().ifPresent(w -> actions.selectWeapon(0, w));
      return actions.snapshot(0);
    }
  }

  private static boolean same(float a, float b) {
    return Float.floatToIntBits(a) == Float.floatToIntBits(b);
  }

  private static void point(StringBuilder s, Vec3 p) {
    s.append((float) p.x())
        .append(' ')
        .append((float) p.y())
        .append(' ')
        .append((float) p.z())
        .append(' ');
  }

  private static List<Native> oracle(
      String exe, String pk3, String map, String commands, Path overlay) throws Exception {
    var command = new ArrayList<>(List.of(exe, pk3, map));
    if (overlay != null) command.add(overlay.toString());
    Process process =
        new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var output =
          executor.submit(
              () -> {
                byte[] bytes = process.getInputStream().readNBytes(16 * 1024 * 1024 + 1);
                if (bytes.length > 16 * 1024 * 1024)
                  throw new IllegalStateException("Oracle output cap");
                return new String(bytes, StandardCharsets.US_ASCII);
              });
      try (var in = process.getOutputStream()) {
        in.write(commands.getBytes(StandardCharsets.US_ASCII));
      }
      if (!process.waitFor(60, TimeUnit.SECONDS)) throw new IllegalStateException("Oracle timeout");
      String text = output.get(5, TimeUnit.SECONDS);
      if (process.exitValue() != 0)
        throw new IllegalStateException("Oracle failed " + process.exitValue());
      var lines =
          text.lines()
              .filter(s -> s.startsWith("RESULT ") || s.startsWith("STATE ") || s.startsWith("EA "))
              .toList();
      if (lines.size() % 3 != 0) throw new AssertionError("Incomplete native result");
      var records = new ArrayList<Native>();
      for (int i = 0; i < lines.size(); i += 3)
        records.add(new Native(lines.get(i), lines.get(i + 1), lines.get(i + 2)));
      return records;
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  private static final class World implements TraceWorld {
    int mode, entity = 1022, calls, contents;
    float floor;
    int forced, hitEntity, solids;
    float fraction;
    boolean groundReply;
    int groundEntity, groundSolids;
    float groundFraction;
    boolean platformReply;
    int platformEntity, platformSolids;
    float platformFraction;
    final Map<Integer, MoverQueries.Entity> entities = new TreeMap<>();

    public TraceResult trace(TraceRequest request) {
      return trace(request, false);
    }

    TraceResult trace(TraceRequest request, boolean entityCallback) {
      calls++;
      if (platformReply
          && !entityCallback
          && request.contentsMask() == 65537
          && request.mins().x() == -16
          && request.mins().y() == -16
          && request.mins().z() == -8
          && request.maxs().z() == 8) {
        Vec3 end =
            new Vec3(
                (float) request.start().x()
                    + platformFraction * ((float) request.end().x() - (float) request.start().x()),
                (float) request.start().y()
                    + platformFraction * ((float) request.end().y() - (float) request.start().y()),
                (float) request.start().z()
                    + platformFraction * ((float) request.end().z() - (float) request.start().z()));
        return new TraceResult(
            platformFraction,
            end,
            (platformSolids & 1) != 0,
            (platformSolids & 2) != 0,
            Optional.of(
                new TraceResult.Hit(
                    new TraceResult.Plane(new Vec3(0, 0, 1), 0),
                    1,
                    0,
                    platformEntity,
                    -1,
                    -1,
                    -1,
                    -1,
                    "")));
      }
      if (groundReply
          && !entityCallback
          && request.contentsMask() == 65537
          && request.end().x() == request.start().x()
          && request.end().y() == request.start().y()
          && (float) request.end().z() == (float) request.start().z() - 3) {
        Vec3 end =
            new Vec3(
                (float) request.start().x()
                    + groundFraction * ((float) request.end().x() - (float) request.start().x()),
                (float) request.start().y()
                    + groundFraction * ((float) request.end().y() - (float) request.start().y()),
                (float) request.start().z()
                    + groundFraction * ((float) request.end().z() - (float) request.start().z()));
        return new TraceResult(
            groundFraction,
            end,
            (groundSolids & 1) != 0,
            (groundSolids & 2) != 0,
            Optional.of(
                new TraceResult.Hit(
                    new TraceResult.Plane(new Vec3(0, 0, 1), 0),
                    1,
                    0,
                    groundEntity,
                    -1,
                    -1,
                    -1,
                    -1,
                    "")));
      }
      if (calls == forced) {
        Vec3 end =
            new Vec3(
                (float) request.start().x()
                    + fraction * ((float) request.end().x() - (float) request.start().x()),
                (float) request.start().y()
                    + fraction * ((float) request.end().y() - (float) request.start().y()),
                (float) request.start().z()
                    + fraction * ((float) request.end().z() - (float) request.start().z()));
        return new TraceResult(
            fraction,
            end,
            (solids & 1) != 0,
            (solids & 2) != 0,
            Optional.of(
                new TraceResult.Hit(
                    new TraceResult.Plane(new Vec3(0, 0, 1), 0),
                    1,
                    0,
                    hitEntity,
                    -1,
                    -1,
                    -1,
                    -1,
                    "")));
      }
      if (mode == 0 || (request.contentsMask() & 1) == 0) return TraceResult.clear(request);
      boolean start = false, all = false;
      float fraction = 1;
      Vec3 end = request.end();
      if (mode == 2) {
        start = all = true;
        fraction = 0;
        end = request.start();
      } else {
        float a = (float) request.start().z() + (float) request.mins().z() - floor,
            b = (float) request.end().z() + (float) request.mins().z() - floor;
        if (a < 0) {
          start = true;
          all = b < 0;
          if (all) {
            fraction = 0;
            end = request.start();
          }
        } else if (b < 0) {
          fraction = a / (a - b);
          end =
              new Vec3(
                  (float) request.start().x()
                      + fraction * ((float) request.end().x() - (float) request.start().x()),
                  (float) request.start().y()
                      + fraction * ((float) request.end().y() - (float) request.start().y()),
                  (float) request.start().z()
                      + fraction * ((float) request.end().z() - (float) request.start().z()));
        }
      }
      Optional<TraceResult.Hit> hit =
          fraction < 1 || start
              ? Optional.of(
                  new TraceResult.Hit(
                      new TraceResult.Plane(new Vec3(0, 0, 1), floor),
                      1,
                      0,
                      entity,
                      -1,
                      -1,
                      -1,
                      -1,
                      ""))
              : Optional.empty();
      return new TraceResult(fraction, end, start, all, hit);
    }

    public int pointContents(Vec3 p, int mask, int ignore) {
      return contents;
    }
  }
}
