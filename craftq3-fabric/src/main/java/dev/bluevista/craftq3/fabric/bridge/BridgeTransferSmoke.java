package dev.bluevista.craftq3.fabric.bridge;

import dev.bluevista.craftq3.fabric.game.QuakeSession;
import dev.bluevista.craftq3.fabric.render.BridgeDepthSmoke;
import dev.bluevista.craftq3.server.PlayerLoadout;
import dev.bluevista.craftq3.server.Q3Server;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/** Actual standalone map to native-world handoff through the user console command. */
public final class BridgeTransferSmoke {
  private static PlayerLoadout expected, returned;
  private static BridgeGame returningSource;
  private static int phase, failedReturns;
  private static Q3Server original;
  private static boolean checked, mismatchRejected, audioRollback;

  private BridgeTransferSmoke() {}

  public static boolean enabled() {
    return FabricLoader.getInstance().isDevelopmentEnvironment()
        && (Boolean.getBoolean("craftq3.bridgeTransferSmoke") || roundtrip());
  }

  public static boolean roundtrip() {
    return Boolean.getBoolean("craftq3.bridgeRoundtripSmoke");
  }

  public static CompletableFuture<Void> setup(Minecraft client) {
    expected = returned = null;
    phase = failedReturns = 0;
    checked = mismatchRejected = audioRollback = false;
    return BridgeDepthSmoke.setup(client);
  }

  public static void sourceStep(QuakeSession source, int frame) {
    if (!enabled()) return;
    if (roundtrip() && phase >= 2) {
      if (frame == 0) {
        var actual = PlayerLoadout.capture(source.server().playerState(0), source.server().time());
        if (!returned.equals(actual)
            || source.client().selectedWeapon() != returned.weapon()
            || returningSource.audioDiagnostics().available())
          throw new IllegalStateException(
              "Return trip changed inventory or retained source audio: " + actual);
        phase = 3;
      }
      if (frame == 60) source.input().key(178, true, source.inputTime());
      if (frame == 70) source.input().key(178, false, source.inputTime());
      return;
    }
    if (frame == 0) source.command("set net_enabled 0; devmap q3dm17");
    if (frame == 10) source.command("give all; give Mega Health; give Medkit");
    if (frame == 30) source.command("weapon 5");
    if (frame == 70) {
      var client = Minecraft.getInstance();
      var prepared =
          dev.bluevista.craftq3.fabric.audio.MinecraftAudioBackend.prepare(
              client,
              new dev.bluevista.craftq3.platform.CoordinateTransform(
                  32, new dev.bluevista.craftq3.core.math.Vec3(0, 0, 0)));
      try {
        prepared.activateAfterRelease().join();
        throw new IllegalStateException("Prepared audio stole the active source engine");
      } catch (java.util.concurrent.CompletionException expectedFailure) {
        prepared.close();
        var engine =
            ((dev.bluevista.craftq3.fabric.mixin.SoundManagerAccessor) client.getSoundManager())
                .craftq3$soundEngine();
        audioRollback =
            prepared.closeCompletion().isDone()
                && source.audioDiagnostics().available()
                && dev.bluevista.craftq3.fabric.audio.MinecraftAudioBackend.ownsListener(engine);
      }
      byte[] before = source.server().playerState(0);
      try {
        source.bridgeLoadout(new byte[] {1, 2, 3});
        throw new AssertionError("Different module accepted");
      } catch (IllegalArgumentException expectedFailure) {
        mismatchRejected = Arrays.equals(before, source.server().playerState(0));
      } catch (IOException failure) {
        throw new IllegalStateException(failure);
      }
    }
    if (frame == 80) source.command("give Quad Damage");
    if (frame == 100) source.command("minecraft");
    if (frame > 180) throw new IllegalStateException("Standalone command did not open bridge");
  }

  public static void captured(QuakeSession source, PlayerLoadout state) {
    if (!enabled()) return;
    if (source == null
        || state == null
        || !source.world().mapName().contains("q3dm17")
        || !mismatchRejected
        || !audioRollback
        || state.weapon() != 5
        || state.armor() <= 0
        || state.holdable() <= 0
        || state.powerupMillis().getFirst() <= 0)
      throw new IllegalStateException("Incomplete live source transfer: " + state);
    phase = 1;
    expected = state;
    original = source.server();
    System.out.println("CraftQ3 live transfer captured: " + state);
  }

