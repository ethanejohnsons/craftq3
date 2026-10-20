package dev.bluevista.craftq3.engine.vmabi.layout;

import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.AbiProfile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * {@code usercmd_t}. Both profiles use 24 bytes, but retail stores {@code buttons} as a byte right
 * after {@code serverTime}, then {@code weapon}, the angles and the three moves:
 *
 * <pre>
 *   1.32:   serverTime angles[3] buttons(int) weapon forwardmove rightmove upmove
 *   retail: serverTime buttons(byte) weapon pad[2] angles[3] forwardmove rightmove upmove pad
 * </pre>
 *
 * The protocol-68 canonical form is the 1.32 layout.
 */
public final class UserCmdLayout {
  public static final int BYTES = 24;

  public static final UserCmdLayout Q3_132 = new UserCmdLayout(0, 4, 16, false, 20, 21, 22, 23);
  public static final UserCmdLayout RETAIL_1999 = new UserCmdLayout(0, 8, 4, true, 5, 20, 21, 22);

  private final int serverTime;
  private final int angles;
  private final int buttons;
  private final boolean byteButtons;
  private final int weapon;
  private final int forwardMove;
  private final int rightMove;
  private final int upMove;

  private UserCmdLayout(
      int serverTime,
      int angles,
      int buttons,
      boolean byteButtons,
      int weapon,
      int forwardMove,
      int rightMove,
      int upMove) {
    this.serverTime = serverTime;
    this.angles = angles;
    this.buttons = buttons;
    this.byteButtons = byteButtons;
    this.weapon = weapon;
    this.forwardMove = forwardMove;
    this.rightMove = rightMove;
    this.upMove = upMove;
  }

  public static UserCmdLayout of(AbiProfile profile) {
    return profile == AbiProfile.RETAIL_1999 ? RETAIL_1999 : Q3_132;
  }

  public int size() {
    return BYTES;
  }

  public int serverTime() {
    return serverTime;
  }

  /** {@code int angles[3]}: 16-bit short angles widened to ints. */
  public int angles() {
    return angles;
  }

  public int buttons() {
    return buttons;
  }

  /** True when {@code buttons} is one byte (retail). */
  public boolean byteButtons() {
    return byteButtons;
  }

  public int weapon() {
    return weapon;
  }

  public int forwardMove() {
    return forwardMove;
  }

  public int rightMove() {
    return rightMove;
  }

  public int upMove() {
    return upMove;
  }

  /** One input frame in Quake units. */
  public record UserCmd(
      int serverTime,
      int pitch,
      int yaw,
      int roll,
      int buttons,
      int weapon,
      int forward,
      int right,
      int up) {
    public UserCmd {
      if (weapon < 0
          || weapon > 255
          || forward < -128
          || forward > 127
          || right < -128
          || right > 127
          || up < -128
          || up > 127)
        throw new IllegalArgumentException(
            "User command byte field out of range: weapon="
                + weapon
                + " forward="
                + forward
                + " right="
                + right
                + " up="
                + up);
    }

    /** No input at {@code time}. */
    public static UserCmd idle(int time) {
      return new UserCmd(time, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** Parses the protocol-68 canonical 24 bytes (the 1.32 layout). */
    public static UserCmd fromCanonical(byte[] bytes) {
      if (bytes.length != BYTES) throw new IllegalArgumentException("Invalid user command size");
      ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
      return new UserCmd(
          in.getInt(0),
          in.getInt(4),
          in.getInt(8),
          in.getInt(12),
          in.getInt(16),
          Byte.toUnsignedInt(in.get(20)),
          in.get(21),
          in.get(22),
          in.get(23));
    }

    /** The protocol-68 canonical 24 bytes. */
    public byte[] toCanonical() {
      ByteBuffer out = ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN);
      out.putInt(serverTime).putInt(pitch).putInt(yaw).putInt(roll).putInt(buttons);
      out.put((byte) weapon).put((byte) forward).put((byte) right).put((byte) up);
      return out.array();
    }
  }

  /** Writes {@code cmd} in this layout, zeroing padding. Retail keeps only the low button byte. */
  public void write(QvmMemory memory, int address, UserCmd cmd) {
    memory.fill(address, BYTES, 0);
    memory.writeInt(address + serverTime, cmd.serverTime());
    memory.writeInt(address + angles, cmd.pitch());
    memory.writeInt(address + angles + 4, cmd.yaw());
    memory.writeInt(address + angles + 8, cmd.roll());
    if (byteButtons) memory.writeByte(address + buttons, cmd.buttons());
    else memory.writeInt(address + buttons, cmd.buttons());
    memory.writeByte(address + weapon, cmd.weapon());
    memory.writeByte(address + forwardMove, cmd.forward());
    memory.writeByte(address + rightMove, cmd.right());
    memory.writeByte(address + upMove, cmd.up());
  }

  public UserCmd read(QvmMemory memory, int address) {
    memory.resolve(address, BYTES);
    return new UserCmd(
        memory.readInt(address + serverTime),
        memory.readInt(address + angles),
        memory.readInt(address + angles + 4),
        memory.readInt(address + angles + 8),
        byteButtons
            ? memory.readUnsignedByte(address + buttons)
            : memory.readInt(address + buttons),
        memory.readUnsignedByte(address + weapon),
        (byte) memory.readUnsignedByte(address + forwardMove),
        (byte) memory.readUnsignedByte(address + rightMove),
        (byte) memory.readUnsignedByte(address + upMove));
  }
}
