package dev.bluevista.craftq3.engine.vmabi.layout;

import dev.bluevista.craftq3.engine.vm.QvmMemory;

/** {@code markFragment_t}: a run of points in the {@code trap_CM_MarkFragments} point buffer. */
public final class MarkFragmentLayout {
  public static final int FIRST_POINT = 0;
  public static final int NUM_POINTS = 4;
  public static final int BYTES = 8;

  private MarkFragmentLayout() {}

  public static void write(QvmMemory memory, int address, int firstPoint, int numPoints) {
    memory.resolve(address, BYTES);
    memory.writeInt(address + FIRST_POINT, firstPoint);
    memory.writeInt(address + NUM_POINTS, numPoints);
  }

  public static int firstPoint(QvmMemory memory, int address) {
    return memory.readInt(address + FIRST_POINT);
  }

  public static int numPoints(QvmMemory memory, int address) {
    return memory.readInt(address + NUM_POINTS);
  }
}
