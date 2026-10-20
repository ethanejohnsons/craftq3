package dev.bluevista.craftq3.engine.vmabi.service;

import dev.bluevista.craftq3.engine.common.math.VecMath;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.layout.VmStructs;

/**
 * The libc and math intrinsics every module imports (memset, memcpy, strncpy, the float functions,
 * the qagame vector helpers and {@code trap_SnapVector}). Float arguments and results travel as raw
 * bits; transcendental functions use {@link StrictMath} in double precision and round once to
 * float, which matches the C library calls on the platforms Quake III shipped for and is
 * reproducible everywhere.
 */
public final class Intrinsics {
  private Intrinsics() {}

  private static float f(int bits) {
    return Float.intBitsToFloat(bits);
  }

  private static int bits(double value) {
    return Float.floatToRawIntBits((float) value);
  }

  /** {@code memset(dest, c, count)}: returns {@code dest}. */
  public static int memset(QvmInstance vm, int[] a) {
    vm.memory().fill(a[0], a[2], a[1]);
    return a[0];
  }

  /** {@code memcpy(dest, src, count)} with defined overlap: returns {@code dest}. */
  public static int memcpy(QvmInstance vm, int[] a) {
    vm.memory().copy(a[0], a[1], a[2]);
    return a[0];
  }

  /**
   * {@code strncpy(dest, src, count)}: copies up to {@code count} bytes, stopping at the source's
   * NUL, and pads the rest of {@code dest} with NULs; returns {@code dest}.
   */
  public static int strncpy(QvmInstance vm, int[] a) {
    QvmMemory memory = vm.memory();
    int count = a[2];
    memory.resolve(a[0], count);
    byte[] bytes = new byte[count];
    for (int i = 0; i < count; i++) {
      int value = memory.readUnsignedByte(a[1] + i);
      if (value == 0) break;
      bytes[i] = (byte) value;
    }
    memory.writeBytes(a[0], bytes);
    return a[0];
  }

  public static int sin(QvmInstance vm, int[] a) {
    return bits(StrictMath.sin(f(a[0])));
  }

  public static int cos(QvmInstance vm, int[] a) {
    return bits(StrictMath.cos(f(a[0])));
  }

  /** {@code atan2(y, x)}. */
  public static int atan2(QvmInstance vm, int[] a) {
    return bits(StrictMath.atan2(f(a[0]), f(a[1])));
  }

  public static int sqrt(QvmInstance vm, int[] a) {
    return bits(StrictMath.sqrt(f(a[0])));
  }

  public static int floor(QvmInstance vm, int[] a) {
    return bits(StrictMath.floor(f(a[0])));
  }

  public static int ceil(QvmInstance vm, int[] a) {
    return bits(StrictMath.ceil(f(a[0])));
  }

  /** cgame {@code acos}: results above pi or below -pi are clamped to pi; NaN stays NaN. */
  public static int acos(QvmInstance vm, int[] a) {
    float angle = (float) StrictMath.acos(f(a[0]));
    if (angle > (float) Math.PI || angle < (float) -Math.PI) angle = (float) Math.PI;
    return Float.floatToRawIntBits(angle);
  }

  /** {@code MatrixMultiply(in1[3][3], in2[3][3], out[3][3])}: {@code out = in1 x in2}. */
  public static int matrixMultiply(QvmInstance vm, int[] a) {
    QvmMemory memory = vm.memory();
    float[] m1 = readMatrix(memory, a[0]);
    float[] m2 = readMatrix(memory, a[1]);
    float[] out = multiply(m1, m2);
    memory.resolve(a[2], VmStructs.AXIS_BYTES);
    for (int i = 0; i < 9; i++) memory.writeFloat(a[2] + 4 * i, out[i]);
    return 0;
  }

  /** Row-major 3x3 product in float arithmetic, summing terms in index order. */
  public static float[] multiply(float[] m1, float[] m2) {
    float[] out = new float[9];
    for (int i = 0; i < 3; i++)
      for (int j = 0; j < 3; j++)
        out[3 * i + j] = m1[3 * i] * m2[j] + m1[3 * i + 1] * m2[3 + j] + m1[3 * i + 2] * m2[6 + j];
    return out;
  }

  private static float[] readMatrix(QvmMemory memory, int address) {
    memory.resolve(address, VmStructs.AXIS_BYTES);
    float[] m = new float[9];
    for (int i = 0; i < 9; i++) m[i] = memory.readFloat(address + 4 * i);
    return m;
  }

  /**
   * {@code AngleVectors(angles, forward, right, up)} through {@link VecMath#angleVectors}; null
   * output pointers are skipped.
   */
  public static int angleVectors(QvmInstance vm, int[] a) {
    QvmMemory memory = vm.memory();
    float[] angles = new float[3];
    VmStructs.readVec3(memory, a[0], angles);
    float[] forward = new float[3], right = new float[3], up = new float[3];
    VecMath.angleVectors(angles, forward, right, up);
    if (a[1] != 0) VmStructs.writeVec3(memory, a[1], forward);
    if (a[2] != 0) VmStructs.writeVec3(memory, a[2], right);
    if (a[3] != 0) VmStructs.writeVec3(memory, a[3], up);
    return 0;
  }

  /** {@code PerpendicularVector(dst, src)}. */
  public static int perpendicularVector(QvmInstance vm, int[] a) {
    QvmMemory memory = vm.memory();
    float[] source = new float[3];
    VmStructs.readVec3(memory, a[1], source);
    VmStructs.writeVec3(memory, a[0], perpendicular(source));
    return 0;
  }

  /**
   * A unit vector perpendicular to {@code src} (assumed normalized): the axis along which {@code
   * src} is smallest (the first such axis on ties, threshold 1) is projected onto the plane through
   * the origin with normal {@code src}, then normalized.
   */
  public static float[] perpendicular(float[] src) {
    int axis = 0;
    float smallest = 1f;
    for (int i = 0; i < 3; i++) {
      if (Math.abs(src[i]) < smallest) {
        axis = i;
        smallest = Math.abs(src[i]);
      }
    }
    float[] unit = new float[3];
    unit[axis] = 1f;
    float inverse = 1f / VecMath.dot(src, src);
    float distance = VecMath.dot(src, unit) * inverse;
    float[] result = new float[3];
    for (int i = 0; i < 3; i++) result[i] = unit[i] - distance * (src[i] * inverse);
    VecMath.normalize(result);
    return result;
  }

  /** {@code trap_SnapVector(v)}: rounds each component to the nearest integer (ties to even). */
  public static int snapVector(QvmInstance vm, int[] a) {
    QvmMemory memory = vm.memory();
    memory.resolve(a[0], VmStructs.VEC3_BYTES);
    for (int i = 0; i < 3; i++) {
      int address = a[0] + 4 * i;
      memory.writeFloat(address, (float) Math.rint(memory.readFloat(address)));
    }
    return 0;
  }
}
