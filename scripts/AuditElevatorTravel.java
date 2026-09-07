import dev.bluevista.craftq3.assets.aas.*;
import dev.bluevista.craftq3.assets.bsp.*;
import dev.bluevista.craftq3.botlib.aas.*;
import dev.bluevista.craftq3.botlib.ea.*;
import dev.bluevista.craftq3.botlib.movement.*;
import dev.bluevista.craftq3.collision.*;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

/**
 * Authored elevator comparison with synthetic type11 reaches and AAS. Reads original model metadata directly without extracting assets; no original map contains an elevator reach.
 */
class AuditElevatorTravel {
  static final Vec3 ZERO = new Vec3(0, 0, 0), SEED = new Vec3(.25, -.5, .125);
  static final int[] FLAGS = {0, 1, 2, 4, 6, 8, 16, 32, 64, 128, 256, 512, 514, 1023};
  static int calls, forced, queryHash;
  static void hash(int value) { queryHash = (queryHash ^ value) * 16777619; }
  static void hash(Vec3 value) { hash(Float.floatToRawIntBits((float)value.x()));hash(Float.floatToRawIntBits((float)value.y()));hash(Float.floatToRawIntBits((float)value.z())); }
  static float fraction;
  static int solids;

  static TraceResult trace(TraceRequest r, int kind, int entity) {
    calls++; hash(kind);hash(r.start());hash(r.mins());hash(r.maxs());hash(r.end());hash(entity);hash(r.contentsMask());
    if (calls != forced) return TraceResult.clear(r);
    Vec3 end =
        new Vec3(
            (float) r.start().x() + fraction * ((float) r.end().x() - (float) r.start().x()),
            (float) r.start().y() + fraction * ((float) r.end().y() - (float) r.start().y()),
            (float) r.start().z() + fraction * ((float) r.end().z() - (float) r.start().z()));
    return new TraceResult(
        fraction,
        end,
        (solids & 1) != 0,
        (solids & 2) != 0,
        Optional.of(
            new TraceResult.Hit(
                new TraceResult.Plane(new Vec3(0, 0, 1), 0), 1, 0, 180, 0, -1, -1, -1, "")));
  }

  record Expected(ElevatorMovement.Output output, BotInput ea, int count, int hash, String replay) {}

