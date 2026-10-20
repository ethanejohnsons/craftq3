package dev.bluevista.craftq3.engine.vmabi.layout;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.vm.QvmMemory;

/**
 * Typed accessors over {@link QvmMemory} for the primitives the structs are made of. Every access
 * is bounds-checked by the memory; {@code vec3_t} is three little-endian floats.
 */
public final class VmStructs {
  /** {@code sizeof(vec3_t)}. */
  public static final int VEC3_BYTES = 12;

  /** {@code sizeof(vec3_t[3])}, an axis or matrix. */
  public static final int AXIS_BYTES = 36;

  private VmStructs() {}

  public static Vec3f readVec3(QvmMemory memory, int address) {
    memory.resolve(address, VEC3_BYTES);
    return new Vec3f(
        memory.readFloat(address), memory.readFloat(address + 4), memory.readFloat(address + 8));
  }

  public static void writeVec3(QvmMemory memory, int address, Vec3f value) {
    memory.resolve(address, VEC3_BYTES);
    memory.writeFloat(address, value.x());
    memory.writeFloat(address + 4, value.y());
    memory.writeFloat(address + 8, value.z());
  }

  /** Reads a vector into {@code out[0..2]} without allocating. */
  public static void readVec3(QvmMemory memory, int address, float[] out) {
    memory.resolve(address, VEC3_BYTES);
    out[0] = memory.readFloat(address);
    out[1] = memory.readFloat(address + 4);
    out[2] = memory.readFloat(address + 8);
  }

  public static void writeVec3(QvmMemory memory, int address, float[] value) {
    memory.resolve(address, VEC3_BYTES);
    memory.writeFloat(address, value[0]);
    memory.writeFloat(address + 4, value[1]);
    memory.writeFloat(address + 8, value[2]);
  }

  /** Reads {@code vec3_t[3]} as three vectors. */
  public static Vec3f[] readAxis(QvmMemory memory, int address) {
    memory.resolve(address, AXIS_BYTES);
    return new Vec3f[] {
      readVec3(memory, address), readVec3(memory, address + 12), readVec3(memory, address + 24)
    };
  }

  public static void writeAxis(QvmMemory memory, int address, Vec3f[] axis) {
    if (axis.length != 3) throw new IllegalArgumentException("An axis has three vectors");
    memory.resolve(address, AXIS_BYTES);
    for (int i = 0; i < 3; i++) writeVec3(memory, address + 12 * i, axis[i]);
  }

  public static void readInts(QvmMemory memory, int address, int[] out) {
    memory.resolve(address, 4 * out.length);
    for (int i = 0; i < out.length; i++) out[i] = memory.readInt(address + 4 * i);
  }

  public static void writeInts(QvmMemory memory, int address, int[] values) {
    memory.resolve(address, 4 * values.length);
    for (int i = 0; i < values.length; i++) memory.writeInt(address + 4 * i, values[i]);
  }

  /** Reads {@code char[capacity]} up to its first NUL (the whole field when unterminated). */
  public static String readChars(QvmMemory memory, int address, int capacity) {
    byte[] bytes = memory.readBytes(address, capacity);
    int length = 0;
    while (length < capacity && bytes[length] != 0) length++;
    return new String(bytes, 0, length, java.nio.charset.StandardCharsets.ISO_8859_1);
  }

  /** Zero-fills {@code char[capacity]} then writes {@code text} truncated to fit with its NUL. */
  public static void writeChars(QvmMemory memory, int address, String text, int capacity) {
    memory.fill(address, capacity, 0);
    memory.writeString(address, text, capacity);
  }

  /** {@code qboolean}: any non-zero word is true. */
  public static boolean readBoolean(QvmMemory memory, int address) {
    return memory.readInt(address) != 0;
  }

  public static void writeBoolean(QvmMemory memory, int address, boolean value) {
    memory.writeInt(address, value ? 1 : 0);
  }
}
