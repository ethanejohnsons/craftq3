package dev.bluevista.craftq3.server;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.collision.BoxTraceWorld;
import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ExternalDamageTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);

  @Test
  void hurtAdaptersOnlyParticipateInAnExplicitDamageContactWindow() throws Exception {
    var terrain = new BoxTraceWorld(new Vec3(-100, -100, -10), new Vec3(100, 100, -1), 1, 0, 1022);
    var metadata = ExternalWorld.combat(terrain, new Vec3(0, 0, 25), 0).metadata();
    assertEquals(256, metadata.models().size());
    for (var abi : GameAbi.values()) {
      var memory = GameAbiTest.memory();
      var world = new EntityWorld(metadata, memory, abi, terrain);
      world.locate(1024, 4, 1024, 8192, 1024, 1);
      int a = 2048, b = 3072;
      world.brushModel(a, "*25");
      world.brushModel(b, "*30");
      memory.writeInt(a + abi.contents(), 0x40000000);
      memory.writeInt(b + abi.contents(), 0x40000000);
      world.link(a);
      world.link(b);
      var lo = new Vec3(-2, -2, -2);
      var hi = new Vec3(2, 2, 2);
      assertEquals(List.of(), world.entitiesInBox(lo, hi, 1024));
      assertFalse(world.contact(lo, hi, a));
      world.armDamage(25);
      assertEquals(List.of(1), world.entitiesInBox(lo, hi, 1024));
      assertTrue(world.contact(lo, hi, a));
      assertFalse(world.contact(lo, hi, b));
      // A hurt adapter is never solid world geometry or a prediction obstacle.
      assertEquals(
          1, world.trace(TraceRequest.ray(new Vec3(-5, 0, 0), new Vec3(5, 0, 0), 1)).fraction());
      world.armDamage(0);
      assertFalse(world.contact(lo, hi, a));
      assertThrows(IllegalStateException.class, () -> world.armDamage(256));
      world.reset();
      assertEquals(List.of(), world.entitiesInBox(lo, hi, 1024));
    }
  }

  @Test
  void ordinaryExternalTerrainDoesNotAcceptDamageAdapters() throws Exception {
    var terrain = new BoxTraceWorld(new Vec3(-1, -1, -1), new Vec3(1, 1, 1), 1, 0, 1022);
    var world =
        new EntityWorld(
            ExternalWorld.at(terrain, ZERO, 0).metadata(),
            GameAbiTest.memory(),
            GameAbi.Q3_132,
            terrain);
    assertThrows(IllegalStateException.class, () -> world.armDamage(1));
    assertDoesNotThrow(() -> world.armDamage(0));
  }
}