  public static void main(String[] args) throws Exception {
    int count = args.length > 2 ? Integer.parseInt(args[2]) : 1000;
    boolean fixture = true;
    boolean boundary = Boolean.getBoolean("boundary");
    boolean waitingBoundary = Boolean.getBoolean("craftq3.audit.waitingBoundary");
    boolean finish = args.length > 3 && args[3].equals("finish");
    var expected = new ArrayList<Expected>();
    StringBuilder commands = new StringBuilder("easeed .25 -.5 .125 123\nhistory 17 29\nstateseed 1\n");
    try (var zip = new ZipFile(args[0])) {
      int originalMaps=0, elevatorReaches=0;
      for (var entry: zip.stream().filter(e->e.getName().startsWith("maps/") && e.getName().endsWith(".aas")).toList()) {
        try (var stream=zip.getInputStream(entry)) {
          var original=AasReader.read(stream.readAllBytes()); originalMaps++;
          elevatorReaches+=original.reachabilities().stream().filter(r->r.baseTravelType()==11).count();
        }
      }
      System.out.println("Input AAS maps="+originalMaps+" type11Reaches="+elevatorReaches+"; queries use authored type11 reaches");
      var map = AasReader.read(zip.getInputStream(zip.getEntry("maps/q3dm19.aas")).readAllBytes());
      var bsp = BspReader.read(zip.getInputStream(zip.getEntry("maps/q3dm19.bsp")).readAllBytes());
      var nav = new AasNavigation(fixture ? fixtureMap() : map);
      if (fixture) commands.append("fixture 6\nrouting 1\n");
      TraceWorld collision =
          new TraceWorld() {
            public TraceResult trace(TraceRequest r) {
              return AuditElevatorTravel.trace(r, 0, r.ignoreEntity());
            }

            public int pointContents(Vec3 p, int m, int e) {
              return 0;
            }
          };
      var entities = new HashMap<Integer, MoverQueries.Entity>();
      var activeBounds = new MoverQueries.ModelBounds[] {new MoverQueries.ModelBounds(ZERO, ZERO)};
      var movers =
          new MoverQueries(
              collision,
              m -> {
                hash(2);hash(m);hash(ZERO);return activeBounds[0];
              },
              e -> Optional.ofNullable(entities.get(e)),
              1024);
      var world = new AasMovementWorld(nav, (e, r) -> trace(r, 1, e), p -> 0);
      var obstacles = new MovementObstacles(world);
      var helper =
          new ElevatorMovement(
              movers,
              model -> {
                hash(2);hash(model);hash(ZERO);return activeBounds[0];
              },
              new MovementObstruction(
                  collision, a -> 1),
              obstacles::barrierJump);
      var reaches = map.reachabilities().stream().filter(r -> r.baseTravelType() == 19).toList();
      var random = new Random(Long.getLong("seed",1377));
      try (var ea = new ElementaryActions(1, (c, s) -> {})) {
        for (int i = 0; i < count; i++) {
          var reach = reaches.get(random.nextInt(reaches.size()));
          int index = map.reachabilities().indexOf(reach), model = reach.face() & 65535;
          float lowX=random.nextFloat()*2000-1000, lowY=random.nextFloat()*2000-1000, lowZ=random.nextFloat()*2000-1000;
          var b = new MoverQueries.ModelBounds(new Vec3(lowX,lowY,lowZ),new Vec3(lowX+random.nextFloat()*200+.01f,lowY+random.nextFloat()*200+.01f,lowZ+random.nextFloat()*200+.01f));
          activeBounds[0]=b;
          int axis =
              fixture
                  ? i % 3
                  : (reach.face() & 65536) != 0 ? 0 : (reach.face() & 131072) != 0 ? 1 : 2;
          float[] center = {
            ((float) b.min().x() + (float) b.max().x()) * .5f,
            ((float) b.min().y() + (float) b.max().y()) * .5f,
            ((float) b.min().z() + (float) b.max().z()) * .5f
          };
          if (fixture)
            reach =
                new AasMap.Reachability(
                    1,
                    model | (axis == 0 ? 65536 : axis == 1 ? 131072 : 0),
                    ((short) (center[axis] - 100) << 16) | ((short) (center[axis] + 100) & 65535),
                    new Vec3(
                        center[0] - 80, center[1] + random.nextFloat() * 100 - 50, center[2] + random.nextFloat()*200-100),
                    new Vec3(center[0] + random.nextFloat()*160-80, center[1] + random.nextFloat()*160-80, center[2] + random.nextFloat()*400-200),
                    11,
                    100,
                    0);
          if (boundary) {
            reach = new AasMap.Reachability(1,reach.face(),reach.edge(), new Vec3(center[0]-80,center[1], center[2]+24), new Vec3(center[0]+80, center[1],center[2]+124),11,100,0);
          }
          float start = (short) (reach.edge() >>> 16), end = (short) reach.edge();
          float phase = start + (end - start) * (random.nextFloat() * 1.4f - .2f);
          switch (i % 12) {
            case 0 -> phase = start;
            case 1 -> phase = start + 16;
            case 2 -> phase = end;
            case 3 -> phase = end + 16;
            case 4 -> phase = end + 24;
          }
          if (i % 24 == 6) phase = start;
          if (waitingBoundary) phase = start + 32;
          float[] offset = {0, 0, 0};
          offset[axis] = phase - center[axis];
          Vec3 originOffset = new Vec3(offset[0], offset[1], offset[2]);
          entities.put(180, new MoverQueries.Entity(4, model, originOffset));
          world.clear();
          world.link(180, add(b.min(), originOffset), add(b.max(), originOffset));
          Vec3 current = add(new Vec3(center[0], center[1], center[2]), originOffset);
          Vec3 origin =
              add(
                  reach.start(),
                  new Vec3(
                      random.nextFloat() * 200 - 100,
                      random.nextFloat() * 200 - 100,
                      random.nextFloat() * 100 - 50));
          switch (i % 8) {
            case 0 -> origin = reach.start();
            case 1 -> origin = reach.end();
            case 2 ->
                origin =
                    add(
                        reach.end(),
                        new Vec3(
                            random.nextFloat() * 80 - 40,
                            random.nextFloat() * 80 - 40,
                            random.nextFloat() * 80 - 40));
            case 3, 4 ->
                origin =
                    add(
                        current,
                        new Vec3(random.nextFloat() * 20 - 10, random.nextFloat() * 20 - 10, 24));
            case 5 -> origin = add(current, new Vec3(5, 10, 24));
            case 6 -> origin = add(current, new Vec3(0, 0, 24));
          }
          if (waitingBoundary) {
            origin = add(reach.start(), new Vec3(random.nextFloat() * 2 - 1,
                random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1));
          }
          if (boundary) {
            float[] tiny = {0,.5f,.8333333f,Math.nextDown(5f/6),Math.nextUp(5f/6),1,Math.nextDown(64f),64,Math.nextUp(64f)};
            int group = i%4;
            if (group == 0) origin = add(reach.end(), new Vec3(tiny[(i/4)%tiny.length],0,0));
            if (group == 1) origin = add(reach.start(), new Vec3(-tiny[(i/4)%tiny.length],0,0));
            if (group == 2) { float[] heights={Math.nextDown(32f),32,Math.nextUp(32f),-32,Math.nextDown(-32f),Math.nextUp(-32f)}; origin = new Vec3(current.x(), current.y(), (float)reach.end().z()+heights[(i/4)%heights.length]); }
            if (group == 3) { float midpoint=((float)reach.start().z()+(float)reach.end().z())*.5f; float[] heights={Math.nextDown(midpoint),midpoint,Math.nextUp(midpoint)}; origin = new Vec3(current.x(),current.y(),heights[(i/4)%3]); }
          }
          int flags = FLAGS[i % FLAGS.length], presence = i % 3 == 0 ? 4 : 2;
          var input =
              new MovementInit(
                  origin,
                  new Vec3(
                      random.nextFloat() * 1000 - 500,
                      random.nextFloat() * 1000 - 500,
                      random.nextFloat() * 1000 - 500),
                  ZERO,
                  i%64,
                  0,
                  new float[]{0,.001f,.1f,1,2,10,3600}[i%7],
                  presence,
                  ZERO,
                  0);
          forced = (i % 8 == 3 || i % 8 == 4 || i % 8 == 5) ? 1 : (i % 11 == 0 ? 2 : 0);
          fraction = i % 5 == 0 ? 1 : .5f;
          solids = i % 17 == 0 ? 1 : 0;
          if (fixture) {
            forced = 1 + i % 4;
            fraction = i % 7 == 0 ? 1 : i % 7 == 1 ? 0 : .25f;
            solids = i % 13 == 0 ? 3 : i % 13 == 1 ? 1 : 0;
          }
          if (boundary && i%4==2) { forced=1;solids=0;fraction=.5f;}
          int actions =
              i % 5 == 0 ? 8192 : i % 5 == 1 ? 16 : i % 5 == 2 ? 268435456 : i % 5 == 3 ? 32768 : 0;
          int commandOffset = commands.length();
          commands.append("box ");point(commands,b.min());point(commands,b.max());commands.append("\nmoveentity ").append(input.entity()).append("\n");
          commands
              .append("reachmeta ")
              .append(reach.face())
              .append(' ')
              .append(reach.edge())
              .append("\neaactions ")
              .append(actions)
              .append('\n');
          commands
              .append("selected ")
              .append(0)
              .append('\n')
              .append("reacharea ")
              .append(reach.area())
              .append('\n')
              .append("movepresence ")
              .append(presence)
              .append('\n')
              .append("entity 180 ")
              .append(model)
              .append(' ');
          point(commands, originOffset);
          commands.append("\nrepliesclear\n");
          if (forced != 0)
            commands
                .append("reply ")
                .append(forced)
                .append(' ')
                .append(fraction)
                .append(" 180 ")
                .append(solids)
                .append('\n');
          commands.append(finish ? "finish 11 " : "travel 11 ");
          point(commands, origin);
          point(commands, input.velocity());
          point(commands, reach.start());
          point(commands, reach.end());
          commands.append(input.thinkTime()).append(" ").append(flags).append('\n');
          calls = 0; queryHash = 0x811c9dc5;
          var output =
              finish
                  ? helper.finish(input, flags, nav.pointArea(origin), reach)
                  : helper.execute(input, flags, nav.pointArea(origin), reach);
          ea.clearClient(0);
          ea.move(0, SEED, 123);
          ea.action(0, actions);
          output.movement().ifPresent(m -> ea.move(0, m.direction(), m.speed()));
          if ((output.actionFlags() & 16) != 0) ea.jump(0);
          expected.add(
              new Expected(output, ea.getInput(0, .1f), calls, queryHash, commands.substring(commandOffset)));
        }
      }
    }
    List<String> actual = oracle(args[1], args[0], commands.toString());
    if (actual.size() != count) throw new AssertionError("Count " + actual.size());
    int differences = 0, jumps = 0, absent = 0;
    float maxError = 0;
    for (int i = 0; i < count; i++) {
      String[] f = actual.get(i).split(" ");
      var e = expected.get(i);
      if ((e.output.actionFlags() & 16) != 0) jumps++;
      if (e.output.movement().isEmpty()) absent++;
      boolean deadlineDiff = Float.parseFloat(f[25].substring(9)) != (e.output.clearReachDeadline() ? 0 : 77);
      var r = e.output.result();
      var a = e.ea;
      int[] ints = {
        r.failure(), r.type(), r.blocked(), r.blockEntity(), r.travelType(), r.flags(), r.weapon()
      };
      boolean diff = deadlineDiff || e.hash != (int) Long.parseLong(f[24].substring(6)) || Integer.parseInt(f[22]) != 29;
      for (int j = 0; j < ints.length; j++) diff |= ints[j] != Integer.parseInt(f[j + 1]);
      diff |=
          a.actionFlags() != Integer.parseInt(f[19])
              || e.output.movementFlags() != Integer.parseInt(f[21])
              || e.count != Integer.parseInt(f[23].substring(7));
      float[] floats = {
        (float) r.direction().x(),
        (float) r.direction().y(),
        (float) r.direction().z(),
        (float) r.idealViewAngles().x(),
        (float) r.idealViewAngles().y(),
        (float) r.idealViewAngles().z(),
        (float) a.direction().x(),
        (float) a.direction().y(),
        (float) a.direction().z(),
        a.speed()
      };
      for (int j = 0; j < floats.length; j++) {
        float value = Float.parseFloat(f[j < 6 ? 8 + j : 15 + j - 6]);
        maxError = Math.max(maxError, Math.abs(value - floats[j]));
        diff |= Float.floatToIntBits(value) != Float.floatToIntBits(floats[j]);
      }
      if (diff && differences++ < 12)
        System.out.println(
            "DIFF "
                + i
                + " Java="
                + e.output
                + " EA="
                + e.ea
                + " count="
                + e.count
                + " native="
                + actual.get(i)
                + "\nREPLAY\n"
                + e.replay);
    }
    System.out.println(
        "Elevator "
            + (finish ? "finish" : "entry")
            + " queries="
            + count
            + " differences="
            + differences
            + " maxError="
            + maxError
            + " successfulBarriers="
            + jumps
            + " absentMoves="
            + absent);
    if (differences != 0) throw new AssertionError("Elevator differences");
  }

