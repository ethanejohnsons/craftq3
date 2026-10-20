package dev.bluevista.craftq3.engine.vmabi.layout;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.vm.QvmMemory;

/** {@code trace_t} with its embedded {@code cplane_t}. Identical in both profiles. */
public final class TraceLayout {
  public static final int ALL_SOLID = 0;
  public static final int START_SOLID = 4;
  public static final int FRACTION = 8;
  public static final int END_POS = 12;
  public static final int PLANE = 24;
  public static final int PLANE_NORMAL = PLANE;
  public static final int PLANE_DIST = PLANE + 12;
  public static final int PLANE_TYPE = PLANE + 16;
  public static final int PLANE_SIGNBITS = PLANE + 17;

  /** {@code cplane_t} ends with two pad bytes after signbits. */
  public static final int PLANE_BYTES = 20;

  public static final int SURFACE_FLAGS = 44;
  public static final int CONTENTS = 48;
  public static final int ENTITY_NUM = 52;
  public static final int BYTES = 56;

  /** {@code PLANE_X}, {@code PLANE_Y}, {@code PLANE_Z}: axial planes; else non-axial. */
  public static final int PLANE_X = 0, PLANE_Y = 1, PLANE_Z = 2, PLANE_NON_AXIAL = 3;

  /** {@code ENTITYNUM_NONE}: the trace hit nothing. */
  public static final int ENTITYNUM_NONE = 1023;

  /** {@code ENTITYNUM_WORLD}. */
  public static final int ENTITYNUM_WORLD = 1022;

  private TraceLayout() {}

  /** One trace result in guest terms. */
  public record VmTrace(
      boolean allSolid,
      boolean startSolid,
      float fraction,
      Vec3f endPos,
      Vec3f planeNormal,
      float planeDist,
      int surfaceFlags,
      int contents,
      int entityNum) {
    /** A trace that moved the whole way and hit nothing. */
    public static VmTrace clear(Vec3f end) {
      return new VmTrace(false, false, 1, end, Vec3f.ZERO, 0, 0, 0, ENTITYNUM_NONE);
    }
  }

  /** {@code PlaneTypeForNormal}: axial only for an exact unit normal along +X, +Y or +Z. */
  public static int planeType(Vec3f normal) {
    if (normal.x() == 1f) return PLANE_X;
    if (normal.y() == 1f) return PLANE_Y;
    if (normal.z() == 1f) return PLANE_Z;
    return PLANE_NON_AXIAL;
  }

  /** {@code SetPlaneSignbits}: bit {@code j} set when component {@code j} is negative. */
  public static int signBits(Vec3f normal) {
    return (normal.x() < 0 ? 1 : 0) | (normal.y() < 0 ? 2 : 0) | (normal.z() < 0 ? 4 : 0);
  }

  public static void write(QvmMemory memory, int address, VmTrace trace) {
    memory.fill(address, BYTES, 0);
    VmStructs.writeBoolean(memory, address + ALL_SOLID, trace.allSolid());
    VmStructs.writeBoolean(memory, address + START_SOLID, trace.startSolid());
    memory.writeFloat(address + FRACTION, trace.fraction());
    VmStructs.writeVec3(memory, address + END_POS, trace.endPos());
    VmStructs.writeVec3(memory, address + PLANE_NORMAL, trace.planeNormal());
    memory.writeFloat(address + PLANE_DIST, trace.planeDist());
    memory.writeByte(address + PLANE_TYPE, planeType(trace.planeNormal()));
    memory.writeByte(address + PLANE_SIGNBITS, signBits(trace.planeNormal()));
    memory.writeInt(address + SURFACE_FLAGS, trace.surfaceFlags());
    memory.writeInt(address + CONTENTS, trace.contents());
    memory.writeInt(address + ENTITY_NUM, trace.entityNum());
  }

  public static VmTrace read(QvmMemory memory, int address) {
    memory.resolve(address, BYTES);
    return new VmTrace(
        VmStructs.readBoolean(memory, address + ALL_SOLID),
        VmStructs.readBoolean(memory, address + START_SOLID),
        memory.readFloat(address + FRACTION),
        VmStructs.readVec3(memory, address + END_POS),
        VmStructs.readVec3(memory, address + PLANE_NORMAL),
        memory.readFloat(address + PLANE_DIST),
        memory.readInt(address + SURFACE_FLAGS),
        memory.readInt(address + CONTENTS),
        memory.readInt(address + ENTITY_NUM));
  }

  public static int planeType(QvmMemory memory, int address) {
    return memory.readUnsignedByte(address + PLANE_TYPE);
  }

  public static int signBits(QvmMemory memory, int address) {
    return memory.readUnsignedByte(address + PLANE_SIGNBITS);
  }
}
