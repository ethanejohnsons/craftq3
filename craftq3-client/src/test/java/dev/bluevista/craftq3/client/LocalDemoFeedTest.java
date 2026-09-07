package dev.bluevista.craftq3.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspFixture;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.client.demo.LocalDemoFeed;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.net.ConfigstringCommands;
import dev.bluevista.craftq3.core.net.MessageReader;
import dev.bluevista.craftq3.core.net.ServerMessageCodec;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.Command;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.Frame;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.Message;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Baselines;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Snapshot;
import dev.bluevista.craftq3.server.Q3Server;
import dev.bluevista.craftq3.vm.Opcode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class LocalDemoFeedTest {
  @Test
  void capturesCurrentGameThenOneFullSnapshotAndDeltasFromCompletedFrames() throws Exception {
    try (var server = server(false)) {
      server.configstrings().set(77, "initial level data");
      var feed = new LocalDemoFeed(server, 0, 0x12345678);
      var game = feed.initialGameState();
      assertEquals(server.configstrings().snapshot(), game.configstrings());
      assertEquals(0, game.commandSequence());
      assertEquals(0, game.clientNumber());
      assertEquals(0x12345678, game.checksumFeed());
      assertTrue(game.baselines().entities().isEmpty());
      assertEquals(1, feed.nextSequence());

      var first = feed.capture().orElseThrow();
      var initial = frame(decode(first, null));
      assertEquals(1, first.record().sequence());
      assertNull(initial.previous());
      assertSnapshot(server, initial.current());
      assertTrue(feed.capture().isEmpty());
      assertEquals(2, feed.nextSequence());

      server.runFrame(server.time() + 50);
      var next = feed.capture().orElseThrow();
      var delta = frame(decode(next, initial.current()));
      assertEquals(2, next.record().sequence());
      assertSame(initial.current(), delta.previous());
      assertSnapshot(server, delta.current());
      assertEquals(
          server.time(),
          ByteBuffer.wrap(delta.current().player()).order(ByteOrder.LITTLE_ENDIAN).getInt());
      assertTrue(feed.capture().isEmpty());
      assertEquals("initial level data", game.configstrings().get(77));
    }
  }

  @Test
  void serializesChangesClearsAndBigStringsBeforeRelevantTransientCommands() throws Exception {
    try (var server = server(true)) {
      server.configstrings().set(77, "clear me");
      var feed = new LocalDemoFeed(server, 0, 0);
      var initial = frame(decode(feed.capture().orElseThrow(), null));
      assertTrue(feed.capture().isEmpty());
      server.configstrings().set(77, "");
      server.configstrings().set(78, "x".repeat(1000));
      server.configstrings().set(79, "y".repeat(1998));
      server.runFrame(server.time() + 50);
      var message = decode(feed.capture().orElseThrow(), initial.current());
      var commands = commands(message);
      assertEquals(
          List.of(
              "cs 77 \"\"\n",
              "bcs0 78 \"" + "x".repeat(999) + "\"\n",
              "bcs2 78 \"x\"\n",
              "bcs0 79 \"" + "y".repeat(999) + "\"\n",
              "bcs2 79 \"" + "y".repeat(999) + "\"\n",
              "print broadcast\n",
              "print target\n"),
          commands.stream().map(Command::text).toList());
      for (int i = 0; i < commands.size(); i++) assertEquals(i + 1, commands.get(i).sequence());
      var view = new ConfigstringCommands(feed.initialGameState().configstrings());
      commands.forEach(command -> view.consume(command.text()));
      assertEquals(server.configstrings().snapshot(), view.strings());
      assertEquals("clear me", feed.initialGameState().configstrings().get(77));

      server.runFrame(server.time() + 50);
      var later = commands(feed.capture().orElseThrow().message());
      assertEquals(List.of(8, 9), later.stream().map(Command::sequence).toList());
      assertEquals(
          List.of("print broadcast\n", "print target\n"),
          later.stream().map(Command::text).toList());
    }
  }

  @Test
  void startsAfterExpiredHistoryAndNeverReplaysOldTransientCommands() throws Exception {
    try (var server = server(true)) {
      for (int i = 0; i < 90; i++) server.runFrame(server.time() + 20);
      assertThrows(IllegalStateException.class, () -> server.commandsSince(0));
      var feed = new LocalDemoFeed(server, 0, 0);
      assertTrue(commands(feed.capture().orElseThrow().message()).isEmpty());
      server.runFrame(server.time() + 20);
      var next = commands(feed.capture().orElseThrow().message());
      assertEquals(List.of(1, 2), next.stream().map(Command::sequence).toList());
      assertEquals(
          List.of("print broadcast\n", "print target\n"),
          next.stream().map(Command::text).toList());
    }
  }

  @Test
  void rejectsCommandOverflowWithoutCommittingAnyFrameState() throws Exception {
    try (var server = server(false)) {
      var feed = new LocalDemoFeed(server, 0, 0);
      var first = frame(decode(feed.capture().orElseThrow(), null));
      for (int i = 0; i < 65; i++) server.configstrings().set(200 + i, "v" + i);
      server.runFrame(server.time() + 50);
      assertThrows(IllegalStateException.class, feed::capture);
      assertEquals(2, feed.nextSequence());
      server.configstrings().set(264, "");
      var next = decode(feed.capture().orElseThrow(), first.current());
      assertEquals(64, commands(next).size());
      assertEquals(1, commands(next).getFirst().sequence());
      assertEquals(64, commands(next).getLast().sequence());
      assertEquals(2, frame(next).current().sequence());
      assertTrue(feed.capture().isEmpty());
    }
  }

  @Test
  void preservesFastRestartCommandsAndSnapshotServerCountTransition() throws Exception {
    try (var server = server(false)) {
      var feed = new LocalDemoFeed(server, 0, 0);
      var before = frame(decode(feed.capture().orElseThrow(), null));
      server.restart(42);
      var after = decode(feed.capture().orElseThrow(), before.current());
      assertEquals(List.of(new Command(1, "map_restart\n")), commands(after));
      assertEquals(4, frame(after).current().flags());
      assertSnapshot(server, frame(after).current());
    }
  }

  @Test
  void nativeObservedFragmentBoundariesRetainLiteralText() {
    for (int index : new int[] {0, 99, 1023}) {
      assertEquals(
          List.of("cs " + index + " \"\"\n"), LocalDemoFeed.configstringCommands(index, ""));
      assertEquals(
          List.of("cs " + index + " \"" + "a".repeat(999) + "\"\n"),
          LocalDemoFeed.configstringCommands(index, "a".repeat(999)));
      assertEquals(
          List.of("bcs0 " + index + " \"" + "a".repeat(999) + "\"\n", "bcs2 " + index + " \"a\"\n"),
          LocalDemoFeed.configstringCommands(index, "a".repeat(1000)));
    }
    assertEquals(
        List.of(
            "bcs0 77 \"" + "a".repeat(999) + "\"\n",
            "bcs1 77 \"" + "a".repeat(999) + "\"\n",
            "bcs2 77 \"a\"\n"),
        LocalDemoFeed.configstringCommands(77, "a".repeat(1999)));
    String literal = "quote\";back\\line\n\r\t%s %n " + (char) 200;
    assertEquals(
        List.of("cs 77 \"" + literal + "\"\n"), LocalDemoFeed.configstringCommands(77, literal));
    assertThrows(
        UnsupportedOperationException.class,
        () -> LocalDemoFeed.configstringCommands(77, "a".repeat(1000)).clear());
  }

  @Test
  void validatesClientAndConfigstringBoundsBeforeCapture() throws Exception {
    try (var server = server(false)) {
      assertThrows(IllegalStateException.class, () -> new LocalDemoFeed(server, 1, 0));
      assertThrows(IllegalArgumentException.class, () -> new LocalDemoFeed(server, -1, 0));
    }
    assertThrows(NullPointerException.class, () -> LocalDemoFeed.configstringCommands(0, null));
    assertThrows(IllegalArgumentException.class, () -> LocalDemoFeed.configstringCommands(-1, "x"));
    assertThrows(
        IllegalArgumentException.class, () -> LocalDemoFeed.configstringCommands(1024, "x"));
    assertThrows(
        IllegalArgumentException.class, () -> LocalDemoFeed.configstringCommands(0, "x\0y"));
    assertThrows(
        IllegalArgumentException.class, () -> LocalDemoFeed.configstringCommands(0, "\u0100"));
    assertThrows(
        IllegalArgumentException.class,
        () -> LocalDemoFeed.configstringCommands(0, "x".repeat(16000)));
  }

  private static Message decode(LocalDemoFeed.Emission emitted, Snapshot previous) {
    assertEquals(0, emitted.message().reliableAcknowledge());
    return ServerMessageCodec.read(
        new MessageReader(emitted.record().payload()),
        emitted.record().sequence(),
        Baselines.EMPTY,
        sequence -> previous != null && previous.sequence() == sequence ? previous : null);
  }

  private static Frame frame(Message message) {
    return (Frame) message.operations().getLast();
  }

  private static List<Command> commands(Message message) {
    return message.operations().stream()
        .filter(Command.class::isInstance)
        .map(Command.class::cast)
        .toList();
  }

  private static void assertSnapshot(Q3Server server, Snapshot snapshot) {
    assertEquals(server.time(), snapshot.time());
    assertEquals(server.snapshotFlags(), snapshot.flags());
    assertArrayEquals(server.playerState(0), snapshot.player());
    assertArrayEquals(server.entitySnapshot(0).areaMask().copy(), snapshot.areaMask());
    assertEquals(server.entityStates(0).size(), snapshot.entities().size());
    for (int i = 0; i < snapshot.entities().size(); i++)
      assertArrayEquals(server.entityStates(0).get(i), snapshot.entities().get(i));
  }

  private static Q3Server server(boolean commands) throws Exception {
    var guest =
        new ClientTestData.Program().op(Opcode.ENTER, 64).call(15, 4096, 1, 516, 16384, 468);
    // The authored guest stores its current callback argument in canonical player commandTime.
    guest.op(Opcode.CONST, 16384).op(Opcode.LOCAL, 76).op(Opcode.LOAD4).op(Opcode.STORE4);
    if (commands) {
      for (int i = 0; i < 3; i++) {
        String text = List.of("print broadcast\n", "print target\n", "print other\n").get(i);
        byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(bytes, 0, guest.data, 200 + 64 * i, bytes.length);
        guest.call(17, new int[] {-1, 0, 1}[i], 200 + 64 * i);
      }
    }
    guest.op(Opcode.CONST, 0).op(Opcode.LEAVE, 64);
    var files = new ClientTestData.Files(Map.of("vm/qagame.qvm", guest.bytes()));
    var server =
        new Q3Server(
            files,
            "fixture",
            BspReader.read(BspFixture.map(false)),
            null,
            ignored -> {},
            Clock.systemUTC());
    server.cvars().set("bot_enable", "0", CvarSystem.Source.ENGINE);
    server.initialize(1000, 42);
    server.connect(0, Map.of("name", "Demo fixture"));
    return server;
  }
}
