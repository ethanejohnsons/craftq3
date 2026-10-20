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
      Path root = Files.createDirectories(scratch.resolve("cpma"));
      Files.createSymbolicLink(root.resolve("baseq3"), install.resolve("baseq3").toRealPath());
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
}
