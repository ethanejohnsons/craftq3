package dev.bluevista.craftq3.engine.vmabi.layout;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.vm.QvmMemory;

/** {@code polyVert_t}: position, texture coordinates and an RGBA modulate, 24 bytes. */
public final class PolyVertLayout {
  public static final int XYZ = 0;
  public static final int ST = 12;
  public static final int MODULATE = 20;
  public static final int BYTES = 24;

  private PolyVertLayout() {}

  /** One vertex; {@code modulate} is packed as {@code 0xAABBGGRR}. */
  public record PolyVert(Vec3f xyz, float s, float t, int modulate) {}

  public static PolyVert read(QvmMemory memory, int address) {
    memory.resolve(address, BYTES);
    return new PolyVert(
        VmStructs.readVec3(memory, address + XYZ),
        memory.readFloat(address + ST),
        memory.readFloat(address + ST + 4),
        memory.readInt(address + MODULATE));
  }

  public static void write(QvmMemory memory, int address, PolyVert vertex) {
    memory.resolve(address, BYTES);
    VmStructs.writeVec3(memory, address + XYZ, vertex.xyz());
    memory.writeFloat(address + ST, vertex.s());
    memory.writeFloat(address + ST + 4, vertex.t());
    memory.writeInt(address + MODULATE, vertex.modulate());
  }
}
