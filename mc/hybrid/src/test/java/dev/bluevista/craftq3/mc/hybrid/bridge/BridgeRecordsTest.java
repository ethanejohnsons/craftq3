package dev.bluevista.craftq3.mc.hybrid.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.entity.BspEntity;
import dev.bluevista.craftq3.engine.formats.entity.EntityParser;
import dev.bluevista.craftq3.mc.hybrid.CoordinateTransform;
import dev.bluevista.craftq3.mc.hybrid.map.EntityText;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BridgeRecordsTest {
  @Test
  void pickupPlacementsRoundTripAndValidate() throws IOException {
    List<PickupPlacement> list =
        List.of(
            new PickupPlacement(1, "weapon_rocketlauncher", 10.5, 64, -3.25),
            new PickupPlacement(2, "item_armor_combat", -100, 70, 2000),
            new PickupPlacement(7, "holdable_medkit", 0, 0, 0));
    assertEquals(list, PickupPlacementCodec.decode(PickupPlacementCodec.encode(list)));
    assertEquals(List.of(), PickupPlacementCodec.decode(new byte[0]));
    assertEquals(3, PickupPlacement.nextId(list));
    assertThrows(
        IllegalArgumentException.class, () -> new PickupPlacement(1, "worldspawn", 0, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new PickupPlacement(1, "item_\"x", 0, 0, 0));
    assertThrows(
        IllegalArgumentException.class, () -> new PickupPlacement(0, "item_quad", 0, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PickupPlacement(1, "item_quad", Double.NaN, 0, 0));
    byte[] bytes = PickupPlacementCodec.encode(list);
    bytes[bytes.length - 1] ^= 1; // still decodes (a double changed)
    PickupPlacementCodec.decode(bytes);
    byte[] trailing = java.util.Arrays.copyOf(PickupPlacementCodec.encode(list), bytes.length + 1);
    assertThrows(IOException.class, () -> PickupPlacementCodec.decode(trailing));
    assertThrows(
        IllegalArgumentException.class,
        () -> PickupPlacementCodec.encode(List.of(list.get(0), list.get(0))));
    // Quake origin for a session anchored at (10, 64, 0).
    CoordinateTransform t = CoordinateTransform.at(10, 64, 0);
    assertEquals(new Vec3f(16, 104, 0), list.get(0).quakeOrigin(t));
    List<PickupPlacement> near = PickupPlacement.near(list, 0, 0, 50, 10);
    assertEquals(List.of(list.get(2), list.get(0)), near);
    List<PickupPlacement> full = new ArrayList<>();
    for (int i = 1; i <= PickupPlacement.MAX_PLACEMENTS; i++)
      full.add(new PickupPlacement(i, "ammo_bullets", i, 0, 0));
    assertEquals(-1, PickupPlacement.nextId(full));
  }

  @Test
  void actorNamesAreSafeQuakeNames() {
    assertEquals("Bridge Guardian", ActorName.clean("  Bridge \t Guardian "));
    assertEquals("Iron ?olem", ActorName.clean("Iron Αolem"));
    assertEquals("a?b?c?d?", ActorName.clean("a^b\\c\"d;"));
    assertEquals(ActorName.FALLBACK, ActorName.clean("   "));
    assertEquals(ActorName.MAX_LENGTH, ActorName.clean("x".repeat(100)).length());
    assertEquals("Zombie ?", ActorName.clean("Zombie 😀"));
  }

  @Test
  void externalDamageEncodesAmountsAsTriggerSets() {
    ExternalDamage damage = new ExternalDamage();
    assertEquals(0, damage.take(0));
    damage.add(5);
    damage.add(40);
    assertEquals(45, damage.take(1000));
    // The pool re-arms after 100 ms.
    damage.add(300);
    assertEquals(0, damage.take(1050));
    assertEquals(255, damage.take(1100));
    assertEquals(0, damage.take(1150));
    assertEquals(45, damage.take(1200));
    assertEquals(345, damage.delivered());
    damage.add(10);
    int handed = damage.take(1300);
    damage.giveBack(handed);
    assertEquals(10, damage.queued());
    assertEquals(10, damage.take(1300));
    damage.add(99);
    damage.clear();
    assertEquals(0, damage.take(5000));
    assertThrows(IllegalArgumentException.class, () -> damage.add(0));
  }

  @Test
  void externalDamageUsesOnlyArmedTriggers() {
    ExternalDamage damage = new ExternalDamage();
    damage.add(37);
    // Triggers 1, 2, 4 and 8 were touched by another client in this window: 32 now, 5 later.
    assertEquals(32, damage.take(1000, 0b1111_0000));
    assertEquals(5, damage.queued());
    assertEquals(0, damage.take(1050, 0xff));
    // Only 2 is armed: as much as fits.
    assertEquals(4, damage.take(1100, 0b0000_0100));
    assertEquals(1, damage.take(1200, 0xff));
    damage.add(3);
    assertEquals(0, damage.take(1300, 0b1111_1000), "nothing armed fits in 3");
    assertEquals(3, damage.queued());
    assertEquals(44, ExternalDamage.largestWithin(44, 0xff));
    assertEquals(42, ExternalDamage.largestWithin(44, 0b1111_1010));
    assertEquals(0, ExternalDamage.largestWithin(44, 0));
  }

  @Test
  void hostTriggerEntitiesParseAsTheGameReadsThem() {
    float[] park = {0, 0, -60000};
    List<BspEntity> entities = HostTriggers.entities(4, park);
    String text = EntityText.write(entities);
    List<BspEntity> parsed = EntityParser.parse(text);
    assertEquals(HostTriggers.MODELS + 2, parsed.size());
    for (int i = 0; i < ExternalDamage.TRIGGERS; i++) {
      BspEntity hurt = parsed.get(i);
      assertEquals("trigger_hurt", hurt.classname().orElseThrow());
      assertEquals("*" + (4 + i), hurt.get("model").orElseThrow());
      assertEquals(Integer.toString(1 << i), hurt.get("dmg").orElseThrow());
      assertEquals("0 0 -60000", hurt.get("origin").orElseThrow());
    }
    assertEquals("*12", parsed.get(8).get("model").orElseThrow());
    assertEquals(HostTriggers.GIVE_NAME, parsed.get(9).get("targetname").orElseThrow());
    assertEquals("item_health_mega", parsed.get(10).classname().orElseThrow());
    assertTrue(text.startsWith("{\n\"classname\" \"trigger_hurt\""));
    assertEquals("1.5", EntityText.number(1.5f));
    assertEquals("-3", EntityText.number(-3f));
    assertThrows(
        IllegalArgumentException.class, () -> EntityText.write(List.of(BspEntity.of("a", "b\"c"))));
  }
}
