package dev.bluevista.craftq3.mc.hybrid;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CoordinateTransformTest {
  private static final double EPS = 1e-9;

  @Test
  void axesMapAsDocumented() {
    CoordinateTransform t = new CoordinateTransform(32, 10, 20, 30);
    // MC = origin + (q.x, q.z, -q.y) / 32
    double[] mc = t.toMinecraft(new Vec3f(32, 64, 96));
    assertArrayEquals(new double[] {11, 23, 28}, mc, EPS);
    assertEquals(new Vec3f(32, 64, 96), t.toQuake(11, 23, 28));
    // Quake +X is Minecraft +X (east), Quake +Y is Minecraft -Z (north), Quake +Z is Minecraft +Y.
    CoordinateTransform o = CoordinateTransform.at(0, 0, 0);
    assertArrayEquals(new double[] {1, 0, 0}, o.toMinecraft(new Vec3f(32, 0, 0)), EPS);
    assertArrayEquals(new double[] {0, 0, -1}, o.toMinecraft(new Vec3f(0, 32, 0)), EPS);
    assertArrayEquals(new double[] {0, 1, 0}, o.toMinecraft(new Vec3f(0, 0, 32)), EPS);
  }

  @Test
  void roundTripsPositionsExactlyOnTheGrid() {
    CoordinateTransform t = CoordinateTransform.at(-1200, 64, 3400);
    Random random = new Random(7);
    for (int i = 0; i < 1000; i++) {
      // Multiples of 1/32 block are exact in both directions.
      double x = -1200 + random.nextInt(4096) / 32.0;
      double y = 64 + random.nextInt(4096) / 32.0;
      double z = 3400 + random.nextInt(4096) / 32.0;
      Vec3f q = t.toQuake(x, y, z);
      assertArrayEquals(new double[] {x, y, z}, t.toMinecraft(q), 0);
    }
  }

  @Test
  void roundTripsArbitraryPositionsWithinFloatPrecision() {
    CoordinateTransform t = new CoordinateTransform(32, 100.5, -3.25, 7);
    Random random = new Random(11);
    float[] q = new float[3];
    double[] back = new double[3];
    for (int i = 0; i < 1000; i++) {
      double x = 100.5 + (random.nextDouble() - 0.5) * 1000;
      double y = -3.25 + (random.nextDouble() - 0.5) * 300;
      double z = 7 + (random.nextDouble() - 0.5) * 1000;
      t.toQuake(x, y, z, q);
      t.toMinecraft(q[0], q[1], q[2], back);
      // A float Quake coordinate near 16000 units keeps about 1/1000 unit.
      assertArrayEquals(new double[] {x, y, z}, back, 1e-4);
    }
  }

  @Test
  void directionsIgnoreTheOrigin() {
    CoordinateTransform a = CoordinateTransform.at(0, 0, 0);
    CoordinateTransform b = CoordinateTransform.at(500, -40, 9);
    float[] qa = new float[3];
    float[] qb = new float[3];
    a.toQuakeDirection(1, 2, 3, qa);
    b.toQuakeDirection(1, 2, 3, qb);
    assertArrayEquals(qa, qb);
    assertArrayEquals(new float[] {32, -96, 64}, qa);
    double[] back = new double[3];
    b.toMinecraftDirection(qa[0], qa[1], qa[2], back);
    assertArrayEquals(new double[] {1, 2, 3}, back, EPS);
  }

  @Test
  void velocitiesConvertTicksToSeconds() {
    CoordinateTransform t = CoordinateTransform.at(3, 4, 5);
    float[] q = new float[3];
    // 0.5 blocks/tick east = 10 blocks/s = 320 units/s along +X.
    t.velocityToQuake(0.5, 0, 0, q);
    assertArrayEquals(new float[] {320, 0, 0}, q);
    t.velocityToQuake(0, 0.25, -0.5, q);
    assertArrayEquals(new float[] {0, 320, 160}, q);
    double[] back = new double[3];
    t.velocityToMinecraft(q[0], q[1], q[2], back);
    assertArrayEquals(new double[] {0, 0.25, -0.5}, back, EPS);
  }

  @Test
  void normalsRotateWithoutScaling() {
    double[] mc = new double[3];
    CoordinateTransform.normalToMinecraft(0, 0, 1, mc);
    assertArrayEquals(new double[] {0, 1, 0}, mc, EPS);
    CoordinateTransform.normalToMinecraft(0, 1, 0, mc);
    assertArrayEquals(new double[] {0, 0, -1}, mc, EPS);
    // A non-unit input comes back unit length; a scaled displacement would not.
    CoordinateTransform.normalToMinecraft(3, 0, 4, mc);
    assertArrayEquals(new double[] {0.6, 0.8, 0}, mc, EPS);
    float[] q = new float[3];
    CoordinateTransform.normalToQuake(0.6, 0.8, 0, q);
    assertArrayEquals(new float[] {0.6f, 0, 0.8f}, q, 1e-6f);
    Random random = new Random(3);
    for (int i = 0; i < 200; i++) {
      double x = random.nextGaussian();
      double y = random.nextGaussian();
      double z = random.nextGaussian();
      CoordinateTransform.normalToQuake(x, y, z, q);
      CoordinateTransform.normalToMinecraft(q[0], q[1], q[2], mc);
      double length = Math.sqrt(x * x + y * y + z * z);
      assertArrayEquals(new double[] {x / length, y / length, z / length}, mc, 1e-6);
    }
  }

  @Test
  void boxesReorderTheFlippedAxis() {
    CoordinateTransform t = CoordinateTransform.at(10, 20, 30);
    float[] mins = new float[3];
    float[] maxs = new float[3];
    // One block at MC (10..11, 20..21, 30..31) occupies Quake x 0..32, y -32..0, z 0..32.
    t.boxToQuake(10, 20, 30, 11, 21, 31, mins, maxs);
    assertArrayEquals(new float[] {0, -32, 0}, mins);
    assertArrayEquals(new float[] {32, 0, 32}, maxs);
    double[] back = new double[6];
    t.boxToMinecraft(mins, maxs, back);
    assertArrayEquals(new double[] {10, 20, 30, 11, 21, 31}, back, EPS);
    // A slab: MC y 20..20.5 is Quake z 0..16.
    t.boxToQuake(10, 20, 30, 11, 20.5, 31, mins, maxs);
    assertEquals(16, maxs[2]);
    for (int axis = 0; axis < 3; axis++) assertTrue(mins[axis] <= maxs[axis]);
  }

  @Test
  void yawFacesTheSameDirection() {
    for (float qYaw = -180; qYaw < 180; qYaw += 7.5f) {
      double qr = Math.toRadians(qYaw);
      // Quake forward in Quake coordinates.
      double[] mcForward = new double[3];
      CoordinateTransform.at(0, 0, 0)
          .toMinecraftDirection(Math.cos(qr), Math.sin(qr), 0, mcForward);
      float mcYaw = CoordinateTransform.minecraftYaw(qYaw);
      double mr = Math.toRadians(mcYaw);
      // Minecraft facing vector: (-sin(yaw), 0, cos(yaw)).
      assertEquals(-Math.sin(mr), mcForward[0] * 32, 1e-5, "yaw " + qYaw);
      assertEquals(Math.cos(mr), mcForward[2] * 32, 1e-5, "yaw " + qYaw);
      assertEquals(
          CoordinateTransform.wrapDegrees(qYaw),
          CoordinateTransform.quakeYaw(mcYaw),
          1e-4,
          "round trip " + qYaw);
    }
    assertEquals(-90, CoordinateTransform.minecraftYaw(0));
    assertEquals(180, CoordinateTransform.minecraftYaw(90));
    assertEquals(30, CoordinateTransform.minecraftPitch(30));
    assertEquals(-45, CoordinateTransform.quakePitch(-45));
  }

  @Test
  void wrapsDegreesIntoHalfOpenRange() {
    assertEquals(180, CoordinateTransform.wrapDegrees(-180));
    assertEquals(180, CoordinateTransform.wrapDegrees(180));
    assertEquals(-170, CoordinateTransform.wrapDegrees(190));
    assertEquals(10, CoordinateTransform.wrapDegrees(730));
  }

  @Test
  void validatesScaleAndOrigin() {
    assertThrows(IllegalArgumentException.class, () -> new CoordinateTransform(0, 0, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new CoordinateTransform(5000, 0, 0, 0));
    assertThrows(
        IllegalArgumentException.class, () -> new CoordinateTransform(Double.NaN, 0, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CoordinateTransform(32, Double.POSITIVE_INFINITY, 0, 0));
    assertTrue(CoordinateTransform.at(1, 2, 3).gridAligned());
    assertFalse(new CoordinateTransform(32, 1.5, 2, 3).gridAligned());
    assertEquals(CoordinateTransform.at(1, 2, 3), new CoordinateTransform(32, 1, 2, 3));
    assertEquals(
        CoordinateTransform.at(4, 5, 6), CoordinateTransform.at(1, 2, 3).withOrigin(4, 5, 6));
  }
}
