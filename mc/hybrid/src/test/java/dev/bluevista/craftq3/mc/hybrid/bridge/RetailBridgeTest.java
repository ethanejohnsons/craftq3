package dev.bluevista.craftq3.mc.hybrid.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import dev.bluevista.craftq3.engine.vmabi.AbiProfile;
import dev.bluevista.craftq3.mc.hybrid.CoordinateTransform;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/** The same bridge rules with the retail (pak0, 1999 ABI) qagame. */
@RealData
class RetailBridgeTest {
  @Test
  void retailQagameRunsTheBridge() throws Exception {
    Path root = RealDataRoot.require();
    byte[] qagame;
    try (ZipFile pak0 = new ZipFile(root.resolve("baseq3/pak0.pk3").toFile())) {
      var entry = pak0.getEntry("vm/qagame.qvm");
      try (var in = pak0.getInputStream(entry)) {
        qagame = in.readAllBytes();
      }
    }
    CoordinateTransform t = CoordinateTransform.at(0, 64, 0);
    try (BridgeSimulation sim =
        new BridgeSimulation(root, BridgeSimulationTest.TERRAIN, t, false, qagame)) {
      assertTrue(sim.start(new Vec3f(0, 0, 30), List.of()), sim::tail);
      assertEquals(AbiProfile.RETAIL_1999, sim.server.profile().orElseThrow());
      assertTrue(sim.host.triggersReady());
      sim.idle(10);
      assertEquals(24, sim.origin()[2], 0.5f);
      for (int i = 0; i < 60; i++) sim.tick(0, 0, 0, 0, 127, 0, 0);
      assertEquals(625, sim.origin()[0], 1f, () -> Arrays.toString(sim.origin()));

      // Restore a loadout with a railgun, then shoot a mob with it.
      int[] ammo = new int[16];
      ammo[2] = 50;
      ammo[7] = 5;
      int[] powerups = new int[16];
      Loadout loadout = new Loadout(90, 25, 1 << 1 | 1 << 2 | 1 << 7, 7, 0, ammo, powerups);
      sim.self().restoreLoadout(loadout);
      for (int i = 0; i < 40 && sim.self().restoring(); i++) sim.idle(1);
      assertFalse(sim.self().restoring());
      Loadout restored = sim.self().captureLoadout().orElseThrow();
      assertEquals(90, restored.health());
      assertEquals(25, restored.armor());
      assertEquals(loadout.weapons(), restored.weapons());
      assertEquals(Arrays.toString(ammo), Arrays.toString(restored.ammo()));

      sim.targets =
          List.of(
              new ExternalActors.Target(
                  7L, "Creeper", new float[] {290, -10, 0}, new float[] {310, 10, 54}, 0.5f));
      sim.idle(2);
      for (int i = 0; i < 30 && sim.mobDamage.isEmpty(); i++)
        sim.tick(0, 0x8000, i > 8 ? BridgeSimulationTest.BUTTON_ATTACK : 0, 7, 0, 0, 0);
      assertFalse(sim.mobDamage.isEmpty(), sim::tail);
      MobDamage hit = sim.mobDamage.getFirst();
      // A half-health mob was admitted with 63 Quake health: one slug (100, overkill included)
      // kills it.
      assertTrue(hit.killed(), hit.toString());
      assertEquals(100, hit.quakeDamage());
      assertTrue(sim.tail(200).contains("Creeper"), sim::tail);

      int before = sim.stat(Loadout.STAT_HEALTH);
      sim.self().damage(20);
      sim.idle(3);
      // Armor absorbs part of it, as the original rules say.
      int lost = before - sim.stat(Loadout.STAT_HEALTH);
      assertTrue(lost > 0 && lost < 20, "lost " + lost);
    }
  }
}
