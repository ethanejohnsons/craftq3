package dev.bluevista.craftq3.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspFixture;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.core.command.CommandSystem;
import dev.bluevista.craftq3.server.Q3Server;
import dev.bluevista.craftq3.vm.Opcode;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class LocalEngineServicesTest {
  @Test
  void foreignCommandBufferArgumentsOverrideOuterBufferAndRestoreReliableContext()
      throws Exception {
    for (var profile : ClientAbi.values()) {
      // The authored guest copies argv[1] to a cvar on every entry and handles console commands.
      var program =
          new ClientTestData.Program()
              .op(Opcode.ENTER, 64)
              .call(8, 1, 300, 128)
              .call(5, 200, 300)
              .op(Opcode.CONST, 1)
              .op(Opcode.LEAVE, 64);
      byte[] name = "observed_argument".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
      System.arraycopy(name, 0, program.data, 200, name.length);
      var files =
          new ClientTestData.Files(
              Map.of("vm/qagame.qvm", ClientTestData.server(), "vm/cgame.qvm", program.bytes()));
      try (var server =
          new Q3Server(
              files,
              "fixture",
              BspReader.read(BspFixture.map(false)),
              null,
              ignored -> {},
              Clock.systemUTC())) {
        server.initialize(1000, 1);
        server.connect(0, Map.of("name", "Synthetic"));
        try (var client =
            new Q3Client(
                files, server, ignored -> {}, new ClientTestData.Audio(), ignored -> {}, profile)) {
          client.initialize(0, 640, 480);
          var reliable = CommandParser.tokenize("reliable previous");
          var field = Q3Client.class.getDeclaredField("serverCommand");
          field.setAccessible(true);
          field.set(client, reliable);
          var foreign = new CommandSystem(server.cvars(), files, ignored -> {});
          foreign.unknownHandler(client::consoleCommand);
          client
              .commands()
              .register(
                  "outer",
                  ignored ->
                      foreign.submit("foreign \"whole; argument\"", CommandSystem.Execution.NOW));
          client.commands().submit("outer incorrect", CommandSystem.Execution.NOW);
          assertEquals("whole; argument", client.cvars().string("observed_argument"));
          assertSame(
              reliable,
              field.get(client),
              "A scoped console call must preserve the previous reliable-command context");
          client.frame(server.time(), 640, 480);
          assertEquals(
              "",
              client.cvars().string("observed_argument"),
              "Foreign console arguments must not leak into frame callbacks");
        }
      }
    }
  }

  @Test
  void localConsoleSharesGameCvarsAndScopesForwardedCommandArguments() throws Exception {
    // This guest exposes its argv[1] in a configstring on every entry, including frame callbacks.
    byte[] game =
        new ClientTestData.Program()
            .op(Opcode.ENTER, 64)
            .call(15, 4096, 1, 516, 16384, 468)
            .call(9, 1, 300, 200)
            .call(18, 100, 300)
            .op(Opcode.CONST, 0)
            .op(Opcode.LEAVE, 64)
            .bytes();
    var files =
        new ClientTestData.Files(
            Map.of("vm/qagame.qvm", game, "vm/cgame.qvm", ClientTestData.client(ClientAbi.Q3_132)));
    try (var server =
        new Q3Server(
            files,
            "fixture",
            BspReader.read(BspFixture.map(false)),
            null,
            s -> {},
            Clock.systemUTC())) {
      server.initialize(1000, 1);
      server.connect(0, Map.of("name", "Synthetic"));
      server.cvars().register("fraglimit", "20", 0);
      try (var client =
          new Q3Client(
              files, server, frame -> {}, new ClientTestData.Audio(), s -> {}, ClientAbi.Q3_132)) {
        client.initialize(0, 640, 480);
        assertSame(server.cvars(), client.cvars());
        client.commands().submit("fraglimit 7", CommandSystem.Execution.NOW);
        assertEquals(7, server.cvars().integer("fraglimit"));
        client.commands().submit("opaque \"whole; argument\"", CommandSystem.Execution.NOW);
        assertEquals("whole; argument", server.configstrings().get(100));
        server.consoleCommand(CommandParser.tokenize("opaque \"line\nbreak\""));
        assertEquals("line\nbreak", server.configstrings().get(100));
        server.clientCommand(0, "say literal;semicolons");
        assertEquals("literal;semicolons", server.configstrings().get(100));
        server.runFrame(server.time() + 50);
        assertEquals(
            "",
            server.configstrings().get(100),
            "Console arguments must not leak into frame callbacks");
      }
    }
  }
}
