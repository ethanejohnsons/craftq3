package dev.bluevista.craftq3.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.core.command.CommandSystem;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.server.UserCommand;
import dev.bluevista.craftq3.vm.Opcode;
import dev.bluevista.craftq3.vm.QvmInterpreter;
import dev.bluevista.craftq3.vm.QvmMemory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class CgameSourceTest {
  @Test
  void originalGuestEntryUsesSourceBaselinesAndSparseSnapshotsWithoutAnyLocalServer()
      throws Exception {
    for (var abi : ClientAbi.values()) {
      var files = new ClientTestData.Files(Map.of("vm/cgame.qvm", snapshotGuest()));
      var source = new Source();
      source.current = new CgameSource.SnapshotNumber(42, 12000);
      source.snapshots.put(42, snapshot(12000, 0));
      var cvars = new CvarSystem();
      cvars.register("sv_running", "0", CvarSystem.ROM);
      var commands = new CommandSystem(cvars, files, ignored -> {});
      commands.register("host_only", ignored -> {});
      var audio = new ClientTestData.Audio();
      try (var client =
          new Q3Client(files, source, cvars, commands, ignored -> {}, audio, ignored -> {}, abi)) {
        client.initialize(3, 640, 480);
        var memory = memory(client);
        assertEquals(List.of(3), source.initialized);
        assertEquals(17, memory.readInt(1000));
        assertEquals(40, memory.readInt(1004));
        assertEquals(3, memory.readInt(1008));
        assertEquals(12345, memory.readInt(1012));
        assertEquals(7, client.selectedWeapon());
        assertEquals(42, memory.readInt(1016));
        assertEquals(12000, memory.readInt(1020));
        assertEquals(1, memory.readInt(1024));
        assertEquals(23, memory.readInt(20004));
        assertEquals(12000, memory.readInt(20008));
        assertSame(cvars, client.cvars());
        assertSame(commands, client.commands());
        assertEquals("0", cvars.string("sv_running"));
        client.frame(12360, 640, 480);
        client.frame(12370, 640, 480);
        assertEquals(42, memory.readInt(1016), "Rendering does not manufacture packet numbers");
        source.current = new CgameSource.SnapshotNumber(47, 12400);
        source.snapshots.put(47, snapshot(12400, 0));
        client.frame(12410, 640, 480);
        assertEquals(47, memory.readInt(1016));
        assertEquals(3, source.refreshes);
      }
      assertFalse(source.closed);
      assertFalse(audio.closed);
      assertFalse(files.closed);
      assertTrue(commands.complete("host_only").contains("host_only"));
      assertFalse(commands.complete("play").contains("play"));
    }
  }

  @Test
  void missingSnapshotLeavesGuestMemoryUntouchedAndAbsenceDoesNotSeedAPlayer() throws Exception {
    for (var abi : ClientAbi.values()) {
      var source = new Source();
      source.initial = new CgameSource.Initialization(3, 17, 40, 12345, 0);
      try (var client = client(source, snapshotGuest(), abi)) {
        client.initialize(3, 640, 480);
        assertEquals(0, client.selectedWeapon());
        var memory = memory(client);
        assertEquals(0, memory.readInt(1016));
        assertEquals(0, memory.readInt(1024));
        memory.fill(20000, abi.snapshotBytes(), 0xa7);
        byte[] before = memory.readBytes(20000, abi.snapshotBytes());
        client.frame(12360, 640, 480);
        assertEquals(0, memory.readInt(1024));
        assertArrayEquals(before, memory.readBytes(20000, abi.snapshotBytes()));
        assertTrue(
            client.cvars().find("sv_running").isEmpty(),
            "An explicit source is not a local server");
      }
    }
  }

  @Test
  void commonWriterConvertsBothAbisAndPreservesNativeUnwrittenCommandCount() throws Exception {
    byte[] player = new byte[468], entity = new byte[208];
    for (int i = 0; i < player.length; i++) player[i] = (byte) (i * 7);
    for (int i = 0; i < entity.length; i++) entity[i] = (byte) (i * 3);
    var snapshot =
        new CgameSource.Snapshot(9010, 4, 81, player, List.of(entity), new byte[] {3, 7}, 123, -1);
    for (var abi : ClientAbi.values()) {
      var memory = ClientTestData.memory();
      int pointer = 20000, count = pointer + 44 + abi.game().playerStateBytes();
      int tail = count + 4 + 256 * abi.game().entityStateBytes();
      memory.fill(pointer - 4, abi.snapshotBytes() + 8, 0x7f);
      CgameSnapshotWriter.write(memory, abi, snapshot, pointer);
      assertEquals(4, memory.readInt(pointer));
      assertEquals(81, memory.readInt(pointer + 4));
      assertEquals(9010, memory.readInt(pointer + 8));
      assertArrayEquals(Arrays.copyOf(new byte[] {3, 7}, 32), memory.readBytes(pointer + 12, 32));
      assertArrayEquals(Arrays.copyOf(player, 440), memory.readBytes(pointer + 44, 440));
      if (abi == ClientAbi.RETAIL_1999)
        assertArrayEquals(
            Arrays.copyOfRange(player, 452, 456), memory.readBytes(pointer + 44 + 440, 4));
      else assertArrayEquals(player, memory.readBytes(pointer + 44, 468));
      assertEquals(1, memory.readInt(count));
      assertArrayEquals(
          Arrays.copyOf(entity, abi.game().entityStateBytes()),
          memory.readBytes(count + 4, abi.game().entityStateBytes()));
      assertEquals(0, memory.readInt(count + 4 + abi.game().entityStateBytes()));
      assertEquals(0x7f7f7f7f, memory.readInt(tail));
      assertEquals(123, memory.readInt(tail + 4));
      assertEquals(0x7f7f7f7f, memory.readInt(pointer - 4));
      assertEquals(0x7f7f7f7f, memory.readInt(pointer + abi.snapshotBytes()));
      var counted =
          new CgameSource.Snapshot(9010, 4, 81, player, List.of(entity), new byte[0], 123, 6);
      CgameSnapshotWriter.write(memory, abi, counted, pointer);
      assertEquals(6, memory.readInt(tail));
    }
  }

  @Test
  void snapshotsOwnArraysAndRejectLayoutsBeforeTheyCanReachGuestMemory() {
    byte[] player = new byte[468], entity = new byte[208], mask = {1};
    var entities = new ArrayList<>(List.of(entity));
    var value = new CgameSource.Snapshot(0, 0, 0, player, entities, mask, 40, -1);
    player[0] = 9;
    entity[0] = 8;
    mask[0] = 7;
    entities.clear();
    assertEquals(0, value.player()[0]);
    assertEquals(0, value.entities().getFirst()[0]);
    assertEquals(1, value.areaMask()[0]);
    value.player()[0] = 5;
    value.entities().getFirst()[0] = 5;
    value.areaMask()[0] = 5;
    assertEquals(0, value.player()[0]);
    assertEquals(0, value.entities().getFirst()[0]);
    assertEquals(1, value.areaMask()[0]);
    assertThrows(
        IllegalArgumentException.class,
        () -> new CgameSource.Snapshot(0, 0, 0, new byte[444], List.of(), new byte[0], 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CgameSource.Snapshot(
                0, 0, 0, new byte[468], List.of(new byte[204]), new byte[0], 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CgameSource.Snapshot(0, 0, 0, new byte[468], List.of(), new byte[33], 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CgameSource.Snapshot(
                0,
                0,
                0,
                new byte[468],
                java.util.Collections.nCopies(257, new byte[208]),
                new byte[0],
                0,
                0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CgameSource.Snapshot(0, 0, 0, new byte[468], List.of(), new byte[0], 0, -2));
    assertThrows(
        IllegalArgumentException.class, () -> new CgameSource.Initialization(64, 0, 0, 0, 0));
  }

  @Test
  void rendererRestartUsesCoherentSourceBaselinesAndKeepsInputClockAndWeapon() throws Exception {
    for (var abi : ClientAbi.values()) {
      var source = new Source();
      source.current = new CgameSource.SnapshotNumber(42, 12000);
      source.snapshots.put(42, snapshot(12000, 0));
      source.restart = new CgameSource.Initialization(3, 59, 80, 999, 1);
      try (var client = client(source, snapshotGuest(), abi)) {
        client.initialize(3, 640, 480);
        var input = new UserCommand(12370, 1, 2, 3, 4, 7, -128, 127, 0);
        client.userCommand(input);
        client.frame(12400, 800, 600);
        var memory = memory(client);
        assertEquals(59, memory.readInt(1000));
        assertEquals(80, memory.readInt(1004));
        assertEquals(3, memory.readInt(1008));
        assertEquals(12400, memory.readInt(1012), "Resize retains the selected presentation clock");
        assertEquals(7, client.selectedWeapon());
        assertEquals(List.of(input), source.inputs);
        assertEquals(1, source.restarts);
        assertEquals("restart view", source.configStrings().get(7));
      }
    }
  }

  @Test
  void reliableConsumptionControlsGamestateAndNeverExecutesReceivedEngineText() throws Exception {
    for (var abi : ClientAbi.values()) {
      var source = new Source();
      source.strings = Map.of(7, "before consumption");
      source.reliable.put(41, "cs 7 \"after consumption\"");
      source.reliable.put(42, "print \"set forbidden 1; echo payload\"");
      source.consume =
          sequence -> {
            if (sequence == 41) source.strings = Map.of(7, "after consumption");
          };
      var program = new ClientTestData.Program().op(Opcode.ENTER, 64);
      dynamicCall(program, 1024, 53, 200);
      program.call(8, 1, 300, 128).call(50, 60000).op(Opcode.CONST, 0).op(Opcode.LEAVE, 64);
      try (var client = client(source, program.bytes(), abi)) {
        client.initialize(3, 640, 480);
        var memory = memory(client);
        assertEquals("before consumption", configString(memory, 60000, 7));
        memory.writeInt(200, 41);
        client.frame(12400, 640, 480);
        assertEquals(1, memory.readInt(1024));
        assertEquals("7", memory.readCString(300, 128));
        assertEquals("after consumption", configString(memory, 60000, 7));
        memory.writeInt(200, 42);
        client.frame(12416, 640, 480);
        assertEquals("set forbidden 1; echo payload", memory.readCString(300, 128));
        assertTrue(client.cvars().find("forbidden").isEmpty());
        assertEquals(0, client.commands().pending());
        assertTrue(source.sent.isEmpty());
        client.frame(12432, 640, 480);
        assertEquals(
            List.of(0, 41, 42, 42), source.consumed, "Repeated guest requests remain observable");
      }
    }
  }

  @Test
  void mapRestartZerosRetainedUserCommandsWithoutChangingTheirNumbersOrAgeChecks()
      throws Exception {
    for (var abi : ClientAbi.values()) {
      var source = new Source();
      source.reliable.put(41, "map_restart\n");
      var program = new ClientTestData.Program().op(Opcode.ENTER, 64);
      dynamicCall(program, 1024, 53, 200);
      dynamicCall(program, 1032, 55, 204, 20000);
      program
          .op(Opcode.CONST, 1028)
          .op(Opcode.CONST, -55)
          .op(Opcode.CALL)
          .op(Opcode.STORE4)
          .op(Opcode.CONST, 0)
          .op(Opcode.LEAVE, 64);
      try (var client = client(source, program.bytes(), abi)) {
        client.initialize(3, 640, 480);
        for (int number = 1; number <= 123; number++)
          client.userCommand(new UserCommand(12345 + number, 1, 2, 3, 4, 5, 127, -128, 1));
        var memory = memory(client);
        memory.writeInt(204, 123);
        client.frame(12500, 640, 480);
        assertEquals(1, memory.readInt(1032));
        assertEquals(12468, memory.readInt(20000));
        memory.writeInt(200, 41);
        client.frame(12516, 640, 480);
        assertEquals(123, memory.readInt(1028));
        assertEquals(1, memory.readInt(1024));
        assertEquals(1, memory.readInt(1032));
        assertArrayEquals(new byte[24], memory.readBytes(20000, 24));
        memory.writeInt(200, 0);
        for (int number : List.of(60, 100, 123)) {
          memory.fill(20000, 24, 0x7f);
          memory.writeInt(204, number);
          client.frame(12532, 640, 480);
          assertEquals(1, memory.readInt(1032));
          assertArrayEquals(new byte[24], memory.readBytes(20000, 24));
        }
        memory.fill(20000, 24, 0x7f);
        memory.writeInt(204, 59);
        client.frame(12548, 640, 480);
        assertEquals(0, memory.readInt(1032));
        byte[] retained = new byte[24];
        Arrays.fill(retained, (byte) 0x7f);
        assertArrayEquals(retained, memory.readBytes(20000, 24));
        client.userCommand(new UserCommand(12600, 0, 0, 0, 0, 7, 10, 0, 0));
        memory.writeInt(204, 124);
        client.frame(12600, 640, 480);
        assertEquals(124, memory.readInt(1028));
        assertEquals(1, memory.readInt(1032));
        assertEquals(12600, memory.readInt(20000));
        memory.writeInt(204, 125);
        var failure =
            assertThrows(
                dev.bluevista.craftq3.vm.QvmException.class, () -> client.frame(12616, 640, 480));
        assertTrue(failure.getMessage().contains("Future cgame user command"));
      }
    }
  }

  @Test
  void sourceOwnsOutgoingInputAndForeignConsoleFallbackWithoutLocalGameAccess() throws Exception {
    for (var abi : ClientAbi.values()) {
      var source = new Source();
      var program =
          new ClientTestData.Program()
              .op(Opcode.ENTER, 64)
              .call(8, 1, 300, 128)
              .op(Opcode.CONST, 0)
              .op(Opcode.LEAVE, 64);
      try (var client = client(source, program.bytes(), abi)) {
        client.initialize(3, 640, 480);
        var command = new UserCommand(12360, 1, 2, 3, 4, 5, 127, -128, 1);
        client.userCommand(command);
        assertEquals(List.of(command), source.inputs);
        var reliable = CommandParser.tokenize("reliable retained");
        var field = Q3Client.class.getDeclaredField("serverCommand");
        field.setAccessible(true);
        field.set(client, reliable);
        var foreign =
            new CommandSystem(client.cvars(), new ClientTestData.Files(Map.of()), ignored -> {});
        foreign.unknownHandler(client::consoleCommand);
        foreign.submit("say \"whole; argument\"", CommandSystem.Execution.NOW);
        assertEquals("whole; argument", memory(client).readCString(300, 128));
        assertEquals(List.of("say \"whole; argument\""), source.sent);
        assertSame(reliable, field.get(client));
        source.handleConsole = true;
        client.consoleCommand(CommandParser.tokenize("local handled"));
        assertEquals(1, source.sent.size());
        assertEquals(List.of("say", "local"), source.console);
        client.frame(12376, 640, 480);
        assertEquals("", memory(client).readCString(300, 128));
      }
    }
  }

  @Test
  void registeredGuestCommandOffersLocalConsoleBeforeForwardingAndRestoresArgumentScope()
      throws Exception {
    for (var abi : ClientAbi.values()) {
      var source = new Source();
      var program =
          new ClientTestData.Program()
              .op(Opcode.ENTER, 64)
              .call(15, 200)
              .call(8, 1, 300, 128)
              .op(Opcode.CONST, 400)
              .op(Opcode.LOAD4)
              .op(Opcode.LEAVE, 64);
      System.arraycopy(
          "addbot".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, program.data, 200, 6);
      try (var client = client(source, program.bytes(), abi)) {
        client.initialize(3, 640, 480);
        var retained = CommandParser.tokenize("reliable retained");
        var field = Q3Client.class.getDeclaredField("serverCommand");
        field.setAccessible(true);
        field.set(client, retained);
        source.handleConsole = true;
        client.commands().submit("addbot \"quoted name\" 3 red", CommandSystem.Execution.NOW);
        assertEquals(List.of("addbot"), source.console);
        assertTrue(source.sent.isEmpty(), "Local qagame handles its own server command");
        assertEquals("quoted name", memory(client).readCString(300, 128));
        assertSame(retained, field.get(client));
        source.handleConsole = false;
        client.commands().submit("addbot remote", CommandSystem.Execution.NOW);
        assertEquals(List.of("addbot remote"), source.sent);
        assertEquals(List.of("addbot", "addbot"), source.console);
        memory(client).writeInt(400, 1);
        client.commands().submit("addbot cgame", CommandSystem.Execution.NOW);
        assertEquals(2, source.console.size(), "A handled cgame command stops dispatch");
        assertEquals(1, source.sent.size());
        assertSame(retained, field.get(client));
      }
    }
  }

  private static Q3Client client(Source source, byte[] program, ClientAbi abi) throws Exception {
    var fs = new ClientTestData.Files(Map.of("vm/cgame.qvm", program));
    var cvars = new CvarSystem();
    return new Q3Client(
        fs,
        source,
        cvars,
        new CommandSystem(cvars, fs, ignored -> {}),
        ignored -> {},
        new ClientTestData.Audio(),
        ignored -> {},
        abi);
  }

  private static byte[] snapshotGuest() {
    var program =
        new ClientTestData.Program()
            .op(Opcode.ENTER, 64)
            .op(Opcode.LOCAL, 72)
            .op(Opcode.LOAD4)
            .op(Opcode.CONST, 0);
    int skip = program.size();
    program.op(Opcode.NE, 0);
    for (int i = 0; i < 3; i++)
      program
          .op(Opcode.CONST, 1000 + i * 4)
          .op(Opcode.LOCAL, 76 + i * 4)
          .op(Opcode.LOAD4)
          .op(Opcode.STORE4);
    program.op(Opcode.CONST, 1012).op(Opcode.CONST, -3).op(Opcode.CALL).op(Opcode.STORE4);
    program.patch(skip, program.size());
    program.call(51, 1016, 1020);
    dynamicCall(program, 1024, 52, 200, 20000);
    program.op(Opcode.CONST, 0).op(Opcode.LEAVE, 64);
    ByteBuffer.wrap(program.data).order(ByteOrder.LITTLE_ENDIAN).putInt(200, 42);
    return program.bytes();
  }

  private static void dynamicCall(
      ClientTestData.Program program, int result, int syscall, int argumentPointer, int... rest) {
    program
        .op(Opcode.CONST, result)
        .op(Opcode.CONST, argumentPointer)
        .op(Opcode.LOAD4)
        .op(Opcode.ARG, 8);
    for (int i = 0; i < rest.length; i++)
      program.op(Opcode.CONST, rest[i]).op(Opcode.ARG, 12 + i * 4);
    program.op(Opcode.CONST, -1 - syscall).op(Opcode.CALL).op(Opcode.STORE4);
  }

  private static QvmMemory memory(Q3Client client) throws Exception {
    var field = Q3Client.class.getDeclaredField("vm");
    field.setAccessible(true);
    return ((QvmInterpreter) field.get(client)).memory();
  }

  private static String configString(QvmMemory memory, int pointer, int index) {
    return memory.readCString(pointer + 4096 + memory.readInt(pointer + index * 4), 16000);
  }

  private static CgameSource.Snapshot snapshot(int time, int count) {
    return new CgameSource.Snapshot(
        time, 4, 23, new byte[468], List.of(new byte[208]), new byte[32], 40, count);
  }

  private static final class Source implements CgameSource, AutoCloseable {
    Initialization initial = new Initialization(3, 17, 40, 12345, 7), restart = initial;
    SnapshotNumber current = new SnapshotNumber(0, 0);
    final Map<Integer, Snapshot> snapshots = new LinkedHashMap<>();
    final Map<Integer, String> reliable = new LinkedHashMap<>();
    Map<Integer, String> strings = Map.of();
    final List<Integer> initialized = new ArrayList<>(), consumed = new ArrayList<>();
    final List<UserCommand> inputs = new ArrayList<>();
    final List<String> sent = new ArrayList<>(), console = new ArrayList<>();
    java.util.function.IntConsumer consume = ignored -> {};
    int refreshes, restarts;
    boolean closed, handleConsole;

    public Initialization initialize(int client) {
      initialized.add(client);
      return initial;
    }

    public void refresh() {
      refreshes++;
    }

    public SnapshotNumber currentSnapshot() {
      return current;
    }

    public Optional<Snapshot> snapshot(int number) {
      return Optional.ofNullable(snapshots.get(number));
    }

    public Map<Integer, String> configStrings() {
      return strings;
    }

    public Optional<String> serverCommand(int number) {
      consumed.add(number);
      consume.accept(number);
      return Optional.ofNullable(reliable.get(number));
    }

    public void userCommand(UserCommand command) {
      inputs.add(command);
    }

    public void clientCommand(String text) {
      sent.add(text);
    }

    public boolean consoleCommand(CommandParser.Command command) {
      console.add(command.argument(0));
      return handleConsole;
    }

    public Initialization restart() {
      restarts++;
      strings = Map.of(7, "restart view");
      return restart;
    }

    public void close() {
      closed = true;
    }
  }
}
