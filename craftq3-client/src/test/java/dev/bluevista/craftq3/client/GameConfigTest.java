package dev.bluevista.craftq3.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.client.input.GameConfig;
import dev.bluevista.craftq3.client.input.Q3Input;
import dev.bluevista.craftq3.core.command.CommandSystem;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.fs.WritableFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class GameConfigTest {
  @Test
  void homeConfigAndNestedExecOverridePacksAndRestoreBindsAndLatchedValues() throws Exception {
    var home = new Home();
    var files =
        new ClientTestData.Files(
            Map.of(
                "q3config.cfg",
                bytes("seta sensitivity 99"),
                "nested.cfg",
                bytes("seta sensitivity 88")));
    var cvars = new CvarSystem();
    var commands = new CommandSystem(cvars, files, s -> {});
    var input = new Q3Input(cvars, commands, 0);
    cvars.register("game_mode", "0", CvarSystem.ARCHIVE | CvarSystem.LATCH);
    commands.submit(
        "sensitivity 2.5; game_mode 4; unbind w; bind mouse2 +zoom; bind g \"echo hello; echo world\"",
        CommandSystem.Execution.NOW);
    var config = new GameConfig(commands, input.bindings(), files, home, s -> {});
    config.save("q3config");
    home.values.put("autoexec.cfg", bytes("exec nested.cfg"));
    home.values.put("nested.cfg", bytes("seta sensitivity 3"));

    var loaded = new CvarSystem();
    loaded.register("game_mode", "0", CvarSystem.ARCHIVE | CvarSystem.LATCH);
    var nextCommands = new CommandSystem(loaded, files, s -> {});
    var nextInput = new Q3Input(loaded, nextCommands, 0);
    new GameConfig(nextCommands, nextInput.bindings(), files, home, s -> {}).load();
    nextCommands.runFrame(1024);
    assertEquals(3, loaded.number("sensitivity"));
    assertEquals("4", loaded.find("game_mode").orElseThrow().latchedValue().orElseThrow());
    loaded.applyLatchedValues();
    assertEquals(4, loaded.integer("game_mode"));
    assertEquals("", nextInput.bindings().binding('w'));
    assertEquals("+zoom", nextInput.bindings().binding(Q3Input.MOUSE2));
    assertEquals("echo hello; echo world", nextInput.bindings().binding('g'));
    assertArrayEquals(bytes("seta sensitivity 99"), files.read(new VirtualPath("q3config.cfg")));
  }

  @Test
  void ambiguousBindingCannotPartiallyReplaceLastGoodConfig() throws Exception {
    var home = new Home();
    var files = new ClientTestData.Files(Map.of());
    var commands = new CommandSystem(new CvarSystem(), files, s -> {});
    var bindings = new dev.bluevista.craftq3.core.command.KeyBindings();
    var config = new GameConfig(commands, bindings, files, home, s -> {});
    config.save("q3config.cfg");
    byte[] before = home.values.get("q3config.cfg").clone();
    bindings.bind('x', "echo \"a; b\"");
    assertThrows(IOException.class, () -> config.save("q3config.cfg"));
    assertArrayEquals(before, home.values.get("q3config.cfg"));
    assertThrows(IllegalArgumentException.class, () -> config.save("../escape"));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.ISO_8859_1);
  }

  private static final class Home implements WritableFiles {
    final Map<String, byte[]> values = new HashMap<>();

    public Optional<byte[]> read(VirtualPath path) {
      return Optional.ofNullable(values.get(path.value())).map(byte[]::clone);
    }

    public void write(VirtualPath path, byte[] bytes) {
      values.put(path.value(), bytes.clone());
    }
  }
}
