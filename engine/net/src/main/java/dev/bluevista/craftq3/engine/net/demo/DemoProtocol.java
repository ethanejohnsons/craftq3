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
   * Protocol 48. Measured on {@code pak4.pk3:demos/demo000.dm_48}, {@code demo001.dm_48} (both
   * written by 1.25 servers announcing protocol 46) and {@code pak5.pk3:demos/demo127.dm_48}
   * (announcing 48). Everything below was derived from those files, using map item and speaker
   * origins, server times and entity numbers as anchors.
   *
   * <p><b>Messages.</b>
   *
   * <ul>
   *   <li>The container framing is identical to 68.
   *   <li>Messages are not Huffman coded: every bit is raw, least significant first ({@link
   *       MessageReader.Coding#RAW}). The acknowledge word, the svc opcode numbers (nop 1,
   *       gamestate 2, configstring 3, baseline 4, server command 5, snapshot 7), configstrings and
   *       the snapshot header (serverTime, deltaNum, snapFlags, area mask) are as in 68.
   *   <li>No message carries {@code svc_EOF}: a snapshot message ends when fewer than 8 bits, all
   *       zero, are left after its entity list. The gamestate's baseline list ends with a zero byte
   *       and then the message ends: no client number and no checksum feed.
   * </ul>
   *
   * <p><b>Player state</b> (every snapshot of the three demos that is not blocked by an entity
   * selector below parses to its end with this layout).
   *
   * <ul>
   *   <li>Each scalar field has its own change bit followed, when set, by the value: no field count
   *       and no zero shortcut. Floats are a 0 bit and 13 bits of {@code value + 4096} for integral
   *       values in [-4096, 4095], else a 1 bit and the 32-bit IEEE pattern (as in 68).
   *   <li>The order is {@code playerState_t} declaration order with 1.32 widths, except that the
   *       two animation timers come before the animations ({@code groundEntityNum, legsTimer,
   *       torsoTimer, legsAnim, torsoAnim, movementDir}) and {@code grapplePoint} is absent; it
   *       ends at {@code damageCount} (42 fields). Decoded values: commandTime equal to serverTime,
   *       gravity 800, speed 320, groundEntityNum 1022, LEGS_IDLE/LEGS_RUN with the toggle bit,
   *       TORSO_STAND, machinegun, viewheight 26.
   *   <li>Then further change bits whose fields are unknown: five in the protocol-46 demos, six in
   *       demo127. They are clear in every delta snapshot; demo127's first full snapshot sets one,
   *       so its position and width there are unresolved.
   *   <li>Then the arrays stats, persistant, ammo, powerups, each as a change bit, a 16-bit mask
   *       and one signed 16-bit value per set mask bit (decoded: health 123, weapons 6, max health
   *       100; PERS_ATTACKER 1022; ammo -1, 100 ... -1).
   * </ul>
   *
   * <p><b>Entity state</b>.
   *
   * <ul>
   *   <li>Entity number (10 bits, 1023 ends the list) and a remove bit, as in 68. A kept entity
   *       continues with a 6-bit <i>mask selector</i>, then the values of the fields it selects in
   *       field order, with no per-field bits. Field values use the 1.32 widths and the float
   *       encoding above; a field changed to zero is sent as a zero value.
   *   <li>Field order is {@code entityState_t} declaration order with {@code frame} before {@code
   *       clientNum} (51 fields: eType, eFlags, pos, apos, time, time2, origin, origin2, angles,
   *       angles2, otherEntityNum, otherEntityNum2, groundEntityNum, constantLight, loopSound,
   *       modelindex, modelindex2, frame, clientNum, solid, event, eventParm, powerups, weapon,
   *       legsAnim, torsoAnim, generic1).
   *   <li>Selector 63 is followed by an explicit 51-bit change mask in that order. Every gamestate
   *       baseline of demo000 (81), demo001 (48) and demo127 (44) decodes this way, ending exactly
   *       at the message end: items, speakers, triggers, movers, all with plausible values.
   *   <li>Selectors 0 to 62 name masks from a fixed table that the message does not carry. Measured
   *       entries: 7 = eType, pos.trBase, eventParm (bullet impact without a shooter number); 15 =
   *       event; 23 = eType, pos.trType, pos.trTime, pos.trBase, pos.trDelta, weapon (new missile);
   *       39 = eType, pos.trBase, otherEntityNum, eventParm (bullet impact); 41 = eType,
   *       pos.trBase, origin, loopSound, eventParm (looped speaker). With these five, 1344 of
   *       demo000's 1691 snapshots parse structurally to their end. About thirty other selectors
   *       occur (most often 29, which carries a single float, and 27, both on corpses and exploding
   *       missiles; then 58, 32, 46, 0, 44, 40, 60, 52). Each unresolved selector stops its
   *       snapshot's parse and later deltas lose their base, so baselines decode but complete
   *       snapshot playback is not possible and the reader refuses the protocol.
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
