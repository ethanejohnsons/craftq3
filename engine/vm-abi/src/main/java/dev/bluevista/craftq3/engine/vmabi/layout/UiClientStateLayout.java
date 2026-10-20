package dev.bluevista.craftq3.engine.vmabi.layout;

import dev.bluevista.craftq3.engine.vm.QvmMemory;

/** {@code uiClientState_t}, 3084 bytes, identical in both profiles. */
public final class UiClientStateLayout {
  public static final int CONN_STATE = 0;
  public static final int CONNECT_PACKET_COUNT = 4;
  public static final int CLIENT_NUM = 8;
  public static final int SERVER_NAME = 12;
  public static final int UPDATE_INFO_STRING = 1036;
  public static final int MESSAGE_STRING = 2060;

  /** {@code MAX_STRING_CHARS}. */
  public static final int STRING_BYTES = 1024;

  public static final int BYTES = MESSAGE_STRING + STRING_BYTES;

  /** {@code connstate_t} values. */
  public static final int CA_UNINITIALIZED = 0,
      CA_DISCONNECTED = 1,
      CA_AUTHORIZING = 2,
      CA_CONNECTING = 3,
      CA_CHALLENGING = 4,
      CA_CONNECTED = 5,
      CA_LOADING = 6,
      CA_PRIMED = 7,
      CA_ACTIVE = 8,
      CA_CINEMATIC = 9;

  private UiClientStateLayout() {}

  /** The client connection state the ui shows. */
  public record UiClientState(
      int connState,
      int connectPacketCount,
      int clientNum,
      String serverName,
      String updateInfo,
      String message) {
    public static UiClientState disconnected() {
      return new UiClientState(CA_DISCONNECTED, 0, -1, "", "", "");
    }
  }

  public static void write(QvmMemory memory, int address, UiClientState state) {
    memory.fill(address, BYTES, 0);
    memory.writeInt(address + CONN_STATE, state.connState());
    memory.writeInt(address + CONNECT_PACKET_COUNT, state.connectPacketCount());
    memory.writeInt(address + CLIENT_NUM, state.clientNum());
    memory.writeString(address + SERVER_NAME, state.serverName(), STRING_BYTES);
    memory.writeString(address + UPDATE_INFO_STRING, state.updateInfo(), STRING_BYTES);
    memory.writeString(address + MESSAGE_STRING, state.message(), STRING_BYTES);
  }

  public static UiClientState read(QvmMemory memory, int address) {
    memory.resolve(address, BYTES);
    return new UiClientState(
        memory.readInt(address + CONN_STATE),
        memory.readInt(address + CONNECT_PACKET_COUNT),
        memory.readInt(address + CLIENT_NUM),
        VmStructs.readChars(memory, address + SERVER_NAME, STRING_BYTES),
        VmStructs.readChars(memory, address + UPDATE_INFO_STRING, STRING_BYTES),
        VmStructs.readChars(memory, address + MESSAGE_STRING, STRING_BYTES));
  }
}
