package dev.bluevista.craftq3.engine.vmabi;

import dev.bluevista.craftq3.engine.vm.QvmImage;
import dev.bluevista.craftq3.engine.vmabi.layout.GlConfigLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.SharedEntityLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.SnapshotLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.TraceLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout;
import java.util.Locale;
import java.util.Optional;

/**
 * A guest ABI: the struct layouts and trap numbering a QVM build was compiled against.
 *
 * <ul>
 *   <li>{@link #Q3_132}: the published 1.32 interface (Team Arena point release onwards), used by
 *       every mod in the corpus and by baseq3 pak4 to pak8.
 *   <li>{@link #RETAIL_1999}: the original retail interface of baseq3 pak0 (and, by import usage,
 *       the pre-Team-Arena builds in pak1 to pak3): smaller entityState/playerState, no
 *       singleClient, a different usercmd_t order, a 1024-byte glconfig extension string, the old
 *       botlib elementary-action numbering and the old ui server-browser imports.
 * </ul>
 *
 * Sizes are in bytes of 32-bit little-endian QVM memory. Field offsets live in the layout classes
 * of {@code engine.vmabi.layout}.
 */
public enum AbiProfile {
  Q3_132("1.32", 208, 468, 8192, 256, 276, 328),
  RETAIL_1999("retail", 204, 444, 1024, 150, 172, 224);

  /** Name of the console variable that overrides detection ({@code auto}, {@code 1.32}, ...). */
  public static final String OVERRIDE_CVAR = "vm_abi";

  private final String key;
  private final int entityStateBytes;
  private final int playerStateBytes;
  private final int glExtensionsBytes;
  private final int botMessageBytes;
  private final int botConsoleMessageBytes;
  private final int botMatchBytes;

  AbiProfile(
      String key,
      int entityStateBytes,
      int playerStateBytes,
      int glExtensionsBytes,
      int botMessageBytes,
      int botConsoleMessageBytes,
      int botMatchBytes) {
    this.key = key;
    this.entityStateBytes = entityStateBytes;
    this.playerStateBytes = playerStateBytes;
    this.glExtensionsBytes = glExtensionsBytes;
    this.botMessageBytes = botMessageBytes;
    this.botConsoleMessageBytes = botConsoleMessageBytes;
    this.botMatchBytes = botMatchBytes;
  }

  /** The override spelling ({@code 1.32} or {@code retail}). */
  public String key() {
    return key;
  }

  /** {@code sizeof(entityState_t)}: 208, or 204 without {@code generic1}. */
  public int entityStateBytes() {
    return entityStateBytes;
  }

  /** {@code sizeof(playerState_t)}: 468, or 444 without the Team Arena additions. */
  public int playerStateBytes() {
    return playerStateBytes;
  }

  /** {@code sizeof(sharedEntity_t)}: 516, or 504 (smaller states, no singleClient). */
  public int sharedEntityBytes() {
    return SharedEntityLayout.of(this).size();
  }

  /** {@code sizeof(usercmd_t)}; the same size in both profiles, with a different field order. */
  public int userCmdBytes() {
    return UserCmdLayout.BYTES;
  }

  /** {@code sizeof(trace_t)}. */
  public int traceBytes() {
    return TraceLayout.BYTES;
  }

  /** Capacity of {@code glconfig_t.extensions_string}: BIG_INFO_STRING, or 1024 in retail. */
  public int glExtensionsBytes() {
    return glExtensionsBytes;
  }

  /** {@code sizeof(glconfig_t)}: 11332, or 4164 in retail. */
  public int glConfigBytes() {
    return GlConfigLayout.of(this).size();
  }

  /** {@code sizeof(snapshot_t)}. */
  public int snapshotBytes() {
    return SnapshotLayout.of(this).size();
  }

  /** Botlib {@code MAX_MESSAGE_SIZE}: 256, or 150 in retail. */
  public int botMessageBytes() {
    return botMessageBytes;
  }

  /** {@code sizeof(bot_consolemessage_t)}: 276, or 172 in retail. */
  public int botConsoleMessageBytes() {
    return botConsoleMessageBytes;
  }

  /** {@code sizeof(bot_match_t)}: 328, or 224 in retail. */
  public int botMatchBytes() {
    return botMatchBytes;
  }

  /**
   * Detects the profile of a parsed module from its import usage (see {@link AbiDetector}). The
   * module kind comes from {@link QvmImage#name()}.
   */
  public static AbiProfile detect(QvmImage image) {
    return AbiDetector.analyze(image, null).profile();
  }

  /** Detects with the verified SHA-256 table of original file bytes first, then structurally. */
  public static AbiProfile detect(QvmImage image, byte[] fileBytes) {
    return AbiDetector.analyze(image, fileBytes).profile();
  }

  /**
   * Parses a {@code vm_abi} override: empty or {@code auto} means detect; {@code 1.32}/{@code
   * q3_132} and {@code retail}/{@code retail_1999} select a profile.
   *
   * @throws IllegalArgumentException for any other value
   */
  public static Optional<AbiProfile> parseOverride(String value) {
    String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    return switch (text) {
      case "", "auto", "0" -> Optional.empty();
      case "1.32", "132", "q3_132" -> Optional.of(Q3_132);
      case "retail", "retail_1999", "1999" -> Optional.of(RETAIL_1999);
      default ->
          throw new IllegalArgumentException("Unknown " + OVERRIDE_CVAR + " value: " + value);
    };
  }

  /** The override when one is given, otherwise detection over the file bytes and imports. */
  public static AbiProfile resolve(String override, QvmImage image, byte[] fileBytes) {
    return parseOverride(override).orElseGet(() -> detect(image, fileBytes));
  }
}
