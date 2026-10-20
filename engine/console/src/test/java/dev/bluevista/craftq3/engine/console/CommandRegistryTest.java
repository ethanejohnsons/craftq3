package dev.bluevista.craftq3.engine.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandRegistryTest {
  @Test
  void commandsAreCaseInsensitiveAndDuplicatesAreIgnored() {
    var t = new TestConsole();
    var seen = new ArrayList<String>();
    assertTrue(t.commands().register("MyCmd", args -> seen.add("first " + args.args())));
    assertFalse(t.commands().register("mycmd", args -> seen.add("second")));
    t.commands().execute("MYCMD a b");
    assertEquals(List.of("first a b"), seen);
    assertTrue(t.commands().unregister("mycmd"));
    t.commands().execute("mycmd");
    assertEquals("Unknown command \"mycmd\"", t.output.getLast());
    assertThrows(IllegalArgumentException.class, () -> t.commands().register("a b", args -> {}));
    assertThrows(IllegalArgumentException.class, () -> t.commands().register("", args -> {}));
  }

  @Test
  void unknownCommandsFallThroughToCvars() {
    var t = new TestConsole();
    t.cvars().register("cg_fov", "90", CvarFlags.ARCHIVE);
    t.commands().execute("cg_fov");
    assertEquals("\"cg_fov\" is:\"90^7\" default:\"90^7\"", t.output.getLast());
    // Like Cvar_Command, only the first argument is used.
    t.commands().execute("CG_FOV 110 extra");
    assertEquals("110", t.cvars().string("cg_fov"));
    t.cvars().register("version", "1", CvarFlags.ROM);
    t.commands().execute("version 2");
    assertEquals("version is read only.", t.output.getLast());
    t.cvars().register("r_mode", "3", CvarFlags.LATCH);
    t.commands().execute("r_mode 4");
    assertEquals("r_mode will be changed upon restarting.", t.output.getLast());
    t.commands().execute("r_mode");
    assertEquals("latched: \"4\"", t.output.getLast());
  }

  @Test
  void fallbacksRunInOrderAfterCvarsUntilOneHandlesIt() {
    var t = new TestConsole();
    var calls = new ArrayList<String>();
    t.commands()
        .addFallback(
            args -> {
              calls.add("cgame " + args.name());
              return args.name().equals("+scores");
            });
    t.commands()
        .addFallback(
            args -> {
              calls.add("server " + args.name());
              return true;
            });
    assertTrue(t.commands().execute("+scores"));
    assertTrue(t.commands().execute("say hello"));
    assertEquals(List.of("cgame +scores", "cgame say", "server say"), calls);
    t.cvars().register("say", "cvar", 0);
    t.commands().execute("say");
    assertEquals(3, calls.size());
    assertFalse(t.output.stream().anyMatch(line -> line.startsWith("Unknown")));
  }

  @Test
  void usageErrorsArePrintedNotThrown() {
    var t = new TestConsole();
    t.commands()
        .register(
            "strict",
            args -> {
              throw new IllegalArgumentException("needs an argument");
            });
    assertTrue(t.commands().execute("strict"));
    assertEquals("strict: needs an argument", t.output.getLast());
  }

  @Test
  void completionCoversCommandsCvarsAndArgumentCompleters() {
    var t = new TestConsole();
    t.cvars().register("sensitivity", "5", CvarFlags.ARCHIVE);
    t.cvars().register("sv_hostname", "noname", 0);
    t.commands().register("sexy", args -> {});
    assertEquals(
        List.of("sensitivity", "set", "seta", "sets", "setu", "sexy"),
        t.commands().complete("/se"));
    assertEquals(List.of("sensitivity"), t.commands().complete("echo hi; seta sen"));
    assertEquals(
        List.of("MOUSE1", "MOUSE2", "MOUSE3", "MOUSE4", "MOUSE5"),
        t.commands().complete("bind mou"));
    assertEquals(List.of(), t.commands().complete("bind MOUSE1 +att"));
    assertTrue(t.commands().complete("vstr ").contains("sv_hostname"));
    assertEquals(List.of(), t.commands().complete("unknowncommand x"));
  }
}
