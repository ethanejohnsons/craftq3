package dev.bluevista.craftq3.engine.net;

/**
 * Wire constants of the Quake III protocol-68 family (1.29 to 1.32, demo protocols 66 to 68). Every
 * codec in this module refers here; no other class declares its own copy of these limits.
 */
public final class Protocol68 {
  private Protocol68() {}

  /** Protocol number announced by 1.32 servers and accepted in {@code connect} userinfo. */
  public static final int PROTOCOL_VERSION = 68;

  /** Default UDP server port. */
  public static final int PORT_SERVER = 27960;

  // ---- Message and packet sizes -------------------------------------------------------------

  /** Largest assembled message, connectionless datagram or demo record payload, in bytes. */
  public static final int MAX_MSGLEN = 16384;

  /** Largest sequenced datagram accepted by netchan, headers included. */
  public static final int MAX_PACKETLEN = 1400;

  /** Fragment payload size; a message of at least this many bytes is sent in fragments. */
  public static final int FRAGMENT_SIZE = 1300;

  /** Netchan header bit marking a fragment. */
  public static final int FRAGMENT_BIT = 1 << 31;

  /** Bytes of the connectionless {@code 0xffffffff} marker. */
  public static final int CONNECTIONLESS_MARKER_BYTES = 4;

  // ---- Strings ------------------------------------------------------------------------------

  /** {@code MAX_STRING_CHARS}: ordinary MSG strings, including the terminator. */
  public static final int MAX_STRING_CHARS = 1024;

  /** {@code BIG_INFO_STRING}: configstring and system-info strings, including the terminator. */
  public static final int BIG_INFO_STRING = 8192;

  /** {@code MAX_INFO_STRING}: userinfo and server-info strings, including the terminator. */
  public static final int MAX_INFO_STRING = 1024;

  /** {@code MAX_GAMESTATE_CHARS}: combined configstring text of one gamestate. */
  public static final int MAX_GAMESTATE_CHARS = 16000;

  /** {@code MAX_CONFIGSTRINGS}. */
  public static final int MAX_CONFIGSTRINGS = 1024;

  /** Configstring holding the server info string. */
  public static final int CS_SERVERINFO = 0;

  /** Configstring holding the system info string (with {@code sv_serverid}). */
  public static final int CS_SYSTEMINFO = 1;

  // ---- Reliable commands, history and user commands -----------------------------------------

  /** {@code MAX_RELIABLE_COMMANDS}: unacknowledged reliable commands in either direction. */
  public static final int MAX_RELIABLE_COMMANDS = 64;

  /** {@code PACKET_BACKUP}: retained snapshot frames; delta references stay within this. */
  public static final int PACKET_BACKUP = 32;

  /** {@code MAX_PACKET_USERCMDS}: user commands in one client movement operation. */
  public static final int MAX_PACKET_USERCMDS = 32;

  /** Client operations accepted in one message before the payload is treated as hostile. */
  public static final int MAX_CLIENT_OPERATIONS = 128;

  /** Server operations accepted in one message before the payload is treated as hostile. */
  public static final int MAX_SERVER_OPERATIONS = 4096;

  /** Longest prefix of the acknowledged command text that keys user-command fields. */
  public static final int USERCMD_KEY_CHARS = 32;

  // ---- Entities and snapshots ---------------------------------------------------------------

  /** {@code GENTITYNUM_BITS}. */
  public static final int GENTITYNUM_BITS = 10;

  /** {@code MAX_GENTITIES}. */
  public static final int MAX_GENTITIES = 1 << GENTITYNUM_BITS;

  /** {@code ENTITYNUM_NONE}: also the end-of-entities marker in snapshots. */
  public static final int ENTITYNUM_NONE = MAX_GENTITIES - 1;

  /** {@code MAX_CLIENTS}. */
  public static final int MAX_CLIENTS = 64;

  /** {@code MAX_ENTITIES_IN_SNAPSHOT}. */
  public static final int MAX_ENTITIES_IN_SNAPSHOT = 256;

  /** {@code MAX_MAP_AREA_BYTES}: snapshot area-mask capacity. */
  public static final int MAX_MAP_AREA_BYTES = 32;

  /** Size of the canonical little-endian {@code entityState_t} image. */
  public static final int ENTITY_STATE_BYTES = 208;

  /** Size of the canonical little-endian {@code playerState_t} image. */
  public static final int PLAYER_STATE_BYTES = 468;

  /** Size of the canonical little-endian {@code usercmd_t} image. */
  public static final int USERCMD_BYTES = 24;

  /** {@code MAX_STATS}, {@code MAX_PERSISTANT}, {@code MAX_POWERUPS} and {@code MAX_WEAPONS}. */
  public static final int PLAYER_ARRAY_LENGTH = 16;

  // ---- Server-to-client operations (svc_ops_e) ----------------------------------------------

  public static final int SVC_BAD = 0;
  public static final int SVC_NOP = 1;
  public static final int SVC_GAMESTATE = 2;
  public static final int SVC_CONFIGSTRING = 3;
  public static final int SVC_BASELINE = 4;
  public static final int SVC_SERVERCOMMAND = 5;
  public static final int SVC_DOWNLOAD = 6;
  public static final int SVC_SNAPSHOT = 7;
  public static final int SVC_EOF = 8;

  // ---- Client-to-server operations (clc_ops_e) ----------------------------------------------

  public static final int CLC_BAD = 0;
  public static final int CLC_NOP = 1;
  public static final int CLC_MOVE = 2;
  public static final int CLC_MOVENODELTA = 3;
  public static final int CLC_CLIENTCOMMAND = 4;
  public static final int CLC_EOF = 5;

  // ---- Payload obfuscation ------------------------------------------------------------------

  /**
   * Leading client payload bytes (serverId, messageAcknowledge, reliableAcknowledge) left clear.
   */
  public static final int CLIENT_CLEAR_BYTES = 12;

  /** Leading server payload bytes (reliableAcknowledge) left clear. */
  public static final int SERVER_CLEAR_BYTES = 4;
}
