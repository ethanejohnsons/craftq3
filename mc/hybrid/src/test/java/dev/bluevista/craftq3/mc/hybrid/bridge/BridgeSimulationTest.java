package dev.bluevista.craftq3.mc.hybrid.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.game.GameServer;
import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import dev.bluevista.craftq3.mc.hybrid.CoordinateTransform;
import dev.bluevista.craftq3.mc.hybrid.terrain.TerrainCells;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The retail baseq3 qagame running the bridge headless over flat Minecraft terrain: the player
 * spawns, walks into a wall, jumps, picks up an original railgun, shoots a Minecraft mob's actor (a
 * {@link MobDamage} comes out), takes Minecraft damage through the host triggers, and carries its
 * loadout into a fresh level.
 */
@RealData
class BridgeSimulationTest {
  static final int WP_RAILGUN = 7;
  static final int BUTTON_ATTACK = 1;

  /** Ground below Minecraft y 64; a 3-block wall at x 20..21 (Quake x 640..672). */
  static final TerrainCells TERRAIN =
      (x, y, z, sink) -> {
        if (y < 64 || (x == 20 && y < 67)) sink.full();
      };

  @Test
  void playerWalksShootsAMobTakesDamageAndKeepsItsLoadout() throws Exception {
    CoordinateTransform t = CoordinateTransform.at(0, 64, 0);
    try (BridgeSimulation sim = new BridgeSimulation(RealDataRoot.require(), TERRAIN, t)) {
      assertTrue(
          sim.start(
              new Vec3f(0, 0, 30),
              List.of(new SyntheticMapSource.ItemSpawn("weapon_railgun", new Vec3f(160, 0, 40)))),
          sim::tail);
      assertTrue(sim.host.triggersReady(), "host triggers spawned");

      // Spawn and settle on the terrain (feet at z 0, origin 24 above).
      sim.idle(20);
      assertEquals(GameServer.State.RUNNING, sim.server.state(), sim::tail);
      float[] o = sim.origin();
      assertEquals(
          24,
          o[2],
          0.5f,
          () -> "standing height " + Arrays.toString(sim.origin()) + "\n" + sim.tail(40));
      int spawnHealth = sim.stat(Loadout.STAT_HEALTH);
      assertTrue(spawnHealth >= 100, "spawn health " + spawnHealth);

      // Walk east over the railgun into the wall at x = 640: the hull stops at 640 - 15.
      for (int i = 0; i < 60; i++) sim.tick(0, 0, 0, 0, 127, 0, 0);
      o = sim.origin();
      assertEquals(625, o[0], 1f, () -> "against the wall " + Arrays.toString(sim.origin()));
      assertEquals(24, o[2], 0.5f);
      assertTrue(
          (sim.stat(Loadout.STAT_WEAPONS) & (1 << WP_RAILGUN)) != 0, "picked up the railgun");
      assertTrue(sim.ammo(WP_RAILGUN) > 0);

      // Jump: the original game's jump velocity lifts the body well above the floor.
      float peak = 0;
      for (int i = 0; i < 12; i++) {
        sim.tick(0, 0, 0, 0, 0, 0, i == 0 ? 127 : 0);
        peak = Math.max(peak, sim.origin()[2]);
      }
      assertTrue(peak > 24 + 30, "jump apex " + peak);
      sim.idle(10);
      assertEquals(24, sim.origin()[2], 0.5f);

      // A mob standing at x 300 (Minecraft 9.4, 0) in the line of fire.
      ExternalActors.Target zombie =
          new ExternalActors.Target(
              42L, "Zombie", new float[] {290, -10, 0}, new float[] {310, 10, 62}, 1f);
      sim.targets = List.of(zombie);
      sim.idle(2);
      assertEquals(1, sim.host.actors().size());
      int actor = sim.host.actors().client(42L);
      assertTrue(actor >= 0 && actor != sim.player);

      // Turn around (yaw 180) and fire the railgun at it.
      int yaw = 0x8000;
      for (int i = 0; i < 40 && sim.mobDamage.isEmpty(); i++)
        sim.tick(0, yaw, i > 15 ? BUTTON_ATTACK : 0, WP_RAILGUN, 0, 0, 0);
      assertFalse(sim.mobDamage.isEmpty(), () -> "no mob damage\n" + sim.tail(40));
      MobDamage hit = sim.mobDamage.getFirst();
      assertEquals(42L, hit.target());
      assertEquals(100, hit.quakeDamage(), "a rail slug does 100");
      assertEquals(sim.player, hit.attacker());
      assertFalse(hit.killed(), "a full-health mob survives one slug (125 health)");
      assertTrue(hit.impulseX() < 0, "knockback away from the shooter: " + hit);
      assertEquals(20, hit.minecraftDamage(25), 1e-4);
      // The actor's body is in the game but not in the player's snapshot: Minecraft draws the mob.
      assertFalse(sim.server.snapshot(sim.player).contains(actor));
      // Its name reached the game for obituaries.
      assertEquals("Zombie", sim.server.client(actor).name());

      // A Minecraft zombie hits the player for 3 points: 15 Quake points through the triggers.
      sim.idle(20);
      int before = sim.stat(Loadout.STAT_HEALTH);
      sim.host.minecraftDamage(3);
      sim.idle(2);
      int after = sim.stat(Loadout.STAT_HEALTH);
      assertTrue(before - after >= 15 && before - after <= 16, "health " + before + " -> " + after);
      // 300 points arrive over consecutive 100 ms windows, then the player is dead and the rest
      // dropped.
      sim.host.damage(300);
      sim.idle(10);
      assertTrue(sim.stat(Loadout.STAT_HEALTH) <= 0, "killed by Minecraft damage");
      assertEquals(0, sim.host.queuedDamage());
      assertTrue(sim.host.captureLoadout().isEmpty(), "no loadout from a dead player");

      // Respawn (attack) and capture a loadout after picking the railgun up again.
      for (int i = 0; i < 40; i++) sim.tick(0, 0, i % 2 == 0 ? BUTTON_ATTACK : 0, 0, 0, 0, 0);
      assertTrue(sim.stat(Loadout.STAT_HEALTH) > 0, sim::tail);
      for (int i = 0; i < 60; i++) sim.tick(0, 0, 0, 0, 127, 0, 0);
      sim.host.damage(48);
      sim.idle(4);
      Optional<Loadout> captured = sim.host.captureLoadout();
      assertTrue(captured.isPresent());
      byte[] module = new byte[32];
      Loadout saved =
          LoadoutCodec.decode(LoadoutCodec.encode(captured.get(), module), module).orElseThrow();

      // A fresh level: the saved loadout comes back with exact health.
      assertTrue(sim.start(new Vec3f(0, 0, 30), List.of()));
      sim.host.restoreLoadout(saved);
      for (int i = 0; i < 40 && sim.host.restoring(); i++) sim.idle(1);
      assertFalse(sim.host.restoring(), sim::tail);
      Loadout restored = sim.host.captureLoadout().orElseThrow();
      assertEquals(saved.health(), restored.health(), "health");
      assertEquals(saved.weapons(), restored.weapons());
      assertEquals(saved.weapon(), restored.weapon());
      assertEquals(Arrays.toString(saved.ammo()), Arrays.toString(restored.ammo()));
      assertEquals(saved.armor(), restored.armor());

      // Raising health uses the mega health chain: 180 with 50 armor and quad.
      int[] powerups = new int[16];
      powerups[1] = 20_000;
      int[] ammo = saved.ammo();
      Loadout strong = new Loadout(180, 50, saved.weapons(), saved.weapon(), 0, ammo, powerups);
      sim.host.restoreLoadout(strong);
      for (int i = 0; i < 40 && sim.host.restoring(); i++) sim.idle(1);
      assertFalse(sim.host.restoring());
      Loadout raised = sim.host.captureLoadout().orElseThrow();
      assertEquals(180, raised.health(), sim::tail);
      assertEquals(50, raised.armor());
      assertTrue(raised.powerupMillis(1) > 19_000, "quad left " + raised.powerupMillis(1));
    }
  }
}
