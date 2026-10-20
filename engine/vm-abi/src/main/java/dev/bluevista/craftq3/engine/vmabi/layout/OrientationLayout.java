package dev.bluevista.craftq3.engine.vmabi.layout;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.vm.QvmMemory;

/** {@code orientation_t}: an origin and a 3x3 axis, 48 bytes ({@code trap_R_LerpTag} output). */
public final class OrientationLayout {
  public static final int ORIGIN = 0;
  public static final int AXIS = 12;
  public static final int BYTES = 48;

  private OrientationLayout() {}

  public static void write(QvmMemory memory, int address, Vec3f origin, Vec3f[] axis) {
    memory.resolve(address, BYTES);
    VmStructs.writeVec3(memory, address + ORIGIN, origin);
    VmStructs.writeAxis(memory, address + AXIS, axis);
  }

  public static Vec3f origin(QvmMemory memory, int address) {
    return VmStructs.readVec3(memory, address + ORIGIN);
  }

  public static Vec3f[] axis(QvmMemory memory, int address) {
    return VmStructs.readAxis(memory, address + AXIS);
  }
}
