package dev.bluevista.craftq3.server;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.collision.BoxTraceWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.*;
import org.junit.jupiter.api.Test;

class ExternalPickupTest {
  @Test
  void submitsOnlyItemIdentityAndPositionToOriginalGameAlongsideDamageAdapters() {
    var floor = new BoxTraceWorld(new Vec3(-100, -100, -32), new Vec3(100, 100, 0), 1, 0, 1022);
    var pickup = new ExternalPickup("weapon_rocketlauncher", new Vec3(32, 0, 32));
    var requests = new ArrayList<>(List.of(pickup));
    var world = ExternalWorld.combat(floor, new Vec3(0, 0, 24), 0, requests);
    requests.clear();
    assertEquals(
        Map.of("classname", "weapon_rocketlauncher", "origin", "32.0 0.0 32.0"),
        world.metadata().entities().getLast());
    assertEquals(258, world.metadata().entities().size());
    assertEquals(256, world.metadata().models().size());
    assertTrue(world.metadata().brushes().isEmpty());
    assertThrows(
        IllegalArgumentException.class, () -> new ExternalPickup("target_kill", new Vec3(0, 0, 0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExternalPickup("item_quad\"\n", new Vec3(0, 0, 0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExternalPickup("item_quad", new Vec3(1_000_001, 0, 0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ExternalWorld.combat(floor, new Vec3(0, 0, 24), 0, Collections.nCopies(257, pickup)));
  }
}
