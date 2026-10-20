package dev.bluevista.craftq3.engine.console;

import static dev.bluevista.craftq3.engine.console.CvarTable.SetResult.CHANGED;
import static dev.bluevista.craftq3.engine.console.CvarTable.SetResult.CHEAT_PROTECTED;
import static dev.bluevista.craftq3.engine.console.CvarTable.SetResult.LATCHED;
import static dev.bluevista.craftq3.engine.console.CvarTable.SetResult.MISSING;
import static dev.bluevista.craftq3.engine.console.CvarTable.SetResult.READ_ONLY;
import static dev.bluevista.craftq3.engine.console.CvarTable.SetResult.UNCHANGED;
import static dev.bluevista.craftq3.engine.console.CvarTable.SetResult.WRITE_PROTECTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class CvarTableTest {
  private final CvarTable cvars = new CvarTable(new ThreadOwner("test cvars"));

  // Ported from legacy EngineServicesTest.
  @Test
  void handlesAndLatchedRestartPreserveUserSettings() {
    assertEquals(CHANGED, cvars.setUser("g_gametype", "4"));
    assertTrue(cvars.find("g_gametype").orElseThrow().hasAny(CvarFlags.USER_CREATED));
    var initial = cvars.register("g_gametype", "0", CvarFlags.LATCH | CvarFlags.SERVERINFO);
    assertEquals("4", initial.string());
    assertEquals("0", initial.resetString());
    assertFalse(initial.hasAny(CvarFlags.USER_CREATED));
    int count = initial.modificationCount();
    assertEquals(LATCHED, cvars.setUser("G_GAMETYPE", "1"));
    assertEquals("4", cvars.string("g_gametype"));
    assertEquals("1", initial.latchedString().orElseThrow());
    var registered = cvars.register("g_gametype", "0", CvarFlags.LATCH);
    assertSame(initial, registered);
    assertEquals("1", cvars.get(initial.handle()).string());
    assertTrue(registered.latchedString().isEmpty());
    assertTrue(registered.modificationCount() > count);
    assertEquals("\\g_gametype\\1", cvars.info(CvarFlags.SERVERINFO));
  }

  // Ported from legacy EngineServicesTest, with 1.32 forced/user writes instead of sources.
  @Test
  void protectionAndNumbersMatchDistinctIntegerFloatFields() {
    cvars.register("engine", "1", CvarFlags.ROM);
    assertEquals(READ_ONLY, cvars.setUser("engine", "2"));
    assertEquals(CHANGED, cvars.set("engine", "2"));
    cvars.set(CvarTable.CHEATS, "0");
    cvars.register("debug", "0", CvarFlags.CHEAT);
    assertEquals(CHEAT_PROTECTED, cvars.setUser("debug", "1"));
    cvars.set(CvarTable.CHEATS, "1");
    assertEquals(CHANGED, cvars.setUser("debug", "1"));
    cvars.set(CvarTable.CHEATS, "0");
    cvars.applyCheatState();
    assertEquals(0, cvars.integer("debug"));
    cvars.register("numeric", " -12.5e1 words", 0);
    assertEquals(-12, cvars.integer("numeric"));
    assertEquals(-125, cvars.value("numeric"));
  }

  @Test
  void latchedValuesApplyOnDemandAndForcedWritesDiscardThem() {
    var mode = cvars.register("r_mode", "3", CvarFlags.ARCHIVE | CvarFlags.LATCH);
    assertEquals(LATCHED, cvars.setUser("r_mode", "4"));
    assertEquals(UNCHANGED, cvars.setUser("r_mode", "4"));
    assertEquals(LATCHED, cvars.setUser("r_mode", "5"));
    assertEquals(3, mode.integer());
    cvars.applyLatched();
    assertEquals(5, mode.integer());
    assertTrue(mode.latchedString().isEmpty());
    cvars.setUser("r_mode", "6");
    assertEquals(CHANGED, cvars.set("r_mode", "7"));
    assertTrue(mode.latchedString().isEmpty());
    cvars.applyLatched();
    assertEquals(7, mode.integer());
  }

  @Test
  void romAndInitRejectUserWritesWithDistinctMessages() {
    cvars.register("version", "Q3 1.32", CvarFlags.ROM);
    cvars.register("dedicated", "0", CvarFlags.INIT);
    assertEquals(READ_ONLY, cvars.setUser("version", "x"));
    assertEquals(WRITE_PROTECTED, cvars.setUser("dedicated", "1"));
    assertEquals(WRITE_PROTECTED, cvars.reset("dedicated"));
    assertEquals("version is read only.", READ_ONLY.message("version"));
    assertEquals("dedicated is write protected.", WRITE_PROTECTED.message("dedicated"));
    assertEquals("Q3 1.32", cvars.string("version"));
    assertEquals(CHANGED, cvars.set("dedicated", "1"));
  }

  @Test
  void cheatsDefaultOnUntilAServerTurnsThemOff() {
    var cheats = cvars.find(CvarTable.CHEATS).orElseThrow();
    assertEquals(0, cheats.handle());
    assertTrue(cheats.hasAny(CvarFlags.ROM | CvarFlags.SYSTEMINFO));
    assertTrue(cvars.cheatsAllowed());
    var fog = cvars.register("r_showtris", "0", CvarFlags.CHEAT);
    assertEquals(CHANGED, cvars.setUser("r_showtris", "1"));
    assertEquals(READ_ONLY, cvars.setUser(CvarTable.CHEATS, "0"));
    cvars.set(CvarTable.CHEATS, "0");
    assertFalse(cvars.cheatsAllowed());
    assertEquals(1, fog.integer());
    cvars.applyCheatState();
    assertEquals(0, fog.integer());
    assertEquals(CHEAT_PROTECTED, cvars.setUser("r_showtris", "1"));
    assertEquals(CHANGED, cvars.set("r_showtris", "1"));
  }

  @Test
  void modifiedFlagsAccumulateUntilTaken() {
    cvars.takeModified(-1);
    cvars.register("name", "player", CvarFlags.USERINFO | CvarFlags.ARCHIVE);
    assertTrue(cvars.takeModified(CvarFlags.USERINFO));
    assertFalse(cvars.takeModified(CvarFlags.USERINFO));
    assertTrue(cvars.takeModified(CvarFlags.ARCHIVE));
    cvars.setUser("name", "player");
    assertEquals(0, cvars.modifiedFlags());
    cvars.setUser("name", "other");
    assertEquals(CvarFlags.USERINFO | CvarFlags.ARCHIVE, cvars.modifiedFlags());
    cvars.clearModifiedFlags(CvarFlags.ARCHIVE);
    assertEquals(CvarFlags.USERINFO, cvars.modifiedFlags());
    cvars.clearModifiedFlags(-1);
    cvars.setUser("loose", "1");
    cvars.addFlags("loose", CvarFlags.SERVERINFO);
    assertTrue(cvars.takeModified(CvarFlags.SERVERINFO));
    var latch = cvars.register("g_gametype", "0", CvarFlags.SERVERINFO | CvarFlags.LATCH);
    cvars.clearModifiedFlags(-1);
    cvars.setUser("g_gametype", "3");
    // A latch marks the flags (so the config is rewritten) while the value stays unchanged.
    assertTrue(cvars.takeModified(CvarFlags.SERVERINFO));
    assertEquals("0", latch.string());
  }

  @Test
  void perCvarModifiedAndModificationCount() {
    var sensitivity = cvars.register("sensitivity", "5", CvarFlags.ARCHIVE);
    assertEquals(1, sensitivity.modificationCount());
    assertTrue(sensitivity.modified());
    sensitivity.clearModified();
    cvars.setUser("sensitivity", "5");
    assertFalse(sensitivity.modified());
    cvars.setUser("sensitivity", "6");
    assertTrue(sensitivity.modified());
    assertEquals(2, sensitivity.modificationCount());
  }

  @Test
  void registrationKeepsUserValueAndMergesFlags() {
    cvars.setUser("cg_fov", "110");
    var fov = cvars.register("cg_fov", "90", CvarFlags.ARCHIVE);
    assertEquals("110", fov.string());
    assertEquals("90", fov.resetString());
    assertEquals(CvarFlags.ARCHIVE, fov.flags());
    var again = cvars.register("CG_FOV", "100", CvarFlags.CHEAT);
    assertSame(fov, again);
    assertEquals("cg_fov", again.name());
    assertEquals("90", again.resetString());
    assertEquals(CvarFlags.ARCHIVE | CvarFlags.CHEAT, again.flags());
    cvars.setUser("cg_fov", "120");
    assertEquals(CHANGED, cvars.reset("cg_fov"));
    assertEquals("90", fov.string());
    assertEquals(MISSING, cvars.reset("nonexistent"));
    assertTrue(cvars.find("nonexistent").isEmpty());
  }

  @Test
  void emptyResetStringIsFilledByLaterRegistration() {
    var cvar = cvars.register("empty", "", 0);
    cvars.register("empty", "filled", 0);
    assertEquals("filled", cvar.resetString());
    assertEquals("", cvar.string());
  }

  @Test
  void invalidNamesAndInfoValuesBecomePlaceholders() {
    var bad = cvars.register("bad;name", "0", 0);
    assertEquals(CvarTable.BAD_NAME, bad.name());
    var badValue = cvars.register("name", "bad\\info", CvarFlags.USERINFO);
    assertEquals(CvarTable.BAD_VALUE, badValue.string());
    assertEquals(CvarTable.BAD_NAME, cvars.register("", "x", 0).name());
  }

  // Adapted from legacy infoFlagRegistrationCannotApplyAnInvalidLatchedValue: as in 1.32 the
  // value is kept, and the info string skips what it cannot carry.
  @Test
  void infoStringSkipsValuesItCannotCarry() {
    cvars.register("test", "valid", CvarFlags.LATCH);
    cvars.setUser("test", "bad\\value");
    cvars.register("test", "valid", CvarFlags.USERINFO);
    assertEquals("bad\\value", cvars.string("test"));
    cvars.register("rate", "25000", CvarFlags.USERINFO);
    assertEquals("\\rate\\25000", cvars.info(CvarFlags.USERINFO));
  }

  @Test
  void infoStringIsInCreationOrderAndDropsOldestWhenFull() {
    cvars.register("a", "1", CvarFlags.SERVERINFO);
    cvars.register("b", "", CvarFlags.SERVERINFO);
    cvars.register("c", "3", CvarFlags.SERVERINFO | CvarFlags.USERINFO);
    cvars.register("d", "4", CvarFlags.USERINFO);
    assertEquals("\\a\\1\\c\\3", cvars.info(CvarFlags.SERVERINFO));
    assertEquals("\\a\\1\\c\\3\\d\\4", cvars.info(CvarFlags.SERVERINFO | CvarFlags.USERINFO));
    // Each pair is 4 characters; capacity 9 holds two plus the terminator.
    assertEquals("\\c\\3\\d\\4", cvars.info(CvarFlags.SERVERINFO | CvarFlags.USERINFO, 9));
    assertEquals("\\sv_cheats\\1", cvars.info(CvarFlags.SYSTEMINFO, CvarTable.BIG_INFO_CAPACITY));
  }

  @Test
  void handlesAreStableCreationIndices() {
    var first = cvars.register("first", "1", 0);
    var second = cvars.register("second", "2", 0);
    assertEquals(first.handle() + 1, second.handle());
    assertSame(second, cvars.get(second.handle()));
    assertEquals(cvars.size(), second.handle() + 1);
    assertThrows(IllegalArgumentException.class, () -> cvars.get(cvars.size()));
    assertThrows(IllegalArgumentException.class, () -> cvars.get(-1));
  }

  @Test
  void setValueFormatsLikeTheOriginal() {
    cvars.setValue("whole", 3f);
    cvars.setValue("fraction", 0.5f);
    assertEquals("3", cvars.string("whole"));
    assertEquals("0.500000", cvars.string("fraction"));
  }

  @Test
  void atoiAndAtofViews() {
    assertEquals(0, Cvar.parseInt("abc"));
    assertEquals(42, Cvar.parseInt("  +42x"));
    assertEquals(Integer.MAX_VALUE, Cvar.parseInt("99999999999999"));
    assertEquals(Integer.MIN_VALUE, Cvar.parseInt("-99999999999999"));
    assertEquals(0.5f, Cvar.parseFloat(".5"));
    assertEquals(2f, Cvar.parseFloat("2.e"));
    assertEquals(0f, Cvar.parseFloat("-"));
    assertEquals(1500f, Cvar.parseFloat("1.5e3abc"));
  }

  @Test
  void otherThreadsAreRejectedOnceOwned() throws Exception {
    var owned = new CvarTable(new ThreadOwner("owned cvars"));
    var sensitivity = owned.register("sensitivity", "5", 0);
    owned.owner().claim();
    try {
      ExecutionException failure =
          assertThrows(
              ExecutionException.class,
              () -> CompletableFuture.runAsync(() -> owned.set("sensitivity", "6")).get());
      assertTrue(failure.getCause() instanceof IllegalStateException);
      assertThrows(
          ExecutionException.class, () -> CompletableFuture.supplyAsync(sensitivity::string).get());
      assertThrows(
          ExecutionException.class, () -> CompletableFuture.supplyAsync(owned::size).get());
      assertEquals("5", owned.string("sensitivity"));
    } finally {
      owned.owner().release();
    }
  }
}
