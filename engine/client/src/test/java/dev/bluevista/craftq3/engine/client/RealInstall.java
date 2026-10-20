package dev.bluevista.craftq3.engine.client;

import dev.bluevista.craftq3.engine.fs.SearchPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The game builds of the user's installation the real-data tests run: baseq3 (pak8, and the retail
 * pak0 alone) and every mod's effective cgame and ui. Layouts the search path cannot mount as is
 * (pak0 alone, CPMA's nested game directory) are presented through a temporary root of symbolic
 * links; nothing is copied.
 */
final class RealInstall {
  record Build(String name, Path root, String game) {
    SearchPath mount() throws IOException {
      return SearchPath.mount(root, game);
    }
  }

  private RealInstall() {}

  static List<Build> builds(Path install, Path scratch) throws IOException {
    List<Build> builds = new ArrayList<>();
    builds.add(new Build("baseq3 (pak8)", install, "baseq3"));
    Path retail = Files.createDirectories(scratch.resolve("retail/baseq3"));
    Path pak0 = install.resolve("baseq3/pak0.pk3");
    if (Files.exists(pak0)) {
      Files.createSymbolicLink(retail.resolve("pak0.pk3"), pak0.toRealPath());
      builds.add(new Build("baseq3 (pak0 retail)", retail.getParent(), "baseq3"));
    }
    Path cpma = install.resolve("cpma/cpma");
    if (Files.isDirectory(cpma)) {
      // CPMA ships its maps in "cpma baseq3/"; they load once placed in baseq3, so baseq3 is
      // presented as links to its own packs plus those.
      Path root = Files.createDirectories(scratch.resolve("cpma"));
      Path base = Files.createDirectories(root.resolve("baseq3"));
      linkPacks(install.resolve("baseq3"), base);
      Path maps = install.resolve("cpma/cpma baseq3");
      if (Files.isDirectory(maps)) linkPacks(maps, base);
      Files.createSymbolicLink(root.resolve("cpma"), cpma.toRealPath());
      builds.add(new Build("cpma", root, "cpma"));
    }
    for (String mod :
        List.of(
            "jailbreak",
            "truecombat",
            "combat",
            "q3rally",
            "alternatefire",
            "sha",
            "CorkScrew",
            "q3pinball"))
      if (Files.isDirectory(install.resolve(mod))) builds.add(new Build(mod, install, mod));
    return builds;
  }

  private static void linkPacks(Path from, Path into) throws IOException {
    try (var files = Files.list(from)) {
      for (Path file : (Iterable<Path>) files::iterator) {
        String name = file.getFileName().toString();
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".pk3")) continue;
        if (!Files.isRegularFile(file) || Files.exists(into.resolve(name))) continue;
        Files.createSymbolicLink(into.resolve(name), file.toRealPath());
      }
    }
  }
}
