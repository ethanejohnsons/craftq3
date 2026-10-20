package dev.bluevista.craftq3.engine.net.demo;

import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.msg.MessageReader;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Demo protocols found in Quake III installations, named by the {@code .dm_NN} extension the engine
 * writes ({@code demos/<name>.dm_<protocol>}).
 *
 * <p><b>66, 67 and 68</b> (1.29/1.30, 1.31, 1.32) share one server-message layout: fixed-Huffman
 * bit fields, svc opcodes 1 to 8, the field-count ({@code lc}) entity and player deltas of {@link
 * dev.bluevista.craftq3.engine.net.delta.StateFields}. The retail installation ships the same
 * attract demo as {@code pak6.pk3:demos/four.dm_66} and {@code pak8.pk3:demos/four.dm_68}, and the
 * two files are byte-identical; every 66 and 68 demo in the corpus parses to its end marker with
 * the protocol-68 codecs. The protocol-number changes between these releases concern the connection
 * handshake, not the message stream a demo stores. No {@code .dm_67} exists in the corpus; it is
 * decoded with the same codecs.
 *
 * <p><b>48</b> (1.27, also written by 1.25 servers announcing protocol 46) is not supported; see
 * {@link #Q3_1_27}.
 */
public enum DemoProtocol {
  /**
   * Protocol 48. Measured differences from 68 on {@code pak4.pk3:demos/demo000.dm_48}, {@code
   * demo001.dm_48} and {@code pak5.pk3:demos/demo127.dm_48}:
   *
   * <ul>
   *   <li>The container framing is identical.
   *   <li>Messages are not Huffman coded: every bit is raw, least significant first ({@link
   *       MessageReader.Coding#RAW}). The envelope, svc opcode numbers, gamestate configstrings and
   *       the snapshot header (serverTime, deltaNum, snapFlags, area mask) are otherwise identical.
   *   <li>Deltas have no leading changed-field count byte: every field of the table carries its own
   *       change bit. The player-state scalar fields follow {@code playerState_t} declaration order
   *       without {@code grapplePoint} (42 fields, 1.32 widths), confirmed against decoded values
   *       (origin, gravity 800, speed 320, groundEntityNum 1022, LEGS_IDLE, TORSO_STAND,
   *       machinegun, viewheight 26).
   *   <li>Unresolved: the encoding after those player fields (stats, persistant, ammo and powerups
   *       arrays) and the entity-state field order and value encoding. Neither 1.32's order nor
   *       {@code entityState_t} declaration order, with or without a no-delta bit or the zero
   *       shortcut, reproduces the consecutive gamestate baselines, so baselines and snapshots
   *       cannot be parsed and the reader refuses the protocol.
   * </ul>
   */
  Q3_1_27(48, false),
  Q3_1_30(66, true),
  Q3_1_31(67, true),
  Q3_1_32(68, true);

  private static final Pattern EXTENSION = Pattern.compile("\\.dm_(\\d{1,3})$");

  private final int number;
  private final boolean supported;

  DemoProtocol(int number, boolean supported) {
    this.number = number;
    this.supported = supported;
  }

  public int number() {
    return number;
  }

  /** Whether {@link DemoReader} can decode this protocol's message stream. */
  public boolean supported() {
    return supported;
  }

  /** Bit encoding of this protocol's messages. */
  public MessageReader.Coding coding() {
    return this == Q3_1_27 ? MessageReader.Coding.RAW : MessageReader.Coding.HUFFMAN;
  }

  public static Optional<DemoProtocol> fromNumber(int number) {
    for (DemoProtocol protocol : values())
      if (protocol.number == number) return Optional.of(protocol);
    return Optional.empty();
  }

  /** The protocol named by a {@code .dm_NN} file name (case-insensitive), if known. */
  public static Optional<DemoProtocol> fromFileName(String name) {
    var matcher = EXTENSION.matcher(name.toLowerCase(Locale.ROOT));
    return matcher.find() ? fromNumber(Integer.parseInt(matcher.group(1))) : Optional.empty();
  }

  /**
   * Infers the message encoding from a demo's first record, which always starts with a gamestate:
   * returns the Huffman family (68) when the Huffman reading gives {@code svc_gamestate}, 48 when
   * the raw reading does, and empty otherwise.
   */
  public static Optional<DemoProtocol> sniff(byte[] firstPayload) {
    if (opcodeAfterAcknowledge(firstPayload, MessageReader.Coding.HUFFMAN)
        == Protocol68.SVC_GAMESTATE) return Optional.of(Q3_1_32);
    if (opcodeAfterAcknowledge(firstPayload, MessageReader.Coding.RAW) == Protocol68.SVC_GAMESTATE)
      return Optional.of(Q3_1_27);
    return Optional.empty();
  }

  private static int opcodeAfterAcknowledge(byte[] payload, MessageReader.Coding coding) {
    if (payload.length > Protocol68.MAX_MSGLEN) return -1;
    var reader = new MessageReader(payload).coding(coding);
    try {
      reader.intValue();
      return reader.byteValue();
    } catch (IllegalArgumentException malformed) {
      return -1;
    }
  }
}
