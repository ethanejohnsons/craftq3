package dev.bluevista.craftq3.fabric.building;

import java.io.IOException;
import java.nio.file.*;
import java.util.Properties;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;

/** Explicit development-only crash/reload audit confined to the private bridge QA world. */
public final class BuildingRecoverySmoke {
  private static boolean checkpointed, loadedInBuild;

  private BuildingRecoverySmoke() {}

  private static String mode() {
    if (!FabricLoader.getInstance().isDevelopmentEnvironment()) return "";
    return System.getProperty("craftq3.buildRecoverySmoke", "");
  }

  public static boolean enabled() {
    return !mode().isEmpty();
  }

  public static boolean verifying() {
    return Set.of("recover-crash", "recover", "reload", "reopen").contains(mode());
  }

  private static Path root(ServerPlayer player) {
    var root =
        player.level().getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
    if (!root.getFileName().toString().equals("CraftQ3 Bridge QA")
        || !"CraftQ3 Bridge QA".equals(System.getProperty("craftq3.bridgeSmokeWorld")))
      throw new IllegalStateException("Recovery smoke requires the private QA world");
    return root;
  }

  public static void seed(ServerPlayer player) {
    if (!enabled() || verifying()) return;
    root(player);
    if (!Set.of("checkpoint", "leave-crash", "quit").contains(mode()))
      throw new IllegalArgumentException("Unknown recovery smoke mode: " + mode());
    player.setGameMode(GameType.ADVENTURE);
    var abilities = player.getAbilities();
    abilities.setFlyingSpeed(.073f);
    abilities.setWalkingSpeed(.113f);
    player.onUpdateAbilities();
  }

  public static void checkpoint(Minecraft client, BuildingSession session) {
    if (checkpointed || verifying()) return;
    checkpointed = true;
    var host = client.getSingleplayerServer();
    var id = client.player.getUUID();
    host.execute(
        () -> {
          var player = host.getPlayerList().getPlayer(id);
          var directory = root(player).resolve("craftq3");
          try {
            if (!player.level().dimension().equals(BuildingSession.DIMENSION))
              throw new IllegalStateException("Checkpoint is outside the build dimension");
            Files.copy(
                BuildReturnState.path(player),
                directory.resolve("recovery-expected.properties"),
                StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(
                directory.resolve("recovery-player.txt"), player.getUUID().toString());
            if (!host.saveEverything(false, true, true))
              throw new IllegalStateException("Minecraft checkpoint save failed");
            if (mode().equals("leave-crash")) {
              session
                  .leave()
                  .whenComplete(
                      (unused, failure) -> {
                        if (failure != null)
                          throw new IllegalStateException("Pre-crash leave failed", failure);
                        try {
                          assertRestored(player);
                          if (!Files.isRegularFile(BuildReturnState.path(player)))
                            throw new IllegalStateException(
                                "Leave discarded the journal before a confirmed saved return");
                          finish(player, "leave-crash restored=true journal=true", true);
                        } catch (IOException e) {
                          throw new java.io.UncheckedIOException(e);
                        }
                      });
            } else
              finish(player, mode() + " build-saved=true journal=true", !mode().equals("quit"));
          } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
        });
  }

  public static void beforeJoin(ServerPlayer player) throws IOException {
    if (!verifying()) return;
    var directory = root(player).resolve("craftq3");
    if (!Files.readString(directory.resolve("recovery-player.txt"))
        .equals(player.getUUID().toString()))
      throw new IOException("Recovery QA identity changed between processes");
    loadedInBuild = player.level().dimension().equals(BuildingSession.DIMENSION);
    if (!mode().equals("reopen") && loadedInBuild == mode().equals("reload"))
      throw new IOException(
          "Unexpected persisted dimension before recovery: " + player.level().dimension());
    if (!Files.isRegularFile(BuildReturnState.path(player)))
      throw new IOException("Return journal missing at fresh load");
  }

  public static void afterJoin(ServerPlayer player) throws IOException {
    if (!verifying()) return;
    assertRestored(player);
    boolean retained = Files.isRegularFile(BuildReturnState.path(player));
    if (retained != loadedInBuild) throw new IOException("Incorrect recovery journal lifecycle");
    finish(
        player,
        mode() + " loaded-build=" + loadedInBuild + " restored=true journal=" + retained,
        mode().equals("recover-crash"));
  }

  private static void assertRestored(ServerPlayer player) throws IOException {
    var expected = new Properties();
    try (var reader =
        Files.newBufferedReader(root(player).resolve("craftq3/recovery-expected.properties"))) {
      expected.load(reader);
    }
    var actual = BuildReturnState.capture(player);
    if (expected.size() != 14 || !expected.equals(actual))
      throw new IOException("Return state mismatch: expected=" + expected + " actual=" + actual);
  }

  private static void finish(ServerPlayer player, String result, boolean halt) throws IOException {
    root(player); // Recheck before the deliberate process termination.
    String line = "PASS recovery " + result + "\n";
    Files.writeString(
        Minecraft.getInstance().gameDirectory.toPath().resolve("craftq3-bridge.result"), line);
    System.out.print(line);
    if (halt) Runtime.getRuntime().halt(0);
    else Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
  }
}
