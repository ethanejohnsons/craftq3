package dev.bluevista.craftq3.engine.vmabi.layout;

import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.ConfigStrings;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code gameState_t}: {@code int stringOffsets[1024]}, {@code char stringData[16000]}, {@code int
 * dataCount}. Offset 0 of the string data is the empty string every unset configstring points at.
 * Identical in both profiles.
 */
public final class GameStateLayout {
  public static final int STRING_OFFSETS = 0;
  public static final int STRING_DATA = 4 * ConfigStrings.MAX_STRINGS;

  /** {@code MAX_GAMESTATE_CHARS}. */
  public static final int STRING_DATA_BYTES = 16000;

  public static final int DATA_COUNT = STRING_DATA + STRING_DATA_BYTES;
  public static final int BYTES = DATA_COUNT + 4;

  private GameStateLayout() {}

  /** Writes configstrings (index to value) in index order, as the client builds its gamestate. */
  public static void write(QvmMemory memory, int address, Map<Integer, String> strings) {
    memory.fill(address, BYTES, 0);
    int used = 1;
    for (int index = 0; index < ConfigStrings.MAX_STRINGS; index++) {
      String text = strings.getOrDefault(index, "");
      if (text.isEmpty()) continue;
      byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
      if ((long) used + bytes.length + 1 > STRING_DATA_BYTES)
        throw new IllegalStateException("Gamestate exceeds " + STRING_DATA_BYTES + " bytes");
      memory.writeInt(address + STRING_OFFSETS + index * 4, used);
      memory.writeBytes(address + STRING_DATA + used, bytes);
      used += bytes.length + 1;
    }
    memory.writeInt(address + DATA_COUNT, used);
  }

  public static void write(QvmMemory memory, int address, ConfigStrings strings) {
    write(memory, address, strings.snapshot());
  }

  /** Reads back non-empty configstrings; offsets outside the string data are rejected. */
  public static Map<Integer, String> read(QvmMemory memory, int address) {
    memory.resolve(address, BYTES);
    Map<Integer, String> result = new LinkedHashMap<>();
    for (int index = 0; index < ConfigStrings.MAX_STRINGS; index++) {
      int offset = memory.readInt(address + STRING_OFFSETS + index * 4);
      if (offset < 0 || offset >= STRING_DATA_BYTES)
        throw new IllegalArgumentException("Configstring offset out of range: " + offset);
      String text = memory.readString(address + STRING_DATA + offset, STRING_DATA_BYTES - offset);
      if (!text.isEmpty()) result.put(index, text);
    }
    return result;
  }
}
