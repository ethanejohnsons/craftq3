package dev.bluevista.craftq3.core.net;

import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.core.cvar.InfoString;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One protocol-68 challenge/connect exchange. The transport must pin the selected peer before
 * delivering packets. Scheduling, retries, timeouts and displaying server print text belong to the
 * caller; received text never executes commands.
 */
public final class Protocol68Handshake {
  public enum State {
    CHALLENGING,
    CONNECTING,
    CONNECTED
  }

  public enum Result {
    IGNORED,
    CHALLENGE_ACCEPTED,
    CONNECTED,
    PRINT
  }

  private final int nonce;
  private final int qport;
  private final Map<String, String> userInfo;
  private State state = State.CHALLENGING;
  private int challenge;
  private String print = "";
  private byte[] connectPacket;

  public Protocol68Handshake(int nonce, int qport, Map<String, String> userInfo) {
    if (qport < 0 || qport > 65535) throw new IllegalArgumentException("Invalid qport");
    InfoString.encode(userInfo, 1024);
    this.nonce = nonce;
    this.qport = qport;
    this.userInfo = Map.copyOf(userInfo);
    wireInfo(Integer.MIN_VALUE); // Reserve the longest possible signed challenge before networking.
  }

  public State state() {
    return state;
  }

  public int challenge() {
    if (state == State.CHALLENGING) throw new IllegalStateException("No server challenge yet");
    return challenge;
  }

  public int qport() {
    return qport;
  }

  public String print() {
    return print;
  }

  /** The current request is stable across retransmission and returned in an owned array. */
  public byte[] request() {
    return switch (state) {
      case CHALLENGING -> ConnectionlessPacket.text("getchallenge " + nonce + " Quake3Arena");
      case CONNECTING -> connectPacket.clone();
      case CONNECTED -> throw new IllegalStateException("Handshake already complete");
    };
  }

  /** Invalid, stale and unrelated packets cannot change the exchange. */
  public Result receive(byte[] datagram) {
    if (state == State.CONNECTED
        || Protocol68Datagram.classify(datagram) != Protocol68Datagram.Kind.CONNECTIONLESS)
      return Result.IGNORED;
    ConnectionlessMessage envelope;
    CommandParser.Command command;
    try {
      envelope = ConnectionlessMessage.parse(datagram);
      command = CommandParser.tokenize(envelope.line());
    } catch (IllegalArgumentException ignored) {
      return Result.IGNORED;
    }
    try {
      int count = command.arguments().size();
      if (command.argument(0).equals("print")) {
        print = new String(envelope.body(), java.nio.charset.StandardCharsets.ISO_8859_1);
        return Result.PRINT;
      }
      if (state == State.CHALLENGING && command.argument(0).equals("challengeResponse")) {
        if (count < 2 || count > 4) return Result.IGNORED;
        int offered = Integer.parseInt(command.argument(1));
        // Older protocol-68 replies omit the echoed nonce/protocol. Validate both when supplied.
        if (count >= 3 && Integer.parseInt(command.argument(2)) != nonce) return Result.IGNORED;
        if (count == 4 && Integer.parseInt(command.argument(3)) != 68) return Result.IGNORED;
        byte[] packet =
            ConnectPacketCodec.compress(
                ConnectionlessPacket.text("connect \"" + wireInfo(offered) + "\""));
        challenge = offered;
        connectPacket = packet;
        state = State.CONNECTING;
        return Result.CHALLENGE_ACCEPTED;
      }
      if (state == State.CONNECTING && command.argument(0).equals("connectResponse")) {
        if (count < 1 || count > 2) return Result.IGNORED;
        if (count == 2 && Integer.parseInt(command.argument(1)) != challenge) return Result.IGNORED;
        state = State.CONNECTED;
        return Result.CONNECTED;
      }
    } catch (NumberFormatException ignored) {
      // Malformed untrusted input is not an application failure.
    }
    return Result.IGNORED;
  }

  private String wireInfo(int offered) {
    var info = new LinkedHashMap<String, String>();
    new java.util.TreeMap<>(userInfo)
        .forEach(
            (key, value) -> {
              if (!key.equalsIgnoreCase("protocol")
                  && !key.equalsIgnoreCase("qport")
                  && !key.equalsIgnoreCase("challenge")) info.put(key, value);
            });
    info.put("protocol", "68");
    info.put("qport", Integer.toString(qport));
    info.put("challenge", Integer.toString(offered));
    return InfoString.encode(info, 1024);
  }
}
