import dev.bluevista.craftq3.assets.aas.AasMap.Reachability;
import dev.bluevista.craftq3.assets.aas.AasReader;
import dev.bluevista.craftq3.botlib.aas.AasNavigation;
import dev.bluevista.craftq3.botlib.ea.BotInput;
import dev.bluevista.craftq3.botlib.ea.ElementaryActions;
import dev.bluevista.craftq3.botlib.movement.*;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

/** Authored native differential. BFG inputs may substitute type13 on original rocket geometry. */
class AuditWeaponJumpTravel {
  private static final int[] FLAGS = {0, 1, 2, 4, 6, 8, 16, 20, 32, 64, 128, 256, 512, 514, 1023};
  private static final int[] ACTIONS = {0, 8192, 16, 32768, 268435456, 268435472, 268468224};
  private static final Vec3 SEED_DIRECTION = new Vec3(.25, -.5, .125);
  private static final Vec3 SEED_VIEW = new Vec3(1, 2, 3);

  private record Expected(
      WeaponJumpMovement.Output output,
      BotInput ea,
      int flags,
      Reachability reach,
      String replay) {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 6)
      throw new IllegalArgumentException(
          "AuditWeaponJumpTravel <PK3> <oracle> [queries/map] [map|all] [entry|finish] [12|13]");
    int count = args.length > 2 ? Integer.parseInt(args[2]) : 100;
    String selected = args.length > 3 ? args[3] : "all", mode = args.length > 4 ? args[4] : "entry";
    int type = args.length > 5 ? Integer.parseInt(args[5]) : 12;
    if (count < 1
        || count > 10000
        || (!mode.equals("entry") && !mode.equals("finish"))
        || (type != 12 && type != 13)) throw new IllegalArgumentException("Invalid audit mode");
    boolean finish = mode.equals("finish");
    int maps = 0, total = 0, differences = 0, substitutedMaps = 0;
    float maxError = 0;
    long begin = System.nanoTime();
    try (var zip = new ZipFile(Path.of(args[0]).toFile())) {
      for (var entry :
          zip.stream()
              .filter(e -> e.getName().startsWith("maps/") && e.getName().endsWith(".aas"))
              .sorted(Comparator.comparing(java.util.zip.ZipEntry::getName))
              .toList()) {
        String name = entry.getName().substring(5, entry.getName().length() - 4);
        if (!selected.equals("all") && !selected.equals(name)) continue;
        byte[] bytes;
        try (var in = zip.getInputStream(entry)) {
          bytes = in.readNBytes(AasReader.MAX_BYTES + 1);
        }
        var map = AasReader.read(bytes);
        var nav = new AasNavigation(map);
        var reaches =
            map.reachabilities().stream().filter(r -> r.baseTravelType() == type).toList();
        boolean substitute = reaches.isEmpty() && type == 13;
        if (substitute)
          reaches = map.reachabilities().stream().filter(r -> r.baseTravelType() == 12).toList();
        if (reaches.isEmpty()) {
          System.out.println(name + " has no weapon-jump geometry");
          continue;
        }
        if (substitute) substitutedMaps++;
        var helper = new WeaponJumpMovement();
        var commands = new StringBuilder("easeed .25 -.5 .125 123\n");
        var expected = new ArrayList<Expected>();
        var random = new Random(139);
        try (var ea = new ElementaryActions(1, (c, s) -> {})) {
          for (int i = 0; i < count; i++) {
            Reachability original = reaches.get(random.nextInt(reaches.size()));
            var reach =
                new Reachability(
                    original.area(),
                    original.face(),
                    original.edge(),
                    original.start(),
                    original.end(),
                    type,
                    original.travelTime(),
                    original.reserved());
            Vec3 origin =
                reach
                    .start()
                    .add(
                        new Vec3(
                            random.nextFloat() * 180 - 90,
                            random.nextFloat() * 180 - 90,
                            random.nextFloat() * 64 - 32));
            if (i % 4 == 0) origin = reach.start();
            if (i % 4 == 1)
              origin =
                  reach
                      .start()
                      .add(
                          new Vec3(
                              random.nextFloat() * 6 - 3,
                              random.nextFloat() * 6 - 3,
                              random.nextFloat() * 400 - 200));
            float yaw = type == 12 ? yaw(origin, reach.start()) : 0;
            float pitch = type == 12 ? 90 : 0;
            int scenario = i % 16;
            if (scenario == 2 || scenario == 3 || scenario == 4)
              pitch += scenario == 2 ? 4.9f : scenario == 3 ? 5 : 5.1f;
            if (scenario == 5 || scenario == 6 || scenario == 7)
              yaw += scenario == 5 ? 4.9f : scenario == 6 ? 5 : 5.1f;
            if (scenario == 8) {
              pitch += 360;
              yaw -= 360;
            }
            if (scenario == 9) yaw += 720;
            if (scenario == 10) {
              pitch = random.nextFloat() * 720 - 360;
              yaw = random.nextFloat() * 1080 - 540;
            }
            var view = new Vec3(pitch, yaw, random.nextFloat() * 720 - 360);
            var input =
                new MovementInit(
                    origin,
                    new Vec3(
                        random.nextFloat() * 1000 - 500,
                        random.nextFloat() * 1000 - 500,
                        random.nextFloat() * 1000 - 500),
                    new Vec3(20, 30, 40),
                    0,
                    0,
                    .1f,
                    i % 3 == 0 ? 4 : 2,
                    view,
                    128);
            int flags = FLAGS[i % FLAGS.length],
                actions = ACTIONS[i % ACTIONS.length],
                last = map.reachabilities().indexOf(original),
                jump = i % 3 == 0 ? 0 : i % 3 == 1 ? 7 : last;
            if (last == 0) last = 1;
            var output =
                finish
                    ? helper.finish(input, flags, nav.pointArea(origin), reach, last, jump)
                    : helper.execute(input, flags, nav.pointArea(origin), reach, last, jump);
            ea.clearClient(0);
            ea.move(0, SEED_DIRECTION, 123);
            ea.view(0, SEED_VIEW);
            ea.selectWeapon(0, 7);
            ea.action(0, actions);
            output.movement().ifPresent(m -> ea.move(0, m.direction(), m.speed()));
            ea.action(0, output.actionFlags() & ~(16 | 32768));
            if ((output.actionFlags() & 16) != 0) ea.jump(0);
            if ((output.actionFlags() & 32768) != 0) ea.delayedJump(0);
            output.view().ifPresent(v -> ea.view(0, v));
            output.weapon().ifPresent(w -> ea.selectWeapon(0, w));
            int offset = commands.length();
            commands
                .append(i % 2 == 0 ? "clear\n" : "solid\n")
                .append("eaactions ")
                .append(actions)
                .append('\n')
                .append("reachstate ")
                .append(last)
                .append(' ')
                .append(jump)
                .append('\n')
                .append("reacharea ")
                .append(reach.area())
                .append('\n')
                .append("movepresence ")
                .append(input.presenceType())
                .append('\n')
                .append("viewoffset 20 30 40\nviewangles ");
            point(commands, view);
            commands.append('\n').append(finish ? "finish " : "travel ").append(type).append(' ');
            point(commands, origin);
            point(commands, input.velocity());
            point(commands, reach.start());
            point(commands, reach.end());
            commands.append(input.thinkTime()).append(' ').append(flags).append('\n');
            expected.add(
                new Expected(output, ea.snapshot(0), flags, reach, commands.substring(offset)));
          }
        }
        var actual = oracle(args[1], args[0], name, commands.toString());
        if (actual.size() != count)
          throw new AssertionError("Native count " + name + " " + actual.size());
        int different = 0;
        for (int i = 0; i < count; i++) {
          var e = expected.get(i);
          var r = e.output.result();
          var a = e.ea;
          String[] f = actual.get(i).split(" ");
          int[] ints = {
            r.failure(),
            r.type(),
            r.blocked(),
            r.blockEntity(),
            r.travelType(),
            r.flags(),
            r.weapon()
          };
          boolean diff = false;
          for (int j = 0; j < ints.length; j++) diff |= ints[j] != Integer.parseInt(f[j + 1]);
          diff |=
              a.actionFlags() != Integer.parseInt(f[19])
                  || e.flags != Integer.parseInt(f[21])
                  || e.output.jumpReach() != Integer.parseInt(f[22])
                  || Integer.parseInt(f[23].substring(7)) != 0
                  || Integer.parseInt(f[30]) != 0
                  || a.weapon() != Integer.parseInt(f[31]);
          float[] values = {
            (float) r.direction().x(),
            (float) r.direction().y(),
            (float) r.direction().z(),
            (float) r.idealViewAngles().x(),
            (float) r.idealViewAngles().y(),
            (float) r.idealViewAngles().z(),
            (float) a.direction().x(),
            (float) a.direction().y(),
            (float) a.direction().z(),
            a.speed(),
            (float) e.reach.start().x(),
            (float) e.reach.start().y(),
            (float) e.reach.start().z(),
            (float) e.reach.end().x(),
            (float) e.reach.end().y(),
            (float) e.reach.end().z(),
            (float) a.viewAngles().x(),
            (float) a.viewAngles().y(),
            (float) a.viewAngles().z()
          };
          for (int j = 0; j < values.length; j++) {
            int index = j < 6 ? 8 + j : j < 10 ? 15 + j - 6 : j < 16 ? 24 + j - 10 : 32 + j - 16;
            float n = Float.parseFloat(f[index]);
            maxError = Math.max(maxError, Math.abs(values[j] - n));
            diff |= Float.floatToIntBits(values[j]) != Float.floatToIntBits(n);
          }
          if (diff) {
            if (differences++ < 20)
              System.out.println(
                  "DIFF "
                      + name
                      + " "
                      + i
                      + " Java="
                      + e.output
                      + " EA="
                      + e.ea
                      + " native="
                      + actual.get(i)
                      + "\nREPRO\n"
                      + e.replay);
            different++;
          }
        }
        total += count;
        maps++;
        System.out.println(
            name
                + " requests="
                + count
                + " substitutedType13="
                + substitute
                + " differences="
                + different);
      }
    }
    System.out.printf(
        "Weapon jump mode=%s type=%d maps=%d substitutedMaps=%d requests=%d differences=%d"
            + " maxFloatError=%.9g seconds=%.3f%n",
        mode,
        type,
        maps,
        substitutedMaps,
        total,
        differences,
        maxError,
        (System.nanoTime() - begin) / 1e9);
    if (maps == 0 || differences != 0) throw new AssertionError("Weapon jump audit did not pass");
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

