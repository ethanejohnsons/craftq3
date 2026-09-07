package dev.bluevista.craftq3.server;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspFixture;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.botlib.ea.ActionFlags;
import dev.bluevista.craftq3.botlib.goal.Goal;
import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.collision.TraceResult;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.vm.Opcode;
import dev.bluevista.craftq3.vm.QvmInterpreter;
import dev.bluevista.craftq3.vm.QvmMemory;
import dev.bluevista.craftq3.vm.QvmReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class BotlibHostTest {
  @Test
  void lifecycleRetainsVariablesAndRejectsTimeReversalAndUnsupportedAi() throws Exception {
    var memory = memory();
    try (var bot = bot(Map.of(), new Host(), GameAbi.Q3_132)) {
      text(memory, 100, "maxclients");
      text(memory, 200, "2");
      call(bot, memory, 202, 100, 200);
      assertThrows(IllegalStateException.class, () -> call(bot, memory, 406, 0));
      assertEquals(0, call(bot, memory, 200));
      call(bot, memory, 203, 100, 300, 20);
      assertEquals("2", memory.readCString(300, 20));
      call(bot, memory, 205, bits(1.5f));
      assertEquals(1.5f, Float.intBitsToFloat(call(bot, memory, 306)));
      assertThrows(IllegalArgumentException.class, () -> call(bot, memory, 205, bits(1)));
      assertThrows(IllegalArgumentException.class, () -> call(bot, memory, 205, bits(Float.NaN)));
      assertThrows(UnsupportedOperationException.class, () -> call(bot, memory, 575));
      call(bot, memory, 201);
      call(bot, memory, 200);
      assertEquals(0, Float.intBitsToFloat(call(bot, memory, 306)));
      assertFalse(bot.status().mapLoaded());
      assertThrows(UnsupportedOperationException.class, () -> bot.status().calls().clear());
    }
  }

  @Test
  void botUserCommandsRetainEverySignedMotionByteAndRoundTripBothAbis() throws Exception {
    for (var abi : GameAbi.values()) {
      var memory = memory();
      var host = new Host();
      try (var bot = bot(Map.of(), host, abi)) {
        call(bot, memory, 200);
        for (int value = 0; value < 256; value++) {
          memory.fill(100, 24, 0);
          memory.writeInt(100, 800);
          int angles = abi == GameAbi.RETAIL_1999 ? 108 : 104;
          memory.writeInt(angles, 15);
          memory.writeInt(angles + 4, 27);
          memory.writeInt(angles + 8, 41);
          if (abi == GameAbi.RETAIL_1999) {
            memory.writeByte(104, 3);
            memory.writeByte(105, 255);
          } else {
            memory.writeInt(116, 3);
            memory.writeByte(120, 255);
          }
          int motion = abi == GameAbi.RETAIL_1999 ? 120 : 121;
          memory.writeByte(motion, value);
          memory.writeByte(motion + 1, 255 - value);
          memory.writeByte(motion + 2, (value + 128) & 255);
          call(bot, memory, 211, 1, 100);
          assertEquals((byte) value, host.input.forward());
          assertEquals((byte) (255 - value), host.input.right());
          assertEquals((byte) (value + 128), host.input.up());
          assertEquals(255, host.input.weapon());
          memory.fill(199, 26, 127);
          host.input.write(memory, 200, abi);
          assertArrayEquals(memory.readBytes(100, 24), memory.readBytes(200, 24));
          assertEquals(127, memory.readUnsignedByte(199));
          assertEquals(127, memory.readUnsignedByte(224));
        }
      }
    }
  }

  @Test
  void elementaryInputsPreserveFloatAndBitLayoutsAndRejectWritesBeforeConsumingState()
      throws Exception {
    var memory = memory();
    var host = new Host();
    try (var bot = bot(Map.of(), host, GameAbi.Q3_132)) {
      call(bot, memory, 200);
      VmAbi.vector(memory, 100, new Vec3(.25, -.5, 1));
      VmAbi.vector(memory, 200, new Vec3(10, 20, 30));
      call(bot, memory, 419, 1, 100, bits(123.5f));
      call(bot, memory, 420, 1, 200);
      call(bot, memory, 406, 1);
      call(bot, memory, 417, 1);
      call(bot, memory, 416, 1, 7);
      assertThrows(
          IllegalArgumentException.class,
          () -> call(bot, memory, 422, 1, bits(.05f), memory.size() - 39));
      memory.fill(299, 42, 127);
      call(bot, memory, 422, 1, bits(.05f), 300);
      assertEquals(.05f, memory.readFloat(300));
      assertEquals(new Vec3(.25, -.5, 1), VmAbi.vector(memory, 304));
      assertEquals(123.5f, memory.readFloat(316));
      assertEquals(new Vec3(10, 20, 30), VmAbi.vector(memory, 320));
      assertEquals(ActionFlags.ATTACK | ActionFlags.JUMP, memory.readInt(332));
      assertEquals(7, memory.readInt(336));
      assertEquals(127, memory.readUnsignedByte(299));
      assertEquals(127, memory.readUnsignedByte(340));
      call(bot, memory, 423, 1);
      call(bot, memory, 422, 1, bits(.05f), 300);
      assertEquals(ActionFlags.JUMPED_LAST_FRAME, memory.readInt(332));
      text(memory, 500, "hello");
      call(bot, memory, 400, 1, 500);
      assertEquals(List.of("1:say hello"), host.commands);
      assertThrows(IllegalArgumentException.class, () -> call(bot, memory, 406, 2));
    }
  }

  @Test
  void entityStateOwnsGuestBytesAndWritesThe140ByteAasLayout() throws Exception {
    var memory = memory();
    try (var bot = bot(Map.of(), new Host(), GameAbi.Q3_132)) {
      call(bot, memory, 200);
      call(bot, memory, 205, bits(2));
      for (int offset = 0; offset < 112; offset += 4) memory.writeInt(100 + offset, offset + 1);
      for (int offset = 8; offset < 68; offset += 12)
        VmAbi.vector(memory, 100 + offset, new Vec3(offset, offset + 1, offset + 2));
      memory.writeInt(172, 2);
      byte[] source = memory.readBytes(100, 112);
      call(bot, memory, 207, 17, 100);
      memory.fill(100, 112, 0);
      memory.fill(499, 142, 255);
      call(bot, memory, 303, 17, 500);
      assertEquals(1, memory.readInt(500));
      assertEquals(17, memory.readInt(520));
      assertEquals(2f, memory.readFloat(512));
      assertEquals(2, memory.readFloat(516));
      assertEquals(new Vec3(0, 0, 0), VmAbi.vector(memory, 560));
      assertArrayEquals(java.util.Arrays.copyOfRange(source, 0, 8), memory.readBytes(504, 8));
      assertArrayEquals(java.util.Arrays.copyOfRange(source, 8, 44), memory.readBytes(524, 36));
      assertArrayEquals(java.util.Arrays.copyOfRange(source, 44, 112), memory.readBytes(572, 68));
      assertEquals(255, memory.readUnsignedByte(499));
      assertEquals(255, memory.readUnsignedByte(640));
      call(bot, memory, 205, bits(2.25f));
      call(bot, memory, 303, 17, 500);
      assertEquals(0, memory.readInt(500));
      memory.writeBytes(100, source);
      call(bot, memory, 207, 17, 100);
      call(bot, memory, 303, 17, 500);
      assertEquals(.25f, memory.readFloat(516));
      assertEquals(new Vec3(8, 9, 10), VmAbi.vector(memory, 560));
      call(bot, memory, 207, 17, 0);
      call(bot, memory, 303, 17, 500);
      assertEquals(1, memory.readInt(500));
      assertEquals(new Vec3(8, 9, 10), VmAbi.vector(memory, 524));
      call(bot, memory, 205, bits(3));
      call(bot, memory, 303, 17, 500);
      assertEquals(0, memory.readInt(500));
      assertEquals(new Vec3(8, 9, 10), VmAbi.vector(memory, 560));
      assertEquals(2, call(bot, memory, 207, 1024, 0));
      assertThrows(
          IllegalArgumentException.class, () -> call(bot, memory, 207, 17, memory.size() - 111));
    }
  }

  @Test
  void predictionBridgeClearsSuccessfulTraceAndRejectsInvalidOutputBeforeQueries()
      throws Exception {
    for (GameAbi abi : GameAbi.values()) {
      var memory = memory();
      var host = new Host();
      try (var bot = bot(Map.of("maps/fixture.aas", navigationFixture()), host, abi)) {
        call(bot, memory, 200);
        text(memory, 100, "fixture");
        call(bot, memory, 206, 100);
        VmAbi.vector(memory, 200, new Vec3(5, 0, 10));
        VmAbi.vector(memory, 220, new Vec3(10, 20, 30));
        VmAbi.vector(memory, 240, new Vec3(0, 0, 0));
        memory.fill(499, 86, 127);
        assertEquals(
            1, call(bot, memory, 318, 500, -1, 200, 2, 0, 220, 240, 0, 0, bits(.1f), 0, 0, 0));
        assertEquals(new Vec3(5, 0, 10.25), VmAbi.vector(memory, 500));
        assertEquals(1, memory.readInt(512));
        assertEquals(new Vec3(10, 20, 30), VmAbi.vector(memory, 516));
        assertArrayEquals(new byte[36], memory.readBytes(528, 36));
        assertEquals(2, memory.readInt(564));
        assertEquals(0, memory.readInt(568));
        assertEquals(0, memory.readFloat(576));
        assertEquals(0, memory.readInt(580));
        assertEquals(127, memory.readUnsignedByte(499));
        assertEquals(127, memory.readUnsignedByte(584));
        byte[] previous = memory.readBytes(500, 84);
        assertThrows(
            IllegalArgumentException.class,
            () ->
                call(
                    bot,
                    memory,
                    318,
                    memory.size() - 83,
                    0,
                    200,
                    2,
                    0,
                    220,
                    240,
                    0,
                    1,
                    bits(.1f),
                    0,
                    0,
                    0));
        assertThrows(
            UnsupportedOperationException.class,
            () -> call(bot, memory, 318, 500, 0, 200, 2, 0, 220, 240, 0, 1, bits(.1f), 128, 0, 0));
        assertArrayEquals(previous, memory.readBytes(500, 84));
        VmAbi.vector(memory, 220, new Vec3(0, 0, 0));
        assertEquals(
            1, call(bot, memory, 318, 500, -1, 200, 2, 1, 220, 240, 0, 1, bits(.1f), 2, 0, 0));
        assertEquals(2, memory.readInt(568));
        assertEquals(0, memory.readInt(528));
        assertEquals(1, memory.readFloat(532));
        assertEquals(VmAbi.vector(memory, 500), VmAbi.vector(memory, 536));
      }
    }
  }

  @Test
  void visiblePositionBridgePreservesFailureOutputAndChecksGuestRangesBeforeTracing()
      throws Exception {
    for (GameAbi abi : GameAbi.values()) {
      var memory = memory();
      var host = new Host();
      try (var bot = bot(Map.of("maps/fixture.aas", navigationFixture()), host, abi)) {
        call(bot, memory, 200);
        text(memory, 100, "fixture");
        call(bot, memory, 206, 100);
        VmAbi.vector(memory, 200, new Vec3(5, 0, 0));
        var zero = new Vec3(0, 0, 0);
        var goal = new Goal(new Vec3(-5, 2, 3), 2, zero, zero, 42, 0, 0, 0);
        var bytes = ByteBuffer.allocate(Goal.BYTE_SIZE);
        goal.writeTo(bytes, 0);
        memory.writeBytes(300, bytes.array());
        memory.fill(499, 14, 127);
        assertEquals(1, call(bot, memory, 572, 200, 1, 300, 0x80002, 500));
        assertEquals(new Vec3(1, 0, 0), VmAbi.vector(memory, 500));
        assertEquals(goal.origin(), host.lastTrace.start());
        assertEquals(42, host.lastTrace.ignoreEntity());
        assertEquals(0x10001, host.lastTrace.contentsMask());
        assertEquals(127, memory.readUnsignedByte(499));
        assertEquals(127, memory.readUnsignedByte(512));
        host.traceFraction = 0;
        assertEquals(1, call(bot, memory, 572, 200, 1, 300, 0x80002, 500));
        assertEquals(new Vec3(-1, 0, 0), VmAbi.vector(memory, 500));
        memory.fill(500, 12, 127);
        host.lastTrace = null;
        assertEquals(0, call(bot, memory, 572, 200, 1, 300, 0, 500));
        assertEquals(0, call(bot, memory, 572, 200, 2, 300, 0x80002, 500));
        assertEquals(0, call(bot, memory, 572, 200, 1, 0, 0x80002, 500));
        assertNull(host.lastTrace);
        byte[] unchanged = memory.readBytes(500, 12);
        assertThrows(
            IllegalArgumentException.class,
            () -> call(bot, memory, 572, 200, 1, 300, 0x80002, memory.size() - 11));
        assertThrows(
            IllegalArgumentException.class,
            () -> call(bot, memory, 572, 200, 1, memory.size() - 55, 0x80002, 500));
        memory.writeInt(200, bits(Float.NaN));
        assertThrows(
            IllegalArgumentException.class,
            () -> call(bot, memory, 572, 200, 1, 300, 0x80002, 500));
        assertNull(host.lastTrace);
        assertArrayEquals(unchanged, memory.readBytes(500, 12));
      }
    }
  }

  @Test
  void routePredictionBridgeWritesNativeFieldsAndPreservesUnassignedSlotForBothAbis()
      throws Exception {
    for (GameAbi abi : GameAbi.values()) {
      var memory = memory();
      try (var bot = bot(Map.of("maps/fixture.aas", navigationFixture()), new Host(), abi)) {
        call(bot, memory, 200);
        text(memory, 100, "fixture");
        call(bot, memory, 206, 100);
        VmAbi.vector(memory, 200, new Vec3(5, 0, 0));
        memory.fill(499, 38, 127);
        memory.writeInt(528, 0x7fc01234);
        // Stop immediately before the walk link, using the native travel-type event.
        assertEquals(1, call(bot, memory, 576, 500, 1, 200, 2, 0x80002, 0, 0, 2, 0, 2, 0));
        assertEquals(new Vec3(1, 0, 0), VmAbi.vector(memory, 500));
        assertEquals(1, memory.readInt(512));
        assertEquals(2, memory.readInt(516));
        assertEquals(0, memory.readInt(520));
        assertEquals(2, memory.readInt(524));
        assertEquals(0x7fc01234, memory.readInt(528));
        assertEquals(0, memory.readInt(532));
        assertEquals(127, memory.readUnsignedByte(499));
        assertEquals(127, memory.readUnsignedByte(536));

        // A denied route still initializes the assigned output fields and reports failure.
        assertEquals(0, call(bot, memory, 576, 500, 1, 200, 2, 0, 0, 0, 0, 0, 0, 0));
        assertEquals(new Vec3(5, 0, 0), VmAbi.vector(memory, 500));
        assertEquals(2, memory.readInt(512));
        assertEquals(1, memory.readInt(516));
        assertEquals(0, memory.readInt(520));
        assertEquals(0, memory.readInt(524));
        assertEquals(0x7fc01234, memory.readInt(528));
        assertEquals(0, memory.readInt(532));
      }
    }
  }

  @Test
  void routePredictionRejectsInvalidGuestRangesWithoutChangingOutputForBothAbis() throws Exception {
    for (GameAbi abi : GameAbi.values()) {
      var memory = memory();
      try (var bot = bot(Map.of("maps/fixture.aas", navigationFixture()), new Host(), abi)) {
        call(bot, memory, 200);
        text(memory, 100, "fixture");
        call(bot, memory, 206, 100);
        VmAbi.vector(memory, 200, new Vec3(5, 0, 0));
        memory.fill(500, 36, 127);
        byte[] previous = memory.readBytes(500, 36);
        assertThrows(
            IllegalArgumentException.class,
            () -> call(bot, memory, 576, memory.size() - 35, 1, 200, 2, 2, 0, 0, 0, 0, 0, 0));
        assertThrows(
            IllegalArgumentException.class,
            () -> call(bot, memory, 576, 500, 1, memory.size() - 11, 2, 2, 0, 0, 0, 0, 0, 0));
        memory.writeInt(200, bits(Float.NaN));
        assertThrows(
            IllegalArgumentException.class,
            () -> call(bot, memory, 576, 500, 1, 200, 2, 2, 0, 0, 0, 0, 0, 0));
        assertArrayEquals(previous, memory.readBytes(500, 36));
      }
    }
  }

  @Test
  void predictionUsesExplicitEntityLinksEvenAfterFrameInvalidationUntilNullUpdate()
      throws Exception {
    var memory = memory();
    var host = new Host();
    try (var bot = bot(Map.of("maps/fixture.aas", navigationFixture()), host, GameAbi.Q3_132)) {
      call(bot, memory, 200);
      text(memory, 100, "fixture");
      call(bot, memory, 206, 100);
      VmAbi.vector(memory, 200, new Vec3(5, 0, 10));
      VmAbi.vector(memory, 220, new Vec3(0, 0, 0));
      VmAbi.vector(memory, 240, new Vec3(0, 0, 0));
      VmAbi.vector(memory, 308, new Vec3(5, 0, 10));
      VmAbi.vector(memory, 344, new Vec3(-1, -1, -1));
      VmAbi.vector(memory, 356, new Vec3(1, 1, 1));
      memory.writeInt(372, 2);
      call(bot, memory, 207, 3, 300);
      call(bot, memory, 205, bits(1));
      assertEquals(0, bot.status().validEntities());
      call(bot, memory, 318, 500, 0, 200, 2, 0, 220, 240, 0, 1, bits(.1f), 0, 0, 0);
      assertFalse(host.entityTraces.isEmpty());
      assertTrue(host.entityTraces.stream().allMatch(entity -> entity == 3));
      assertNull(host.lastTrace);
      host.entityTraces.clear();
      call(bot, memory, 207, 3, 0);
      call(bot, memory, 318, 500, 0, 200, 2, 0, 220, 240, 0, 1, bits(.1f), 0, 0, 0);
      assertTrue(host.entityTraces.isEmpty());
    }
  }

  @Test
  void secondMissedFrameExpiresCollisionLinksButRetainsEntityMetadataForBothAbis()
      throws Exception {
    for (GameAbi abi : GameAbi.values()) {
      var memory = memory();
      var host = new Host();
      try (var bot = bot(Map.of("maps/fixture.aas", navigationFixture()), host, abi)) {
        call(bot, memory, 200);
        text(memory, 100, "fixture");
        call(bot, memory, 206, 100);
        VmAbi.vector(memory, 200, new Vec3(5, 0, 10));
        VmAbi.vector(memory, 308, new Vec3(5, 0, 10));
        VmAbi.vector(memory, 344, new Vec3(-1, -1, -1));
        VmAbi.vector(memory, 356, new Vec3(1, 1, 1));
        memory.writeInt(372, 2);
        call(bot, memory, 207, 3, 300);
        call(bot, memory, 205, bits(1));
        call(bot, memory, 303, 3, 800);
        assertEquals(0, memory.readInt(800));
        call(bot, memory, 318, 500, 0, 200, 2, 0, 220, 240, 0, 1, bits(.1f), 0, 0, 0);
        assertFalse(host.entityTraces.isEmpty());
        // Refresh before expiration keeps the existing link, even with an identical state.
        call(bot, memory, 207, 3, 300);
        call(bot, memory, 205, bits(2));
        host.entityTraces.clear();
        call(bot, memory, 318, 500, 0, 200, 2, 0, 220, 240, 0, 1, bits(.1f), 0, 0, 0);
        assertFalse(host.entityTraces.isEmpty());
        call(bot, memory, 303, 3, 800);
        byte[] retained = memory.readBytes(800, 140);
        assertThrows(IllegalArgumentException.class, () -> call(bot, memory, 205, bits(1)));
        call(bot, memory, 205, bits(3));
        host.entityTraces.clear();
        call(bot, memory, 318, 500, 0, 200, 2, 0, 220, 240, 0, 1, bits(.1f), 0, 0, 0);
        assertTrue(host.entityTraces.isEmpty());
        call(bot, memory, 303, 3, 800);
        assertArrayEquals(retained, memory.readBytes(800, 140));
        // Validity refresh alone does not recreate an expired collision link.
        call(bot, memory, 207, 3, 300);
        call(bot, memory, 303, 3, 800);
        assertEquals(1, memory.readInt(800));
        call(bot, memory, 318, 500, 0, 200, 2, 0, 220, 240, 0, 1, bits(.1f), 0, 0, 0);
        assertTrue(host.entityTraces.isEmpty());
        memory.writeFloat(312, -0.0f); // Float equality treats signed zero as unchanged.
        call(bot, memory, 207, 3, 300);
        call(bot, memory, 318, 500, 0, 200, 2, 0, 220, 240, 0, 1, bits(.1f), 0, 0, 0);
        assertTrue(host.entityTraces.isEmpty());
        memory.writeFloat(308, 6);
        call(bot, memory, 207, 3, 300);
        call(bot, memory, 318, 500, 0, 200, 2, 0, 220, 240, 0, 1, bits(.1f), 0, 0, 0);
        assertFalse(host.entityTraces.isEmpty());
        call(bot, memory, 207, 3, 0);
        call(bot, memory, 303, 3, 800);
        assertEquals(1, memory.readInt(800), "Explicit unlink preserves current-frame validity");
      }
    }
  }

  @Test
  void firstBotFrameForcesIdenticalEntityRelinkingOnlyDuringThatFrame() throws Exception {
    for (GameAbi abi : GameAbi.values()) {
      var memory = memory();
      var host = new Host();
      try (var bot = bot(Map.of("maps/fixture.aas", navigationFixture()), host, abi)) {
        call(bot, memory, 200);
        text(memory, 100, "fixture");
        call(bot, memory, 206, 100);
        VmAbi.vector(memory, 200, new Vec3(5, 0, 10));
        call(bot, memory, 205, bits(1));
        for (int solid : new int[] {0, 2}) {
          memory.writeInt(372, solid);
          call(bot, memory, 207, 3, 300);
          call(bot, memory, 207, 3, 0);
          call(bot, memory, 207, 3, 300);
          host.entityTraces.clear();
          call(bot, memory, 318, 500, 0, 200, 2, 0, 220, 240, 0, 1, bits(.1f), 0, 0, 0);
          assertFalse(host.entityTraces.isEmpty());
        }
        call(bot, memory, 205, bits(2));
        call(bot, memory, 207, 3, 0);
        call(bot, memory, 207, 3, 300);
        call(bot, memory, 207, 4, 300); // A new all-zero slot is not itself a relink trigger.
        host.entityTraces.clear();
        call(bot, memory, 318, 500, 0, 200, 2, 0, 220, 240, 0, 1, bits(.1f), 0, 0, 0);
        assertTrue(host.entityTraces.isEmpty());
      }
    }
  }

  @Test
  void entityBoundsFollowSolidTypeAndNativeRotatedModelCallback() throws Exception {
    var memory = memory();
    try (var bot = bot(Map.of(), new Host(), GameAbi.Q3_132)) {
      call(bot, memory, 200);
      VmAbi.vector(memory, 144, new Vec3(-1, -2, -3));
      VmAbi.vector(memory, 156, new Vec3(4, 5, 6));
      VmAbi.vector(memory, 120, new Vec3(1, 2, 3));
      memory.writeInt(172, 2);
      call(bot, memory, 207, 3, 100);
      memory.writeInt(172, 1);
      VmAbi.vector(memory, 144, new Vec3(-99, -99, -99));
      VmAbi.vector(memory, 120, new Vec3(10, 20, 30));
      call(bot, memory, 207, 3, 100);
      call(bot, memory, 303, 3, 500);
      assertEquals(new Vec3(-1, -2, -3), VmAbi.vector(memory, 572));
      assertEquals(new Vec3(1, 2, 3), VmAbi.vector(memory, 536));
      call(bot, memory, 207, 4, 100);
      call(bot, memory, 303, 4, 500);
      assertEquals(new Vec3(0, 0, 0), VmAbi.vector(memory, 572));
      assertEquals(new Vec3(0, 0, 0), VmAbi.vector(memory, 536));
      memory.writeInt(172, 3);
      memory.writeInt(176, 0);
      VmAbi.vector(memory, 120, new Vec3(0, 0, 0));
      call(bot, memory, 207, 3, 100);
      call(bot, memory, 303, 3, 500);
      var bounds = BspReader.read(BspFixture.map(false)).models().getFirst().bounds();
      assertEquals(bounds.min(), VmAbi.vector(memory, 572));
      assertEquals(bounds.max(), VmAbi.vector(memory, 584));
      VmAbi.vector(memory, 120, new Vec3(0, 360, 0));
      call(bot, memory, 207, 3, 100);
      call(bot, memory, 303, 3, 500);
      float x = (float) Math.max(Math.abs(bounds.min().x()), Math.abs(bounds.max().x()));
      float y = (float) Math.max(Math.abs(bounds.min().y()), Math.abs(bounds.max().y()));
      float z = (float) Math.max(Math.abs(bounds.min().z()), Math.abs(bounds.max().z()));
      float radius = (float) Math.sqrt(x * x + y * y + z * z);
      assertEquals(new Vec3(-radius, -radius, -radius), VmAbi.vector(memory, 572));
      assertEquals(new Vec3(radius, radius, radius), VmAbi.vector(memory, 584));
    }
  }

  @Test
  void scriptHandlesAndPcTokensAreBoundedAndBadOutputDoesNotConsumeToken() throws Exception {
    var memory = memory();
    try (var bot =
        bot(
            Map.of("botfiles/fixture.c", bytes("#define N 12\nN \"ready\"\n")),
            new Host(),
            GameAbi.Q3_132)) {
      text(memory, 100, "fixture.c");
      int handle = call(bot, memory, 578, 100);
      assertTrue(handle > 0);
      assertThrows(
          IllegalArgumentException.class,
          () -> call(bot, memory, 580, handle, memory.size() - 1039));
      memory.fill(1999, 1042, 127);
      assertEquals(1, call(bot, memory, 580, handle, 2000));
      assertEquals(3, memory.readInt(2000));
      assertEquals(12, memory.readInt(2008));
      assertEquals(12f, memory.readFloat(2012));
      assertEquals("12", memory.readCString(2016, 1024));
      assertEquals(127, memory.readUnsignedByte(1999));
      assertEquals(127, memory.readUnsignedByte(3040));
      assertEquals(1, call(bot, memory, 581, handle, 4000, 5100));
      assertEquals("botfiles/fixture.c", memory.readCString(4000, 1024));
      assertEquals(1, call(bot, memory, 580, handle, 2000));
      assertEquals(1, memory.readInt(2000));
      assertEquals("ready", memory.readCString(2016, 1024));
      assertEquals(0, call(bot, memory, 580, handle, 2000));
      assertEquals(1, call(bot, memory, 579, handle));
      assertEquals(0, call(bot, memory, 579, handle));
      assertThrows(IllegalArgumentException.class, () -> call(bot, memory, 580, handle, 2000));
      assertEquals(0, bot.status().openSources());
    }
  }

  @Test
  void bothGuestUserCommandProfilesReachHostWithSignedMovement() throws Exception {
    for (var abi : GameAbi.values()) {
      var memory = memory();
      var host = new Host();
      try (var bot = bot(Map.of(), host, abi)) {
        var command = new UserCommand(3456, -10, 32000, 23, 129, 9, -127, 100, -1);
        command.write(memory, 100, abi);
        call(bot, memory, 211, 1, 100);
        assertEquals(command, host.input);
        assertEquals(1, host.client);
        assertThrows(
            IllegalArgumentException.class, () -> call(bot, memory, 211, 1, memory.size() - 23));
      }
    }
  }

  @Test
  void bspQueriesDoNotNeedNavigationAndMissingMapsHaveAnExplicitResult() throws Exception {
    var memory = memory();
    try (var bot = bot(Map.of(), new Host(), GameAbi.Q3_132)) {
      assertEquals(1, call(bot, memory, 310, 0));
      assertEquals(2, call(bot, memory, 310, 1));
      assertEquals(0, call(bot, memory, 310, 2));
      text(memory, 100, "classname");
      assertEquals(1, call(bot, memory, 311, 1, 100, 300, 40));
      assertEquals("worldspawn", memory.readCString(300, 40));
      text(memory, 100, "origin");
      assertEquals(1, call(bot, memory, 312, 2, 100, 300));
      assertEquals(new Vec3(0, -200, 100), VmAbi.vector(memory, 300));
      text(memory, 100, "angle");
      assertEquals(1, call(bot, memory, 314, 2, 100, 300));
      assertEquals(90, memory.readInt(300));
      call(bot, memory, 200);
      text(memory, 100, "fixture");
      assertEquals(3, call(bot, memory, 206, 100));
      assertEquals(0, call(bot, memory, 304));
      text(memory, 100, "another");
      assertThrows(IllegalArgumentException.class, () -> call(bot, memory, 206, 100));
      assertThrows(
          IllegalArgumentException.class,
          () -> call(bot, memory, 301, 100, 200, 300, Integer.MAX_VALUE));
    }
  }

  @Test
  void characterCallsPreserveFloatBitsAndBoundStringWrites() throws Exception {
    var memory = memory();
    var files = Map.of("botfiles/bots/default_c.c", bytes("skill 1 { 0 0.25 1 7 2 \"name\" }"));
    try (var bot = bot(files, new Host(), GameAbi.Q3_132)) {
      call(bot, memory, 200);
      text(memory, 100, "bots/default_c.c");
      int handle = call(bot, memory, 500, 100, bits(1));
      assertTrue(handle > 0);
      assertEquals(.25f, Float.intBitsToFloat(call(bot, memory, 502, handle, 0)));
      assertEquals(
          .5f, Float.intBitsToFloat(call(bot, memory, 503, handle, 0, bits(.5f), bits(1))));
      assertEquals(7, call(bot, memory, 504, handle, 1));
      assertEquals(3, call(bot, memory, 505, handle, 1, 0, 3));
      memory.fill(299, 5, 127);
      call(bot, memory, 506, handle, 2, 300, 3);
      assertEquals("na", memory.readCString(300, 3));
      assertEquals(127, memory.readUnsignedByte(303));
      assertThrows(
          IllegalArgumentException.class,
          () -> call(bot, memory, 506, handle, 2, memory.size() - 1, 3));
      call(bot, memory, 501, handle);
      assertEquals(7, call(bot, memory, 504, handle, 1));
    }
  }

  @Test
  void aasQueriesUseThePublishedAreaAndVectorBuffers() throws Exception {
    var memory = memory();
    try (var bot =
        bot(Map.of("maps/fixture.aas", navigationFixture()), new Host(), GameAbi.Q3_132)) {
      call(bot, memory, 200);
      text(memory, 100, "maps/fixture.bsp");
      assertEquals(0, call(bot, memory, 206, 100));
      assertEquals(1, call(bot, memory, 304));
      VmAbi.vector(memory, 200, new Vec3(5, 0, 0));
      VmAbi.vector(memory, 220, new Vec3(-5, 0, 0));
      assertEquals(1, call(bot, memory, 307, 200));
      assertEquals(2, call(bot, memory, 307, 220));
      assertEquals(1, call(bot, memory, 553, 200, 0));
      assertEquals(2, call(bot, memory, 553, 220, 1));
      assertThrows(
          IllegalArgumentException.class, () -> call(bot, memory, 553, memory.size() - 11, 0));
      memory.fill(499, 54, 127);
      assertEquals(1, call(bot, memory, 302, 1, 500));
      assertEquals(2, memory.readInt(508));
      assertEquals(new Vec3(0, -10, -10), VmAbi.vector(memory, 516));
      assertEquals(new Vec3(10, 10, 10), VmAbi.vector(memory, 528));
      assertEquals(127, memory.readUnsignedByte(499));
      assertEquals(127, memory.readUnsignedByte(552));
      call(bot, memory, 305, 2, 600, 620);
      assertEquals(new Vec3(-15, -15, -24), VmAbi.vector(memory, 600));
      assertEquals(new Vec3(15, 15, 32), VmAbi.vector(memory, 620));
      call(bot, memory, 305, 4, 600, 620);
      assertEquals(new Vec3(15, 15, 8), VmAbi.vector(memory, 620));
      assertEquals(2, call(bot, memory, 308, 200, 220, 700, 800, 2));
      assertEquals(1, memory.readInt(700));
      assertEquals(2, memory.readInt(704));
      assertEquals(new Vec3(5, 0, 0), VmAbi.vector(memory, 800));
      assertEquals(new Vec3(0, 0, 0), VmAbi.vector(memory, 812));
      VmAbi.vector(memory, 200, new Vec3(-5, -5, -5));
      VmAbi.vector(memory, 220, new Vec3(5, 5, 5));
      assertEquals(2, call(bot, memory, 301, 200, 220, 700, 2));
      assertEquals(1, memory.readInt(700));
      assertEquals(2, memory.readInt(704));
      assertEquals(39, call(bot, memory, 316, 1, 200, 2, -1));
      assertEquals(1, call(bot, memory, 300, 2, 0));
      assertEquals(0, call(bot, memory, 316, 1, 200, 2, -1));
      assertEquals(0, call(bot, memory, 300, 2, 1));
      assertEquals(39, call(bot, memory, 316, 1, 200, 2, -1));
      VmAbi.vector(memory, 200, new Vec3(1, 0, 0));
      assertEquals(37, call(bot, memory, 316, 1, 200, 2, -1));
      assertEquals(1, call(bot, memory, 316, 1, 200, 1, 0));
      assertEquals(0, call(bot, memory, 316, 0, 200, 1, -1));
      assertThrows(
          IllegalArgumentException.class,
          () -> call(bot, memory, 308, 200, 220, 700, memory.size() - 23, 2));
    }
  }

  @Test
  void geometricGoalQueriesUseEntityUpdateTimeAndTheEngineSolidRay() throws Exception {
    var memory = memory();
    var host = new Host();
    try (var bot = bot(Map.of(), host, GameAbi.Q3_132)) {
      call(bot, memory, 200);
      var encoded = buffer(Goal.BYTE_SIZE);
      new Goal(
              new Vec3(100, 20, 30),
              1,
              new Vec3(-10, -10, -10),
              new Vec3(10, 10, 10),
              3,
              7,
              Goal.ITEM,
              0)
          .writeTo(encoded, 0);
      memory.writeBytes(300, encoded.array());
      VmAbi.vector(memory, 100, new Vec3(75, 20, 30));
      VmAbi.vector(memory, 120, new Vec3(0, 90, 0));
      assertEquals(1, call(bot, memory, 537, 100, 300));
      memory.writeFloat(100, Math.nextDown(75f));
      assertEquals(0, call(bot, memory, 537, 100, 300));
      call(bot, memory, 205, bits(1));
      assertEquals(1, call(bot, memory, 538, 0, 100, 120, 300));
      assertEquals(new Vec3(90, 10, 20), host.lastTrace.end());
      assertEquals(1, host.lastTrace.contentsMask());
      assertEquals(0, host.lastTrace.ignoreEntity());
      memory.fill(1000, 112, 0);
      call(bot, memory, 207, 3, 1000);
      assertEquals(0, call(bot, memory, 538, 0, 100, 120, 300));
      call(bot, memory, 205, bits(1.5f));
      assertEquals(0, call(bot, memory, 538, 0, 100, 120, 300));
      call(bot, memory, 205, bits(Math.nextUp(1.5f)));
      assertEquals(1, call(bot, memory, 538, 0, 100, 120, 300));
      host.traceFraction = .5;
      assertEquals(0, call(bot, memory, 538, 0, 100, 120, 300));
      assertThrows(
          IllegalArgumentException.class, () -> call(bot, memory, 537, 100, memory.size() - 55));
    }
  }

  @Test
  void campAndLocationImportsPreserveCursorsAndEmptyOutputMemory() throws Exception {
    var memory = memory();
    try (var bot =
        bot(
            Map.of("maps/fixture.aas", navigationFixture()),
            new Host(),
            GameAbi.Q3_132,
            """
            { "classname" "worldspawn" }
            { "classname" "info_camp" "origin" "3 4 5" }
            { "classname" "info_camp" "origin" "6 7 8" }
            { "classname" "target_location" "origin" "-3 2 1" "message" "Blue room" }
            """)) {
      call(bot, memory, 200);
      text(memory, 100, "fixture");
      call(bot, memory, 206, 100);
      assertEquals(1, call(bot, memory, 567, -1, 300));
      assertEquals(new Vec3(6, 7, 8), VmAbi.vector(memory, 300));
      assertEquals(2, call(bot, memory, 567, 1, 300));
      assertEquals(new Vec3(3, 4, 5), VmAbi.vector(memory, 300));
      memory.fill(300, 56, 127);
      assertEquals(0, call(bot, memory, 567, 2, 300));
      assertEquals(0x7f7f7f7f, memory.readInt(300));
      text(memory, 100, "BLUE ROOM");
      assertEquals(1, call(bot, memory, 568, 100, 300));
      assertEquals(new Vec3(-3, 2, 1), VmAbi.vector(memory, 300));
      assertEquals(2, memory.readInt(312));
      assertThrows(
          IllegalArgumentException.class, () -> call(bot, memory, 567, 0, memory.size() - 55));
    }
  }

  @Test
  void retailElementaryImportsTranslateThePublishedLegacyOrdering() throws Exception {
    var memory = memory();
    var host = new Host();
    try (var bot = bot(Map.of(), host, GameAbi.RETAIL_1999)) {
      call(bot, memory, 200);
      text(memory, 100, "say hello");
      call(bot, memory, 407, 0, 100);
      assertEquals(List.of("0:say hello"), host.commands);
      call(bot, memory, 408, 0, 7);
      call(bot, memory, 410, 0);
      call(bot, memory, 413, 0);
      VmAbi.vector(memory, 100, new Vec3(1, 0, 0));
      call(bot, memory, 422, 0, 100, bits(320));
      VmAbi.vector(memory, 100, new Vec3(10, 20, 30));
      call(bot, memory, 423, 0, 100);
      call(bot, memory, 425, 0, bits(.1f), 300);
      assertEquals(320, memory.readFloat(316));
      assertEquals(new Vec3(10, 20, 30), VmAbi.vector(memory, 320));
      assertTrue((memory.readInt(332) & ActionFlags.ATTACK) != 0);
      assertTrue((memory.readInt(332) & ActionFlags.JUMP) != 0);
      assertEquals(7, memory.readInt(336));
      call(bot, memory, 426, 0);
      call(bot, memory, 425, 0, bits(.1f), 300);
      assertEquals(0, memory.readInt(332) & ActionFlags.ATTACK);
      assertThrows(UnsupportedOperationException.class, () -> call(bot, memory, 300));
      assertThrows(UnsupportedOperationException.class, () -> call(bot, memory, 402));
    }
  }

  @Test
  void levelItemCallsDiscoverAssociateAndReinitializeWithBoundedGuestGoals() throws Exception {
    var files =
        Map.of(
            "maps/fixture.aas",
            navigationFixture(),
            "botfiles/items.c",
            bytes(
                """
                iteminfo "info_player_deathmatch" {
                  name "Test item" modelindex 17 mins {-1,-1,-1} maxs {1,1,1} respawntime 25
                }
                """),
            "botfiles/item.c",
            bytes("weight \"info_player_deathmatch\" { return 3; }"));
    var memory = memory();
    try (var bot = bot(files, new Host(), GameAbi.Q3_132)) {
      call(bot, memory, 200);
      text(memory, 100, "fixture");
      call(bot, memory, 206, 100);
      call(bot, memory, 205, bits(1));
      text(memory, 100, "Test item");
      assertEquals(1, call(bot, memory, 539, -1, 100, 300));
      assertEquals(0, memory.readInt(340));
      assertEquals(Goal.ITEM, memory.readInt(348));
      call(bot, memory, 532, 1, 400, 6);
      assertEquals("Test ", memory.readCString(400, 6));
      assertEquals(-1, call(bot, memory, 539, 1, 100, 300));
      memory.fill(1000, 112, 0);
      memory.writeInt(1000, 2);
      memory.writeInt(1076, 17);
      VmAbi.vector(memory, 1008, new Vec3(0, -200, 0));
      call(bot, memory, 207, 3, 1000);
      call(bot, memory, 542);
      assertEquals(1, call(bot, memory, 539, -1, 100, 300));
      assertEquals(0, memory.readInt(340));
      call(bot, memory, 207, 3, 1000);
      call(bot, memory, 542);
      assertEquals(1, call(bot, memory, 539, -1, 100, 300));
      assertEquals(3, memory.readInt(340));
      int handle = call(bot, memory, 546, 0);
      call(bot, memory, 573, handle, 1, bits(-1));
      assertEquals(25, Float.intBitsToFloat(call(bot, memory, 540, handle, 1)));
      call(bot, memory, 541);
      assertEquals(1, call(bot, memory, 539, -1, 100, 300));
      assertEquals(0, memory.readInt(340));
      text(memory, 6000, "item.c");
      assertEquals(0, call(bot, memory, 543, handle, 6000));
      memory.fill(7000, 1024, 0);
      assertEquals(0, call(bot, memory, 535, handle, 1008, 7000, -1));
      assertEquals(0, call(bot, memory, 533, handle, 300));
      call(bot, memory, 542);
      call(bot, memory, 526, handle);
      assertEquals(1, call(bot, memory, 535, handle, 1008, 7000, -1));
      assertEquals(1, call(bot, memory, 533, handle, 300));
      assertEquals(3, memory.readInt(340));
      assertEquals(1, memory.readInt(344));
      call(bot, memory, 525, handle);
      assertEquals(1, call(bot, memory, 536, handle, 1008, 7000, -1, 0, bits(1000)));
      assertEquals(1, call(bot, memory, 533, handle, 300));
      assertEquals(3, memory.readInt(340));
      assertThrows(
          IllegalArgumentException.class,
          () -> call(bot, memory, 539, -1, 100, memory.size() - 55));
    }
  }

  @Test
  void goalStateCallsPreserveEmptyOutputsAndRetainOwnedRecordsAndWeights() throws Exception {
    var memory = memory();
    try (var bot =
        bot(
            Map.of("botfiles/item.c", bytes("weight \"item\" { return 3; }")),
            new Host(),
            GameAbi.Q3_132)) {
      call(bot, memory, 200);
      call(bot, memory, 205, bits(1));
      int handle = call(bot, memory, 546, 0);
      assertEquals(1, handle);
      memory.fill(500, 56, 127);
      assertEquals(0, call(bot, memory, 533, handle, 500));
      assertEquals(0x7f7f7f7f, memory.readInt(544));
      memory.fill(100, 56, 0);
      VmAbi.vector(memory, 100, new Vec3(1, 2, 3));
      memory.writeInt(144, 17);
      call(bot, memory, 527, handle, 100);
      memory.writeInt(144, 99);
      assertThrows(
          IllegalArgumentException.class, () -> call(bot, memory, 533, handle, memory.size() - 55));
      assertEquals(1, call(bot, memory, 533, handle, 500));
      assertEquals(17, memory.readInt(544));
      call(bot, memory, 573, handle, 17, bits(3));
      assertEquals(3f, Float.intBitsToFloat(call(bot, memory, 540, handle, 17)));
      text(memory, 1000, "item.c");
      assertEquals(0, call(bot, memory, 543, handle, 1000));
      call(bot, memory, 525, handle);
      assertEquals(0, call(bot, memory, 533, handle, 500));
      assertEquals(0, Float.intBitsToFloat(call(bot, memory, 540, handle, 17)));
      call(bot, memory, 544, handle);
      call(bot, memory, 547, handle);
      assertEquals(1, call(bot, memory, 546, 1));
      assertEquals(0, call(bot, memory, 533, handle, 500));
      text(memory, 1000, "missing.c");
      assertEquals(9, call(bot, memory, 543, handle, 1000));
      assertEquals(0, call(bot, memory, 535, handle, 0, 2000, -1));
      assertEquals(0, call(bot, memory, 536, handle, 0, 2000, -1, 0, bits(100)));
      assertThrows(
          IllegalArgumentException.class,
          () -> call(bot, memory, 535, handle, 0, memory.size() - 1023, -1));
      assertThrows(
          IllegalArgumentException.class,
          () -> call(bot, memory, 536, handle, 0, 2000, -1, memory.size() - 55, bits(100)));
      assertThrows(
          IllegalArgumentException.class,
          () -> call(bot, memory, 536, handle, 0, 2000, -1, 0, bits(Float.NaN)));
    }
  }

  @Test
  void weaponCallsUseTheGuestInventoryAndWriteTheFull552ByteRecord() throws Exception {
    var files =
        Map.of(
            "botfiles/weapons.c",
            bytes(
                "projectileinfo { name \"p\" damage 17 } weaponinfo { name \"Tool\" number 4"
                    + " projectile \"p\" }"),
            "botfiles/tool_w.c",
            bytes("weight \"Tool\" { switch (0) { case 1: return 0; default: return 10; } }"));
    var memory = memory();
    try (var bot = bot(files, new Host(), GameAbi.Q3_132)) {
      call(bot, memory, 200);
      int handle = call(bot, memory, 561);
      text(memory, 100, "tool_w.c");
      assertEquals(0, call(bot, memory, 560, handle, 100));
      assertEquals(0, call(bot, memory, 558, handle, 3000));
      memory.writeInt(3000, 1000000);
      assertEquals(4, call(bot, memory, 558, handle, 3000));
      memory.fill(999, 554, 127);
      call(bot, memory, 559, handle, 4, 1000);
      assertEquals(1, memory.readInt(1000));
      assertEquals(4, memory.readInt(1004));
      assertEquals("Tool", memory.readCString(1008, 80));
      assertEquals("p", memory.readCString(1344, 80));
      assertEquals(127, memory.readUnsignedByte(999));
      assertEquals(127, memory.readUnsignedByte(1552));
      assertThrows(
          IllegalArgumentException.class,
          () -> call(bot, memory, 558, handle, memory.size() - 1023));
      assertThrows(
          IllegalArgumentException.class,
          () -> call(bot, memory, 559, handle, 4, memory.size() - 551));
      call(bot, memory, 563, handle);
      assertEquals(4, call(bot, memory, 558, handle, 3000));
      call(bot, memory, 562, handle);
      assertEquals(0, call(bot, memory, 558, handle, 3000));
    }
  }

  @Test
  void movementBridgeValidatesCompleteInputBeforeKeepingTheState() throws Exception {
    var memory = memory();
    try (var bot = bot(Map.of(), new Host(), GameAbi.RETAIL_1999)) {
      call(bot, memory, 200);
      int handle = call(bot, memory, 555);
      assertEquals(1, handle);
      assertThrows(
          IllegalArgumentException.class, () -> call(bot, memory, 557, handle, memory.size() - 67));
      memory.fill(100, 68, 0);
      memory.writeFloat(144, .05f);
      memory.writeInt(148, 2);
      call(bot, memory, 557, handle, 100);
      call(bot, memory, 548, handle);
      assertEquals(0, call(bot, memory, 551, handle));
      assertEquals(0, call(bot, memory, 552, handle));
      // AVOID_CLEAR does not read its unused vector pointer or float argument.
      call(bot, memory, 574, handle, -1, bits(Float.NaN), 0);
      assertThrows(
          IllegalArgumentException.class, () -> call(bot, memory, 574, handle, -1, bits(1), 1));
      assertThrows(IllegalStateException.class, () -> call(bot, memory, 549, 300, handle, 500, -1));
      call(bot, memory, 556, handle);
      assertEquals(handle, call(bot, memory, 555));
    }
  }

  @Test
  void swimmingSamplesTwoUnitsBelowTheSuppliedOrigin() throws Exception {
    for (var abi : GameAbi.values()) {
      var memory = memory();
      try (var bot = bot(Map.of(), new Host(), abi)) {
        call(bot, memory, 200);
        VmAbi.vector(memory, 100, new Vec3(1, 2, 1));
        assertEquals(1, call(bot, memory, 317, 100));
        memory.writeFloat(108, 2);
        assertEquals(0, call(bot, memory, 317, 100));
        memory.writeFloat(108, Math.nextDown(2f));
        assertEquals(1, call(bot, memory, 317, 100));
      }
    }
  }

  @Test
  void directionalMovementPublishesSwimmingInputAndPreservesActionsWhenAirborne() throws Exception {
    for (var abi : GameAbi.values()) {
      var memory = memory();
      var host = new Host();
      try (var bot = bot(Map.of("maps/fixture.aas", navigationFixture()), host, abi)) {
        call(bot, memory, 200);
        text(memory, 1000, "fixture");
        call(bot, memory, 206, 1000);
        int handle = call(bot, memory, 555);
        memory.fill(100, 68, 0);
        VmAbi.vector(memory, 100, new Vec3(5, 0, 1));
        memory.writeInt(136, 1);
        memory.writeInt(140, 1);
        memory.writeFloat(144, .1f);
        memory.writeInt(148, 2);
        call(bot, memory, 557, handle, 100);
        VmAbi.vector(memory, 200, new Vec3(0, 3, 4));
        assertEquals(1, call(bot, memory, 550, handle, 200, bits(600), 6));
        int getInput = abi == GameAbi.RETAIL_1999 ? 425 : 422;
        call(bot, memory, getInput, 1, bits(.1f), 700);
        assertEquals(new Vec3(0, .6f, .8f), VmAbi.vector(memory, 704));
        assertEquals(400, memory.readFloat(716));
        assertEquals(0, memory.readInt(732));
        byte[] previous = memory.readBytes(700, 40);

        // An airborne request without pending barrier steering succeeds without replacing EA.
        memory.writeFloat(108, 10);
        call(bot, memory, 557, handle, 100);
        VmAbi.vector(memory, 200, new Vec3(-1, 0, 0));
        assertEquals(1, call(bot, memory, 550, handle, 200, bits(100), 0));
        call(bot, memory, getInput, 1, bits(.1f), 700);
        assertArrayEquals(previous, memory.readBytes(700, 40));

        assertThrows(
            IllegalArgumentException.class,
            () -> call(bot, memory, 550, handle, memory.size() - 11, bits(100), 0));
        assertThrows(
            IllegalArgumentException.class,
            () -> call(bot, memory, 550, handle, 200, bits(Float.NaN), 0));
        VmAbi.vector(memory, 200, new Vec3(1_000_001, 0, 0));
        assertThrows(
            IllegalArgumentException.class,
            () -> call(bot, memory, 550, handle, 200, bits(100), 0));
        call(bot, memory, getInput, 1, bits(.1f), 700);
        assertArrayEquals(previous, memory.readBytes(700, 40));
        assertNull(host.lastTrace);
      }
    }
  }

  @Test
  void groundMovesPublishActionsAndViewHistoryWhileEarlyReturnsPreserveResultSuffix()
      throws Exception {
    for (var abi : GameAbi.values()) {
      var memory = memory();
      var host = new Host();
      try (var bot = bot(Map.of("maps/fixture.aas", navigationFixture()), host, abi)) {
        call(bot, memory, 200);
        text(memory, 1000, "fixture");
        call(bot, memory, 206, 1000);
        call(bot, memory, 205, bits(1));
        int handle = call(bot, memory, 555);
        memory.fill(100, 68, 0);
        VmAbi.vector(memory, 100, new Vec3(5, 0, 3));
        memory.writeInt(136, 1);
        memory.writeInt(140, 1);
        memory.writeFloat(144, .1f);
        memory.writeInt(148, 2);
        memory.writeInt(164, 2);
        call(bot, memory, 557, handle, 100);
        var goal = buffer(56);
        new Goal(new Vec3(-5, 0, 3), 2, new Vec3(0, 0, 0), new Vec3(0, 0, 0), 0, 0, 0, 0)
            .writeTo(goal, 0);
        memory.writeBytes(300, goal.array());
        memory.fill(499, 54, 127);
        text(memory, 1100, "phys_maxstep");
        text(memory, 1150, "19");
        call(bot, memory, 202, 1100, 1150);
        text(memory, 1100, "sv_step");
        call(bot, memory, 202, 1100, 1150);
        assertThrows(
            UnsupportedOperationException.class,
            () -> call(bot, memory, 549, 500, handle, 300, -1));
        assertEquals(0x7f7f7f7f, memory.readInt(500));
        text(memory, 1150, "18");
        call(bot, memory, 202, 1100, 1150);
        assertThrows(
            IllegalArgumentException.class,
            () -> call(bot, memory, 549, memory.size() - 51, handle, 300, -1));
        assertEquals(0, call(bot, memory, 554, handle, 300, -1, bits(2), 600));
        assertEquals(0, call(bot, memory, 549, 500, handle, 300, -1));
        assertEquals(0, memory.readInt(500));
        assertEquals(2, memory.readInt(516));
        assertEquals(new Vec3(-1, 0, 0), VmAbi.vector(memory, 528));
        assertEquals(127, memory.readUnsignedByte(499));
        assertEquals(127, memory.readUnsignedByte(552));
        int getInput = abi == GameAbi.RETAIL_1999 ? 425 : 422;
        call(bot, memory, getInput, 1, bits(.1f), 700);
        assertEquals(new Vec3(-1, 0, 0), VmAbi.vector(memory, 704));
        assertEquals(400, memory.readFloat(716));
        assertEquals(1, call(bot, memory, 554, handle, 300, -1, bits(2), 600));
        assertEquals(new Vec3(3.4f, 0, 1.8f), VmAbi.vector(memory, 600));
        host.traceFraction = .5;
        host.traceHit =
            new TraceResult.Hit(
                new TraceResult.Plane(new Vec3(0, 0, 1), 0),
                1,
                0,
                0,
                0,
                -1,
                -1,
                -1,
                "authored entity");
        memory.fill(500, 52, 127);
        memory.writeInt(528, 0x7fc00001);
        byte[] suffix = memory.readBytes(524, 28);
        call(bot, memory, abi == GameAbi.RETAIL_1999 ? 426 : 423, 1);
        assertEquals(0, call(bot, memory, 549, 500, handle, 300, -1));
        assertEquals(1, memory.readInt(508));
        assertEquals(0, memory.readInt(512));
        assertEquals(32, memory.readInt(520));
        assertArrayEquals(suffix, memory.readBytes(524, 28));
        call(bot, memory, getInput, 1, bits(.1f), 700);
        assertEquals(0, memory.readFloat(716));
        assertEquals(1, call(bot, memory, 554, handle, 300, -1, bits(2), 600));
        assertEquals(new Vec3(3.4f, 0, 1.8f), VmAbi.vector(memory, 600));
      }
    }
  }

  @Test
  void jumpPadBridgeChangesFromRawApproachToCachedAirSteeringForBothAbis() throws Exception {
    for (var abi : GameAbi.values()) {
      var memory = memory();
      try (var bot = bot(Map.of("maps/fixture.aas", navigationFixture(18)), new Host(), abi)) {
        call(bot, memory, 200);
        text(memory, 1000, "fixture");
        call(bot, memory, 206, 1000);
        call(bot, memory, 205, bits(1));
        int handle = call(bot, memory, 555);
        memory.fill(100, 68, 0);
        VmAbi.vector(memory, 100, new Vec3(5, 0, 3));
        memory.writeInt(136, 1);
        memory.writeInt(140, 1);
        memory.writeFloat(144, .1f);
        memory.writeInt(148, 2);
        memory.writeInt(164, 2);
        call(bot, memory, 557, handle, 100);
        var goal = buffer(56);
        new Goal(new Vec3(-5, 0, 3), 2, new Vec3(0, 0, 0), new Vec3(0, 0, 0), 0, 0, 0, 0)
            .writeTo(goal, 0);
        memory.writeBytes(300, goal.array());
        call(bot, memory, 549, 500, handle, 300, -1);
        assertEquals(18, memory.readInt(516));
        assertEquals(new Vec3(-4, 0, 0), VmAbi.vector(memory, 528));

        VmAbi.vector(memory, 100, new Vec3(5, 0, 10));
        VmAbi.vector(memory, 112, new Vec3(1, 2, 30));
        memory.writeInt(164, 0);
        call(bot, memory, 557, handle, 100);
        call(bot, memory, abi == GameAbi.RETAIL_1999 ? 426 : 423, 1);
        // The current route mask does not cancel an already active airborne reach.
        call(bot, memory, 549, 500, handle, 300, 0);
        var nativeDirection = new Vec3(-.998983979f, -.045066949f, 0);
        assertEquals(18, memory.readInt(516));
        assertEquals(nativeDirection, VmAbi.vector(memory, 528));
        call(bot, memory, abi == GameAbi.RETAIL_1999 ? 425 : 422, 1, bits(.1f), 700);
        assertEquals(nativeDirection, VmAbi.vector(memory, 704));
        assertEquals(79.8811646f, memory.readFloat(716));
        assertEquals(0, memory.readInt(732));
      }
    }
  }

  @Test
  void teleportCompletionKeepsPriorActionsAndPreservesTheAirborneResultSuffixForBothAbis()
      throws Exception {
    for (var abi : GameAbi.values()) {
      var memory = memory();
      try (var bot = bot(Map.of("maps/fixture.aas", navigationFixture(10)), new Host(), abi)) {
        call(bot, memory, 200);
        text(memory, 1000, "fixture");
        call(bot, memory, 206, 1000);
        call(bot, memory, 205, bits(1));
        int handle = call(bot, memory, 555);
        memory.fill(100, 68, 0);
        VmAbi.vector(memory, 100, new Vec3(5, 0, 3));
        memory.writeInt(136, 1);
        memory.writeInt(140, 1);
        memory.writeFloat(144, .1f);
        memory.writeInt(148, 2);
        memory.writeInt(164, 2);
        call(bot, memory, 557, handle, 100);
        var goal = buffer(56);
        new Goal(new Vec3(-5, 0, 3), 2, new Vec3(0, 0, 0), new Vec3(0, 0, 0), 0, 0, 0, 0)
            .writeTo(goal, 0);
        memory.writeBytes(300, goal.array());
        call(bot, memory, 549, 500, handle, 300, -1);
        assertEquals(10, memory.readInt(516));
        assertEquals(new Vec3(-1, 0, 0), VmAbi.vector(memory, 528));
        int getInput = abi == GameAbi.RETAIL_1999 ? 425 : 422;
        call(bot, memory, getInput, 1, bits(.1f), 700);
        assertEquals(new Vec3(-1, 0, 0), VmAbi.vector(memory, 704));
        assertEquals(200, memory.readFloat(716));
        byte[] priorInput = memory.readBytes(700, 40);

        // The TELEPORTED flag ends entry without replacing the accumulated movement input.
        memory.writeInt(164, 34);
        call(bot, memory, 557, handle, 100);
        memory.fill(500, 52, 127);
        call(bot, memory, 549, 500, handle, 300, -1);
        assertEquals(10, memory.readInt(516));
        assertArrayEquals(new byte[28], memory.readBytes(524, 28));
        call(bot, memory, getInput, 1, bits(.1f), 700);
        assertArrayEquals(priorInput, memory.readBytes(700, 40));

        // Cached airborne teleport writes only its prefix and never issues a move.
        VmAbi.vector(memory, 100, new Vec3(5, 0, 10));
        memory.writeInt(164, 32);
        call(bot, memory, 557, handle, 100);
        memory.fill(500, 52, 127);
        call(bot, memory, 549, 500, handle, 300, 0);
        assertEquals(10, memory.readInt(516));
        for (byte value : memory.readBytes(524, 28)) assertEquals(127, value);
        call(bot, memory, getInput, 1, bits(.1f), 700);
        assertArrayEquals(priorInput, memory.readBytes(700, 40));
      }
    }
  }

  @Test
  void barrierJumpOnlyEntryPreservesMovementAndUsesJumpHistoryForBothAbis() throws Exception {
    for (var abi : GameAbi.values()) {
      var memory = memory();
      try (var bot = bot(Map.of("maps/fixture.aas", navigationFixture(4)), new Host(), abi)) {
        call(bot, memory, 200);
        text(memory, 1000, "fixture");
        call(bot, memory, 206, 1000);
        call(bot, memory, 205, bits(1));
        int handle = call(bot, memory, 555);
        memory.fill(100, 68, 0);
        VmAbi.vector(memory, 100, new Vec3(5, 0, 3));
        memory.writeInt(136, 1);
        memory.writeInt(140, 1);
        memory.writeFloat(144, .1f);
        memory.writeInt(148, 2);
        memory.writeInt(164, 2);
        call(bot, memory, 557, handle, 100);
        var goal = buffer(56);
        new Goal(new Vec3(-5, 0, 3), 2, new Vec3(0, 0, 0), new Vec3(0, 0, 0), 0, 0, 0, 0)
            .writeTo(goal, 0);
        memory.writeBytes(300, goal.array());
        int move = abi == GameAbi.RETAIL_1999 ? 422 : 419;
        int getInput = abi == GameAbi.RETAIL_1999 ? 425 : 422;
        VmAbi.vector(memory, 400, new Vec3(2, 3, 4));
        call(bot, memory, move, 1, 400, bits(123));
        call(bot, memory, 549, 500, handle, 300, -1);
        assertEquals(4, memory.readInt(516));
        assertEquals(new Vec3(-1, 0, 0), VmAbi.vector(memory, 528));
        call(bot, memory, getInput, 1, bits(.1f), 700);
        assertEquals(new Vec3(2, 3, 4), VmAbi.vector(memory, 704));
        assertEquals(123, memory.readFloat(716));
        assertEquals(16, memory.readInt(732));

        call(bot, memory, abi == GameAbi.RETAIL_1999 ? 426 : 423, 1);
        call(bot, memory, move, 1, 400, bits(99));
        call(bot, memory, 549, 500, handle, 300, -1);
        call(bot, memory, getInput, 1, bits(.1f), 700);
        assertEquals(new Vec3(2, 3, 4), VmAbi.vector(memory, 704));
        assertEquals(99, memory.readFloat(716));
        // EA_Jump suppresses consecutive jumping; OR-ing ACTION_JUMP would break this.
        assertEquals(0x10000000, memory.readInt(732));
        byte[] prior = memory.readBytes(700, 40);

        VmAbi.vector(memory, 100, new Vec3(5, 0, 10));
        VmAbi.vector(memory, 112, new Vec3(0, 0, 250));
        memory.writeInt(164, 0);
        call(bot, memory, 557, handle, 100);
        call(bot, memory, 549, 500, handle, 300, 0);
        assertEquals(4, memory.readInt(516));
        assertArrayEquals(new byte[28], memory.readBytes(524, 28));
        call(bot, memory, getInput, 1, bits(.1f), 700);
        assertArrayEquals(prior, memory.readBytes(700, 40));
      }
    }
  }

  @Test
  void swimBridgeSupportsLiquidRoutingCachedAirAndVerticalSameAreaGoalsForBothAbis()
      throws Exception {
    for (var abi : GameAbi.values()) {
      var memory = memory();
      try (var bot = bot(Map.of("maps/fixture.aas", navigationFixture(8)), new Host(), abi)) {
        call(bot, memory, 200);
        text(memory, 1000, "fixture");
        call(bot, memory, 206, 1000);
        call(bot, memory, 205, bits(1));
        int handle = call(bot, memory, 555);
        memory.fill(100, 68, 0);
        VmAbi.vector(memory, 100, new Vec3(5, 0, 1));
        memory.writeInt(136, 1);
        memory.writeInt(140, 1);
        memory.writeFloat(144, .1f);
        memory.writeInt(148, 2);
        call(bot, memory, 557, handle, 100);
        var goal = buffer(56);
        var zero = new Vec3(0, 0, 0);
        new Goal(new Vec3(-5, 0, 1), 2, zero, zero, 0, 0, 0, 0).writeTo(goal, 0);
        memory.writeBytes(300, goal.array());
        call(bot, memory, abi == GameAbi.RETAIL_1999 ? 410 : 406, 1);
        memory.fill(499, 54, 127);
        call(bot, memory, 549, 500, handle, 300, -1);
        assertEquals(8, memory.readInt(516));
        assertEquals(2, memory.readInt(520));
        assertTrue(memory.readFloat(536) < 0, "Swim approach retains vertical movement");
        assertEquals(127, memory.readUnsignedByte(499));
        assertEquals(127, memory.readUnsignedByte(552));
        int getInput = abi == GameAbi.RETAIL_1999 ? 425 : 422;
        call(bot, memory, getInput, 1, bits(.1f), 700);
        assertEquals(VmAbi.vector(memory, 528), VmAbi.vector(memory, 704));
        assertEquals(400, memory.readFloat(716));
        assertEquals(ActionFlags.ATTACK, memory.readInt(732));

        // Dry airborne continuation retains a cached swim reach and still approaches its start.
        VmAbi.vector(memory, 100, new Vec3(5, 0, 10));
        call(bot, memory, 557, handle, 100);
        call(bot, memory, 549, 500, handle, 300, 0);
        assertEquals(8, memory.readInt(516));
        assertEquals(2, memory.readInt(520));
        assertTrue(memory.readFloat(536) < 0);
        call(bot, memory, getInput, 1, bits(.1f), 700);
        assertEquals(400, memory.readFloat(716));

        VmAbi.vector(memory, 100, new Vec3(5, 0, 1));
        call(bot, memory, 557, handle, 100);
        new Goal(new Vec3(5, 0, 101), 1, zero, zero, 0, 0, 0, 0).writeTo(goal, 0);
        memory.writeBytes(300, goal.array());
        call(bot, memory, 549, 500, handle, 300, -1);
        assertEquals(8, memory.readInt(516));
        assertEquals(new Vec3(0, 0, 1), VmAbi.vector(memory, 528));
        call(bot, memory, getInput, 1, bits(.1f), 700);
        assertEquals(new Vec3(0, 0, 1), VmAbi.vector(memory, 704));
        assertEquals(400, memory.readFloat(716));
        assertEquals(ActionFlags.ATTACK, memory.readInt(732));
      }
    }
  }

  @Test
  void waterJumpBridgePublishesDirectionalActionsAndPreservesPriorMovementThroughAirForBothAbis()
      throws Exception {
    for (var abi : GameAbi.values()) {
      var memory = memory();
      try (var bot = bot(Map.of("maps/fixture.aas", navigationFixture(9)), new Host(), abi)) {
        call(bot, memory, 200);
        text(memory, 1000, "fixture");
        call(bot, memory, 206, 1000);
        call(bot, memory, 205, bits(1));
        int handle = call(bot, memory, 555);
        memory.fill(100, 68, 0);
        VmAbi.vector(memory, 100, new Vec3(5, 0, 1));
        memory.writeInt(136, 1);
        memory.writeInt(140, 1);
        memory.writeFloat(144, .1f);
        memory.writeInt(148, 2);
        call(bot, memory, 557, handle, 100);
        var goal = buffer(56);
        var zero = new Vec3(0, 0, 0);
        new Goal(new Vec3(-5, 0, 1), 2, zero, zero, 0, 0, 0, 0).writeTo(goal, 0);
        memory.writeBytes(300, goal.array());
        int move = abi == GameAbi.RETAIL_1999 ? 422 : 419;
        int getInput = abi == GameAbi.RETAIL_1999 ? 425 : 422;
        VmAbi.vector(memory, 400, new Vec3(2, 3, 4));
        call(bot, memory, move, 1, 400, bits(123));
        call(bot, memory, 549, 500, handle, 300, -1);
        assertEquals(9, memory.readInt(516));
        assertEquals(1, memory.readInt(520));
        call(bot, memory, getInput, 1, bits(.1f), 700);
        assertEquals(new Vec3(2, 3, 4), VmAbi.vector(memory, 704));
        assertEquals(123, memory.readFloat(716));
        assertEquals(544, memory.readInt(732));
        byte[] prior = memory.readBytes(700, 40);

        VmAbi.vector(memory, 100, new Vec3(5, 0, 10));
        call(bot, memory, 557, handle, 100);
        memory.fill(500, 52, 127);
        call(bot, memory, 549, 500, handle, 300, 0);
        assertEquals(9, memory.readInt(516));
        assertArrayEquals(new byte[32], memory.readBytes(520, 32));
        call(bot, memory, getInput, 1, bits(.1f), 700);
        assertArrayEquals(prior, memory.readBytes(700, 40));
      }
    }
  }

  @Test
  void ordinaryJumpBridgeUsesRealRunUpPredictionAndBothJumpActionServicesForBothAbis()
      throws Exception {
    for (var abi : GameAbi.values()) {
      for (int originX : new int[] {5, 29}) {
        var memory = memory();
        byte[] aas = navigationFixture(5);
        var bytes = ByteBuffer.wrap(aas).order(ByteOrder.LITTLE_ENDIAN);
        int reachOffset = bytes.getInt(12 + 9 * 8);
        for (int reach : new int[] {1, 2}) {
          bytes.putFloat(reachOffset + reach * 44 + 20, 32);
          bytes.putFloat(reachOffset + reach * 44 + 32, 32);
        }
        try (var bot = bot(Map.of("maps/fixture.aas", aas), new Host(), abi)) {
          call(bot, memory, 200);
          text(memory, 1000, "fixture");
          call(bot, memory, 206, 1000);
          call(bot, memory, 205, bits(1));
          int handle = call(bot, memory, 555);
          memory.fill(100, 68, 0);
          VmAbi.vector(memory, 100, new Vec3(originX, 0, 35));
          memory.writeInt(136, 1);
          memory.writeInt(140, 1);
          memory.writeFloat(144, .1f);
          memory.writeInt(148, 2);
          memory.writeInt(164, 2);
          call(bot, memory, 557, handle, 100);
          var goal = buffer(56);
          var zero = new Vec3(0, 0, 0);
          new Goal(new Vec3(-5, 0, 35), 2, zero, zero, 0, 0, 0, 0).writeTo(goal, 0);
          memory.writeBytes(300, goal.array());
          memory.fill(499, 54, 127);
          call(bot, memory, 549, 500, handle, 300, -1);
          assertEquals(5, memory.readInt(516));
          assertEquals(0, memory.readInt(520));
          assertEquals(new Vec3(-1, 0, 0), VmAbi.vector(memory, 528));
          assertEquals(127, memory.readUnsignedByte(499));
          assertEquals(127, memory.readUnsignedByte(552));
          int getInput = abi == GameAbi.RETAIL_1999 ? 425 : 422;
          call(bot, memory, getInput, 1, bits(.1f), 700);
          assertEquals(400, memory.readFloat(716));
          assertEquals(
              originX == 5 ? ActionFlags.JUMP : ActionFlags.DELAYED_JUMP, memory.readInt(732));

          call(bot, memory, abi == GameAbi.RETAIL_1999 ? 413 : 417, 1);
          call(bot, memory, abi == GameAbi.RETAIL_1999 ? 426 : 423, 1);
          call(bot, memory, 549, 500, handle, 300, -1);
          call(bot, memory, getInput, 1, bits(.1f), 700);
          assertEquals(ActionFlags.JUMPED_LAST_FRAME, memory.readInt(732));

          VmAbi.vector(memory, 100, new Vec3(5, 0, 50));
          memory.writeInt(164, 0);
          call(bot, memory, 557, handle, 100);
          call(bot, memory, 549, 500, handle, 300, 0);
          assertEquals(5, memory.readInt(516));
          call(bot, memory, getInput, 1, bits(.1f), 700);
          assertEquals(new Vec3(-1, 0, 0), VmAbi.vector(memory, 704));
          assertEquals(400, memory.readFloat(716));
          assertEquals(ActionFlags.JUMPED_LAST_FRAME, memory.readInt(732));
        }
      }
    }
  }

  @Test
  void bobbingBridgeRetainsMoverMetadataAndCachedAirCommandsForBothAbis() throws Exception {
    for (var abi : GameAbi.values()) {
      var memory = memory();
      byte[] aas = navigationFixture(19);
      var bytes = ByteBuffer.wrap(aas).order(ByteOrder.LITTLE_ENDIAN);
      int reachOffset = bytes.getInt(12 + 9 * 8);
      for (int reach : new int[] {1, 2}) {
        bytes.putFloat(reachOffset + reach * 44 + 20, 3);
        bytes.putFloat(reachOffset + reach * 44 + 32, 3);
      }
      try (var bot = bot(Map.of("maps/fixture.aas", aas), new Host(), abi)) {
        call(bot, memory, 200);
        text(memory, 1000, "fixture");
        call(bot, memory, 206, 1000);
        call(bot, memory, 205, bits(1));
        memory.fill(1100, 112, 0);
        memory.writeInt(1100, 4);
        VmAbi.vector(memory, 1108, new Vec3(0, 0, 60));
        call(bot, memory, 207, 0, 1100);
        // Native origin lookup includes slot zero and retains an explicitly unlinked entity.
        call(bot, memory, 207, 0, 0);
        call(bot, memory, 205, bits(2));
        int handle = call(bot, memory, 555);
        memory.fill(100, 68, 0);
        VmAbi.vector(memory, 100, new Vec3(5, 0, 3));
        memory.writeInt(136, 1);
        memory.writeInt(140, 1);
        memory.writeFloat(144, .1f);
        memory.writeInt(148, 2);
        memory.writeInt(164, 2);
        call(bot, memory, 557, handle, 100);
        var goal = buffer(56);
        var zero = new Vec3(0, 0, 0);
        new Goal(new Vec3(-5, 0, 3), 2, zero, zero, 0, 0, 0, 0).writeTo(goal, 0);
        memory.writeBytes(300, goal.array());
        VmAbi.vector(memory, 400, new Vec3(11, 22, 33));
        call(bot, memory, abi == GameAbi.RETAIL_1999 ? 423 : 420, 1, 400);
        call(bot, memory, abi == GameAbi.RETAIL_1999 ? 408 : 416, 1, 2);
        memory.fill(499, 54, 127);
        call(bot, memory, 549, 500, handle, 300, -1);
        assertEquals(19, memory.readInt(516));
        assertEquals(0, memory.readInt(520));
        assertEquals(new Vec3(-6, 0, 0), VmAbi.vector(memory, 528));
        assertEquals(127, memory.readUnsignedByte(499));
        assertEquals(127, memory.readUnsignedByte(552));
        int getInput = abi == GameAbi.RETAIL_1999 ? 425 : 422;
        call(bot, memory, getInput, 1, bits(.1f), 700);
        assertEquals(new Vec3(-6, 0, 0), VmAbi.vector(memory, 704));
        assertEquals(36, memory.readFloat(716));
        assertEquals(new Vec3(11, 22, 33), VmAbi.vector(memory, 720));
        assertEquals(2, memory.readInt(736));
        assertEquals(0, memory.readInt(732));

        VmAbi.vector(memory, 100, new Vec3(8, 0, 10));
        memory.writeInt(164, 0);
        call(bot, memory, 557, handle, 100);
        call(bot, memory, 549, 500, handle, 300, 0);
        assertEquals(19, memory.readInt(516));
        assertEquals(new Vec3(-1, 0, 0), VmAbi.vector(memory, 528));
        call(bot, memory, getInput, 1, bits(.1f), 700);
        assertEquals(new Vec3(-1, 0, 0), VmAbi.vector(memory, 704));
        assertEquals(32, memory.readFloat(716));
        assertEquals(new Vec3(11, 22, 33), VmAbi.vector(memory, 720));
        assertEquals(2, memory.readInt(736));
      }
    }
  }

  @Test
  void weaponJumpBridgePublishesViewWeaponAndJumpHistoryThroughAirForBothAbis() throws Exception {
    for (var abi : GameAbi.values()) {
      for (int travelType : new int[] {12, 13}) {
        var memory = memory();
        try (var bot =
            bot(Map.of("maps/fixture.aas", navigationFixture(travelType)), new Host(), abi)) {
          call(bot, memory, 200);
          text(memory, 1000, "fixture");
          call(bot, memory, 206, 1000);
          call(bot, memory, 205, bits(1));
          int handle = call(bot, memory, 555);
          memory.fill(100, 68, 0);
          VmAbi.vector(memory, 100, new Vec3(5, 0, 3));
          memory.writeInt(136, 1);
          memory.writeInt(140, 1);
          memory.writeFloat(144, .1f);
          memory.writeInt(148, 2);
          VmAbi.vector(memory, 152, new Vec3(20, 30, 40));
          memory.writeInt(164, 2);
          call(bot, memory, 557, handle, 100);
          var goal = buffer(56);
          var zero = new Vec3(0, 0, 0);
          new Goal(new Vec3(-5, 0, 3), 2, zero, zero, 0, 0, 0, 0).writeTo(goal, 0);
          memory.writeBytes(300, goal.array());
          int getInput = abi == GameAbi.RETAIL_1999 ? 425 : 422;
          int selectedWeapon = travelType == 12 ? 5 : 9;
          memory.fill(499, 54, 127);
          call(bot, memory, 549, 500, handle, 300, -1);
          assertEquals(travelType, memory.readInt(516));
          assertEquals(24, memory.readInt(520));
          assertEquals(selectedWeapon, memory.readInt(524));
          assertEquals(new Vec3(-1, 0, 0), VmAbi.vector(memory, 528));
          assertEquals(new Vec3(90, 180, 0), VmAbi.vector(memory, 540));
          assertEquals(127, memory.readUnsignedByte(499));
          assertEquals(127, memory.readUnsignedByte(552));
          call(bot, memory, getInput, 1, bits(.1f), 700);
          assertEquals(20, memory.readFloat(716));
          assertEquals(new Vec3(90, 180, 0), VmAbi.vector(memory, 720));
          assertEquals(0, memory.readInt(732));
          assertEquals(selectedWeapon, memory.readInt(736));

          VmAbi.vector(memory, 152, travelType == 12 ? new Vec3(90, 180, 0) : zero);
          call(bot, memory, 557, handle, 100);
          call(bot, memory, 549, 500, handle, 300, -1);
          call(bot, memory, getInput, 1, bits(.1f), 700);
          assertEquals(400, memory.readFloat(716));
          assertEquals(ActionFlags.ATTACK | ActionFlags.JUMP, memory.readInt(732));

          call(bot, memory, abi == GameAbi.RETAIL_1999 ? 426 : 423, 1);
          call(bot, memory, 549, 500, handle, 300, -1);
          call(bot, memory, getInput, 1, bits(.1f), 700);
          assertEquals(ActionFlags.ATTACK | ActionFlags.JUMPED_LAST_FRAME, memory.readInt(732));

          VmAbi.vector(memory, 400, new Vec3(11, 22, 33));
          call(bot, memory, abi == GameAbi.RETAIL_1999 ? 423 : 420, 1, 400);
          call(bot, memory, abi == GameAbi.RETAIL_1999 ? 408 : 416, 1, 2);
          VmAbi.vector(memory, 100, new Vec3(5, 0, 10));
          memory.writeInt(164, 0);
          call(bot, memory, 557, handle, 100);
          memory.fill(500, 52, 127);
          call(bot, memory, 549, 500, handle, 300, 0);
          assertEquals(travelType, memory.readInt(516));
          assertEquals(0, memory.readInt(520));
          assertEquals(0, memory.readInt(524));
          assertEquals(zero, VmAbi.vector(memory, 540));
          call(bot, memory, getInput, 1, bits(.1f), 700);
          assertEquals(new Vec3(-1, 0, 0), VmAbi.vector(memory, 704));
          assertTrue(memory.readFloat(716) > 0, "Cached jump history enables airborne steering");
          assertEquals(new Vec3(11, 22, 33), VmAbi.vector(memory, 720));
          assertEquals(2, memory.readInt(736));
          assertEquals(ActionFlags.ATTACK | ActionFlags.JUMPED_LAST_FRAME, memory.readInt(732));
        }
      }
    }
  }

  @Test
  void movementViewWithoutRoutePreservesGuestOutputAndValidatesWholeVector() throws Exception {
    for (var abi : GameAbi.values()) {
      var memory = memory();
      try (var bot = bot(Map.of(), new Host(), abi)) {
        call(bot, memory, 200);
        int handle = call(bot, memory, 555);
        memory.fill(100, 68, 0);
        memory.writeFloat(144, .1f);
        memory.writeInt(148, 2);
        call(bot, memory, 557, handle, 100);
        memory.fill(300, 56, 0);
        memory.fill(499, 14, 127);
        assertEquals(0, call(bot, memory, 554, handle, 300, -1, bits(100), 500));
        assertEquals(0x7f7f7f7f, memory.readInt(500));
        assertEquals(0x7f7f7f7f, memory.readInt(508));
        assertEquals(127, memory.readUnsignedByte(499));
        assertEquals(127, memory.readUnsignedByte(512));
        assertThrows(
            IllegalArgumentException.class,
            () -> call(bot, memory, 554, handle, 300, -1, bits(100), memory.size() - 11));
        assertThrows(
            IllegalArgumentException.class,
            () -> call(bot, memory, 554, handle, memory.size() - 55, -1, bits(100), 500));
        assertThrows(
            IllegalArgumentException.class,
            () -> call(bot, memory, 554, handle, 300, -1, bits(Float.NaN), 500));
        assertEquals(0, call(bot, memory, 554, handle, 0, -1, bits(100), 500));
        assertEquals(0x7f7f7f7f, memory.readInt(500));
      }
    }
  }

  @Test
  void replyImportsDistinguishNullCaptureRetentionFromEmptyOverride() throws Exception {
    var files =
        Map.of(
            "botfiles/rnd.c",
            bytes(""),
            "botfiles/syn.c",
            bytes(""),
            "botfiles/match.c",
            bytes(""),
            "botfiles/rchat.c",
            bytes("[(0,\" hi \",1)]=1{0,\"|\",1;}"));
    for (var abi : GameAbi.values()) {
      var memory = memory();
      try (var bot = bot(files, new Host(), abi)) {
        call(bot, memory, 200);
        int handle = call(bot, memory, 507);
        text(memory, 100, "left hi right");
        text(memory, 200, "");
        assertEquals(1, call(bot, memory, 514, handle, 100, 0, 0, 0, 200, 0, 0, 0, 0, 0, 0));
        call(bot, memory, 570, handle, 300, 50);
        assertEquals("left|", memory.readCString(300, 50));
        text(memory, 200, "replacement");
        assertEquals(1, call(bot, memory, 514, handle, 100, 0, 0, 200, 0, 0, 0, 0, 0, 0, 0));
        call(bot, memory, 570, handle, 300, 50);
        assertEquals("replacement|right", memory.readCString(300, 50));
        text(memory, 100, "unmatched");
        assertEquals(0, call(bot, memory, 514, handle, 100, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
      }
    }
  }

  @Test
  void chatMatchProfilesPreservePaddingPartialWritesAndCaptureBounds() throws Exception {
    var files =
        Map.of(
            "botfiles/rnd.c",
            bytes(""),
            "botfiles/syn.c",
            bytes(""),
            "botfiles/rchat.c",
            bytes(""),
            "botfiles/match.c",
            bytes("1 { \"name: \", 0 = (3,4); }"));
    for (var abi : GameAbi.values()) {
      var memory = memory();
      try (var bot = bot(files, new Host(), abi)) {
        call(bot, memory, 200);
        int size = abi == GameAbi.RETAIL_1999 ? 224 : 328;
        int type = abi == GameAbi.RETAIL_1999 ? 152 : 256;
        int textBytes = abi == GameAbi.RETAIL_1999 ? 150 : 256;
        text(memory, 1000, "name: " + "a".repeat(250));
        memory.fill(2999, size + 2, 0xab);
        assertEquals(1, call(bot, memory, 518, 1000, 3000, 1));
        assertEquals(3, memory.readInt(3000 + type));
        assertEquals(4, memory.readInt(3004 + type));
        assertEquals(6, memory.readUnsignedByte(3008 + type));
        assertEquals(0xab, memory.readUnsignedByte(3009 + type));
        assertEquals(textBytes - 7, memory.readInt(3012 + type));
        assertEquals(0xab, memory.readUnsignedByte(2999));
        assertEquals(0xab, memory.readUnsignedByte(3000 + size));
        if (abi == GameAbi.RETAIL_1999) {
          assertEquals(0xab, memory.readUnsignedByte(3150));
          assertEquals(0xab, memory.readUnsignedByte(3151));
        }
        call(bot, memory, 519, 3000, 0, 4000, 8);
        assertEquals("aaaaaaa", memory.readCString(4000, 8));
        text(memory, 1000, "does not match");
        assertEquals(0, call(bot, memory, 518, 1000, 3000, 1));
        assertEquals(3, memory.readInt(3000 + type));
        assertEquals(255, memory.readUnsignedByte(3008 + type));
        assertThrows(
            IllegalArgumentException.class,
            () -> call(bot, memory, 518, 1000, memory.size() - size + 1, 1));
      }
    }
  }

  @Test
  void chatMessagesUseEachProfilesQueueSizeAndSender() throws Exception {
    var files =
        Map.of(
            "botfiles/rnd.c",
            bytes(""),
            "botfiles/syn.c",
            bytes(""),
            "botfiles/match.c",
            bytes(""),
            "botfiles/rchat.c",
            bytes(""),
            "botfiles/fixture_t.c",
            bytes("chat \"fixture\" { type \"hello\" { \"Hi \", 0, \"; okay\"; } }"));
    for (var abi : GameAbi.values()) {
      var memory = memory();
      var host = new Host();
      int queueSize = abi == GameAbi.RETAIL_1999 ? 172 : 276;
      int textSize = abi == GameAbi.RETAIL_1999 ? 150 : 256;
      try (var bot = bot(files, host, abi)) {
        bot.seed(123);
        call(bot, memory, 200);
        call(bot, memory, 205, bits(2));
        int handle = call(bot, memory, 507);
        text(memory, 100, "fixture_t.c");
        text(memory, 200, "fixture");
        assertEquals(0, call(bot, memory, 522, handle, 100, 200));
        text(memory, 300, "hello");
        text(memory, 400, "friend");
        if (abi == GameAbi.Q3_132) call(bot, memory, 524, handle, 200, 1);
        call(bot, memory, 513, handle, 300, 0, 400, 0, 0, 0, 0, 0, 0, 0);
        assertEquals(15, call(bot, memory, 515, handle));
        call(bot, memory, 516, handle, abi == GameAbi.RETAIL_1999 ? 1 : 55, 0);
        assertEquals(List.of("1:say Hi friend; okay"), host.commands);
        assertEquals(0, call(bot, memory, 515, handle));
        text(memory, 500, "message");
        call(bot, memory, 509, handle, 2, 500);
        assertThrows(
            IllegalArgumentException.class,
            () -> call(bot, memory, 511, handle, memory.size() - queueSize + 1));
        memory.fill(999, queueSize + 2, 127);
        int id = call(bot, memory, 511, handle, 1000);
        assertTrue(id > 0);
        assertEquals(id, memory.readInt(1000));
        assertEquals(2f, memory.readFloat(1004));
        assertEquals(2, memory.readInt(1008));
        assertEquals("message", memory.readCString(1012, textSize));
        assertEquals(0, memory.readInt(1000 + queueSize - 8));
        assertEquals(0, memory.readInt(1000 + queueSize - 4));
        assertEquals(127, memory.readUnsignedByte(999));
        assertEquals(127, memory.readUnsignedByte(1000 + queueSize));
        assertEquals(id, call(bot, memory, 511, handle, 1000));
        assertEquals(1, call(bot, memory, 512, handle));
        call(bot, memory, 510, handle, id);
        assertEquals(0, call(bot, memory, 512, handle));
        call(bot, memory, 513, handle, 300, 0, 400, 0, 0, 0, 0, 0, 0, 0);
        assertThrows(
            IllegalArgumentException.class,
            () -> call(bot, memory, 570, handle, memory.size() - 1, 3));
        call(bot, memory, 570, handle, 500, 4);
        assertEquals("Hi ", memory.readCString(500, 4));
        assertEquals(0, call(bot, memory, 515, handle));
        call(bot, memory, 508, handle);
      }
    }
  }

  /** Authored two half-spaces in one cluster with reciprocal walk links; no original asset data. */
  private static byte[] navigationFixture() {
    return navigationFixture(2);
  }

  private static byte[] navigationFixture(int travelType) {
    byte[][] lumps = new byte[14][];
    java.util.Arrays.setAll(lumps, ignored -> new byte[0]);
    lumps[0] =
        buffer(32)
            .putInt(2)
            .putInt(0)
            .putFloat(-15)
            .putFloat(-15)
            .putFloat(-24)
            .putFloat(15)
            .putFloat(15)
            .putFloat(32)
            .array();
    lumps[1] = new byte[12];
    lumps[2] = buffer(20).putFloat(1).putFloat(0).putFloat(0).putFloat(0).putInt(0).array();
    lumps[3] = new byte[8];
    lumps[5] = new byte[24];
    var areas = buffer(144);
    for (int area = 0; area < 3; area++) {
      float min = area == 2 ? -10 : 0, max = area == 1 ? 10 : 0;
      areas
          .putInt(area)
          .putInt(0)
          .putInt(0)
          .putFloat(min)
          .putFloat(-10)
          .putFloat(-10)
          .putFloat(max)
          .putFloat(10)
          .putFloat(10)
          .putFloat((min + max) / 2)
          .putFloat(0)
          .putFloat(0);
    }
    lumps[7] = areas.array();
    var settings = buffer(84);
    for (int area = 0; area < 3; area++)
      settings
          .putInt(0)
          .putInt(1)
          .putInt(2)
          .putInt(area == 0 ? 0 : 1)
          .putInt(area == 2 ? 1 : 0)
          .putInt(area == 0 ? 0 : 1)
          .putInt(area);
    lumps[8] = settings.array();
    var reaches = buffer(132);
    reaches.position(44);
    reaches
        .putInt(2)
        .putInt(0)
        .putInt(0)
        .putFloat(1)
        .putFloat(0)
        .putFloat(0)
        .putFloat(-1)
        .putFloat(0)
        .putFloat(0)
        .putInt(travelType)
        .putShort((short) 35)
        .putShort((short) 0);
    reaches
        .putInt(1)
        .putInt(0)
        .putInt(0)
        .putFloat(-1)
        .putFloat(0)
        .putFloat(0)
        .putFloat(1)
        .putFloat(0)
        .putFloat(0)
        .putInt(travelType)
        .putShort((short) 35)
        .putShort((short) 0);
    lumps[9] = reaches.array();
    var nodes = buffer(24);
    nodes.position(12);
    nodes.putInt(0).putInt(-1).putInt(-2);
    lumps[10] = nodes.array();
    var clusters = buffer(32);
    clusters.position(16);
    clusters.putInt(2).putInt(2).putInt(0).putInt(0);
    lumps[13] = clusters.array();
    int size = 124;
    for (byte[] lump : lumps) size += lump.length;
    var file = buffer(size);
    file.putInt(0x53414145).putInt(4).putInt(0);
    int offset = 124;
    for (byte[] lump : lumps) {
      file.putInt(offset).putInt(lump.length);
      offset += lump.length;
    }
    for (byte[] lump : lumps) file.put(lump);
    return file.array();
  }

  private static ByteBuffer buffer(int size) {
    return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
  }

  private static final class Host implements BotlibHost.Host {
    final List<String> commands = new ArrayList<>();
    final List<Integer> entityTraces = new ArrayList<>();
    UserCommand input;
    int client;
    TraceRequest lastTrace;
    TraceResult.Hit traceHit;
    double traceFraction = 1;

    public int maxClients() {
      return 2;
    }

    public void clientCommand(int number, String command) {
      commands.add(number + ":" + command);
    }

    public void userCommand(int number, UserCommand command) {
      client = number;
      input = command;
    }

    public int snapshotEntity(int number, int index) {
      return -1;
    }

    public String consoleMessage(int number) {
      return null;
    }

    public int pointContents(Vec3 point) {
      return point.z() < 0 ? 32 : 0;
    }

    public TraceResult entityTrace(int entity, TraceRequest request) {
      entityTraces.add(entity);
      return TraceResult.clear(request);
    }

    public TraceResult trace(TraceRequest request) {
      lastTrace = request;
      return new TraceResult(
          traceFraction, request.end(), false, false, Optional.ofNullable(traceHit));
    }
  }

  private static int bits(float value) {
    return Float.floatToRawIntBits(value);
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.ISO_8859_1);
  }

  private static void text(QvmMemory memory, int pointer, String text) {
    memory.writeCString(pointer, text, text.length() + 1);
  }

  private static int call(BotlibHost bot, QvmMemory memory, int call, int... args)
      throws Exception {
    return bot.invoke(memory, call, args);
  }

  private static BotlibHost bot(Map<String, byte[]> files, Host host, GameAbi abi)
      throws Exception {
    return bot(files, host, abi, null);
  }

  private static BotlibHost bot(
      Map<String, byte[]> files, Host host, GameAbi abi, String entityText) throws Exception {
    VirtualFileSystem fs =
        new VirtualFileSystem() {
          public byte[] read(VirtualPath path) throws NoSuchFileException {
            byte[] bytes = files.get(path.value());
            if (bytes == null) throw new NoSuchFileException(path.value());
            return bytes.clone();
          }

          public Optional<Origin> which(VirtualPath path) {
            return files.containsKey(path.value())
                ? Optional.of(new Origin("test", "memory", false))
                : Optional.empty();
          }

          public List<VirtualPath> list(String directory) {
            return files.keySet().stream()
                .filter(path -> path.startsWith(directory))
                .map(VirtualPath::new)
                .toList();
          }

          public List<Origin> searchOrder() {
            return List.of(new Origin("test", "memory", false));
          }

          public void close() {}
        };
    byte[] bsp = BspFixture.map(false);
    if (entityText != null) {
      byte[] entities = bytes(entityText + "\0");
      int offset = bsp.length;
      bsp = java.util.Arrays.copyOf(bsp, offset + entities.length);
      System.arraycopy(entities, 0, bsp, offset, entities.length);
      var header = ByteBuffer.wrap(bsp).order(ByteOrder.LITTLE_ENDIAN);
      header.putInt(8, offset);
      header.putInt(12, entities.length);
    }
    return new BotlibHost(fs, "fixture", BspReader.read(bsp), abi, host, ignored -> {});
  }

  private static QvmMemory memory() throws Exception {
    var file = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
    file.putInt(QvmReader.MAGIC)
        .putInt(3)
        .putInt(32)
        .putInt(15)
        .putInt(48)
        .putInt(0)
        .putInt(0)
        .putInt(65536);
    file.put((byte) Opcode.ENTER.ordinal())
        .putInt(8)
        .put((byte) Opcode.CONST.ordinal())
        .putInt(0)
        .put((byte) Opcode.LEAVE.ordinal())
        .putInt(8);
    return new QvmInterpreter(QvmReader.read("botlib-abi-fixture", file.array()), (m, c, a) -> 0)
        .memory();
  }
}
