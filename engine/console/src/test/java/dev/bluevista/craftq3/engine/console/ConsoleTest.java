package dev.bluevista.craftq3.engine.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class ConsoleTest {
  @Test
  void setVariantsJoinArgumentsAndAddFlags() {
    var t = new TestConsole();
    t.run("set name Player One; sets sv_hostname \"My Server\"; setu team red; seta cg_fov 110");
    assertEquals("Player One", t.cvars().string("name"));
    var hostname = t.cvars().find("sv_hostname").orElseThrow();
    assertTrue(hostname.hasAny(CvarFlags.SERVERINFO | CvarFlags.USER_CREATED));
    assertTrue(t.cvars().find("team").orElseThrow().hasAny(CvarFlags.USERINFO));
    assertTrue(t.cvars().find("cg_fov").orElseThrow().hasAny(CvarFlags.ARCHIVE));
    assertEquals("\\team\\red", t.cvars().info(CvarFlags.USERINFO));
    t.run("set lonely");
    assertEquals("usage: set <variable> <value>", t.output.getLast());
    t.run("seta");
    assertEquals("usage: seta <variable> <value>", t.output.getLast());
    t.cvars().register("version", "1", CvarFlags.ROM);
    t.run("set version 2");
    assertEquals("version is read only.", t.output.getLast());
  }

  @Test
  void resetAndToggle() {
    var t = new TestConsole();
    t.cvars().register("cl_run", "1", CvarFlags.ARCHIVE);
    t.run("toggle cl_run");
    assertEquals("0", t.cvars().string("cl_run"));
    t.run("toggle cl_run");
    assertEquals("1", t.cvars().string("cl_run"));
    t.run("toggle cg_draw2d");
    assertEquals("1", t.cvars().string("cg_draw2d"));
    t.run("toggle r_mode 3 4 6; toggle r_mode 3 4 6");
    assertEquals("4", t.cvars().string("r_mode"));
    t.run("toggle r_mode 3 4 6; toggle r_mode 3 4 6");
    assertEquals("3", t.cvars().string("r_mode"));
    t.run("set cl_run 0; reset cl_run");
    assertEquals("1", t.cvars().string("cl_run"));
    t.run("reset");
    assertEquals("usage: reset <variable>", t.output.getLast());
  }

  @Test
  void echoCvarlistAndCmdlistFormats() {
    var t = new TestConsole(false);
    t.cvars().register("g_gametype", "0", CvarFlags.SERVERINFO | CvarFlags.LATCH);
    t.cvars().register("name", "x", CvarFlags.USERINFO | CvarFlags.ARCHIVE | CvarFlags.CHEAT);
    t.run("echo  hello   \"big world\"");
    assertEquals("hello big world", t.output.getLast());
    t.output.clear();
    t.run("cvarlist");
    assertEquals(
        List.of(
            " U  A C name \"x\"",
            "S    L  g_gametype \"0\"",
            "  R     sv_cheats \"1\"",
            "",
            "3 total cvars",
            "3 cvar indexes"),
        t.output);
    t.output.clear();
    t.run("cvarlist g_*");
    assertEquals("S    L  g_gametype \"0\"", t.output.getFirst());
    t.output.clear();
    t.run("cmdlist set*");
    assertEquals(List.of("set", "seta", "sets", "setu"), t.output.subList(0, 4));
    assertTrue(t.output.getLast().endsWith(" commands"));
    assertFalse(t.commands().exists("bind"));
  }

  @Test
  void execAddsTheDefaultExtensionAndReportsMissingFiles() {
    var t = new TestConsole().file("configs/duel.cfg", "echo duel").file("plain.txt", "echo txt");
    t.run("exec configs/duel");
    t.run("exec plain.txt");
    t.run("exec missing");
    t.run("exec");
    assertEquals(
        List.of(
            "execing configs/duel",
            "duel",
            "execing plain.txt",
            "txt",
            "couldn't exec missing",
            "exec <filename> : execute a script file"),
        t.output);
  }

  @Test
  void writeconfigUsesTheSinkAndReportsFailure() {
    var t = new TestConsole();
    t.cvars().register("sensitivity", "5", CvarFlags.ARCHIVE);
    t.run("writeconfig");
    assertEquals("Usage: writeconfig <filename>", t.output.getLast());
    t.run("writeconfig backup");
    assertEquals("Writing backup.cfg.", t.output.getLast());
    assertTrue(t.written.get("backup.cfg").contains("seta sensitivity \"5\"\n"));
    t.failWrites = true;
    t.run("writeconfig broken.cfg");
    assertEquals("Couldn't write broken.cfg: disk full", t.output.getLast());
  }

  @Test
  void configIsRewrittenOnlyAfterArchivedChanges() {
    var t = new TestConsole();
    t.cvars().register("sensitivity", "5", CvarFlags.ARCHIVE);
    assertTrue(t.console.writeConfigIfModified());
    assertFalse(t.console.writeConfigIfModified());
    t.cvars().register("cl_running", "0", 0);
    t.cvars().set("cl_running", "1");
    assertFalse(t.console.writeConfigIfModified());
    t.run("bind x +zoom");
    t.failWrites = true;
    assertFalse(t.console.writeConfigIfModified());
    t.failWrites = false;
    assertTrue(t.console.writeConfigIfModified());
    assertTrue(t.written.get(Console.CONFIG_FILE).contains("bind x \"+zoom\""));
  }

  @Test
  void bindCommandsMatchTheOriginalMessages() {
    var t = new TestConsole();
    t.run("bind");
    assertEquals("bind <key> [command] : attach a command to a key", t.output.getLast());
    t.run("bind NOPE +attack");
    assertEquals("\"NOPE\" isn't a valid key", t.output.getLast());
    t.run("bind mouse1");
    assertEquals("\"mouse1\" is not bound", t.output.getLast());
    t.run("bind MOUSE1 +attack; bind SPACE +moveup");
    t.run("bind mouse1");
    assertEquals("\"mouse1\" = \"+attack\"", t.output.getLast());
    t.output.clear();
    t.run("bindlist");
    assertEquals(List.of("SPACE \"+moveup\"", "MOUSE1 \"+attack\""), t.output);
    t.run("unbind mouse1");
    assertFalse(t.bindings().isBound(KeyNames.MOUSE1));
    t.run("unbindall");
    assertTrue(t.bindings().all().isEmpty());
  }

  @Test
  void inboxForwardsOtherThreadsCommandsToTheOwner() throws Exception {
    var t = new TestConsole();
    t.console.owner().claim();
    try {
      CompletableFuture.runAsync(
              () -> {
                assertTrue(t.console.inbox().post("set g_gravity 400"));
                assertTrue(t.console.inbox().post("echo posted\n"));
              })
          .get();
      var direct =
          assertThrows(
              ExecutionException.class,
              () -> CompletableFuture.runAsync(() -> t.cvars().set("g_gravity", "1")).get());
      assertTrue(direct.getCause() instanceof IllegalStateException);
      assertEquals(2, t.console.inbox().pending());
      t.console.frame();
      assertEquals("400", t.cvars().string("g_gravity"));
      assertEquals(List.of("posted"), t.output);
      assertEquals(0, t.console.inbox().pending());
    } finally {
      t.console.owner().release();
    }
  }

  @Test
  void inboxIsBounded() {
    var t = new TestConsole();
    for (int i = 0; i < CommandInbox.CAPACITY; i++) assertTrue(t.console.inbox().post("echo x"));
    assertFalse(t.console.inbox().post("echo overflow"));
    assertEquals(CommandInbox.CAPACITY, t.console.inbox().drainTo(t.buffer()));
  }

  @Test
  void commandsRejectOtherThreadsOnceOwned() throws Exception {
    var t = new TestConsole();
    t.console.owner().claim();
    try {
      var failure =
          assertThrows(
              ExecutionException.class,
              () -> CompletableFuture.runAsync(() -> t.commands().execute("echo x")).get());
      assertTrue(failure.getCause() instanceof IllegalStateException);
      assertThrows(
          ExecutionException.class,
          () -> CompletableFuture.runAsync(() -> t.bindings().bind('x', "y")).get());
      assertEquals(List.of(), t.output);
    } finally {
      t.console.owner().release();
    }
  }
}
