package dev.bluevista.craftq3.core.net;

/** Legacy message key mixing. This small weighted checksum is not a cryptographic hash. */
public final class MessageHash {
  private MessageHash() {}

  public static int key(String text, int maximumBytes) {
    if (maximumBytes < 0 || maximumBytes > Protocol68Channel.MAX_MESSAGE)
      throw new IllegalArgumentException("Message hash bound must be 0..16384");
    int sum = 0;
    for (int i = 0; i < Math.min(text.length(), maximumBytes); i++) {
      char value = text.charAt(i);
      if (value == 0) break;
      if (value > 255) throw new IllegalArgumentException("Message keys require byte text");
      sum += (i + 119) * MessageStrings.sanitize(value);
    }
    return sum ^ (sum >> 10) ^ (sum >> 20);
  }

  /** Protocol-68 usercmd field key before the per-command timestamp is mixed by its delta codec. */
  public static int userCommandKey(int checksumFeed, int messageAcknowledge, String serverCommand) {
    return checksumFeed ^ messageAcknowledge ^ key(serverCommand, 32);
  }
}
