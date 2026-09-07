package dev.bluevista.craftq3.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.assets.bsp.ShaderFixture;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.server.VmAbi;
import org.junit.jupiter.api.Test;

final class ClientCollisionTest {
  @Test
  void projectedFragmentsWriteOwnedPointAndRangeTablesWithinGuestCapacities() throws Exception {
    var collision = new ClientCollision(BspReader.read(ShaderFixture.map()));
    var memory = ClientTestData.memory();
    Vec3[] points = {
      new Vec3(-280, -.1, 250),
      new Vec3(-260, -.1, 250),
      new Vec3(-260, -.1, 270),
      new Vec3(-280, -.1, 270)
    };
    for (int i = 0; i < points.length; i++) VmAbi.vector(memory, 100 + i * 12, points[i]);
    VmAbi.vector(memory, 200, new Vec3(0, 4, 0));
    memory.fill(1000, 1204, 255);
    memory.fill(3000, 132, 255);
    int count = collision.marks(memory, new int[] {4, 100, 200, 100, 1000, 16, 3000});
    assertTrue(count > 0);
    int used = 0;
    for (int i = 0; i < count; i++) {
      assertEquals(used, memory.readInt(3000 + i * 8));
      int size = memory.readInt(3004 + i * 8);
      assertTrue(size >= 3);
      used += size;
    }
    assertTrue(used <= 100);
    assertEquals(-1, memory.readInt(2200));
    assertEquals(-1, memory.readInt(3128));
    assertThrows(
        dev.bluevista.craftq3.vm.QvmException.class,
        () -> collision.marks(memory, new int[] {4, 100, 200, 100, memory.size() - 4, 16, 3000}));
  }

  @Test
  void allSolidImpactWithZeroProjectionDoesNotDecodeUndefinedCorners() throws Exception {
    var collision = new ClientCollision(BspReader.read(ShaderFixture.map()));
    var memory = ClientTestData.memory();
    for (int i = 0; i < 12; i++) VmAbi.floating(memory, 100 + i * 4, Float.NaN);
    for (int i = 0; i < 3; i++) VmAbi.floating(memory, 200 + i * 4, -0.0f);
    memory.fill(1000, 1204, 255);
    memory.fill(3000, 132, 255);
    assertEquals(0, collision.marks(memory, new int[] {4, 100, 200, 100, 1000, 16, 3000}));
    for (int offset = 1000; offset < 2204; offset += 4) assertEquals(-1, memory.readInt(offset));
    for (int offset = 3000; offset < 3132; offset += 4) assertEquals(-1, memory.readInt(offset));
    assertThrows(
        dev.bluevista.craftq3.vm.QvmException.class,
        () -> collision.marks(memory, new int[] {4, memory.size() - 4, 200, 100, 1000, 16, 3000}));
    assertThrows(
        dev.bluevista.craftq3.vm.QvmException.class,
        () -> collision.marks(memory, new int[] {4, 100, 200, 100, memory.size() - 4, 16, 3000}));
    VmAbi.vector(memory, 200, new Vec3(0, 4, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> collision.marks(memory, new int[] {4, 100, 200, 100, 1000, 16, 3000}));
  }

  @Test
  void temporaryPredictionBoxesPreserveContentsAndTransformedOrigin() throws Exception {
    var collision = new ClientCollision(BspReader.read(ShaderFixture.map()));
    var memory = ClientTestData.memory();
    int handle = collision.temporary(new Vec3(-1, -2, -3), new Vec3(1, 2, 3));
    VmAbi.vector(memory, 100, new Vec3(10, 20, 30));
    VmAbi.vector(memory, 200, new Vec3(10, 20, 30));
    VmAbi.vector(memory, 300, new Vec3(0, 0, 0));
    assertEquals(0x2000000, collision.contents(memory, new int[] {100, handle, 200, 300}, true));
    assertEquals(0, collision.contents(memory, new int[] {100, handle}, false));
    assertThrows(IllegalArgumentException.class, () -> collision.inline(9999));
  }
}
