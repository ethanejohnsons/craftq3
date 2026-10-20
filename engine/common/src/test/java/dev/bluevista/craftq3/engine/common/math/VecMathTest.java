package dev.bluevista.craftq3.engine.common.math;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VecMathTest {
  @Test
  void recordAndArrayOperationsAgree() {
    Vec3f a = new Vec3f(1, 2, 3), b = new Vec3f(-4, 5, 0.5f);
    float[] out = new float[3];
    VecMath.cross(a.toArray(), b.toArray(), out);
    assertEquals(a.cross(b), Vec3f.of(out));
    assertEquals(a.dot(b), VecMath.dot(a.toArray(), b.toArray()));
    VecMath.multiplyAdd(a.toArray(), 2, b.toArray(), out);
    assertEquals(a.multiplyAdd(2, b), Vec3f.of(out));
  }

  @Test
  void crossMayAliasItsInputs() {
    float[] a = {1, 0, 0};
    VecMath.cross(a, new float[] {0, 1, 0}, a);
    assertArrayEquals(new float[] {0, 0, 1}, a);
  }

  @Test
  void normalizeLeavesZeroVectorsAlone() {
    float[] zero = {0, 0, 0};
    assertEquals(0, VecMath.normalize(zero));
    assertArrayEquals(new float[] {0, 0, 0}, zero);
    assertEquals(Vec3f.ZERO, Vec3f.ZERO.normalize());
    assertEquals(1, new Vec3f(3, 4, 12).normalize().length(), 1e-6);
  }

  @Test
  void angleVectorsFollowQuakeConventions() {
    float[] forward = new float[3], right = new float[3], up = new float[3];
    VecMath.angleVectors(new float[] {0, 90, 0}, forward, right, up);
    assertArrayEquals(new float[] {0, 1, 0}, forward, 1e-6f);
    assertArrayEquals(new float[] {1, 0, 0}, right, 1e-6f);
    assertArrayEquals(new float[] {0, 0, 1}, up, 1e-6f);
    VecMath.angleVectors(new float[] {90, 0, 0}, forward, null, null);
    assertArrayEquals(new float[] {0, 0, -1}, forward, 1e-6f);
  }
}