  public static void step(BridgeGame game, int frame) {
    if (frame == 0) {
      if (expected == null
          || !expected.equals(game.loadout())
          || game.selectedWeapon() != expected.weapon()
          || original.state() != Q3Server.State.CLOSED)
        throw new IllegalStateException("Live transfer changed inventory or left source running");
      checked = true;
    }
    if (frame == 60) game.input().key(178, true, game.time());
    if (frame == 70) game.input().key(178, false, game.time());
    if (roundtrip() && frame == 100) game.command("quake craftq3_missing_transfer_qa");
    if (roundtrip() && frame == 130) {
      if (failedReturns != 1) throw new IllegalStateException("Missing-map rejection not observed");
      result(game);
      game.command("quake q3dm17");
    }
    if (roundtrip() && frame > 220)
      throw new IllegalStateException("Return command did not open map");
  }

  public static void returnFailed(BridgeGame source, PlayerLoadout before) {
    if (!enabled() || !roundtrip()) return;
    if (!source.loadout().equals(before) || !source.audioDiagnostics().available())
      throw new IllegalStateException("Failed map preparation changed source inventory/audio");
    failedReturns++;
  }

  public static void returnPrepared(BridgeGame source, PlayerLoadout loadout) {
    if (!enabled() || !roundtrip()) return;
    if (failedReturns != 1) throw new IllegalStateException("Return failure path was not checked");
    returned = loadout;
    returningSource = source;
    phase = 2;
    System.out.println("CraftQ3 return transfer captured: " + loadout);
  }

  public static void finish(Minecraft client, QuakeSession session, int frame) {
    if (!enabled() || !roundtrip() || phase != 3 || frame != 180) return;
    var actual = PlayerLoadout.capture(session.server().playerState(0), session.server().time());
    var audio = session.audioDiagnostics();
    if (actual.ammo().get(5) != returned.ammo().get(5) - 1
        || actual.powerupMillis().getFirst() >= returned.powerupMillis().getFirst()
        || session.cvars().integer("sv_cheats") != 0
        || !audio.available()
        || audio.startedVoices() == 0
        || audio.failures() != 0)
      throw new IllegalStateException(
          "Returned original gameplay/audio failed: " + actual + " " + audio);
    System.out.println("CraftQ3 roundtrip audio: " + audio);
    var server = client.getSingleplayerServer();
    var id = client.player.getUUID();
    server
        .submit(() -> server.getPlayerList().getPlayer(id).gameMode.getGameModeForPlayer())
        .whenComplete(
            (mode, error) ->
                client.execute(
                    () -> {
                      if (error != null || mode != net.minecraft.world.level.GameType.CREATIVE)
                        throw new IllegalStateException(
                            "Native mode did not restore before Quake resumed", error);
                      net.minecraft.client.Screenshot.grab(
                          client.gameDirectory,
                          "craftq3-roundtrip.png",
                          client.gameRenderer.mainRenderTarget(),
                          1,
                          message ->
                              client.execute(
                                  () -> {
                                    try {
                                      java.nio.file.Files.writeString(
                                          FabricLoader.getInstance()
                                              .getGameDir()
                                              .resolve("craftq3-bridge.result"),
                                          "PASS roundtrip=true exact-loadout=true"
                                              + " original-rocket-both-worlds=true"
                                              + " missing-map-preserved=true audio=true"
                                              + " restoredMode="
                                              + mode
                                              + "\n");
                                    } catch (IOException failure) {
                                      throw new java.io.UncheckedIOException(failure);
                                    }
                                    client.gui.setScreen(null);
                                    client.stop();
                                  }));
                    }));
  }

  public static String result(BridgeGame game) {
    var current = game.loadout();
    System.out.println("CraftQ3 transfer audio: " + game.audioDiagnostics());
    if (!game.audioDiagnostics().available()
        || game.audioDiagnostics().startedVoices() == 0
        || game.audioDiagnostics().failures() != 0)
      throw new IllegalStateException("Destination audio did not acquire cleanly");
    if (!checked
        || current.ammo().get(5) != expected.ammo().get(5) - 1
        || current.powerupMillis().getFirst() >= expected.powerupMillis().getFirst())
      throw new IllegalStateException("Transferred rocket/powerup did not run: " + current);
    return "standalone-transfer=true exact-loadout=true mismatch-rejected=true source-closed=true"
        + " rocket=true";
  }
}
