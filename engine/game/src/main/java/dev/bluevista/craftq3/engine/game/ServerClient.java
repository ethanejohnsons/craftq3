package dev.bluevista.craftq3.engine.game;

import dev.bluevista.craftq3.engine.common.info.InfoString;
import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout.UserCmd;
import java.util.Map;

/**
 * One client slot ({@code client_t}): its connection state, userinfo, last user command, snapshot
 * schedule and the reliable server-command window. Bots and network clients share the slot type; a
 * network client's commands are drained to its connection by {@code Protocol68Host}, a bot's are
 * read by botlib's {@code GetConsoleMessage}. Owned by the simulation thread.
 */
public final class ServerClient {
  /** {@code clientState_t}. */
  public enum State {
    FREE,
    /** Dropped; the slot waits a moment so the final disconnect can be delivered. */
    ZOMBIE,
    /** Accepted by the game; waiting to receive the gamestate. */
    CONNECTED,
    /** Gamestate sent; enters the world with its first user command. */
    PRIMED,
    ACTIVE
  }

  /** {@code MAX_RELIABLE_COMMANDS}. */
  public static final int MAX_RELIABLE = Protocol68.MAX_RELIABLE_COMMANDS;

  private final int number;
  State state = State.FREE;
  boolean bot;
  boolean local;
  String address = "";
  String userinfo = "";
  String name = "";
  UserCmd lastUsercmd = UserCmd.idle(0);
  int snapshotMsec = 50;
  int nextSnapshotTime;
  int lastPacketTime;
  int lastConnectTime;
  int nextReliableTime;
  int timeoutCount;
  int ping = 999;
  int rate = 3000;
  boolean botDegraded;
  int botFailures;
  final String[] reliable = new String[MAX_RELIABLE];
  int reliableSequence;
  int reliableAcknowledge;

  ServerClient(int number) {
    this.number = number;
  }

  public int number() {
    return number;
  }

  public State state() {
    return state;
  }

  public boolean isBot() {
    return bot;
  }

  /** A loopback (local player) connection: snapshots every frame, never rate limited. */
  public boolean isLocal() {
    return local;
  }

  /** The connection's address as published in the {@code ip} key. */
  public String address() {
    return address;
  }

  public String userinfo() {
    return userinfo;
  }

  /** The userinfo as key/value pairs; an unparsable string reads as empty. */
  public Map<String, String> userinfoMap() {
    try {
      return InfoString.parse(userinfo, Protocol68.MAX_INFO_STRING);
    } catch (IllegalArgumentException malformed) {
      return Map.of();
    }
  }

  public String name() {
    return name;
  }

  public UserCmd lastUsercmd() {
    return lastUsercmd;
  }

  public int ping() {
    return ping;
  }

  public int rate() {
    return rate;
  }

  /** {@code snapshotMsec}: 1000 / snaps, at most one per server frame. */
  public int snapshotMsec() {
    return snapshotMsec;
  }

  public int nextSnapshotTime() {
    return nextSnapshotTime;
  }

  public int lastPacketTime() {
    return lastPacketTime;
  }

  /** True after a botlib call for this bot failed. */
  public boolean botDegraded() {
    return botDegraded;
  }

  public int botFailures() {
    return botFailures;
  }

  public int reliableSequence() {
    return reliableSequence;
  }

  public int reliableAcknowledge() {
    return reliableAcknowledge;
  }

  /** Schedules the next snapshot ({@code nextSnapshotTime}, in server time). */
  public void scheduleSnapshot(int serverTime) {
    nextSnapshotTime = serverTime;
  }

  /** Reliable command {@code sequence} while it is still in the window, else null. */
  public String reliableCommand(int sequence) {
    if (sequence <= reliableSequence - MAX_RELIABLE || sequence > reliableSequence) return null;
    return reliable[sequence & (MAX_RELIABLE - 1)];
  }

  /** The connection reports that the client acknowledged commands up to {@code sequence}. */
  public void acknowledgeReliable(int sequence) {
    if (sequence > reliableAcknowledge && sequence <= reliableSequence)
      reliableAcknowledge = sequence;
  }

  /** Clears everything for a new occupant. */
  void reset() {
    state = State.FREE;
    bot = false;
    local = false;
    address = "";
    userinfo = "";
    name = "";
    lastUsercmd = UserCmd.idle(0);
    snapshotMsec = 50;
    nextSnapshotTime = 0;
    lastPacketTime = 0;
    nextReliableTime = 0;
    timeoutCount = 0;
    ping = 999;
    rate = 3000;
    botDegraded = false;
    botFailures = 0;
    java.util.Arrays.fill(reliable, null);
    reliableSequence = 0;
    reliableAcknowledge = 0;
  }

  @Override
  public String toString() {
    return "client " + number + " (" + state + (bot ? ", bot" : "") + ", " + name + ")";
  }
}
