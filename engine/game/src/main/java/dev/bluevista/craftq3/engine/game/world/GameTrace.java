package dev.bluevista.craftq3.engine.game.world;

import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.layout.TraceLayout;

/**
 * A mutable {@code trace_t}: one result buffer reused by the server's traces so the hot path does
 * not allocate. Owned by the simulation thread.
 */
public final class GameTrace {
  private static final float[] ORIGIN = new float[3];

  public boolean allSolid;
  public boolean startSolid;
  public float fraction = 1;
  public final float[] endPos = new float[3];
  public final float[] planeNormal = new float[3];
  public float planeDist;
  public int surfaceFlags;
  public int contents;
  public int entityNum = TraceLayout.ENTITYNUM_NONE;

  /** A trace that moved the whole way to {@code end} and hit nothing. */
  public GameTrace clear(float[] end) {
    allSolid = false;
    startSolid = false;
    fraction = 1;
    endPos[0] = end[0];
    endPos[1] = end[1];
    endPos[2] = end[2];
    planeNormal[0] = planeNormal[1] = planeNormal[2] = 0;
    planeDist = 0;
    surfaceFlags = 0;
    contents = 0;
    entityNum = TraceLayout.ENTITYNUM_NONE;
    return this;
  }

  /** {@code memset(trace, 0)} followed by {@code fraction = 1}, as {@code SV_ClipToEntity}. */
  public GameTrace zero() {
    clear(ORIGIN);
    entityNum = 0;
    return this;
  }

  public GameTrace copyFrom(GameTrace other) {
    allSolid = other.allSolid;
    startSolid = other.startSolid;
    fraction = other.fraction;
    System.arraycopy(other.endPos, 0, endPos, 0, 3);
    System.arraycopy(other.planeNormal, 0, planeNormal, 0, 3);
    planeDist = other.planeDist;
    surfaceFlags = other.surfaceFlags;
    contents = other.contents;
    entityNum = other.entityNum;
    return this;
  }

  /** Writes this result as a guest {@code trace_t}. */
  public void write(QvmMemory memory, int address) {
    memory.resolve(address, TraceLayout.BYTES);
    memory.writeInt(address + TraceLayout.ALL_SOLID, allSolid ? 1 : 0);
    memory.writeInt(address + TraceLayout.START_SOLID, startSolid ? 1 : 0);
    memory.writeFloat(address + TraceLayout.FRACTION, fraction);
    for (int i = 0; i < 3; i++) {
      memory.writeFloat(address + TraceLayout.END_POS + 4 * i, endPos[i]);
      memory.writeFloat(address + TraceLayout.PLANE_NORMAL + 4 * i, planeNormal[i]);
    }
    memory.writeFloat(address + TraceLayout.PLANE_DIST, planeDist);
    int type =
        planeNormal[0] == 1f
            ? TraceLayout.PLANE_X
            : planeNormal[1] == 1f
                ? TraceLayout.PLANE_Y
                : planeNormal[2] == 1f ? TraceLayout.PLANE_Z : TraceLayout.PLANE_NON_AXIAL;
    int signs = (planeNormal[0] < 0 ? 1 : 0) | (planeNormal[1] < 0 ? 2 : 0);
    signs |= planeNormal[2] < 0 ? 4 : 0;
    memory.writeByte(address + TraceLayout.PLANE_TYPE, type);
    memory.writeByte(address + TraceLayout.PLANE_SIGNBITS, signs);
    memory.writeShort(address + TraceLayout.PLANE_SIGNBITS + 1, 0);
    memory.writeInt(address + TraceLayout.SURFACE_FLAGS, surfaceFlags);
    memory.writeInt(address + TraceLayout.CONTENTS, contents);
    memory.writeInt(address + TraceLayout.ENTITY_NUM, entityNum);
  }

  @Override
  public String toString() {
    return "GameTrace[fraction="
        + fraction
        + ", allSolid="
        + allSolid
        + ", startSolid="
        + startSolid
        + ", end=("
        + endPos[0]
        + ","
        + endPos[1]
        + ","
        + endPos[2]
        + "), entity="
        + entityNum
        + ", contents="
        + contents
        + "]";
  }
}
