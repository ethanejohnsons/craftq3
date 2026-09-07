package dev.bluevista.craftq3.fabric.bridge;

import java.io.IOException;
import java.nio.file.*;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/** Forced-crash stages only in the explicitly selected private bridge QA save. */
public final class BridgeRecoverySmoke {
  private static boolean checkpointed, marked;

  private BridgeRecoverySmoke() {}

  private static String mode() {
    return FabricLoader.getInstance().isDevelopmentEnvironment()
        ? System.getProperty("craftq3.bridgeRecoverySmoke", "")
        : "";
  }

  public static boolean enabled() {
    return !mode().isEmpty();
  }

  public static boolean verifying() {
    return Set.of("recover-crash", "recover", "reload").contains(mode());
  }

  private static Path root(ServerPlayer player) {
    var path = BridgeReturnState.directory(player).getParent();
    if (!path.getParent()
        .toAbsolutePath()
        .normalize()
        .getFileName()
        .toString()
        .equals("CraftQ3 Bridge QA"))
      throw new IllegalStateException("Bridge recovery requires private QA save");
    return path;
  }

  public static void seed(ServerPlayer player) {
    if (mode().isEmpty() || verifying()) return;
    root(player);
    player.setGameMode(GameType.SURVIVAL);
    var abilities = player.getAbilities();
    abilities.setFlyingSpeed(.073f);
    abilities.setWalkingSpeed(.113f);
    player.onUpdateAbilities();
  }

  public static void checkpoint(Minecraft client, int frame) {
    if (mode().isEmpty() || verifying() || checkpointed || frame < 20) return;
    checkpointed = true;
    var server = client.getSingleplayerServer();
    var id = client.player.getUUID();
    server.execute(
        () -> {
          var player = server.getPlayerList().getPlayer(id);
          var marker = (BridgeReturnState.Marker) player;
          try {
            var root = root(player);
            String token = marker.craftq3$bridgeMarker();
            var record = BridgeReturnState.directory(player).resolve(token + ".properties");
            Files.copy(
                record,
                root.resolve("bridge-recovery-expected.properties"),
                StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(
                root.resolve("bridge-recovery-position.txt"),
                player.getX() + "," + player.getY() + "," + player.getZ());
            if (!server.saveEverything(false, true, true))
              throw new IOException("Bridge checkpoint save failed");
            if (mode().equals("leave-crash")) {
              BridgePlayerController.get(player).close();
              assertRestored(player);
            } else if (mode().equals("reentry-crash")) {
              BridgePlayerController.get(player).close();
              assertRestored(player);
              BridgePlayerController.begin(player);
              if (token.equals(marker.craftq3$bridgeMarker()) || !Files.exists(record))
                throw new IOException("Old bridge generation lost");
            } else if (!mode().equals("checkpoint"))
              throw new IOException("Unknown bridge recovery stage");
            finish(player, "saved-marker=true journal=true", true);
          } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
        });
  }

  public static void beforeJoin(ServerPlayer player) throws IOException {
    if (!verifying()) return;
    root(player);
    marked = !((BridgeReturnState.Marker) player).craftq3$bridgeMarker().isEmpty();
    if (marked == mode().equals("reload"))
      throw new IOException("Unexpected saved bridge marker: " + marked);
  }

  public static void afterJoin(ServerPlayer player) throws IOException {
    if (!verifying()) return;
    assertRestored(player);
    var expected =
        BridgeReturnState.read(root(player).resolve("bridge-recovery-expected.properties"));
    boolean journal =
        Files.exists(BridgeReturnState.directory(player).resolve(expected.token() + ".properties"));
    if (journal != marked) throw new IOException("Incorrect bridge recovery record lifecycle");
    var position =
        Files.readString(root(player).resolve("bridge-recovery-position.txt")).split(",");
    if (player
            .position()
            .distanceTo(
                new net.minecraft.world.phys.Vec3(
                    Double.parseDouble(position[0]),
                    Double.parseDouble(position[1]),
                    Double.parseDouble(position[2])))
        > .00001) throw new IOException("Bridge recovery changed saved position");
    finish(
        player,
        "loaded-marker=" + marked + " restored=true position=true journal=" + journal,
        mode().equals("recover-crash"));
  }

  private static void assertRestored(ServerPlayer player) throws IOException {
    var expected =
        BridgeReturnState.read(root(player).resolve("bridge-recovery-expected.properties"));
    if (!expected.state().equals(BridgeReturnState.capture(player))
        || !((BridgeReturnState.Marker) player).craftq3$bridgeMarker().isEmpty())
      throw new IOException(
          "Bridge player state not restored: " + BridgeReturnState.capture(player));
  }

  private static void finish(ServerPlayer player, String evidence, boolean halt)
      throws IOException {
    root(player);
    String result = "PASS bridge-recovery " + mode() + " " + evidence;
    Files.writeString(
        FabricLoader.getInstance().getGameDir().resolve("craftq3-bridge.result"), result + "\n");
    System.out.println(result);
    System.out.flush();
    if (halt) Runtime.getRuntime().halt(0);
    Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
  }
}
