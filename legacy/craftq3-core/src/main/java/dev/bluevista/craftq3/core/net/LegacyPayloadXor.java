package dev.bluevista.craftq3.core.net;

/** Protocol-68 payload obfuscation, separate from Huffman coding and netchan framing. */
public final class LegacyPayloadXor {
  public static final int CLIENT_CLEAR_BYTES = 12, SERVER_CLEAR_BYTES = 4;

  private LegacyPayloadXor() {}

  /** Same operation encodes or decodes; header metadata is supplied by the session owner. */
  public static byte[] client(
      byte[] payload,
      int challenge,
      int serverId,
      int messageAcknowledge,
      String acknowledgedServerCommand) {
    return transform(
        payload,
        CLIENT_CLEAR_BYTES,
        challenge ^ serverId ^ messageAcknowledge,
        acknowledgedServerCommand);
  }

  public static byte[] server(
      byte[] payload, int challenge, int messageSequence, String acknowledgedClientCommand) {
    return transform(
        payload, SERVER_CLEAR_BYTES, challenge ^ messageSequence, acknowledgedClientCommand);
  }

  private static byte[] transform(byte[] payload, int start, int key, String command) {
    if (payload.length > Protocol68Channel.MAX_MESSAGE)
      throw new IllegalArgumentException("Legacy payload exceeds 16384 bytes");
    byte[] output = payload.clone();
    if (output.length <= start) return output;
    int length = command.indexOf(0);
    if (length < 0) length = command.length();
    if (length >= 1024) throw new IllegalArgumentException("Legacy command key exceeds 1023 bytes");
    byte[] text = new byte[length];
    for (int i = 0; i < length; i++) {
      char value = command.charAt(i);
      if (value > 255) throw new IllegalArgumentException("Legacy command keys require byte text");
      text[i] = (byte) MessageStrings.sanitize(value);
    }
    int index = 0;
    for (int i = start; i < output.length; i++) {
      int value = length == 0 ? 0 : Byte.toUnsignedInt(text[index]);
      key ^= value << (i & 1);
      output[i] ^= (byte) key;
      if (++index == length) index = 0;
    }
    return output;
  }
}
