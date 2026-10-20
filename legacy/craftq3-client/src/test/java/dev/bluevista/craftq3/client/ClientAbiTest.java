package dev.bluevista.craftq3.client;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ClientAbiTest {
  @Test
  void glconfigAndSnapshotLayoutsHaveSeparateVerifiedLegacySizes() throws Exception {
    var memory = ClientTestData.memory();
    assertEquals(4164, ClientAbi.RETAIL_1999.glconfigBytes());
    assertEquals(11332, ClientAbi.Q3_132.glconfigBytes());
    assertEquals(52724, ClientAbi.RETAIL_1999.snapshotBytes());
    assertEquals(53772, ClientAbi.Q3_132.snapshotBytes());
    for (var profile : ClientAbi.values()) {
      memory.fill(100, profile.glconfigBytes() + 4, 255);
      profile.glconfig(memory, 100, 1600, 900);
      assertEquals(1600, memory.readInt(100 + profile.glconfigWidth()));
      assertEquals(900, memory.readInt(104 + profile.glconfigWidth()));
      assertEquals(16f / 9, memory.readFloat(108 + profile.glconfigWidth()));
      assertEquals(-1, memory.readInt(100 + profile.glconfigBytes()));
      assertThrows(
          dev.bluevista.craftq3.vm.QvmException.class,
          () -> profile.glconfig(memory, memory.size() - 100, 640, 480));
    }
    assertEquals(
        ClientAbi.Q3_132,
        ClientAbi.detect("Nov 21 1999".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
  }

  @Test
  void retailPlayerStateUsesCanonicalPingAndExcludesModernPrivateTail() throws Exception {
    var memory = ClientTestData.memory();
    var state = ByteBuffer.allocate(468).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < 468; i += 4) state.putInt(i, i + 100);
    memory.fill(1000, 472, 255);
    ClientAbi.RETAIL_1999.playerState(memory, 1000, state.array());
    assertEquals(536, memory.readInt(1436));
    assertEquals(552, memory.readInt(1440));
    assertEquals(-1, memory.readInt(1444));
    ClientAbi.Q3_132.playerState(memory, 1000, state.array());
    assertEquals(564, memory.readInt(1464));
  }

  @Test
  void gamestateHasNullZeroOffsetAndOwnedBoundedStringTable() throws Exception {
    var memory = ClientTestData.memory();
    ClientAbi.gamestate(memory, 100, Map.of(0, "\\mapname\\fixture", 42, "hello"));
    int offset = memory.readInt(100 + 42 * 4);
    assertEquals("hello", memory.readCString(100 + 4096 + offset, 6));
    assertEquals(0, memory.readInt(104));
    assertEquals(0, memory.readUnsignedByte(100 + 4096));
    assertThrows(
        IllegalStateException.class,
        () -> ClientAbi.gamestate(memory, 100, Map.of(0, "x".repeat(16000))));
  }
}