  private static void point(StringBuilder s, Vec3 p) {
    s.append((float) p.x())
        .append(' ')
        .append((float) p.y())
        .append(' ')
        .append((float) p.z())
        .append(' ');
  }

  private static List<String> oracle(String executable, String pk3, String map, String commands)
      throws Exception {
    Process p =
        new ProcessBuilder(executable, pk3, map)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var out =
          executor.submit(
              () -> {
                byte[] b = p.getInputStream().readNBytes(16 * 1024 * 1024 + 1);
                if (b.length > 16 * 1024 * 1024)
                  throw new IllegalStateException("Oracle output cap");
                return new String(b, StandardCharsets.US_ASCII);
              });
      try (var in = p.getOutputStream()) {
        in.write(commands.getBytes(StandardCharsets.US_ASCII));
      }
      if (!p.waitFor(60, TimeUnit.SECONDS)) throw new IllegalStateException("Oracle timeout");
      String text = out.get(5, TimeUnit.SECONDS);
      if (p.exitValue() != 0) throw new IllegalStateException("Oracle failure " + p.exitValue());
      var results = new ArrayList<String>();
      String pending = null;
      for (String line : text.lines().toList()) {
        if (line.startsWith("TRAVEL ")) pending = line;
        else if (line.startsWith("REACHAFT ") && pending != null)
          pending += " " + line.substring(9);
        else if (line.startsWith("DRAWS ") && pending != null) pending += " " + line.substring(6);
        else if (line.startsWith("EAEX ") && pending != null) {
          results.add(pending + " " + line.substring(5));
          pending = null;
        }
      }
      if (pending != null) throw new IllegalStateException("Incomplete native result");
      return List.copyOf(results);
    } finally {
      if (p.isAlive()) p.destroyForcibly();
    }
  }
}
