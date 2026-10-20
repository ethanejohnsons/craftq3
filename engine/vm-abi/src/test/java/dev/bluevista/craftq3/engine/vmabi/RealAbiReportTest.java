package dev.bluevista.craftq3.engine.vmabi;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import dev.bluevista.craftq3.engine.vm.QvmImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * Reports, for every {@code vm/*.qvm} in the user's installation, the detected ABI profile, how it
 * was chosen and any imported trap numbers that profile's table does not know. The report goes to
 * {@code build/reports/vm-abi/abi-report.txt} and standard output; only file names, profiles and
 * trap numbers are printed, never game data. The effective builds (the ones the original search
 * order runs) must be fully covered.
 */
@RealData
final class RealAbiReportTest {
  private record Build(String source, String pack, String entry, byte[] bytes) {}

  /** Pack (relative to the install root) whose modules must be fully covered, and the profile. */
  private static final Map<String, AbiProfile> EFFECTIVE = new LinkedHashMap<>();

  static {
    EFFECTIVE.put("baseq3/pak8.pk3", AbiProfile.Q3_132);
    EFFECTIVE.put("baseq3/pak0.pk3", AbiProfile.RETAIL_1999);
    EFFECTIVE.put("cpma/cpma/z-cpma-pak146.pk3", AbiProfile.Q3_132);
    EFFECTIVE.put("jailbreak/q3jb127.pk3", AbiProfile.Q3_132);
    EFFECTIVE.put("truecombat/pak2.pk3", AbiProfile.Q3_132);
    EFFECTIVE.put("combat/combat_qvm.pk3", AbiProfile.Q3_132);
    EFFECTIVE.put("q3rally/qvms.pk3", AbiProfile.Q3_132);
    EFFECTIVE.put("alternatefire/z-alternatefire-03g.pk3", AbiProfile.Q3_132);
    EFFECTIVE.put("sha/sha0.pk3", AbiProfile.Q3_132);
    EFFECTIVE.put("q3pinball/pak0.pk3", AbiProfile.Q3_132);
    EFFECTIVE.put("CorkScrew/CorkScrew_216.pk3", AbiProfile.Q3_132);
  }

  @Test
  void reportsTheProfileAndUnknownImportsOfEveryBuild() throws IOException {
    Path root = RealDataRoot.require();
    List<Build> builds = collect(root);
    assertFalse(builds.isEmpty(), "no QVMs found under " + root);
    StringBuilder report = new StringBuilder();
    report.append(
        String.format(
            Locale.ROOT,
            "%-55s %-7s %-7s %-10s %s%n",
            "build",
            "module",
            "profile",
            "basis",
            "unknown imports (detail)"));
    List<String> failures = new ArrayList<>();
    Map<String, Integer> effectiveModules = new LinkedHashMap<>();
    for (Build build : builds) {
      QvmImage image = QvmImage.parse(build.entry(), build.bytes());
      AbiDetector.Detection detection = AbiDetector.analyze(image, build.bytes());
      report.append(
          String.format(
              Locale.ROOT,
              "%-55s %-7s %-7s %-10s %s (%s; %d imports)%n",
              build.source(),
              detection.module().moduleName(),
              detection.profile().key(),
              detection.basis(),
              detection.unknownImports(),
              detection.reason(),
              detection.imports().size()));
      // The import usage profile alone must agree with the hash table, so detection does not
      // depend on hashes for the verified builds either.
      AbiProfile structural = AbiDetector.analyze(image, null).profile();
      if (detection.basis() == AbiDetector.Basis.HASH && structural != detection.profile())
        failures.add(build.source() + " structural detection gives " + structural);
      AbiProfile expected = EFFECTIVE.get(build.pack());
      if (expected == null) continue;
      effectiveModules.merge(build.pack(), 1, Integer::sum);
      if (detection.profile() != expected)
        failures.add(
            build.source() + " detected " + detection.profile() + ", expected " + expected);
      if (!detection.unknownImports().isEmpty())
        failures.add(build.source() + " imports unknown " + detection.unknownImports());
    }
    for (String pack : EFFECTIVE.keySet())
      if (Files.exists(root.resolve(pack)) && !effectiveModules.containsKey(pack))
        failures.add(pack + " holds no QVM");
    Path out = Path.of("build/reports/vm-abi/abi-report.txt");
    Files.createDirectories(out.getParent());
    Files.writeString(out, report, StandardCharsets.UTF_8);
    System.out.print(report);
    assertTrue(failures.isEmpty(), String.join("\n", failures));
  }

  private static List<Build> collect(Path root) throws IOException {
    List<Path> files;
    try (Stream<Path> walk = Files.walk(root)) {
      files = walk.filter(Files::isRegularFile).sorted().toList();
    }
    List<Build> builds = new ArrayList<>();
    for (Path file : files) {
      String relative = root.relativize(file).toString().replace('\\', '/');
      String lower = relative.toLowerCase(Locale.ROOT);
      if (lower.endsWith(".qvm")) {
        builds.add(
            new Build(relative, relative, file.getFileName().toString(), Files.readAllBytes(file)));
      } else if (lower.endsWith(".pk3") && Files.size(file) > 0) {
        try (ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.ISO_8859_1)) {
          for (var entries = zip.entries(); entries.hasMoreElements(); ) {
            var entry = entries.nextElement();
            String name = entry.getName().replace('\\', '/');
            String key = name.toLowerCase(Locale.ROOT);
            if (entry.isDirectory() || !key.startsWith("vm/") || !key.endsWith(".qvm")) continue;
            try (InputStream in = zip.getInputStream(entry)) {
              builds.add(new Build(relative + "!" + name, relative, name, in.readAllBytes()));
            }
          }
        } catch (ZipException unreadable) {
          // Unreadable PK3s are skipped, as the original engine skips them.
        }
      }
    }
    return builds;
  }
}
