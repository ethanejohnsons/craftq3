package dev.bluevista.craftq3.client;

import dev.bluevista.craftq3.server.GameAbi;
import dev.bluevista.craftq3.server.VmAbi;
import dev.bluevista.craftq3.vm.QvmMemory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Verified guest layouts; all offsets refer to little-endian QVM memory. */
public enum ClientAbi {
  Q3_132(GameAbi.Q3_132, 8192),
  RETAIL_1999(GameAbi.RETAIL_1999, 1024);

  static final String RETAIL_SHA256 =
      "ee31bdb9865c3e11afdff3b5f65dbe9599de9527f2493a25a347a8b0ce5eb098";
  private final GameAbi game;
  private final int extensionBytes;

  ClientAbi(GameAbi game, int extensionBytes) {
    this.game = game;
    this.extensionBytes = extensionBytes;
  }

  public GameAbi game() {
    return game;
  }

  public int glconfigBytes() {
    return 3072 + extensionBytes + 68;
  }

  public int glconfigWidth() {
    return 3072 + extensionBytes + 40;
  }

  public int snapshotBytes() {
    return 44 + game.playerStateBytes() + 4 + 256 * game.entityStateBytes() + 8;
  }

  public static ClientAbi detect(byte[] bytes) {
    try {
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      return RETAIL_SHA256.equals(hash) ? RETAIL_1999 : Q3_132;
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  void glconfig(QvmMemory memory, int address, int width, int height) {
    memory.fill(address, glconfigBytes(), 0);
    VmAbi.string(memory, address, "CraftQ3", 1024);
    VmAbi.string(memory, address + 1024, "CraftQ3 Java", 1024);
    VmAbi.string(memory, address + 2048, "Q3 renderer service ABI", 1024);
    int tail = address + 3072 + extensionBytes;
    memory.writeInt(tail, 4096);
    memory.writeInt(tail + 4, 2);
    memory.writeInt(tail + 8, 32);
    memory.writeInt(tail + 12, 24);
    memory.writeInt(tail + 16, 8);
    memory.writeInt(tail + 40, width);
    memory.writeInt(tail + 44, height);
    memory.writeFloat(tail + 48, (float) width / height);
    memory.writeInt(tail + 52, 60);
  }

  static void gamestate(QvmMemory memory, int address, Map<Integer, String> strings) {
    memory.fill(address, 20100, 0);
    int used = 1;
    for (int index = 0; index < 1024; index++) {
      String text = strings.getOrDefault(index, "");
      if (text.isEmpty()) continue;
      if ((long) used + text.length() + 1 > 16000)
        throw new IllegalStateException("Gamestate string budget exceeded");
      memory.writeInt(address + index * 4, used);
      VmAbi.string(memory, address + 4096 + used, text, text.length() + 1);
      used += text.length() + 1;
    }
    memory.writeInt(address + 20096, used);
  }

  void playerState(QvmMemory memory, int address, byte[] canonical) {
    if (canonical.length != VmAbi.PLAYER_STATE_BYTES)
      throw new IllegalArgumentException("Invalid canonical player state");
    if (this == Q3_132) memory.writeBytes(address, canonical);
    else {
      memory.writeBytes(address, java.util.Arrays.copyOf(canonical, 440));
      memory.writeBytes(address + 440, java.util.Arrays.copyOfRange(canonical, 452, 456));
    }
  }
}
