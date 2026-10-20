package dev.bluevista.craftq3.engine.botlib.weapon;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.botlib.script.ScriptSources;
import dev.bluevista.craftq3.engine.botlib.testing.TestFiles;
import dev.bluevista.craftq3.engine.formats.AssetLookup;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BotWeaponsTest {
  private static final String CONFIG =
      """
      projectileinfo { name "hit" damage 10 }
      weaponinfo { name "Beta" number 3 projectile "hit" }
      weaponinfo { name "Alpha" number 1 projectile "hit" }
      weaponinfo { name "Gamma" number 2 projectile "hit" }
      """;
  private static final String WEIGHTS =
      """
      weight "Alpha" { return 10; }
      weight "Beta" { return 10; }
      weight "Gamma" { switch (0) { case 0: return 5; case 100: return 25; default: return 25; } }
      """;
  private static final String FULL =
      """
      weaponinfo { name "All" model "wm" number 2 level -3 weaponindex 4 flags 5 projectile "proj"
        numprojectiles 6 hspread 1.25 vspread -2.5 speed 300 acceleration -4 recoil { 1, -2, 3 }
        offset {4, 5, 6} angleoffset {-7, 8, 9} extrazvelocity -10 ammoamount 11 ammoindex 12
        activate 13 reload 14 spinup 15 spindown 16 }
      projectileinfo { name "proj" model "pm" flags 1 gravity 0.45 damage -3 radius 4.5
        visdamage 6 damagetype 7 healthinc 8 push 9 detonation 10 bounce 11 bouncefric 12 bouncestop 13 }
      """;

  @Test
  void allTypedFieldsMatchAuthoredNativeFixture() throws Exception {
    try (var sources = sources(FULL)) {
      WeaponConfig config = WeaponConfig.load(sources, "weapons.c");
      WeaponInfo info = config.weapons().get(2);
      assertTrue(info.valid());
      assertEquals("All", info.name());
      assertEquals(-3, info.level());
      assertEquals(-2.5f, info.verticalSpread());
      assertEquals(-2, info.recoil().y());
      assertEquals(-3, info.projectileInfo().damage());
      assertEquals(13, info.projectileInfo().bounceStop());
      // The 552-byte weaponinfo_t layout and its native-fixture digest belong to the game ABI.
      assertEquals(0, sources.openCount());
      assertThrows(UnsupportedOperationException.class, () -> config.weapons().clear());
      assertThrows(UnsupportedOperationException.class, () -> config.projectiles().clear());
    }
  }

  @Test
  void selectionUsesOnlyConfiguredWeightsWithPositiveTieAndInventoryRules() {
    try (var sources = sources(CONFIG);
        var weapons = new BotWeapons(sources)) {
      assertEquals(0, weapons.setup("weapons.c"));
      int state = weapons.allocate();
      assertEquals(0, weapons.chooseBestFightWeapon(state, new int[256]));
      assertEquals(0, weapons.loadWeaponWeights(state, "weights.c"));
      int[] inventory = new int[256];
      assertEquals(1, weapons.chooseBestFightWeapon(state, inventory));
      inventory[0] = 25;
      assertEquals(1, weapons.chooseBestFightWeapon(state, inventory));
      inventory[0] = 50;
      assertEquals(2, weapons.chooseBestFightWeapon(state, inventory));
      assertEquals(10, weapons.weaponInfo(state, 2).projectileInfo().damage());
      assertFalse(weapons.weaponInfo(state, 31).valid());
    }
  }

  @Test
  void resetKeepsWeightsAndFailedReloadClearsThemWithoutDanglingState() {
    try (var sources = sources(CONFIG);
        var weapons = new BotWeapons(sources)) {
      weapons.setup("weapons.c");
      int state = weapons.allocate();
      weapons.loadWeaponWeights(state, "weights.c");
      weapons.reset(state);
      assertEquals(1, weapons.chooseBestFightWeapon(state, new int[256]));
      assertEquals(11, weapons.loadWeaponWeights(state, "missing.c"));
      assertEquals(0, weapons.chooseBestFightWeapon(state, new int[256]));
      weapons.free(state);
      weapons.free(state);
      assertEquals(0, weapons.stateCount());
      assertFalse(weapons.weaponInfo(state, 1).valid());
      assertEquals(0, sources.openCount());
    }
  }

  @Test
  void stateIsolationZeroWeightsMissingNamesAndCaseSensitiveLookup() {
    try (var sources =
            new ScriptSources(
                memory(
                    Map.of(
                        "botfiles/weapons.c",
                        CONFIG,
                        "botfiles/a.c",
                        WEIGHTS,
                        "botfiles/b.c",
                        "weight \"alpha\" { return 99; } weight \"Beta\" { return 0; }")));
        var weapons = new BotWeapons(sources)) {
      weapons.setup("weapons.c");
      int a = weapons.allocate(), b = weapons.allocate();
      weapons.loadWeaponWeights(a, "a.c");
      weapons.loadWeaponWeights(b, "b.c");
      assertEquals(1, weapons.chooseBestFightWeapon(a, new int[256]));
      assertEquals(0, weapons.chooseBestFightWeapon(b, new int[256]));
      weapons.free(a);
      assertEquals(0, weapons.chooseBestFightWeapon(b, new int[256]));
    }
  }

  @Test
  void duplicateSlotUsesLastAndDuplicateProjectileUsesFirst() throws Exception {
    try (var sources =
        sources(
            """
        projectileinfo { name "p" damage 1 }
        projectileinfo { name "p" damage 2 }
        weaponinfo { name "first" number 1 projectile "p" }
        weaponinfo { name "second" number 1 projectile "p" }
        """)) {
      var config = WeaponConfig.load(sources, "weapons.c");
      assertEquals("second", config.weapons().get(1).name());
      assertEquals(1, config.weapons().get(1).projectileInfo().damage());
      assertEquals(2, config.diagnostics().size());
    }
    try (var sources =
        sources(
            "projectileinfo { name \"p\" } weaponinfo { name \""
                + "x".repeat(100)
                + "\" number 1 projectile \"p\" }")) {
      assertEquals(79, WeaponConfig.load(sources, "weapons.c").weapons().get(1).name().length());
    }
  }

  @Test
  void badConfigurationBoundsReferencesAndTypesFailWithoutPublishingPartialData() {
    for (String bad :
        List.of(
            "weaponinfo { name \"missing\" number 1 projectile \"none\" }",
            "weaponinfo { number 32 }",
            "weaponinfo { number -1 }",
            "weaponinfo { bogus 1 }",
            "weaponinfo { recoil {1,2,3,4} }",
            "weaponinfo { number 1.5 }",
            "weaponinfo { model 12 }",
            "projectileinfo { name \"p\" }\n".repeat(33))) {
      try (var sources = sources(bad);
          var weapons = new BotWeapons(sources)) {
        assertEquals(12, weapons.setup("weapons.c"));
        int state = weapons.allocate();
        assertFalse(weapons.weaponInfo(state, 1).valid());
        assertEquals(11, weapons.loadWeaponWeights(state, "weights.c"));
        assertEquals(0, weapons.chooseBestFightWeapon(state, new int[256]));
        assertEquals(0, sources.openCount());
      }
    }
  }

  @Test
  void handlesInventoryAndServiceOwnershipRemainBounded() throws Exception {
    try (var sources = sources(CONFIG)) {
      var weapons = new BotWeapons(sources);
      weapons.setup("weapons.c");
      int first = weapons.allocate();
      for (int i = 1; i < 64; i++) assertNotEquals(0, weapons.allocate());
      assertEquals(0, weapons.allocate());
      weapons.free(first);
      int replacement = weapons.allocate();
      assertTrue(replacement > 64);
      assertFalse(weapons.weaponInfo(first, 1).valid());
      weapons.loadWeaponWeights(replacement, "weights.c");
      assertEquals(0, weapons.chooseBestFightWeapon(replacement, new int[255]));
      assertEquals(0, weapons.chooseBestFightWeapon(replacement, new int[257]));
      weapons.close();
      weapons.close();
      assertThrows(IllegalStateException.class, weapons::allocate);
      int source = sources.load("weapons.c");
      assertTrue(sources.free(source));
    }
  }

  private static ScriptSources sources(String config) {
    return new ScriptSources(
        memory(Map.of("botfiles/weapons.c", config, "botfiles/weights.c", WEIGHTS)));
  }

  private static AssetLookup memory(Map<String, String> files) {
    return TestFiles.memory(files);
  }
}
