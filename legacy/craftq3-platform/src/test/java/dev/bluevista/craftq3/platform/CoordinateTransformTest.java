package dev.bluevista.craftq3.platform;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.math.Vec3;
import org.junit.jupiter.api.Test;

class CoordinateTransformTest {
  @Test
  void axesAndRoundTrip() {
    var transform = new CoordinateTransform(32, new Vec3(10, 20, 30));
    assertEquals(new Vec3(11, 23, 28), transform.toMinecraft(new Vec3(32, 64, 96)));
    var q = new Vec3(-17.5, 90.25, 1024);
    assertEquals(q, transform.toQuake(transform.toMinecraft(q)));
    assertThrows(IllegalArgumentException.class, () -> new CoordinateTransform(0, q));
  }
}
