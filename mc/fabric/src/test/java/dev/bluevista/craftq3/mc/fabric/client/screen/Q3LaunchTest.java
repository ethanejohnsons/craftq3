package dev.bluevista.craftq3.mc.fabric.client.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.mc.fabric.config.CraftQ3Config;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Q3LaunchTest {
  @Test
  void commandsAreOneConsoleLine() {
    assertEquals(Optional.empty(), Q3Launch.menu().command());
    assertEquals(Optional.of("map q3dm17"), Q3Launch.map(" q3dm17 ").command());
    assertEquals(
        Optional.of("connect 10.0.0.2:27961"), Q3Launch.connect("10.0.0.2:27961").command());
    assertEquals(Optional.of("connect [::1]:27960"), Q3Launch.connect("[::1]:27960").command());
    assertEquals(Optional.of("demo four"), Q3Launch.demo("four").command());
  }

  @Test
  void argumentsCannotSmuggleCommands() {
    assertThrows(IllegalArgumentException.class, () -> Q3Launch.map("q3dm17;quit"));
    assertThrows(IllegalArgumentException.class, () -> Q3Launch.map("q3dm17 quit"));
    assertThrows(IllegalArgumentException.class, () -> Q3Launch.demo("a\"b"));
    assertThrows(IllegalArgumentException.class, () -> Q3Launch.connect("host\nquit"));
    assertThrows(IllegalArgumentException.class, () -> Q3Launch.map("  "));
  }

  @Test
  void missingInstallationsAreExplained(@TempDir Path config) throws IOException {
    CraftQ3Config none = CraftQ3Config.defaults(config);
    assertTrue(Q3Launcher.problem(none).orElseThrow().contains("No Quake III installation"));

    Path root = config.resolve("Quake III Arena");
    CraftQ3Config configured = none.withInstallation(root.toString());
    assertTrue(Q3Launcher.problem(configured).orElseThrow().contains("does not exist"));

    Files.createDirectories(root);
    assertTrue(Q3Launcher.problem(configured).orElseThrow().contains("no baseq3"));

    Path base = Files.createDirectories(root.resolve("baseq3"));
    assertTrue(Q3Launcher.problem(configured).orElseThrow().contains("no PK3"));

    Files.write(base.resolve("PAK0.PK3"), new byte[0]);
    assertEquals(Optional.empty(), Q3Launcher.problem(configured));
    // A baseq3 directory typed directly means its parent.
    assertEquals(Optional.empty(), Q3Launcher.problem(none.withInstallation(base.toString())));

    assertTrue(
        Q3Launcher.problem(configured.withGame("cpma")).orElseThrow().contains("cpma is missing"));
    Files.createDirectories(root.resolve("cpma"));
    assertEquals(Optional.empty(), Q3Launcher.problem(configured.withGame("cpma")));
  }
}
