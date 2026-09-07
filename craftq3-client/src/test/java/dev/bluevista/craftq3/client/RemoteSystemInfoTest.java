package dev.bluevista.craftq3.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.cvar.CvarSystem;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

final class RemoteSystemInfoTest {
  @Test
  void nativeFlagMatrixAllowsSystemServerAndUserCreatedButHonorsProtection() {
    for (int flags = 0; flags < 16384; flags++) {
      var cvars = new CvarSystem();
      cvars.register("probe", "original", flags);
      var service = new RemoteSystemInfo(cvars, ignored -> {});
      service.apply("\\sv_serverid\\91\\probe\\remote");
      boolean allowed =
          (flags & (CvarSystem.SYSTEMINFO | CvarSystem.SERVER_CREATED | CvarSystem.USER_CREATED))
                  != 0
              && (flags & CvarSystem.PROTECTED) == 0;
      assertEquals(allowed ? "remote" : "original", cvars.string("probe"), "flags=" + flags);
    }
  }

  @Test
  void unknownValuesAreReadOnlyServerCvarsAndTextNeverExecutes() {
    var cvars = new CvarSystem();
    var service = new RemoteSystemInfo(cvars, ignored -> {});
    var settings = service.apply("\\sv_serverid\\91\\custom\\echo something\\sv_pure\\1");
    assertEquals(91, settings.serverId());
    assertTrue(settings.pure());
    assertEquals("baseq3", settings.game());
    assertEquals(
        CvarSystem.SERVER_CREATED | CvarSystem.ROM, cvars.find("custom").orElseThrow().flags());
    assertEquals("echo something", cvars.string("custom"));
    assertEquals(
        CvarSystem.Change.READ_ONLY, cvars.set("custom", "changed", CvarSystem.Source.CONSOLE));
    assertThrows(UnsupportedOperationException.class, () -> settings.values().put("x", "y"));
  }

  @Test
  void cheatResetRunsBeforeThePermittedServerOverrides() {
    var cvars = new CvarSystem();
    cvars.register("probe", "default", CvarSystem.CHEAT | CvarSystem.SYSTEMINFO);
    cvars.register("other", "default", CvarSystem.CHEAT);
    cvars.cheatsEnabled(true);
    cvars.set("probe", "user", CvarSystem.Source.CONSOLE);
    cvars.set("other", "user", CvarSystem.Source.CONSOLE);
    new RemoteSystemInfo(cvars, ignored -> {}).apply("\\sv_serverid\\91\\probe\\server");
    assertEquals("server", cvars.string("probe"));
    assertEquals("default", cvars.string("other"));
    assertEquals(
        CvarSystem.Change.CHEAT_PROTECTED, cvars.set("other", "again", CvarSystem.Source.CONSOLE));
  }

  @Test
  void protectedAndOrdinarySettingsRemainLocalWithDiagnostics() {
    var cvars = new CvarSystem();
    cvars.register("protected", "keep", CvarSystem.PROTECTED | CvarSystem.SYSTEMINFO);
    cvars.register("sensitivity", "5", CvarSystem.ARCHIVE);
    var output = new ArrayList<String>();
    new RemoteSystemInfo(cvars, output::add)
        .apply("\\sv_serverid\\91\\protected\\bad\\sensitivity\\100");
    assertEquals("keep", cvars.string("protected"));
    assertEquals("5", cvars.string("sensitivity"));
    assertEquals(2, output.size());
  }

  @Test
  void mountedGameAndMalformedMetadataFailBeforeChangingSettings() {
    var settings = RemoteSystemInfo.parse("\\sv_serverid\\91\\fs_game\\missionpack");
    settings.requireGame("missionpack");
    assertThrows(UnsupportedOperationException.class, () -> settings.requireGame("baseq3"));
    RemoteSystemInfo.parse("\\sv_serverid\\91").requireGame(null);
    var cvars = new CvarSystem();
    cvars.register("probe", "unchanged", CvarSystem.SYSTEMINFO);
    var service = new RemoteSystemInfo(cvars, ignored -> {});
    for (String text :
        new String[] {
          "\\probe\\changed",
          "\\sv_serverid\\91\\fs_game\\../bad",
          "\\sv_serverid\\91\\probe\\changed\\bad name\\value",
          "\\sv_serverid\\91\\sv_pure\\invalid"
        }) {
      assertThrows(IllegalArgumentException.class, () -> service.apply(text));
      assertEquals("unchanged", cvars.string("probe"));
    }
  }
}
