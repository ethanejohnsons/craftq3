package dev.bluevista.craftq3.server;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.collision.TraceResult;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.vm.Opcode;
import dev.bluevista.craftq3.vm.QvmInterpreter;
import dev.bluevista.craftq3.vm.QvmMemory;
import dev.bluevista.craftq3.vm.QvmReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ServerContractsTest {
  @Test
  void zeroInitializedVmCvarCanReferToTheFirstEngineCvar() throws Exception {
    var memory = memory();
    var cvars = new CvarSystem();
    var cheats = cvars.register("sv_cheats", "0", CvarSystem.ROM);
    assertEquals(0, cheats.handle());
    VmAbi.cvar(memory, 100, cvars.byHandle(memory.readInt(100)));
    assertEquals("0", memory.readCString(116, 256));
    assertEquals(0, memory.readInt(112));
    assertThrows(IllegalArgumentException.class, () -> cvars.byHandle(-1));
    assertThrows(IllegalArgumentException.class, () -> cvars.byHandle(1));
  }

  @Test
  void writesPlayerInputInThePublished32BitLayout() throws Exception {
    var memory = memory();
    new UserCommand(1234, 0x1234, 0xabcd, -2, 2049, 7, 127, -127, -1).write(memory, 100);
    assertEquals(1234, memory.readInt(100));
    assertEquals(0xabcd, memory.readInt(108));
    assertEquals(2049, memory.readInt(116));
    assertEquals(7, memory.readUnsignedByte(120));
    assertEquals(129, memory.readUnsignedByte(122));
    assertEquals(255, memory.readUnsignedByte(123));
    assertEquals(0, memory.readUnsignedByte(124));
  }

  @Test
  void vmCvarPreservesHandleAndClampsOnlyTheVmStringField() throws Exception {
    var memory = memory();
    var cvars = new CvarSystem();
    var variable = cvars.register("name", "a".repeat(300), 0);
    VmAbi.cvar(memory, 100, variable);
    assertEquals(variable.handle(), memory.readInt(100));
    assertEquals(255, memory.readCString(116, 256).length());
    assertEquals(300, cvars.string("name").length());
    assertThrows(
        IllegalArgumentException.class, () -> VmAbi.cvar(memory, memory.size() - 100, variable));
  }

  @Test
  void traceLayoutPreservesHitMetadataAndClearsPriorData() throws Exception {
    var memory = memory();
    memory.fill(100, 56, 255);
    var zero = new Vec3(0, 0, 0);
    var hit =
        new TraceResult.Hit(
            new TraceResult.Plane(new Vec3(-1, 0, 0), 10), 1, 32, 1022, 0, 7, 2, -1, "wall");
    VmAbi.trace(
        memory, 100, new TraceResult(.25, new Vec3(1, 2, 3), false, false, Optional.of(hit)));
    assertEquals(.25f, memory.readFloat(108));
    assertEquals(-1, memory.readFloat(124));
    assertEquals(1, memory.readUnsignedByte(141));
    assertEquals(0, memory.readUnsignedShort(142));
    assertEquals(32, memory.readInt(144));
    assertEquals(1022, memory.readInt(152));
    VmAbi.trace(memory, 100, TraceResult.clear(TraceRequest.ray(zero, zero, -1)));
    assertEquals(1023, memory.readInt(152));
    assertEquals(0, memory.readInt(144));
  }

  @Test
  void libcIntrinsicsBoundCopyAndPreserveStrncpyPadding() throws Exception {
    var memory = memory();
    memory.writeCString(100, "abc", 4);
    memory.fill(200, 8, 255);
    VmIntrinsics.invoke(memory, 102, new int[] {200, 100, 8});
    assertArrayEquals(new byte[] {'a', 'b', 'c', 0, 0, 0, 0, 0}, memory.readBytes(200, 8));
    assertThrows(
        IllegalArgumentException.class,
        () -> VmIntrinsics.invoke(memory, 101, new int[] {200, memory.size() - 1, 8}));
    int value =
        VmIntrinsics.invoke(memory, 106, new int[] {Float.floatToRawIntBits(9)}).orElseThrow();
    assertEquals(3f, Float.intBitsToFloat(value));
  }

  @Test
  void configStringsAreTransactionalAtTheWireLimit() {
    var strings = new ConfigStrings();
    strings.set(10, "x".repeat(ConfigStrings.MAX_GAMESTATE_BYTES - 2));
    int generation = strings.generation();
    assertThrows(IllegalStateException.class, () -> strings.set(11, "overflow"));
    assertEquals("", strings.get(11));
    assertEquals(generation, strings.generation());
    strings.set(10, "small");
    strings.set(11, "fits");
    assertEquals(2, strings.snapshot().size());
    assertThrows(IllegalArgumentException.class, () -> strings.set(1024, "bad"));
  }

  private static QvmMemory memory() throws Exception {
    ByteBuffer file = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
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
    return new QvmInterpreter(QvmReader.read("abi-fixture", file.array()), (m, c, a) -> 0).memory();
  }
}
