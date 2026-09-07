package dev.bluevista.craftq3.client;

import java.util.List;

/** Host state borrowed by a standalone or in-game original Quake user interface. */
public interface UiHost {
  record ClientState(
      int connectionState,
      int connectPacketCount,
      int clientNum,
      String serverName,
      String updateInfo,
      String message) {
    public ClientState {
      if (connectionState < 0
          || connectionState > 9
          || connectPacketCount < 0
          || clientNum < -1
          || clientNum > 63) throw new IllegalArgumentException("Invalid UI client state");
      for (String value : new String[] {serverName, updateInfo, message}) {
        if (value == null || value.indexOf('\0') >= 0 || value.length() > 8192)
          throw new IllegalArgumentException("Invalid UI client state string");
      }
    }
  }

  ClientState clientState();

  String configString(int index);

  int keyCatcher();

  void keyCatcher(int value);

  void clearKeys();

  default UiBrowser browser() {
    return UiBrowser.EMPTY;
  }

  /** Flat physical protocol-68 filenames from the host's separate demo storage. */
  default List<String> demoFiles() {
    return List.of();
  }

  default boolean keyDown(int key) {
    return false;
  }

  default String clipboard() {
    return "";
  }

  default String cdKey() {
    return "";
  }

  default void cdKey(String value) {
    throw new UnsupportedOperationException("UI CD-key storage is not configured");
  }

  default boolean verifyCdKey(String value, String checksum) {
    if (value.isEmpty()) return false;
    throw new UnsupportedOperationException("UI CD-key verification is not configured");
  }

  /** An isolated disconnected host, useful before any local server exists. */
  static UiHost disconnected() {
    return new UiHost() {
      private int catcher;

      public ClientState clientState() {
        return new ClientState(1, 0, -1, "", "", "");
      }

      public String configString(int index) {
        return "";
      }

      public int keyCatcher() {
        return catcher;
      }

      public void keyCatcher(int value) {
        catcher = value;
      }

      public void clearKeys() {}
    };
  }
}