  static AasMap fixtureMap() {
    var empty = new AasMap.Indices(new int[0]);
    return new AasMap(
        4,
        0,
        List.of(),
        List.of(),
        List.of(),
        List.of(
            new AasMap.Plane(new Vec3(1, 0, 0), 0, 0), new AasMap.Plane(new Vec3(-1, 0, 0), 0, 0)),
        List.of(),
        empty,
        List.of(),
        empty,
        List.of(
            new AasMap.Area(0, 0, 0, ZERO, ZERO, ZERO), new AasMap.Area(1, 0, 0, ZERO, ZERO, ZERO)),
        List.of(
            new AasMap.AreaSettings(0, 0, 0, 0, 0, 0, 0),
            new AasMap.AreaSettings(0, 1, 6, 0, 0, 1, 0)),
        List.of(),
        List.of(new AasMap.Node(0, 0, 0), new AasMap.Node(0, -1, -1)),
        List.of(),
        empty,
        List.of());
  }

  static Vec3 add(Vec3 a, Vec3 b) {
    return new Vec3(
        (float) a.x() + (float) b.x(),
        (float) a.y() + (float) b.y(),
        (float) a.z() + (float) b.z());
  }

  static void point(StringBuilder s, Vec3 v) {
    s.append((float) v.x())
        .append(' ')
        .append((float) v.y())
        .append(' ')
        .append((float) v.z())
        .append(' ');
  }

