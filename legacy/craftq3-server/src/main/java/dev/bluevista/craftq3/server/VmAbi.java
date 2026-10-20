package dev.bluevista.craftq3.server;

import dev.bluevista.craftq3.collision.TraceResult;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.vm.QvmMemory;
import java.nio.charset.StandardCharsets;

/** Explicit 32-bit Q3 engine ABI layouts; never host-JVM object layouts. */
public final class VmAbi {
  public static final int ENTITY_STATE_BYTES = 208,
      PLAYER_STATE_BYTES = 468,
      SHARED_ENTITY_BYTES = 516,
      USER_COMMAND_BYTES = 24,
      TRACE_BYTES = 56;

  private VmAbi() {}

  public static void range(QvmMemory memory, int address, int length) {
    if (address < 0 || length < 0 || (long) address + length > memory.size())
      throw new IllegalArgumentException(
          "VM ABI range outside memory: " + address + " + " + length);
  }

  public static float floating(QvmMemory memory, int address) {
    return Float.intBitsToFloat(memory.readInt(address));
  }

  public static void floating(QvmMemory memory, int address, float value) {
    memory.writeInt(address, Float.floatToRawIntBits(value));
  }

  public static Vec3 vector(QvmMemory memory, int address) {
    range(memory, address, 12);
    return new Vec3(
        floating(memory, address), floating(memory, address + 4), floating(memory, address + 8));
  }

  public static void vector(QvmMemory memory, int address, Vec3 value) {
    range(memory, address, 12);
    floating(memory, address, (float) value.x());
    floating(memory, address + 4, (float) value.y());
    floating(memory, address + 8, (float) value.z());
  }

  public static void string(QvmMemory memory, int address, String text, int capacity) {
    if (capacity < 1)
      throw new IllegalArgumentException("VM string buffer capacity must be positive");
    range(memory, address, capacity);
    byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
    int count = Math.min(bytes.length, capacity - 1);
    memory.writeBytes(address, java.util.Arrays.copyOf(bytes, count));
    memory.writeByte(address + count, 0);
  }

  public static void cvar(QvmMemory memory, int address, CvarSystem.Snapshot variable) {
    if (address == 0) return;
    range(memory, address, 272);
    memory.writeInt(address, variable.handle());
    memory.writeInt(address + 4, variable.modificationCount());
    floating(memory, address + 8, variable.floatValue());
    memory.writeInt(address + 12, variable.intValue());
    string(memory, address + 16, variable.value(), 256);
  }

  public static void trace(QvmMemory memory, int address, TraceResult result) {
    range(memory, address, TRACE_BYTES);
    memory.writeBytes(address, new byte[TRACE_BYTES]);
    memory.writeInt(address, result.allSolid() ? 1 : 0);
    memory.writeInt(address + 4, result.startSolid() ? 1 : 0);
    floating(memory, address + 8, (float) result.fraction());
    vector(memory, address + 12, result.endPosition());
    memory.writeInt(address + 52, 1023);
    result
        .hit()
        .ifPresent(
            hit -> {
              Vec3 normal = hit.plane().normal();
              vector(memory, address + 24, normal);
              floating(memory, address + 36, (float) hit.plane().distance());
              int type = normal.x() == 1 ? 0 : normal.y() == 1 ? 1 : normal.z() == 1 ? 2 : 3;
              int signs =
                  (normal.x() < 0 ? 1 : 0) | (normal.y() < 0 ? 2 : 0) | (normal.z() < 0 ? 4 : 0);
              memory.writeByte(address + 40, type);
              memory.writeByte(address + 41, signs);
              memory.writeInt(address + 44, hit.surfaceFlags());
              memory.writeInt(address + 48, hit.contents());
              memory.writeInt(address + 52, hit.entity());
            });
  }
}
