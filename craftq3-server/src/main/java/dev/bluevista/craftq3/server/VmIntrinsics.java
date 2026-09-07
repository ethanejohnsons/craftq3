package dev.bluevista.craftq3.server;

import dev.bluevista.craftq3.vm.QvmMemory;
import java.util.Arrays;
import java.util.OptionalInt;

/** Shared Q3 VM libc/math traps. Offsets and float arguments are 32-bit VM words. */
public final class VmIntrinsics {
  private VmIntrinsics() {}

  public static OptionalInt invoke(QvmMemory memory, int call, int[] args) {
    int result;
    switch (call) {
      case 100 -> {
        VmAbi.range(memory, args[0], args[2]);
        byte[] bytes = new byte[args[2]];
        Arrays.fill(bytes, (byte) args[1]);
        memory.writeBytes(args[0], bytes);
        result = args[0];
      }
      case 101 -> {
        VmAbi.range(memory, args[0], args[2]);
        VmAbi.range(memory, args[1], args[2]);
        memory.writeBytes(args[0], memory.readBytes(args[1], args[2]));
        result = args[0];
      }
      case 102 -> {
        VmAbi.range(memory, args[0], args[2]);
        byte[] bytes = new byte[args[2]];
        for (int i = 0; i < bytes.length; i++) {
          int value = memory.readUnsignedByte(Math.addExact(args[1], i));
          if (value == 0) break;
          bytes[i] = (byte) value;
        }
        memory.writeBytes(args[0], bytes);
        result = args[0];
      }
      case 103 -> result = bits(StrictMath.sin(f(args[0])));
      case 104 -> result = bits(StrictMath.cos(f(args[0])));
      case 105 -> result = bits(StrictMath.atan2(f(args[0]), f(args[1])));
      case 106 -> result = bits(StrictMath.sqrt(f(args[0])));
      case 110 -> result = bits(StrictMath.floor(f(args[0])));
      case 111 -> result = bits(StrictMath.ceil(f(args[0])));
      default -> {
        return OptionalInt.empty();
      }
    }
    return OptionalInt.of(result);
  }

  private static float f(int bits) {
    return Float.intBitsToFloat(bits);
  }

  private static int bits(double value) {
    return Float.floatToRawIntBits((float) value);
  }
}
