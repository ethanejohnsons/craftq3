package dev.bluevista.craftq3.fabric.game;

import java.io.IOException;
import java.nio.file.Files;
import net.fabricmc.loader.api.FabricLoader;

/** Explicit development completion: a clean Minecraft exit alone does not mean a smoke passed. */
public final class SmokeResult {
  private SmokeResult() {}

  public static void passed(String scenario, String screenshot) {
    if (!FabricLoader.getInstance().isDevelopmentEnvironment()) return;
    if (!scenario.matches("[a-z]+") || !screenshot.matches("[a-z0-9._-]+\\.png"))
      throw new IllegalArgumentException("Invalid capture result name");
    var game = FabricLoader.getInstance().getGameDir();
    try {
      var image = game.resolve("screenshots").resolve(screenshot);
      if (!Files.isRegularFile(image)
          || Files.size(image) == 0
          || Files.getLastModifiedTime(image).toMillis()
              < java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime())
        throw new IOException("Screenshot was not saved: " + screenshot);
      try (var input = Files.newInputStream(image)) {
        if (!java.util.Arrays.equals(
            input.readNBytes(8), new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}))
          throw new IOException("Capture is not a PNG: " + screenshot);
      }
      var directory = game.resolve("craftq3-smoke");
      Files.createDirectories(directory);
      Files.writeString(directory.resolve(scenario + ".result"), "PASS\n" + screenshot + "\n");
    } catch (IOException failure) {
      dev.bluevista.craftq3.fabric.CraftQ3Client.LOGGER.error(
          "Could not record smoke completion", failure);
    }
  }
}
