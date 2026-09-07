package dev.bluevista.craftq3.client;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Exact retail UI recognition; modern modules use the public service layouts. */
public enum UiAbi {
  RETAIL_1999(ClientAbi.RETAIL_1999),
  Q3_132(ClientAbi.Q3_132);
  public static final String RETAIL_SHA256 =
      "826a342a108ac8a7fa45f4e752dfa5be50fa5ddf4fdb87d7c404d833c4989627";
  private final ClientAbi renderer;

  UiAbi(ClientAbi renderer) {
    this.renderer = renderer;
  }

  public ClientAbi renderer() {
    return renderer;
  }

  public static UiAbi detect(byte[] bytes) {
    try {
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      return RETAIL_SHA256.equals(hash) ? RETAIL_1999 : Q3_132;
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }
}
