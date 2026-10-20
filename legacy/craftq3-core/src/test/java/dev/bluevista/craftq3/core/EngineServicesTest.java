package dev.bluevista.craftq3.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.core.command.CommandSystem;
import dev.bluevista.craftq3.core.command.KeyBindings;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.cvar.InfoString;
import dev.bluevista.craftq3.core.fs.GameFileHandles;
import dev.bluevista.craftq3.core.fs.GameFileStore;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineServicesTest {
  @Test
  void adjacentQuotesStartNewTokensIncludingAnUnmatchedEmptyTail() {
    var command = CommandParser.tokenize("cs 100 \"ab\"c\"");
    assertEquals(List.of("cs", "100", "ab", "c", ""), command.arguments());
    assertEquals("ab c ", command.argumentsFrom(2));
    assertEquals(
        List.of("cs", "100", "ab", "c", "d"),
        CommandParser.tokenize("cs 100 \"ab\"c\"d\"").arguments());
  }

  @Test
  void reliableCommandTokenizationPreservesQuotedNewlinesWithoutExecutingSeparators() {
    var command =
        dev.bluevista.craftq3.core.command.CommandParser.tokenize(
            "print \"hello; echo unsafe\nworld\"\n");
    assertEquals(java.util.List.of("print", "hello; echo unsafe\nworld"), command.arguments());
    assertEquals(
        java.util.List.of("cs", "5"),
        dev.bluevista.craftq3.core.command.CommandParser.tokenize("cs /* note */ 5 // tail")
            .arguments());
    assertEquals(
        java.util.List.of("foo;bar", "baz"),
        dev.bluevista.craftq3.core.command.CommandParser.tokenize("foo;bar baz").arguments());
    assertEquals(
        2, dev.bluevista.craftq3.core.command.CommandParser.parse("echo a; echo b").size());
  }

  @Test
  void engineTransitionsYieldBufferedAndImmediateRemaindersUntilTheNextFrame() {
    for (var execution : CommandSystem.Execution.values()) {
      var output = new ArrayList<String>();
      var commands = new CommandSystem(new CvarSystem(), memoryFs(Map.of()), output::add);
      commands.register(
          "change_map",
          command -> {
            output.add("map requested");
            commands.frameBoundary();
          });
      commands.submit("echo queued", CommandSystem.Execution.APPEND);
      commands.submit("change_map; echo after", execution);
      if (execution != CommandSystem.Execution.NOW) commands.runFrame(100);
      assertFalse(output.contains("after"));
      output.add("new map ready");
      commands.runFrame(100);
      assertTrue(output.indexOf("after") > output.indexOf("new map ready"));
      assertEquals(1, output.stream().filter("after"::equals).count());
      assertTrue(output.contains("queued"));
    }
  }

  @TempDir Path temporary;

  @Test
  void cvarHandlesAndLatchedRestartPreserveUserSettings() {
    var cvars = new CvarSystem();
    cvars.set("g_gametype", "4", CvarSystem.Source.CONSOLE);
    var initial = cvars.register("g_gametype", "0", CvarSystem.LATCH | CvarSystem.SERVERINFO);
    assertEquals("4", initial.value());
    assertEquals("0", initial.resetValue());
    assertEquals(0, initial.flags() & CvarSystem.USER_CREATED);
    assertEquals(
        CvarSystem.Change.LATCHED, cvars.set("G_GAMETYPE", "1", CvarSystem.Source.CONSOLE));
    assertEquals("4", cvars.string("g_gametype"));
    var registered = cvars.register("g_gametype", "0", CvarSystem.LATCH);
    assertEquals(initial.handle(), registered.handle());
    assertEquals("1", cvars.byHandle(initial.handle()).value());
    assertTrue(registered.modificationCount() > initial.modificationCount());
    assertEquals("\\g_gametype\\1", cvars.infoString(CvarSystem.SERVERINFO, 1024));
  }

  @Test
  void cvarProtectionAndNumbersMatchDistinctIntegerFloatFields() {
    var cvars = new CvarSystem();
    cvars.register("engine", "1", CvarSystem.PROTECTED | CvarSystem.ROM);
    assertEquals(CvarSystem.Change.READ_ONLY, cvars.set("engine", "2", CvarSystem.Source.CONSOLE));
    assertEquals(CvarSystem.Change.PROTECTED, cvars.set("engine", "2", CvarSystem.Source.VM));
    assertEquals(CvarSystem.Change.CHANGED, cvars.set("engine", "2", CvarSystem.Source.ENGINE));
    cvars.register("debug", "0", CvarSystem.CHEAT);
    assertEquals(
        CvarSystem.Change.CHEAT_PROTECTED, cvars.set("debug", "1", CvarSystem.Source.CONSOLE));
    cvars.cheatsEnabled(true);
    cvars.set("debug", "1", CvarSystem.Source.CONSOLE);
    cvars.cheatsEnabled(false);
    assertEquals(0, cvars.integer("debug"));
    cvars.register("numeric", " -12.5e1 words", 0);
    assertEquals(-12, cvars.integer("numeric"));
    assertEquals(-125, cvars.number("numeric"));
    assertThrows(IllegalArgumentException.class, () -> cvars.register("bad;name", "0", 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> cvars.register("name", "bad\\info", CvarSystem.USERINFO));
  }

  @Test
  void infoStringsRejectAmbiguousOrOversizedWireData() {
    assertEquals(
        Map.of("name", "Player One", "rate", "25000"),
        InfoString.parse("\\name\\Player One\\rate\\25000", 1024));
    assertThrows(IllegalArgumentException.class, () -> InfoString.parse("\\name", 1024));
    assertThrows(
        IllegalArgumentException.class, () -> InfoString.encode(Map.of("name", "x;y"), 1024));
    assertThrows(IllegalArgumentException.class, () -> InfoString.encode(Map.of("a", "b"), 4));
  }

  @Test
  void commandsRespectQuotesCommentsInsertionAndWait() {
    var parsed =
        CommandParser.parse(
            "seta name \"one;two // three\"; /* ignored; */ echo yes // no\necho end");
    assertEquals(3, parsed.size());
    assertEquals("one;two // three", parsed.getFirst().argument(2));
    var output = new ArrayList<String>();
    var cvars = new CvarSystem();
    var commands =
        new CommandSystem(
            cvars,
            memoryFs(Map.of("autoexec.cfg", "echo config; wait 2; echo resumed")),
            output::add);
    commands.submit("echo before; exec autoexec; echo after", CommandSystem.Execution.APPEND);
    commands.runFrame(100);
    assertEquals(List.of("before", "config"), output);
    commands.runFrame(100);
    assertEquals(2, output.size());
    commands.runFrame(100);
    assertEquals(List.of("before", "config", "resumed", "after"), output);
    commands.submit("seta name \"Player One\"", CommandSystem.Execution.NOW);
    var restored = new CvarSystem();
    new CommandSystem(restored, memoryFs(Map.of()), output::add)
        .submit(commands.archivedConfig(), CommandSystem.Execution.NOW);
    assertEquals("Player One", restored.string("name"));
  }

  @Test
  void recursiveConfigCannotConsumeAnUnboundedFrame() {
    var commands =
        new CommandSystem(
            new CvarSystem(), memoryFs(Map.of("loop.cfg", "exec loop")), ignored -> {});
    commands.submit("exec loop", CommandSystem.Execution.APPEND);
    assertEquals(20, commands.runFrame(20));
    assertEquals(1, commands.pending());
    assertThrows(
        IllegalArgumentException.class, () -> CommandParser.parse("echo " + "x".repeat(8193)));
    assertThrows(IllegalArgumentException.class, () -> CommandParser.parse("echo x\0"));
  }

  @Test
  void keyReleaseUsesBindingAtPressAndClearsOnFocusLoss() {
    var keys = new KeyBindings();
    keys.bind(178, "+attack; echo firing");
    assertEquals(List.of("+attack 178 100", "echo firing"), keys.press(178, 100));
    assertEquals(List.of(), keys.press(178, 110));
    keys.bind(178, "+zoom");
    assertEquals(List.of("-attack 178 120"), keys.releaseAll(120));
    assertEquals(List.of(), keys.releaseAll(121));
    assertEquals(List.of("+zoom 178 130"), keys.press(178, 130));
  }

  @Test
  void immediateCommandsPreserveBufferedCommandsAndFrameWait() {
    var output = new ArrayList<String>();
    var commands =
        new CommandSystem(
            new CvarSystem(), memoryFs(Map.of("test.cfg", "echo config")), output::add);
    commands.submit("wait 2; echo buffered", CommandSystem.Execution.APPEND);
    commands.submit("echo now; exec test", CommandSystem.Execution.NOW);
    assertEquals(List.of("now"), output);
    assertEquals(3, commands.pending());
    commands.runFrame(100);
    assertEquals(List.of("now", "config"), output);
    commands.submit("echo whilewaiting", CommandSystem.Execution.NOW);
    assertEquals(List.of("now", "config", "whilewaiting"), output);
    assertEquals(0, commands.runFrame(100));
    commands.runFrame(100);
    assertEquals(List.of("now", "config", "whilewaiting", "buffered"), output);
  }

  @Test
  void nestedImmediateDispatchRestoresCurrentCommandAndSharesBudget() {
    var output = new ArrayList<String>();
    var commands = new CommandSystem(new CvarSystem(), memoryFs(Map.of()), output::add);
    commands.register(
        "outer",
        command -> {
          commands.submit("echo nested", CommandSystem.Execution.NOW);
          assertSame(command, commands.current());
        });
    commands.submit("outer; echo later", CommandSystem.Execution.APPEND);
    assertEquals(2, commands.runFrame(2));
    assertEquals(List.of("nested"), output);
    assertEquals(1, commands.pending());
    commands.runFrame(1);
    assertEquals(List.of("nested", "later"), output);
    commands.register(
        "recursive", ignored -> commands.submit("recursive", CommandSystem.Execution.NOW));
    commands.submit("recursive", CommandSystem.Execution.NOW);
    assertTrue(output.getLast().contains("recursion limit"));
    assertEquals("", commands.current().text());
  }

  @Test
  void quotedButtonCommandReleasesWithArgumentsIntact() {
    var keys = new KeyBindings();
    keys.bind(1, "  \"+attack\" \"left; right\"; +zoom");
    assertEquals(List.of("\"+attack\" \"left; right\" 1 10", "+zoom 1 10"), keys.press(1, 10));
    assertEquals(List.of("\"-attack\" \"left; right\" 1 20", "-zoom 1 20"), keys.release(1, 20));
  }

  @Test
  void infoFlagRegistrationCannotApplyAnInvalidLatchedValue() {
    var cvars = new CvarSystem();
    cvars.register("test", "valid", CvarSystem.LATCH);
    cvars.set("test", "bad\\value", CvarSystem.Source.CONSOLE);
    assertThrows(
        IllegalArgumentException.class, () -> cvars.register("test", "valid", CvarSystem.USERINFO));
    assertEquals("valid", cvars.string("test"));
    assertEquals(0, cvars.find("test").orElseThrow().flags() & CvarSystem.USERINFO);
  }

  @Test
  void fileHandlesReadSeekAppendPersistAndRejectStaleHandles() throws IOException {
    var store = new GameFileStore(temporary.resolve("game"));
    var fs = memoryFs(Map.of("config.cfg", "base", "vm/cgame.qvm", "module"));
    try (store;
        var handles = new GameFileHandles(fs, store)) {
      var original = handles.open(new VirtualPath("config.cfg"), GameFileHandles.Mode.READ);
      assertEquals(
          "ba", new String(handles.read(original.handle(), 2), StandardCharsets.ISO_8859_1));
      handles.seek(original.handle(), -1, GameFileHandles.Seek.END);
      assertEquals(
          "e", new String(handles.read(original.handle(), 20), StandardCharsets.ISO_8859_1));
      handles.close(original.handle());
      assertThrows(IOException.class, () -> handles.read(original.handle(), 1));
      var append = handles.open(new VirtualPath("config.cfg"), GameFileHandles.Mode.APPEND_SYNC);
      handles.write(append.handle(), " plus".getBytes(StandardCharsets.ISO_8859_1));
      assertEquals("base plus", Files.readString(temporary.resolve("game/config.cfg")));
      handles.close(append.handle());
      var changed = handles.open(new VirtualPath("config.cfg"), GameFileHandles.Mode.READ);
      assertEquals(9, changed.length());
      assertTrue(changed.handle() > original.handle());
      assertEquals(
          6, handles.open(new VirtualPath("vm/cgame.qvm"), GameFileHandles.Mode.READ).length());
      assertThrows(
          IOException.class, () -> handles.seek(changed.handle(), -1, GameFileHandles.Seek.START));
      assertThrows(IOException.class, () -> handles.write(changed.handle(), new byte[0]));
    }
  }

  @Test
  void savedFilesRejectSymlinksAndExecutableReplacement() throws IOException {
    Path root = temporary.resolve("game"), outside = temporary.resolve("outside");
    var store = new GameFileStore(root);
    Files.createDirectory(outside);
    Files.createSymbolicLink(root.resolve("escape"), outside);
    Files.writeString(outside.resolve("important"), "keep");
    Files.createSymbolicLink(root.resolve("alias"), outside.resolve("important"));
    assertThrows(
        IOException.class, () -> store.write(new VirtualPath("escape/file.cfg"), new byte[1]));
    assertThrows(IOException.class, () -> store.write(new VirtualPath("alias"), new byte[1]));
    assertThrows(IOException.class, () -> store.read(new VirtualPath("alias")));
    assertThrows(
        IOException.class, () -> store.write(new VirtualPath("vm/qagame.qvm"), new byte[1]));
    assertEquals("keep", Files.readString(outside.resolve("important")));
    store.close();
  }

  @Test
  void savedFileQuotasCountTheWholeRootAndAllowReplacement() throws IOException {
    Path root = temporary.resolve("quota");
    Files.createDirectories(root.resolve("demos"));
    try (var store = new GameFileStore(root, new GameFileStore.Limits(8, 10, 2, 1, 1))) {
      store.write(new VirtualPath("one.cfg"), new byte[6]);
      store.write(new VirtualPath("demos/two.dm_68"), new byte[4]);
      assertEquals(new GameFileStore.Usage(10, 2, 1), store.usage());
      assertThrows(IOException.class, () -> store.write(new VirtualPath("one.cfg"), new byte[7]));
      assertThrows(IOException.class, () -> store.write(new VirtualPath("third.cfg"), new byte[0]));
      store.write(new VirtualPath("one.cfg"), new byte[2]);
      assertEquals(new GameFileStore.Usage(6, 2, 1), store.usage());
      assertEquals(2, store.read(new VirtualPath("one.cfg")).orElseThrow().length);
      assertTrue(store.read(new VirtualPath("missing/file.cfg")).isEmpty());
      assertThrows(
          IOException.class, () -> store.write(new VirtualPath("missing/file.cfg"), new byte[1]));
      assertFalse(Files.exists(root.resolve("missing")));
      assertThrows(IOException.class, () -> store.write(new VirtualPath("one.cfg"), new byte[9]));
    }
    assertThrows(
        IOException.class, () -> new GameFileStore(root, new GameFileStore.Limits(8, 10, 2, 0, 1)));
    assertThrows(
        IOException.class, () -> new GameFileStore(root, new GameFileStore.Limits(8, 10, 2, 1, 0)));
  }

  @Test
  void savedRootRemainsPinnedWhenItsPathIsReplaced() throws IOException {
    Path root = temporary.resolve("game"),
        moved = temporary.resolve("renamed"),
        outside = temporary.resolve("outside");
    Files.createDirectory(outside);
    var store = new GameFileStore(root);
    try (store) {
      store.write(new VirtualPath("config.cfg"), "first".getBytes(StandardCharsets.US_ASCII));
      Files.move(root, moved);
      Files.createSymbolicLink(root, outside);
      store.write(new VirtualPath("config.cfg"), "second".getBytes(StandardCharsets.US_ASCII));
      assertEquals(
          "second",
          new String(
              store.read(new VirtualPath("config.cfg")).orElseThrow(), StandardCharsets.US_ASCII));
      assertEquals("second", Files.readString(moved.resolve("config.cfg")));
      assertFalse(Files.exists(outside.resolve("config.cfg")));
    }
    assertThrows(IOException.class, () -> store.read(new VirtualPath("config.cfg")));
    assertThrows(IOException.class, () -> store.write(new VirtualPath("config.cfg"), new byte[0]));
  }

  @Test
  void appendWritesStayAtEndAfterSeekAndEmptyAppendCreatesAFile() throws IOException {
    try (var store = new GameFileStore(temporary.resolve("game"));
        var handles = new GameFileHandles(memoryFs(Map.of("config.cfg", "base")), store)) {
      var opened = handles.open(new VirtualPath("config.cfg"), GameFileHandles.Mode.APPEND);
      handles.seek(opened.handle(), 0, GameFileHandles.Seek.START);
      handles.write(opened.handle(), new byte[] {'!'});
      handles.close(opened.handle());
      assertEquals(
          "base!",
          new String(
              store.read(new VirtualPath("config.cfg")).orElseThrow(), StandardCharsets.US_ASCII));
      var empty = handles.open(new VirtualPath("empty.log"), GameFileHandles.Mode.APPEND);
      handles.close(empty.handle());
      assertEquals(0, store.read(new VirtualPath("empty.log")).orElseThrow().length);
    }
  }

  @Test
  void unsupportedDirectoryProviderFailsClosed() throws IOException {
    Path archive = temporary.resolve("unsupported.zip");
    try (var fs = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
      assertThrows(IOException.class, () -> new GameFileStore(fs.getPath("/saves")));
      assertFalse(Files.exists(fs.getPath("/saves/config.cfg")));
    }
  }

  private static VirtualFileSystem memoryFs(Map<String, String> data) {
    return new VirtualFileSystem() {
      @Override
      public Optional<Origin> which(VirtualPath path) {
        return data.containsKey(path.value())
            ? Optional.of(new Origin("baseq3", "fixture", false))
            : Optional.empty();
      }

      @Override
      public List<VirtualPath> list(String directory) {
        return data.keySet().stream()
            .filter(p -> p.startsWith(directory))
            .map(VirtualPath::new)
            .sorted()
            .toList();
      }

      @Override
      public List<Origin> searchOrder() {
        return List.of();
      }

      @Override
      public byte[] read(VirtualPath path) throws IOException {
        String value = data.get(path.value());
        if (value == null) throw new java.nio.file.NoSuchFileException(path.value());
        return value.getBytes(StandardCharsets.ISO_8859_1);
      }

      @Override
      public void close() {}
    };
  }
}
