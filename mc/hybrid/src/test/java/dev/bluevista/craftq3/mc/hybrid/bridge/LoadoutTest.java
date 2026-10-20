package dev.bluevista.craftq3.mc.hybrid.bridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.AbiProfile;
import dev.bluevista.craftq3.engine.vmabi.layout.PlayerStateLayout;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LoadoutTest {
  private static final byte[] MODULE = new byte[32];
  private static final byte[] OTHER = new byte[32];

  static {
    Arrays.fill(MODULE, (byte) 7);
    Arrays.fill(OTHER, (byte) 9);
  }

  static Loadout sample() {
    int[] ammo = new int[16];
    ammo[2] = 87;
    ammo[5] = 12;
    ammo[7] = 9;
    int[] powerups = new int[16];
    powerups[1] = 23_456; // quad
    powerups[7] = 5000; // red flag: not carried by the base game policy
    // Gauntlet, machinegun, rocket launcher, railgun.
    int weapons = 1 << 1 | 1 << 2 | 1 << 5 | 1 << 7;
    return new Loadout(173, 66, weapons, 7, 26, ammo, powerups);
  }

  @Test
  void capturesFromBothProfiles() {
    for (AbiProfile profile : AbiProfile.values()) {
      PlayerStateLayout layout = PlayerStateLayout.of(profile);
      QvmMemory memory = TestVm.memory();
      int ps = 0x2000;
      memory.writeInt(ps + PlayerStateLayout.STATS, 173);
      memory.writeInt(ps + PlayerStateLayout.STATS + 4, 26);
      memory.writeInt(ps + PlayerStateLayout.STATS + 8, 1 << 1 | 1 << 2 | 1 << 5 | 1 << 7);
      memory.writeInt(ps + PlayerStateLayout.STATS + 12, 66);
      memory.writeInt(ps + PlayerStateLayout.STATS + 24, 100);
      memory.writeInt(ps + PlayerStateLayout.WEAPON, 7);
      memory.writeInt(ps + PlayerStateLayout.AMMO + 2 * 4, 87);
      memory.writeInt(ps + PlayerStateLayout.AMMO + 5 * 4, 12);
      memory.writeInt(ps + PlayerStateLayout.AMMO + 7 * 4, 9);
      memory.writeInt(ps + PlayerStateLayout.POWERUPS + 4, 100_000 + 23_456);
      memory.writeInt(ps + PlayerStateLayout.POWERUPS + 7 * 4, 105_000);
      Loadout captured = Loadout.capture(layout.readCanonical(memory, ps), 100_000);
      assertEquals(sample(), captured, profile.toString());
    }
  }

  @Test
  void policyWritesInventoryAndCarriesOnlyTimedPowerups() {
    QvmMemory memory = TestVm.memory();
    int ps = 0x4000;
    // A freshly spawned player: machinegun, 100 bullets, health 125, a flag it already holds.
    memory.writeInt(ps + PlayerStateLayout.STATS, 125);
    memory.writeInt(ps + PlayerStateLayout.STATS + 8, 1 << 1 | 1 << 2);
    memory.writeInt(ps + PlayerStateLayout.STATS + 24, 100);
    memory.writeInt(ps + PlayerStateLayout.WEAPON, 2);
    memory.writeInt(ps + PlayerStateLayout.WEAPONSTATE, 3);
    memory.writeInt(ps + PlayerStateLayout.AMMO + 8, 100);
    memory.writeInt(ps + PlayerStateLayout.POWERUPS + 8 * 4, 999_999);
    LoadoutPolicy policy = LoadoutPolicy.BASE_GAME;
    Loadout carried = policy.filter(sample());
    assertEquals(0, carried.powerupMillis(7));
    policy.writeInventory(memory, ps, carried, 50_000);
    assertEquals(sample().weapons(), memory.readInt(ps + PlayerStateLayout.STATS + 8));
    assertEquals(66, memory.readInt(ps + PlayerStateLayout.STATS + 12));
    assertEquals(26, memory.readInt(ps + PlayerStateLayout.STATS + 4));
    assertEquals(7, memory.readInt(ps + PlayerStateLayout.WEAPON));
    assertEquals(LoadoutPolicy.WEAPON_READY, memory.readInt(ps + PlayerStateLayout.WEAPONSTATE));
    assertEquals(87, memory.readInt(ps + PlayerStateLayout.AMMO + 8));
    assertEquals(50_000 + 23_456, memory.readInt(ps + PlayerStateLayout.POWERUPS + 4));
    // The flag slot is the destination's, untouched.
    assertEquals(999_999, memory.readInt(ps + PlayerStateLayout.POWERUPS + 8 * 4));
    // Health is not written directly.
    assertEquals(125, LoadoutPolicy.health(memory, ps));
    // Re-capture: everything but health and the uncarried flag round-trips.
    Loadout back = Loadout.capture(PlayerStateLayout.Q3_132.readCanonical(memory, ps), 50_000);
    assertEquals(carried.weapons(), back.weapons());
    assertArrayEquals(carried.ammo(), back.ammo());
    assertEquals(carried.powerupMillis(1), back.powerupMillis(1));

    memory.writeInt(ps + PlayerStateLayout.POWERUPS + LoadoutPolicy.PW_BATTLESUIT * 4, 77_000);
    policy.prepareForHealth(memory, ps);
    assertEquals(0, memory.readInt(ps + PlayerStateLayout.STATS + 12));
    assertEquals(
        0, memory.readInt(ps + PlayerStateLayout.POWERUPS + LoadoutPolicy.PW_BATTLESUIT * 4));
  }

  @Test
  void healthPlansUseMegaHealthThenHurtDamage() {
    // Spawned at 125 (max 100): lowering needs only damage.
    assertEquals(new LoadoutPolicy.HealthPlan(0, 52, 73), LoadoutPolicy.healthPlan(125, 100, 73));
    assertEquals(new LoadoutPolicy.HealthPlan(0, 0, 125), LoadoutPolicy.healthPlan(125, 100, 125));
    // Raising: one mega health (to 200, the cap), then damage down.
    assertEquals(new LoadoutPolicy.HealthPlan(1, 27, 173), LoadoutPolicy.healthPlan(125, 100, 173));
    assertEquals(new LoadoutPolicy.HealthPlan(1, 0, 200), LoadoutPolicy.healthPlan(125, 100, 200));
    // Above the cap is lowered to it; from low health two grants may be needed.
    assertEquals(new LoadoutPolicy.HealthPlan(1, 0, 200), LoadoutPolicy.healthPlan(125, 100, 250));
    assertEquals(new LoadoutPolicy.HealthPlan(2, 10, 190), LoadoutPolicy.healthPlan(5, 100, 190));
    assertThrows(IllegalArgumentException.class, () -> LoadoutPolicy.healthPlan(0, 100, 50));
  }

  @Test
  void codecRoundTripsAndIsTiedToTheModule() throws IOException {
    byte[] bytes = LoadoutCodec.encode(sample(), MODULE);
    assertEquals(LoadoutCodec.BYTES, bytes.length);
    assertEquals(Optional.of(sample()), LoadoutCodec.decode(bytes, MODULE));
    assertEquals(Optional.empty(), LoadoutCodec.decode(bytes, OTHER));
    assertEquals(Optional.empty(), LoadoutCodec.decode(new byte[0], MODULE));
    byte[] truncated = Arrays.copyOf(bytes, bytes.length - 1);
    assertThrows(IOException.class, () -> LoadoutCodec.decode(truncated, MODULE));
    byte[] corrupt = bytes.clone();
    corrupt[40 + 3] = 0; // health 0
    assertThrows(IOException.class, () -> LoadoutCodec.decode(corrupt, MODULE));
    byte[] future = bytes.clone();
    future[7] = 2;
    assertThrows(IOException.class, () -> LoadoutCodec.decode(future, MODULE));
  }

  @Test
  void rejectsImpossibleLoadouts() {
    int[] none = new int[16];
    assertThrows(IllegalArgumentException.class, () -> new Loadout(0, 0, 0, 0, 0, none, none));
    // Selected weapon not owned.
    assertThrows(
        IllegalArgumentException.class, () -> new Loadout(50, 0, 1 << 2, 5, 0, none, none));
    assertThrows(
        IllegalArgumentException.class, () -> new Loadout(50, 0, 0, 0, 0, new int[3], none));
    int[] badPowerup = new int[16];
    badPowerup[1] = -5;
    assertThrows(
        IllegalArgumentException.class, () -> new Loadout(50, 0, 0, 0, 0, none, badPowerup));
    // A dead player cannot be captured.
    byte[] dead = new byte[PlayerStateLayout.CANONICAL_BYTES];
    assertThrows(IllegalArgumentException.class, () -> Loadout.capture(dead, 0));
    assertTrue(new Loadout(1, 0, 0, 0, 0, none, none).toString().contains("health=1"));
  }
}
