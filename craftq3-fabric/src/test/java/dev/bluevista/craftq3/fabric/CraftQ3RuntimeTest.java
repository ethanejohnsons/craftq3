package dev.bluevista.craftq3.fabric;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CraftQ3RuntimeTest {
  @TempDir Path root;

  @Test
  void failedGameAndMapLoadsPreserveWorkingState() throws Exception {
    Path file = root.resolve("config/craftq3.properties"), games = root.resolve("games");
    try (var runtime = new CraftQ3Runtime(file, games)) {
      runtime.reload();
      Files.createDirectories(games.resolve("baseq3/maps"));
      Files.write(games.resolve("baseq3/maps/test.bsp"), BspFixture.map(true));
      runtime.reload();
      assertEquals(128, runtime.loadMap("test").triangles().size());
      String config = Files.readString(file), bsp = runtime.bspStatus();
      assertThrows(java.io.IOException.class, () -> runtime.game("missing"));
      assertEquals(config, Files.readString(file));
      assertEquals(bsp, runtime.bspStatus());
      assertThrows(java.io.IOException.class, () -> runtime.loadMap("missing"));
      assertEquals(bsp, runtime.bspStatus());
      Files.createDirectories(games.resolve("mod"));
      runtime.game("mod");
      assertTrue(runtime.bspStatus().startsWith("No BSP"));
      assertTrue(runtime.which("maps/test.bsp").contains("baseq3"));
      assertTrue(Files.readString(file).contains("game=mod"));
    }
  }
}
