import dev.bluevista.craftq3.assets.aas.*;
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

/** Full weapon-jump move-to-goal differential against an isolated native development oracle. */
class AuditWeaponGoals {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);

  private record Expected(
      String commands,
      String scenario,
      byte[] result,
      BotMovement.Snapshot state,
      GroundMoveToGoal.Output output,
      BotInput actions,
      int traces) {}

  private record Native(String result, String state, String action, String draws) {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 5)
      throw new IllegalArgumentException(
          "AuditWeaponGoals <PK3> <oracle> [queries/map] [map|all]"
              + " [rocket|air-rocket|bfg|air-bfg|ground]");
    if (args.length == 5
        && !Set.of("rocket", "air-rocket", "bfg", "air-bfg", "ground").contains(args[4]))
      throw new IllegalArgumentException("Unknown weapon goal mode");
    boolean airborne = args.length == 5 && args[4].startsWith("air");
    boolean airbornePad = args.length == 5 && args[4].equals("air-pad");
    boolean airborneLedge = args.length == 5 && args[4].equals("air-ledge");
    boolean teleport = args.length == 5 && args[4].endsWith("teleport");
    boolean barrier = args.length == 5 && args[4].endsWith("barrier");
    boolean rocket = args.length == 5 && args[4].endsWith("rocket");
    boolean bfg = args.length == 5 && args[4].endsWith("bfg");
    boolean jump = rocket || bfg;
    boolean waterJump = args.length == 5 && args[4].endsWith("waterjump");
    boolean swim = args.length == 5 && args[4].endsWith("swim");
    boolean liquid =
        !airborne && (swim || waterJump || args.length == 5 && args[4].equals("liquid"));
    int requestedTravel =
        rocket
            ? 12
            : bfg
                ? 13
                : waterJump
                    ? 9
                    : swim
                        ? 8
                        : barrier ? 4 : teleport ? 10 : airbornePad ? 18 : airborneLedge ? 7 : 0;
    int airborneKind = airborne ? requestedTravel : 0;
    boolean forceCrouch = args.length == 5 && args[4].equals("air-crouch");
    int count = args.length > 2 ? Integer.parseInt(args[2]) : 100;
    if (count < 1 || count > 10000) throw new IllegalArgumentException("Invalid count");
    int maps = 0, total = 0, differences = 0, skipped = 0;
    Map<String, Integer> reasons = new TreeMap<>(),
        scenarios = new TreeMap<>(),
        diffScenarios = new TreeMap<>(),
        commandKinds = new TreeMap<>();
    Set<String> reported = new HashSet<>();
    long started = System.nanoTime();
    try (var zip = new ZipFile(Path.of(args[0]).toFile())) {
      for (var entry :
          zip.stream()
              .filter(e -> e.getName().startsWith("maps/") && e.getName().endsWith(".aas"))
              .sorted(Comparator.comparing(java.util.zip.ZipEntry::getName))
              .toList()) {
        String name = entry.getName().substring(5, entry.getName().length() - 4);
        if (args.length >= 4 && !args[3].equals("all") && !args[3].equals(name)) continue;
        byte[] data;
        try (var in = zip.getInputStream(entry)) {
          data = in.readNBytes(AasReader.MAX_BYTES + 1);
        }
        Path overlay = null;
        var originalMap = AasReader.read(data);
        if (bfg) {
          if (!name.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("Unsafe map name");
          int offset = originalMap.lumps().get(AasMap.LumpKind.REACHABILITIES.ordinal()).offset();
          var buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
          for (int r = 0; r < originalMap.reachabilities().size(); r++)
            if (originalMap.reachabilities().get(r).baseTravelType() == 12)
              buffer.putInt(offset + r * 44 + 36, 13);
          overlay =
              Path.of(".tools/weapon-goal-oracle/overlays", airborne ? "air" : "ground", name);
          Files.createDirectories(overlay.resolve("maps"));
          Files.write(overlay.resolve("maps/" + name + ".aas"), data);
        }
        var map = forceCrouch ? crouchMap(AasReader.read(data)) : AasReader.read(data);
        var nav = new AasNavigation(map);
        var world = new World();
        var aasWorld =
            new AasMovementWorld(nav, (e, r) -> TraceResult.clear(r), p -> world.contents);
        var gaps = new MovementObstacles(aasWorld);
        var predictor =
            new AasMovementPredictor(aasWorld, AasMovementPredictor.Settings.defaults());
        var area = new AasReachabilityArea(nav, List.of(), world, e -> Optional.empty());
        var routeTimes = new AasRouteTimes(map);
        var routes = new AasMovementRoutes(routeTimes);
        var ground =
            new GroundReachMovement(
                world,
                a -> a <= 0 ? 0 : map.areaSettings().get(a).presenceType(),
                a -> a <= 0 ? 0 : map.areaSettings().get(a).reachabilityCount(),
                gaps::gapDistance);
        try (var states =
            new BotMovement(
                message -> {
                  throw new AssertionError(message);
                })) {
          int handle = states.allocate();
          var jumpPads =
              new JumpPadMovement(
                  new MovementObstruction(
                      world, a -> a <= 0 ? 0 : map.areaSettings().get(a).reachabilityCount()));
          var ledges =
              new LedgeReachMovement(
                  new MovementObstruction(
                      world, a -> a <= 0 ? 0 : map.areaSettings().get(a).reachabilityCount()));
          var teleports =
              new TeleportReachMovement(
                  new MovementObstruction(
                      world, a -> a <= 0 ? 0 : map.areaSettings().get(a).reachabilityCount()));
          var barriers =
              new BarrierReachMovement(
                  new MovementObstruction(
                      world, a -> a <= 0 ? 0 : map.areaSettings().get(a).reachabilityCount()));
          var liquidMovement =
              new LiquidReachMovement(
                  new MovementObstruction(
                      world, a -> a <= 0 ? 0 : map.areaSettings().get(a).reachabilityCount()),
                  () -> 0);
          var runStart =
              new JumpRunStart(
                  request ->
                      predictor
                          .predict(request)
                          .orElseThrow(
                              () ->
                                  new UnsupportedOperationException(
                                      "Jump run-up prediction did not complete")));
          var weaponJumps = new WeaponJumpMovement();
          var move =
              new GroundMoveToGoal(
                  states,
                  nav,
                  area::fuzzyArea,
                  routes,
                  world,
                  predictor::onGround,
                  e -> false,
                  ground,
                  Map.of(
                      4,
                      barriers::execute,
                      7,
                      ledges::execute,
                      8,
                      liquidMovement::execute,
                      9,
                      liquidMovement::execute,
                      10,
                      teleports::execute,
                      18,
                      jumpPads::execute),
                  Map.of(
                      4,
                      barriers::finish,
                      7,
                      ledges::finish,
                      8,
                      liquidMovement::execute,
                      9,
                      liquidMovement::finish,
                      18,
                      jumpPads::finish),
                  liquidMovement::moveInGoalArea,
                  Map.of(12, weaponJumps::execute, 13, weaponJumps::execute),
                  Map.of(12, weaponJumps::finish, 13, weaponJumps::finish));
          List<Integer> candidates = new ArrayList<>();
          for (int a = 1; a < map.areas().size(); a++)
            if (map.areaSettings().get(a).reachabilityCount() > 0
                && (map.areaSettings().get(a).flags() & 2) == 0
                && (requestedTravel == 0 || hasTravel(map, a, requestedTravel))) candidates.add(a);
          if (candidates.isEmpty()) {
            System.out.println(name + " no eligible source areas");
            continue;
          }
          Random random = new Random(729);
          var expected = new ArrayList<Expected>();
          var commands = new StringBuilder();
          int mapSkip = 0;
          for (int i = 0; i < count; i++) {
            int mode = i % 22;
            world.contents =
                liquid
                    ? switch (i % 4) {
                      case 0 -> 8;
                      case 1 -> 16;
                      case 2 -> 32;
                      default -> 56;
                    }
                    : 0;
            int seedArea = candidates.get(random.nextInt(candidates.size()));
            Vec3 origin = map.areas().get(seedArea).center();
            if (mode == 9 && !airborne) origin = new Vec3(-1000000, -1000000, -1000000);
            int source = area.fuzzyArea(origin),
                goalArea = candidates.get(random.nextInt(candidates.size()));
            Vec3 goalPoint = map.areas().get(goalArea).center();
            int policyFlags =
                TravelFlags.DEFAULT | TravelFlags.forTravelType(12) | TravelFlags.forTravelType(13);
            int first = 0;
            if (source > 0) {
              var set = map.areaSettings().get(source);
              for (int r = set.firstReachability();
                  r < set.firstReachability() + set.reachabilityCount();
                  r++)
                if (requestedTravel != 0
                    ? map.reachabilities().get(r).baseTravelType() == requestedTravel
                    : map.reachabilities().get(r).baseTravelType() == 2
                        || map.reachabilities().get(r).baseTravelType() == 3) {
                  first = r;
                  break;
                }
            }
            if (mode == 0 && first != 0) {
              goalArea = map.reachabilities().get(first).area();
              goalPoint = map.reachabilities().get(first).end();
            }
            if (mode == 5) {
              goalArea = source;
              goalPoint = origin.add(new Vec3(3, 4, 30));
            }
            if (mode == 6 || mode == 15) policyFlags = 0;
            if (mode == 21) {
              goalArea = 0;
              goalPoint = ZERO;
            }
            int effective = mode == 7 ? 0 : mode % 3 == 0 ? 514 : 2;
            if (mode == 16) effective = 2 | 4 | 8 | 128;
            var history = new BotMovement.History(0, 0, 0, 0, 0, 0, 0, new Vec3(7, 8, 9));
            if (mode == 1
                || mode == 2
                || mode == 3
                || mode == 4
                || mode == 11
                || mode == 14
                || mode == 15)
              history =
                  new BotMovement.History(
                      source,
                      mode == 3 ? 0 : source,
                      mode == 4 ? 0 : goalArea,
                      first,
                      source,
                      0,
                      mode == 2 ? 9 : mode == 14 ? 10 : 15,
                      new Vec3(7, 8, 9));
            if (mode == 5 || mode == 6 || mode == 7 || mode == 8 || mode == 9)
              history =
                  new BotMovement.History(
                      seedArea, seedArea, seedArea, 0, seedArea, 0, 7, new Vec3(7, 8, 9));
            if ((barrier || jump) && !airborne && first != 0 && i % 3 == 0) {
              var start = map.reachabilities().get(first).start();
              origin =
                  new Vec3(
                      (float) start.x() + i % 7 - 3,
                      (float) start.y() + i % 5 - 2,
                      (float) start.z() + .125f);
            }
            world.floor = (float) origin.z() - 25;
            world.mode = mode == 8 || mode == 17 ? 1 : mode == 13 ? 2 : 0;
            world.entity = mode == 8 ? 15 : 1022;
            int presence = mode == 12 || mode == 17 ? 4 : 2;
            if (airborne) {
              if (first == 0) {
                skipped++;
                mapSkip++;
                reasons.merge("No source WALK/CROUCH reach", 1, Integer::sum);
                continue;
              }
              var reach = map.reachabilities().get(first);
              origin =
                  new Vec3(
                      (float) reach.start().x() + random.nextFloat() * 64 - 32,
                      (float) reach.start().y() + random.nextFloat() * 64 - 32,
                      (float) reach.start().z() + random.nextFloat() * 128 + .5f);
              if (predictor.onGround(origin, presence, 1)) {
                skipped++;
                mapSkip++;
                reasons.merge("AAS grounded sample", 1, Integer::sum);
                continue;
              }
              effective = mode % 3 == 0 ? 512 : mode % 3 == 1 ? 0 : 4 | 8 | 128 | 1;
              history =
                  new BotMovement.History(
                      mode % 3 == 0 ? 0 : source,
                      source,
                      source,
                      first,
                      source,
                      i % 3 == 0 ? 0 : i % 3 == 1 ? first : 7,
                      mode % 2 == 0 ? -5 : 99,
                      new Vec3(7, 8, 9));
              world.floor = (float) origin.z() - 25;
              if (mode % 2 == 0) policyFlags = 0;
            }
            if (teleport && (i / 22) % 2 == 1) effective |= 32;
            Vec3 view = ZERO;
            if (jump && first != 0) {
              float heading = rocket ? yaw(origin, map.reachabilities().get(first).start()) : 0;
              float pitch = rocket ? 90 : 0;
              if (i % 5 == 1) heading += 20;
              if (i % 5 == 2) pitch += 4.9f;
              view = new Vec3(pitch, heading, 37);
            }
            var input =
                new MovementInit(
                    origin,
                    airborneKind != 0
                        ? new Vec3(
                            random.nextFloat() * 800 - 400,
                            random.nextFloat() * 800 - 400,
                            random.nextFloat() * 800 - (airborneLedge ? 600 : 0))
                        : ZERO,
                    new Vec3(0, 0, 26),
                    1,
                    1,
                    .1f,
                    presence,
                    view,
                    mode == 18 ? 0 : effective);
            states.reset(handle);
            states.initialize(handle, input);
            states.updateHistory(handle, history);
            states.updateMovementFlags(handle, effective);
            if ((mode == 10 || mode == 19 || mode == 20) && first != 0) {
              for (int tries = 0; tries < 5; tries++)
                states.recordReachAttempt(handle, first, mode == 19 ? 9 : mode == 20 ? 8 : 10, 1);
            }
            if ((mode == 11 || mode == 12) && first != 0)
              states.addAvoidSpot(
                  handle,
                  new BotMovement.AvoidSpot(
                      map.reachabilities().get(first).start(), 64, mode == 11 ? 1 : 2));
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
                    (barrier || jump || liquid || swim || waterJump)
                        ? new Vec3(.25f + i % 8 * .03125f, -.5f, .125f)
                        : ZERO,
                    (barrier || jump || liquid || swim || waterJump) ? 123 : 0,
                    new Vec3(1, 2, 3),
                    (barrier || jump)
                        ? switch (i % 4) {
                          case 1 -> 1;
                          case 2 -> ActionFlags.JUMPED_LAST_FRAME;
                          case 3 -> ActionFlags.JUMPED_LAST_FRAME | ActionFlags.JUMP;
                          default -> 0;
                        }
                        : 8192,
                    7);
            String caseCommands = commands(input, before, goal, policyFlags, world, seed);
            if (forceCrouch) caseCommands = "patchreach " + first + " 3\n" + caseCommands;
            world.calls = 0;
            GroundMoveToGoal.Output output;
            try {
              output = move.execute(handle, goal, TravelPolicy.ofFlags(policyFlags), 10);
            } catch (UnsupportedOperationException unsupported) {
              if (!before.equals(states.snapshot(handle).orElseThrow()))
                throw new AssertionError("Unsupported request changed state");
              skipped++;
              mapSkip++;
              reasons.merge(
                  unsupported instanceof GroundMoveToGoal.UnsupportedMovement detail
                      ? detail.reason()
                      : unsupported.getMessage(),
                  1,
                  Integer::sum);
              continue;
            }
            byte[] result = new byte[52];
            Arrays.fill(result, (byte) 0x7f);
            output.writeTo(ByteBuffer.wrap(result), 0);
            String scenario =
                switch (mode) {
                  case 0 -> "fresh";
                  case 1 -> "cached";
                  case 2 -> "expired";
                  case 3 -> "different-area";
                  case 4 -> "different-goal";
                  case 5 -> "same-area";
                  case 6 -> "no-flags";
                  case 7 -> "air-no-reach";
                  case 8 -> "entity-ground";
                  case 9 -> "solid-area";
                  case 10 -> "avoided-reach";
                  case 11 -> "cached-spot";
                  case 12 -> "crouch-spot";
                  case 13 -> "solid-trace";
                  case 14 -> "deadline-equal";
                  case 15 -> "cached-no-flags";
                  case 16 -> "stale-contact-flags";
                  case 17 -> "crouch-world-floor";
                  case 18 -> "effective-versus-input-flags";
                  case 19 -> "avoid-equal";
                  case 20 -> "avoid-expired";
                  default -> "zero-goal";
                };
            if (airborne)
              scenario = "air-" + map.reachabilities().get(first).baseTravelType() + "-" + scenario;
            expected.add(
                new Expected(
                    caseCommands,
                    scenario,
                    result,
                    states.snapshot(handle).orElseThrow(),
                    output,
                    applyActions(seed, output),
                    world.calls));
            commands.append(caseCommands);
            scenarios.merge(scenario, 1, Integer::sum);
            commandKinds.merge(
                (output.writtenBytes() == 24
                        ? "prefix"
                        : output.command().isPresent() ? "move" : "full-no-move")
                    + ":"
                    + output.actionFlags(),
                1,
                Integer::sum);
          }
          var actual = oracle(args[1], args[0], name, commands.toString(), overlay);
          if (actual.size() != expected.size())
            throw new AssertionError(
                "Oracle count " + name + " " + actual.size() + "/" + expected.size());
          int mapDiff = 0;
          for (int i = 0; i < actual.size(); i++) {
            String diff = compare(expected.get(i), actual.get(i));
            if (!diff.isEmpty()) {
              diffScenarios.merge(expected.get(i).scenario() + ": " + diff, 1, Integer::sum);
              differences++;
              if (reported.add(expected.get(i).scenario() + ": " + diff) && reported.size() <= 40) {
                System.out.println(
                    "DIFF " + name + " #" + i + " " + expected.get(i).scenario() + " " + diff);
                System.out.println("REPRO\n" + expected.get(i).commands());
                System.out.println(
                    "JAVA " + expected.get(i).output() + " STATE " + expected.get(i).state());
                System.out.println("NATIVE " + actual.get(i));
              }
              mapDiff++;
            }
          }
          total += expected.size();
          maps++;
          System.out.println(
              name
                  + " compared="
                  + expected.size()
                  + " skipped="
                  + mapSkip
                  + " differences="
                  + mapDiff);
        }
      }
    }
    System.out.printf(
        "Weapon goals maps=%d compared=%d skipped=%d differences=%d seconds=%.3f%n",
        maps, total, skipped, differences, (System.nanoTime() - started) / 1e9);
    System.out.println("SCENARIOS " + scenarios);
    System.out.println("EXCLUSIONS " + reasons);
    System.out.println("DIFFERENCES " + diffScenarios);
    System.out.println("COMMANDS " + commandKinds);
    if (maps == 0) throw new IllegalArgumentException("No matching AAS maps");
    if (differences != 0) throw new AssertionError("Ground goal differences " + differences);
  }

  private static boolean hasTravel(AasMap map, int area, int kind) {
    var setting = map.areaSettings().get(area);
    for (int i = setting.firstReachability();
        i < setting.firstReachability() + setting.reachabilityCount();
        i++) if (map.reachabilities().get(i).baseTravelType() == kind) return true;
    return false;
  }

  private static AasMap crouchMap(AasMap map) {
    var reaches =
        map.reachabilities().stream()
            .map(
                r ->
                    r.baseTravelType() != 2
                        ? r
                        : new AasMap.Reachability(
                            r.area(),
                            r.face(),
                            r.edge(),
                            r.start(),
                            r.end(),
                            3,
                            r.travelTime(),
                            r.reserved()))
            .toList();
    return new AasMap(
        map.version(),
        map.bspChecksum(),
        map.lumps(),
        map.boundingBoxes(),
        map.vertices(),
        map.planes(),
        map.edges(),
        map.edgeIndices(),
        map.faces(),
        map.faceIndices(),
        map.areas(),
        map.areaSettings(),
        reaches,
        map.nodes(),
        map.portals(),
        map.portalIndices(),
        map.clusters());
  }

  private static String commands(
      MovementInit in,
      BotMovement.Snapshot state,
      Goal goal,
      int flags,
      World world,
      BotInput seed) {
    var s =
        new StringBuilder("reset\nframe 10\nrandom 0\ncontents ")
            .append(world.contents)
            .append("\n");
    s.append(
            world.mode == 0
                ? "clear\n"
                : world.mode == 2 ? "solid\n" : "floor " + world.floor + "\n")
        .append("hitentity ")
        .append(world.entity)
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
    if (actions.weapon() != Integer.parseInt(ea[6])) diff.append("weapon ");
    for (int j = 0; j < 3; j++)
      if (!same(
          (float)
              (j == 0
                  ? actions.viewAngles().x()
                  : j == 1 ? actions.viewAngles().y() : actions.viewAngles().z()),
          Float.parseFloat(ea[7 + j]))) diff.append("view").append(j).append(' ');
    int draws =
        (e.output().result().travelType() & 0xff) == 9 && (e.output().actionFlags() & 512) != 0
            ? 1
            : 0;
    if (draws != Integer.parseInt(n.draws().substring(6))) diff.append("draws ");
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

  private static float yaw(Vec3 origin, Vec3 target) {
    float x = (float) target.x() - (float) origin.x(),
        y = (float) target.y() - (float) origin.y(),
        s = x * x + y * y,
        inv = s == 0 ? 0 : 1 / (float) Math.sqrt(s);
    x *= inv;
    y *= inv;
    if (x == 0 && y == 0) return 0;
    float a = (float) (Math.atan2(y, x) * 180 / Math.PI);
    return a < 0 ? a + 360 : a;
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
              .filter(
                  s ->
                      s.startsWith("RESULT ")
                          || s.startsWith("STATE ")
                          || s.startsWith("EA ")
                          || s.startsWith("DRAWS "))
              .toList();
      if (lines.size() % 4 != 0) throw new AssertionError("Incomplete native result");
      var records = new ArrayList<Native>();
      for (int i = 0; i < lines.size(); i += 4)
        records.add(new Native(lines.get(i), lines.get(i + 1), lines.get(i + 2), lines.get(i + 3)));
      return records;
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  private static final class World implements TraceWorld {
    int mode, entity = 1022, calls, contents;
    float floor;

    public TraceResult trace(TraceRequest request) {
      calls++;
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
