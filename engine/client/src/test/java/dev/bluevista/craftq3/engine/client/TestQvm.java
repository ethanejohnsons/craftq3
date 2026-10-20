package dev.bluevista.craftq3.engine.client;

import dev.bluevista.craftq3.engine.vm.Opcode;
import dev.bluevista.craftq3.engine.vm.QvmImage;
import dev.bluevista.craftq3.engine.vmabi.CgameImport;
import dev.bluevista.craftq3.engine.vmabi.VmExports;
import dev.bluevista.craftq3.engine.vmabi.layout.SnapshotLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Hand-assembled QVM modules for client tests: no game code or data. */
public final class TestQvm {
  /** BSS of the synthetic modules; buffers live in the first part, below the 64 KiB stack. */
  public static final int BSS = 131072;

  private record Instruction(Opcode opcode, int operand) {}

  private final List<Instruction> code = new ArrayList<>();
  private final byte[] data = new byte[64];

  public TestQvm op(Opcode opcode, int operand) {
    code.add(new Instruction(opcode, operand));
    return this;
  }

  public TestQvm op(Opcode opcode) {
    return op(opcode, 0);
  }

  /** The index the next instruction gets (jump targets). */
  public int here() {
    return code.size();
  }

  public void patch(int index, int operand) {
    code.set(index, new Instruction(code.get(index).opcode(), operand));
  }

  /** Calls trap {@code number} with constant argument words, discarding the result. */
  public TestQvm trap(int number, int... args) {
    for (int i = 0; i < args.length; i++) op(Opcode.CONST, args[i]).op(Opcode.ARG, 8 + 4 * i);
    return op(Opcode.CONST, -1 - number).op(Opcode.CALL).op(Opcode.POP);
  }

  public byte[] bytes() {
    int length = code.stream().mapToInt(i -> 1 + i.opcode().operandBytes()).sum();
    length = (length + 3) & ~3;
    ByteBuffer out = ByteBuffer.allocate(32 + length + data.length).order(ByteOrder.LITTLE_ENDIAN);
    out.putInt(QvmImage.MAGIC_V1).putInt(code.size()).putInt(32).putInt(length);
    out.putInt(32 + length).putInt(data.length).putInt(0).putInt(BSS);
    for (Instruction instruction : code) {
      out.put((byte) instruction.opcode().ordinal());
      if (instruction.opcode().operandBytes() == 4) out.putInt(instruction.operand());
      else if (instruction.opcode().operandBytes() == 1) out.put((byte) instruction.operand());
    }
    out.position(32 + length);
    out.put(data);
    return out.array();
  }

  /** A module whose {@code vmMain(0)} returns {@code value} and every other command 0. */
  public static byte[] returning(int value) {
    TestQvm q = new TestQvm();
    int frame = 16;
    q.op(Opcode.ENTER, frame);
    q.op(Opcode.LOCAL, frame + 8).op(Opcode.LOAD4).op(Opcode.CONST, 0);
    int other = q.here();
    q.op(Opcode.NE, 0);
    q.op(Opcode.CONST, value).op(Opcode.LEAVE, frame);
    q.patch(other, q.here());
    q.op(Opcode.CONST, 0).op(Opcode.LEAVE, frame);
    return q.bytes();
  }

  /** Address of the snapshot buffer of {@link #snapshotCgame()}. */
  public static final int SNAPSHOT = 1024;

  /** Address of the snapshot number and time words. */
  public static final int NUMBER = 256, TIME = 260;

  /**
   * A cgame whose {@code CG_INIT} reads the gamestate and whose {@code CG_DRAW_ACTIVE_FRAME} reads
   * the current snapshot and draws one stretch pic at x = its server time, y = its entity count.
   */
  public static byte[] snapshotCgame() {
    TestQvm q = new TestQvm();
    int frame = 64;
    q.op(Opcode.ENTER, frame);
    // if (command == CG_INIT) gamestate
    q.op(Opcode.LOCAL, frame + 8)
        .op(Opcode.LOAD4)
        .op(Opcode.CONST, VmExports.Cgame.CG_INIT.command());
    int notInit = q.here();
    q.op(Opcode.NE, 0);
    q.trap(CgameImport.CG_GETGAMESTATE.number(), SNAPSHOT);
    q.op(Opcode.CONST, 0).op(Opcode.LEAVE, frame);
    q.patch(notInit, q.here());
    // if (command != CG_DRAW_ACTIVE_FRAME) return 0
    q.op(Opcode.LOCAL, frame + 8)
        .op(Opcode.LOAD4)
        .op(Opcode.CONST, VmExports.Cgame.CG_DRAW_ACTIVE_FRAME.command());
    int notDraw = q.here();
    q.op(Opcode.NE, 0);
    q.trap(CgameImport.CG_GETCURRENTSNAPSHOTNUMBER.number(), NUMBER, TIME);
    // trap_GetSnapshot(*NUMBER, SNAPSHOT)
    q.op(Opcode.CONST, NUMBER).op(Opcode.LOAD4).op(Opcode.ARG, 8);
    q.op(Opcode.CONST, SNAPSHOT).op(Opcode.ARG, 12);
    q.op(Opcode.CONST, -1 - CgameImport.CG_GETSNAPSHOT.number()).op(Opcode.CALL).op(Opcode.POP);
    // trap_R_DrawStretchPic(serverTime, numEntities, 1, 1, 0, 0, 1, 1, 0)
    q.op(Opcode.CONST, SNAPSHOT + SnapshotLayout.SERVER_TIME)
        .op(Opcode.LOAD4)
        .op(Opcode.CVIF)
        .op(Opcode.ARG, 8);
    q.op(Opcode.CONST, SNAPSHOT + SnapshotLayout.Q3_132.numEntities())
        .op(Opcode.LOAD4)
        .op(Opcode.CVIF)
        .op(Opcode.ARG, 12);
    int one = Float.floatToRawIntBits(1);
    int[] rest = {one, one, 0, 0, one, one, 0};
    for (int i = 0; i < rest.length; i++) q.op(Opcode.CONST, rest[i]).op(Opcode.ARG, 16 + 4 * i);
    q.op(Opcode.CONST, -1 - CgameImport.CG_R_DRAWSTRETCHPIC.number())
        .op(Opcode.CALL)
        .op(Opcode.POP);
    q.patch(notDraw, q.here());
    q.op(Opcode.CONST, 0).op(Opcode.LEAVE, frame);
    return q.bytes();
  }
}
