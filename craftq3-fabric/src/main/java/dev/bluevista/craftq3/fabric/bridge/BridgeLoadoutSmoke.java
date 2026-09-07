package dev.bluevista.craftq3.fabric.bridge;

import dev.bluevista.craftq3.fabric.render.BridgeDepthSmoke;
import dev.bluevista.craftq3.server.PlayerLoadout;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/** Separate-process loadout round trip, explicit fresh entry and original death reset. */
public final class BridgeLoadoutSmoke {
  private static final UUID PROFILE = UUID.fromString("63d73146-7c62-40ee-a630-fb937aa481be");
  private static final Path EXPECTED = Path.of("craftq3-bridge-loadout-expected.txt");
  private static boolean checked;
  private static int rocketAmmo, powerup;

  private BridgeLoadoutSmoke() {}

  private static String mode() {
    return System.getProperty("craftq3.bridgeLoadoutSmoke", "");
  }

  public static boolean enabled() {
    return FabricLoader.getInstance().isDevelopmentEnvironment() && !mode().isEmpty();
  }

  public static boolean fresh() {
    return enabled() && mode().equals("fresh");
  }

  public static boolean startFresh() {
    return enabled() && (mode().equals("prepare") || mode().equals("fresh"));
  }

  public static CompletableFuture<Void> setup(Minecraft client) {
    checked = false;
    return BridgeDepthSmoke.setup(client);
  }

  private static Path expected() {
    return FabricLoader.getInstance().getGameDir().resolve(EXPECTED);
  }

  private static Path checkpoint() {
    return FabricLoader.getInstance()
        .getGameDir()
        .resolve("craftq3-bridge-loadout-qa/baseq3/loadout-" + PROFILE + ".dat");
  }

  public static void step(BridgeGame game, int frame) {
    if (frame == 0) {
      try {
        var state = game.loadout();
        if (mode().equals("verify")) {
          if (!state.toString().equals(Files.readString(expected())))
            throw new IllegalStateException(
                "Restored loadout differs before first frame: " + state);
          if (game.selectedWeapon() != state.weapon())
            throw new IllegalStateException("Cgame did not select restored weapon");
          rocketAmmo = state.ammo().get(5);
          powerup = state.powerupMillis().getFirst();
          checked = true;
        } else if (fresh() || mode().equals("verify-dead")) {
          var bytes = Files.readAllBytes(checkpoint());
          if (fresh() && (bytes.length != 148 || (ByteBuffer.wrap(bytes).getInt(48) & 32) == 0))
            throw new IllegalStateException(
                "Fresh entry did not have an older rocket loadout to bypass");
          if (mode().equals("verify-dead") && bytes.length != 0)
            throw new IllegalStateException("Death did not clear the checkpoint");
          if (state.health() != 125
              || state.armor() != 0
              || state.weapons() != 6
              || state.weapon() != 2
              || state.ammo().get(2) != 100
              || state.holdable() != 0
              || state.powerupMillis().stream().anyMatch(v -> v != 0))
            throw new IllegalStateException("Fresh spawn differs from original defaults: " + state);
          checked = true;
        }
      } catch (IOException error) {
        throw new UncheckedIOException(error);
      }
    }
    if (mode().equals("prepare")) {
      if (frame == 5) game.command("give all; give Mega Health; give Medkit");
      if (frame == 30) game.command("weapon 5");
      if (frame == 80) game.input().key(178, true, game.time());
      if (frame == 90) game.input().key(178, false, game.time());
      if (frame == 110) game.command("give Quad Damage");
    } else if (mode().equals("verify")) {
      if (frame == 60) game.input().key(178, true, game.time());
      if (frame == 70) game.input().key(178, false, game.time());
    } else if (mode().equals("dead") && frame == 40) game.command("kill");
  }

  public static void saved(PlayerLoadout saved) throws IOException {
    if (!enabled()) return;
    if (mode().equals("prepare")) {
      if (saved == null) throw new IOException("Prepare loadout was dead");
      Files.writeString(expected(), saved.toString());
    }
    System.out.println("CraftQ3 loadout saved mode=" + mode() + " state=" + saved);
  }

  public static String result(BridgeGame game) {
    if (mode().equals("prepare")) {
      var state = game.loadout();
      if (state.armor() <= 0
          || state.health() <= 0
          || state.holdable() <= 0
          || state.weapon() != 5
          || state.ammo().get(5) >= 999
          || state.powerupMillis().getFirst() <= 0)
        throw new IllegalStateException("Prepare inventory incomplete: " + state);
    } else if (mode().equals("verify")) {
      var state = game.loadout();
      if (!checked
          || state.ammo().get(5) != rocketAmmo - 1
          || state.powerupMillis().getFirst() >= powerup)
        throw new IllegalStateException("Restored ammo/powerup gameplay failed: " + state);
    } else if (mode().equals("dead")) {
      if (game.health() > 0) throw new IllegalStateException("Original kill did not cause death");
    } else if (!checked) throw new IllegalStateException("Loadout stage not checked: " + mode());
    return "loadout=" + mode() + " verified=true";
  }
}
