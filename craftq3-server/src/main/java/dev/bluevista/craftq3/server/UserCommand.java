package dev.bluevista.craftq3.server;

import dev.bluevista.craftq3.vm.QvmMemory;

/** Input in Quake units, independent of Minecraft ticks and key codes. */
public record UserCommand(
    int serverTime,
    int pitch,
    int yaw,
    int roll,
    int buttons,
    int weapon,
    int forward,
    int right,
    int up) {
  public UserCommand {
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

  /** Protocol-68 canonical bytes, independent of the selected qagame ABI. */
  public static UserCommand fromBytes(byte[] bytes) {
    if (bytes.length != 24) throw new IllegalArgumentException("Invalid user command size");
    var input = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    return new UserCommand(
        input.getInt(),
        input.getInt(),
        input.getInt(),
        input.getInt(),
        input.getInt(),
        Byte.toUnsignedInt(input.get()),
        input.get(),
        input.get(),
        input.get());
  }

  public static UserCommand idle(int time) {
    return new UserCommand(time, 0, 0, 0, 0, 0, 0, 0, 0);
  }

  public void write(QvmMemory memory, int address) {
    write(memory, address, GameAbi.Q3_132);
  }

  public void write(QvmMemory memory, int address, GameAbi abi) {
    VmAbi.range(memory, address, VmAbi.USER_COMMAND_BYTES);
    if (abi == GameAbi.RETAIL_1999) {
      memory.fill(address, VmAbi.USER_COMMAND_BYTES, 0);
      memory.writeInt(address, serverTime);
      memory.writeByte(address + 4, buttons);
      memory.writeByte(address + 5, weapon);
      memory.writeInt(address + 8, pitch);
      memory.writeInt(address + 12, yaw);
      memory.writeInt(address + 16, roll);
      memory.writeByte(address + 20, forward);
      memory.writeByte(address + 21, right);
      memory.writeByte(address + 22, up);
      return;
    }
    memory.writeInt(address, serverTime);
    memory.writeInt(address + 4, pitch);
    memory.writeInt(address + 8, yaw);
    memory.writeInt(address + 12, roll);
    memory.writeInt(address + 16, buttons);
    memory.writeByte(address + 20, weapon);
    memory.writeByte(address + 21, forward & 255);
    memory.writeByte(address + 22, right & 255);
    memory.writeByte(address + 23, up & 255);
  }
}
