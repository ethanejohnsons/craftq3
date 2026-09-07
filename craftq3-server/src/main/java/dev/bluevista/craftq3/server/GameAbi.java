package dev.bluevista.craftq3.server;

import dev.bluevista.craftq3.vm.QvmMemory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/** Explicit guest structure profiles, independent from CraftQ3's canonical snapshot layout. */
public enum GameAbi {
  Q3_132(208, 468, 416, 428),
  RETAIL_1999(204, 444, 408, -1);

  static final String RETAIL_QAGAME_SHA256 =
      "73d07e341bd21bff3e7ec2c961ea9cafe7ff9150e0ddcc5d8055757edece0f72";

  private final int entityStateBytes;
  private final int playerStateBytes;
  private final int linked;
  private final int singleClient;

  GameAbi(int entityStateBytes, int playerStateBytes, int linked, int singleClient) {
    this.entityStateBytes = entityStateBytes;
    this.playerStateBytes = playerStateBytes;
    this.linked = linked;
    this.singleClient = singleClient;
  }

  /** Recognizes only verified original bytes. Other modules use the published 1.32 ABI. */
  public static GameAbi detect(byte[] module) {
    try {
      return fromHash(
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(module)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError("Java requires SHA-256", impossible);
    }
  }

  static GameAbi fromHash(String hash) {
    return RETAIL_QAGAME_SHA256.equals(hash) ? RETAIL_1999 : Q3_132;
  }

  public int entityStateBytes() {
    return entityStateBytes;
  }

  public int playerStateBytes() {
    return playerStateBytes;
  }

  public int linked() {
    return linked;
  }

  public int linkCount() {
    return linked + 4;
  }

  public int svFlags() {
    return linked + 8;
  }

  public int singleClient() {
    return singleClient;
  }

  public int bmodel() {
    return linked + (singleClient < 0 ? 12 : 16);
  }

  public int mins() {
    return bmodel() + 4;
  }

  public int maxs() {
    return mins() + 12;
  }

  public int contents() {
    return maxs() + 12;
  }

  public int absmin() {
    return contents() + 4;
  }

  public int absmax() {
    return absmin() + 12;
  }

  public int origin() {
    return absmax() + 12;
  }

  public int angles() {
    return origin() + 12;
  }

  public int owner() {
    return angles() + 12;
  }

  public int sharedEntityBytes() {
    return owner() + 4;
  }

  public byte[] entityState(QvmMemory memory, int pointer) {
    return Arrays.copyOf(memory.readBytes(pointer, entityStateBytes), VmAbi.ENTITY_STATE_BYTES);
  }

  public byte[] playerState(QvmMemory memory, int pointer) {
    byte[] guest = memory.readBytes(pointer, playerStateBytes);
    if (this == Q3_132) return guest;
    byte[] canonical = new byte[VmAbi.PLAYER_STATE_BYTES];
    // Retail ends after ammo[16] and ping. Later releases add six independent words.
    System.arraycopy(guest, 0, canonical, 0, 440);
    System.arraycopy(guest, 440, canonical, 452, 4);
    return canonical;
  }
}