  static List<String> oracle(String binary, String pk3, String commands) throws Exception {
    Process p =
        new ProcessBuilder(binary, pk3, "q3dm19")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var output =
          executor.submit(
              () ->
                  new String(
                      p.getInputStream().readNBytes(32 * 1024 * 1024), StandardCharsets.US_ASCII));
      try (var in = p.getOutputStream()) {
        in.write(commands.getBytes(StandardCharsets.US_ASCII));
      }
      if (!p.waitFor(60, TimeUnit.SECONDS)) throw new AssertionError("Timeout");
      String text = output.get();
      if (p.exitValue() != 0) throw new AssertionError("Native failure " + p.exitValue());
      long results = text.lines().filter(l -> l.startsWith("TRAVEL ")).count();
      if (text.lines().filter(l -> l.equals("EFFECTS 1 2 3 7")).count() != results)
        throw new AssertionError("Native changed seeded view/weapon");
      for (String line : text.lines().filter(l -> l.startsWith("MUTATIONS")).toList())
        for (String change : line.substring(9).trim().split(" "))
          if (!change.isEmpty() && !change.startsWith("96:") && !change.startsWith("112:"))
            throw new AssertionError("Unexpected native state mutation "+line);
      if (text.lines().filter(l -> l.equals("DRAWS 0")).count() != results)
        throw new AssertionError("Unexpected native random draw");
      var records = new ArrayList<String>();
      for (String line : text.lines().toList()) {
        if (line.startsWith("TRAVEL ")) records.add(line);
        if (line.startsWith("QHASH ")) records.set(records.size() - 1, records.getLast() + " qhash=" + line.substring(6));
        if (line.startsWith("DEADLINE "))
          records.set(records.size() - 1, records.getLast() + " deadline=" + line.substring(9));
      }
      return List.copyOf(records);
    } finally {
      if (p.isAlive()) p.destroyForcibly();
    }
  }
}
