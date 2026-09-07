import dev.bluevista.craftq3.assets.aas.*;
import dev.bluevista.craftq3.botlib.aas.*;
import dev.bluevista.craftq3.botlib.movement.*;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

/** Read-only native airborne jump-pad contact differential; physics output is not compared. */
class AuditJumpPadContacts {
  private record Query(Vec3 origin, Vec3 velocity, Optional<JumpPadContact.Contact> expected) {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 3) throw new IllegalArgumentException("AuditJumpPadContacts <PK3> <oracle> [queries/map]");
    int count = args.length == 3 ? Integer.parseInt(args[2]) : 1000;
    if (count < 1 || count > 10000) throw new IllegalArgumentException("Invalid count");
    int maps = 0, compared = 0, differences = 0, grounded = 0, ladder = 0, found = 0;
    long start = System.nanoTime();
    try (var zip = new ZipFile(Path.of(args[0]).toFile())) {
      for (var entry : zip.stream().filter(e -> e.getName().startsWith("maps/") && e.getName().endsWith(".aas")).sorted(Comparator.comparing(java.util.zip.ZipEntry::getName)).toList()) {
        byte[] bytes; try (var input = zip.getInputStream(entry)) { bytes = input.readNBytes(AasReader.MAX_BYTES + 1); }
        var map = AasReader.read(bytes); var navigation = new AasNavigation(map); var contacts = new JumpPadContact(map);
        var pads = new ArrayList<Integer>();
        for (int i = 1; i < map.areas().size(); i++) if ((map.areaSettings().get(i).contents() & 128) != 0) pads.add(i);
        String name = entry.getName().substring(5, entry.getName().length() - 4);
        Random random = new Random(23197); var queries = new ArrayList<Query>(); var commands = new StringBuilder();
        for (int i = 0; i < count; i++) {
          Vec3 origin, velocity;
          int area = i % 3 != 1 && !pads.isEmpty() ? pads.get(random.nextInt(pads.size())) : 1 + random.nextInt(map.areas().size() - 1);
          Vec3 center = map.areas().get(area).center();
          if (i % 3 == 2) {
            var offset = new Vec3(random.nextFloat() * 128 - 64, random.nextFloat() * 128 - 64, random.nextFloat() * 256);
            origin = floats(center.add(offset)); velocity = new Vec3((float) offset.x() * 5, (float) offset.y() * 5, (float) offset.z() * 5);
          } else {
            origin = floats(center); velocity = new Vec3(random.nextFloat() * 1600 - 800, random.nextFloat() * 1600 - 800, random.nextFloat() * 1600 - 800);
          }
          int point = navigation.pointArea(origin);
          if (point != 0 && (map.areaSettings().get(point).flags() & 2) != 0) { ladder++; continue; }
          queries.add(new Query(origin, velocity, contacts.find(origin, velocity)));
          commands.append("contact "); point(commands, origin); point(commands, velocity); commands.append('\n');
        }
        var answers = oracle(args[1], args[0], name, commands.toString());
        if (answers.size() != queries.size()) throw new AssertionError("Oracle count " + name);
        int mapCompared = 0, mapDiff = 0, mapGround = 0;
        for (int i = 0; i < answers.size(); i++) {
          String[] fields = answers.get(i).split(" ");
          if ((Integer.parseInt(fields[1]) & 2) != 0) { grounded++; mapGround++; continue; }
          int reach = Integer.parseInt(fields[3]), area = Integer.parseInt(fields[2]);
          Optional<JumpPadContact.Contact> actual = reach == 0 ? Optional.empty() : Optional.of(new JumpPadContact.Contact(area, reach));
          var query = queries.get(i);
          if (!actual.equals(query.expected())) {
            if (differences++ < 15) System.out.println("DIFF " + name + " " + query + " native=" + actual);
            mapDiff++;
          }
          if (actual.isPresent()) found++;
          compared++; mapCompared++;
        }
        maps++; System.out.println(name + " compared=" + mapCompared + " grounded=" + mapGround + " differences=" + mapDiff);
      }
    }
    System.out.printf("Jump-pad contacts maps=%d compared=%d contacts=%d grounded=%d ladder=%d differences=%d seconds=%.3f%n", maps, compared, found, grounded, ladder, differences, (System.nanoTime() - start) / 1e9);
    if (differences != 0) throw new AssertionError("Jump-pad contact differences " + differences);
  }

  private static Vec3 floats(Vec3 p) { return new Vec3((float) p.x(), (float) p.y(), (float) p.z()); }
  private static void point(StringBuilder out, Vec3 p) { out.append((float) p.x()).append(' ').append((float) p.y()).append(' ').append((float) p.z()).append(' '); }
  private static List<String> oracle(String executable, String pk3, String map, String commands) throws Exception {
    var process = new ProcessBuilder(executable, pk3, map).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var output = executor.submit(() -> { byte[] bytes = process.getInputStream().readNBytes(8 * 1024 * 1024 + 1); if (bytes.length > 8 * 1024 * 1024) throw new IllegalStateException("Oracle output budget"); return new String(bytes, StandardCharsets.US_ASCII); });
      try (var input = process.getOutputStream()) { input.write(commands.getBytes(StandardCharsets.US_ASCII)); }
      if (!process.waitFor(60, TimeUnit.SECONDS)) throw new IllegalStateException("Oracle timeout");
      String text = output.get(5, TimeUnit.SECONDS);
      if (process.exitValue() != 0) throw new IllegalStateException("Oracle failed " + process.exitValue());
      return text.lines().filter(line -> line.startsWith("CONTACT ")).toList();
    } finally { if (process.isAlive()) process.destroyForcibly(); }
  }
}
