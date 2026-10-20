package dev.bluevista.craftq3.engine.vmabi.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.common.math.VecMath;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.AbiProfile;
import dev.bluevista.craftq3.engine.vmabi.Bindings;
import dev.bluevista.craftq3.engine.vmabi.CgameImport;
import dev.bluevista.craftq3.engine.vmabi.ImportTable;
import dev.bluevista.craftq3.engine.vmabi.QagameImport;
import dev.bluevista.craftq3.engine.vmabi.TestVm;
import dev.bluevista.craftq3.engine.vmabi.VmHost;
import dev.bluevista.craftq3.engine.vmabi.VmImport;
import dev.bluevista.craftq3.engine.vmabi.VmModule;
import dev.bluevista.craftq3.engine.vmabi.layout.VmStructs;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class IntrinsicsTest {
  private final QvmInstance vm = TestVm.instance();
  private final QvmMemory memory = vm.memory();

  private static int bits(float value) {
    return Float.floatToRawIntBits(value);
  }

  private static float f(int bits) {
    return Float.intBitsToFloat(bits);
  }

  private static int[] args(int... values) {
    int[] padded = new int[16];
    System.arraycopy(values, 0, padded, 0, values.length);
    return padded;
  }

  @Test
  void memoryIntrinsicsHaveLibcSemantics() {
    assertEquals(0x100, Intrinsics.memset(vm, args(0x100, 0xab, 8)));
    for (int i = 0; i < 8; i++) assertEquals(0xab, memory.readUnsignedByte(0x100 + i));
    assertEquals(0, memory.readUnsignedByte(0x108));

    memory.writeString(0x200, "hello", 16);
    assertEquals(0x202, Intrinsics.memcpy(vm, args(0x202, 0x200, 5)));
    assertEquals("hehello", memory.readString(0x200, 16));

    memory.fill(0x300, 8, 0x55);
    memory.writeString(0x400, "abc", 8);
    assertEquals(0x300, Intrinsics.strncpy(vm, args(0x300, 0x400, 6)));
    assertArrayEquals(new byte[] {'a', 'b', 'c', 0, 0, 0}, memory.readBytes(0x300, 6));
    assertEquals(0x55, memory.readUnsignedByte(0x306));
    memory.writeString(0x400, "abcdefgh", 16);
    memory.fill(0x300, 8, 0x55);
    Intrinsics.strncpy(vm, args(0x300, 0x400, 4));
    assertArrayEquals(new byte[] {'a', 'b', 'c', 'd', 0x55}, memory.readBytes(0x300, 5));
  }

  @Test
  void floatFunctionsRoundOnceFromDouble() {
    Random random = new Random(11);
    for (int i = 0; i < 200; i++) {
      float x = (random.nextFloat() - 0.5f) * 20;
      float y = (random.nextFloat() - 0.5f) * 20;
      assertEquals((float) StrictMath.sin(x), f(Intrinsics.sin(vm, args(bits(x)))));
      assertEquals((float) StrictMath.cos(x), f(Intrinsics.cos(vm, args(bits(x)))));
      assertEquals((float) StrictMath.atan2(y, x), f(Intrinsics.atan2(vm, args(bits(y), bits(x)))));
      assertEquals(
          (float) StrictMath.sqrt(Math.abs(x)), f(Intrinsics.sqrt(vm, args(bits(Math.abs(x))))));
      assertEquals((float) Math.floor(x), f(Intrinsics.floor(vm, args(bits(x)))));
      assertEquals((float) Math.ceil(x), f(Intrinsics.ceil(vm, args(bits(x)))));
    }
    assertEquals((float) Math.PI, f(Intrinsics.acos(vm, args(bits(-1f)))));
    assertEquals(0f, f(Intrinsics.acos(vm, args(bits(1f)))));
    assertTrue(Float.isNaN(f(Intrinsics.acos(vm, args(bits(2f))))));
    assertTrue(Float.isNaN(f(Intrinsics.sqrt(vm, args(bits(-1f))))));
  }

  @Test
  void angleVectorsMatchVecMath() {
    float[] angles = {30, 45, 10};
    VmStructs.writeVec3(memory, 0x100, angles);
    Intrinsics.angleVectors(vm, args(0x100, 0x200, 0x300, 0x400));
    float[] forward = new float[3], right = new float[3], up = new float[3];
    VecMath.angleVectors(angles, forward, right, up);
    float[] out = new float[3];
    VmStructs.readVec3(memory, 0x200, out);
    assertArrayEquals(forward, out);
    VmStructs.readVec3(memory, 0x300, out);
    assertArrayEquals(right, out);
    VmStructs.readVec3(memory, 0x400, out);
    assertArrayEquals(up, out);
    memory.fill(0x500, 12, 0x11);
    Intrinsics.angleVectors(vm, args(0x100, 0, 0x500, 0));
    VmStructs.readVec3(memory, 0x500, out);
    assertArrayEquals(right, out);
  }

  @Test
  void matrixMultiplyIsTheRowMajorProduct() {
    float[] a = {1, 2, 3, 4, 5, 6, 7, 8, 9};
    float[] b = {9, 8, 7, 6, 5, 4, 3, 2, 1};
    for (int i = 0; i < 9; i++) {
      memory.writeFloat(0x100 + 4 * i, a[i]);
      memory.writeFloat(0x200 + 4 * i, b[i]);
    }
    Intrinsics.matrixMultiply(vm, args(0x100, 0x200, 0x300));
    float[] expected = {30, 24, 18, 84, 69, 54, 138, 114, 90};
    for (int i = 0; i < 9; i++) assertEquals(expected[i], memory.readFloat(0x300 + 4 * i));
    // In-place output is allowed.
    Intrinsics.matrixMultiply(vm, args(0x100, 0x200, 0x100));
    assertEquals(30f, memory.readFloat(0x100));
  }

  @Test
  void perpendicularVectorIsUnitAndOrthogonal() {
    Random random = new Random(5);
    for (int i = 0; i < 100; i++) {
      float[] source = {random.nextFloat() - 0.5f, random.nextFloat() - 0.5f, random.nextFloat()};
      VecMath.normalize(source);
      VmStructs.writeVec3(memory, 0x100, source);
      Intrinsics.perpendicularVector(vm, args(0x200, 0x100));
      float[] result = new float[3];
      VmStructs.readVec3(memory, 0x200, result);
      assertEquals(0, VecMath.dot(source, result), 1e-5);
      assertEquals(1, VecMath.length(result), 1e-5);
    }
    float[] axis = Intrinsics.perpendicular(new float[] {0, 0, 1});
    assertArrayEquals(new float[] {1, 0, 0}, axis);
  }

  @Test
  void snapVectorRoundsToNearestEven() {
    VmStructs.writeVec3(memory, 0x100, new float[] {1.5f, 2.5f, -0.6f});
    Intrinsics.snapVector(vm, args(0x100));
    float[] out = new float[3];
    VmStructs.readVec3(memory, 0x100, out);
    assertArrayEquals(new float[] {2, 2, -1}, out);
  }

  @Test
  void eachModuleBindsItsOwnIntrinsicNumbers() {
    Bindings game = SharedServices.installIntrinsics(new Bindings(VmModule.QAGAME));
    assertTrue(game.bound(QagameImport.TRAP_PERPENDICULARVECTOR));
    Bindings cgame = SharedServices.installIntrinsics(new Bindings(VmModule.CGAME));
    assertTrue(cgame.bound(CgameImport.CG_ACOS));
    // cgame 107 is floor, qagame 107 is MatrixMultiply.
    for (VmImport entry : ImportTable.of(VmModule.CGAME, AbiProfile.Q3_132).entries())
      if (entry.policy().requiresHandler() && !cgame.bound(entry)) cgame.bind(entry, (v, a) -> 0);
    VmHost host =
        new VmHost(
            ImportTable.of(VmModule.CGAME, AbiProfile.Q3_132), cgame, VmHost.Options.strict());
    assertEquals(2.0f, f(TestVm.caller(host, 107, bits(2.75f)).vmMain(0)));
    assertEquals(3.0f, f(TestVm.caller(host, 108, bits(2.25f)).vmMain(0)));
  }
}
