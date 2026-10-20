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
 * <p><b>48</b> (1.27, also written by 1.25 servers announcing protocol 46) has its own message
 * layout, decoded onto the same canonical states and snapshot structures; see {@link #Q3_1_27}.
 */
public enum DemoProtocol {
  /**
   * Protocol 48, decoded by {@link dev.bluevista.craftq3.engine.net.msg.Protocol48MessageCodec} and
   * {@link dev.bluevista.craftq3.engine.net.delta.Protocol48Codec}. Measured on {@code
   * pak4.pk3:demos/demo000.dm_48}, {@code demo001.dm_48} (both written by 1.25 servers announcing
   * protocol 46) and {@code pak5.pk3:demos/demo127.dm_48} (announcing 48): with the layout below,
   * every message of the three demos (5491 snapshots) parses to its end-of-message padding, every
   * delta finds its base, and player and missile motion is continuous from snapshot to snapshot.
   * Map item and speaker origins, server times, entity numbers and that continuity were the
   * anchors.
   *
   * <p><b>Messages.</b>
   *
   * <ul>
   *   <li>The container framing is identical to 68.
   *   <li>Messages are not Huffman coded: every bit is raw, least significant first ({@link
   *       MessageReader.Coding#RAW}). The acknowledge word, the svc opcode numbers (nop 1,
   *       gamestate 2, configstring 3, baseline 4, server command 5, snapshot 7), configstrings and
   *       the snapshot header (serverTime, deltaNum, snapFlags, area mask) are as in 68.
   *   <li>No message carries {@code svc_EOF}: a message ends when fewer than 8 bits, all zero, are
   *       left. The gamestate's records end with a zero byte and then the message ends: no client
   *       number and no checksum feed. The recorded client is the player state's {@code clientNum}.
   * </ul>
   *
   * <p><b>Player state.</b>
   *
   * <ul>
   *   <li>Each scalar field has its own change bit followed, when set, by the value: no field count
   *       and no zero shortcut. Floats are a 0 bit and 13 bits of {@code value + 4096} for integral
   *       values in [-4096, 4095], else a 1 bit and the 32-bit IEEE pattern (as in 68).
   *   <li>The order is {@code playerState_t} declaration order with 1.32 widths, except that the
   *       two animation timers come before the animations ({@code groundEntityNum, legsTimer,
   *       torsoTimer, legsAnim, torsoAnim, movementDir}) and {@code grapplePoint} is absent; it
   *       ends at {@code damageCount} (42 fields).
   *   <li>Then further change bits: five when the server announces protocol 46, six for 48. In
   *       protocol 48 the fourth is a 10-bit field that is set to a trigger_push entity number and
   *       cleared again in the next snapshot, i.e. {@code jumppad_ent}. The others are never set in
   *       the demos and their widths are unknown; a message setting one is rejected.
   *   <li>Then the arrays stats, persistant, ammo, powerups, each as a change bit, a 16-bit mask
   *       and one value per set mask bit: signed 16 bits, but 32 bits for powerups (the CTF demo's
   *       flag carriers hold large expiry times there). There is no overall arrays-changed bit.
   * </ul>
   *
   * <p><b>Entity state</b>.
   *
   * <ul>
   *   <li>Entity number (10 bits, 1023 ends the list) and a remove bit, as in 68, then a change
   *       bit: 0 resends the entity unchanged. When set, a 5-bit mask index follows, then the
   *       values of the fields the mask selects in field order, with no per-field bits. Field
   *       values use the 1.32 widths and the float encoding above; a field changed to zero is sent
   *       as a zero.
   *   <li>Field order is {@code entityState_t} declaration order with {@code frame} before {@code
   *       clientNum} (51 fields: eType, eFlags, pos, apos, time, time2, origin, origin2, angles,
   *       angles2, otherEntityNum, otherEntityNum2, groundEntityNum, constantLight, loopSound,
   *       modelindex, modelindex2, frame, clientNum, solid, event, eventParm, powerups, weapon,
   *       legsAnim, torsoAnim, generic1). The field in {@code constantLight}'s position is 8 bits
   *       wide; the demos set it only to 1, on dead players reaching the ground. It is stored in
   *       {@code constantLight}.
   *   <li>Index 31 is followed by an explicit 51-bit change mask in that order (every gamestate
   *       baseline but the looped speakers uses it).
   *   <li>Indexes 0 to 30 name masks from a fixed table that the message does not carry. Seven
   *       occur in the demos and are known: 3 = eType, pos.trBase, eventParm (bullet impact without
   *       a shooter number); 7 = event; 11 = eType, pos.trType, pos.trTime, pos.trBase,
   *       pos.trDelta, weapon (new missile); 13 = eType, pos.trType, pos.trTime, pos.trBase,
   *       pos.trDelta, event, eventParm (missile explosion); 14 = pos.trBase[2] (corpse settling);
   *       19 = eType, pos.trBase, otherEntityNum, eventParm (bullet impact); 20 = eType,
   *       pos.trBase, origin, loopSound, eventParm (looped speaker). Each was fixed by the bit
   *       length its occurrences must have for their messages to parse to the end, then checked
   *       against the states before and after. Another index is rejected.
   * </ul>
   *
   * <p>Event numbers, entity types and configstring indexes are those of 1.32 as far as the demos
   * show (missile bounce and miss events, item and mover types, CS_PLAYERS and CS_SOUNDS); the
   * decoded states are not renumbered.
   */
  Q3_1_27(48, true),
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
