import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.assets.aas.AasReader;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipFile;

/** Optional read-only corpus audit. Run with Java 25 and the assets/core main classes on classpath. */
class AuditAas {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Usage: AuditAas.java /path/to/pak0.pk3");
    Map<Integer, Integer> versions = new TreeMap<>(), travel = new TreeMap<>();
    long[] totals = new long[AasMap.LumpKind.values().length];
    int passed = 0, failed = 0;
    try (ZipFile archive = new ZipFile(Path.of(args[0]).toFile())) {
      var entries = archive.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        if (!entry.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".aas")) continue;
        try (var input = archive.getInputStream(entry)) {
          AasMap map = AasReader.read(input.readNBytes(AasReader.MAX_BYTES + 1));
          versions.merge(map.version(), 1, Integer::sum);
          for (var lump : map.lumps()) totals[lump.kind().ordinal()] += lump.count();
          for (var reach : map.reachabilities()) travel.merge(reach.baseTravelType(), 1, Integer::sum);
          passed++;
          System.out.printf("PASS %s version=%d areas=%d reaches=%d clusters=%d%n", entry.getName(), map.version(), map.areas().size(), map.reachabilities().size(), map.clusters().size());
        } catch (Exception failure) {
          failed++;
          System.out.printf("FAIL %s: %s%n", entry.getName(), failure.getMessage());
        }
      }
    }
    System.out.printf("AAS passed=%d failed=%d versions=%s%n", passed, failed, versions);
    for (var kind : AasMap.LumpKind.values()) System.out.printf("%s=%d%n", kind, totals[kind.ordinal()]);
    System.out.println("Travel types=" + travel);
    if (failed != 0) System.exit(1);
  }
}
