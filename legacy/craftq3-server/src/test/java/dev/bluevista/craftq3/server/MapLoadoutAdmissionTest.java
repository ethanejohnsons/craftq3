package dev.bluevista.craftq3.server;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspFixture;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class MapLoadoutAdmissionTest {
  private PlayerLoadout loadout(int health) {
    return new PlayerLoadout(
        health, 57, 6, 2, 0, Collections.nCopies(16, 0), Collections.nCopies(6, 0));
  }

  @Test
  void retainsOriginalGeometryAndAddsOnlyAnExplicitlyTrustedInertContact() throws Exception {
    var source = BspReader.read(BspFixture.map(false));
    var admission = MapLoadoutAdmission.create(source, loadout(73));
    var map = admission.map();
    assertEquals(source.models().size() + 1, map.models().size());
    assertEquals(source.entities(), map.entities().subList(0, source.entities().size()));
    assertSame(source.brushes(), map.brushes());
    assertSame(source.planes(), map.planes());
    assertSame(source.faces(), map.faces());
    assertSame(source.vertices(), map.vertices());
    assertSame(source.lightmaps(), map.lightmaps());
    assertSame(source.visibility(), map.visibility());
    var abi = GameAbi.RETAIL_1999;
    var memory = GameAbiTest.memory();
    var untrusted = new EntityWorld(map, memory, abi);
    assertThrows(IllegalStateException.class, () -> untrusted.armDamage(27));
    var world = new EntityWorld(map, memory, abi, null, admission.damageModels());
    world.locate(1024, 2, 1024, 8192, 1024, 2);
    int entity = 2048;
    world.brushModel(entity, "*" + source.models().size());
    world.link(entity);
    var low = new Vec3(500, 500, 500);
    var high = new Vec3(510, 510, 510);
    assertFalse(world.contact(low, high, entity));
    assertFalse(world.entitiesInBox(low, high, 16).contains(1));
    world.armDamage(27);
    assertTrue(world.contact(low, high, entity));
    assertEquals(List.of(1), world.entitiesInBox(low, high, 16));
    world.armDamage(0);
    assertFalse(world.contact(low, high, entity));
  }

  @Test
  void naturalHealthBaselinesDoNotAddAnEntityOrModel() throws Exception {
    var source = BspReader.read(BspFixture.map(false));
    for (int health : List.of(100, 200)) {
      var admission = MapLoadoutAdmission.create(source, loadout(health));
      assertSame(source, admission.map());
      assertTrue(admission.damageModels().isEmpty());
    }
  }
}
