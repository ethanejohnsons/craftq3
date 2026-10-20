package dev.bluevista.craftq3.engine.game;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.vmabi.AbiProfile;
import dev.bluevista.craftq3.engine.vmabi.BotlibImport;
import dev.bluevista.craftq3.engine.vmabi.ImportTable;
import dev.bluevista.craftq3.engine.vmabi.Policy;
import dev.bluevista.craftq3.engine.vmabi.QagameImport;
import dev.bluevista.craftq3.engine.vmabi.VmImport;
import dev.bluevista.craftq3.engine.vmabi.VmModule;
import org.junit.jupiter.api.Test;

final class QagameHostTest {
  @Test
  void everyImportOfBothProfilesHasAHandler() {
    Harness h = new Harness().spawn();
    var host = h.server.vmHost().orElseThrow();
    assertEquals(ImportTable.of(VmModule.QAGAME, AbiProfile.Q3_132), host.table());
    // The dispatcher refuses construction when a required handler is missing; the stubs 39/40
    // are bound too.
    for (VmImport entry : ImportTable.of(VmModule.QAGAME, AbiProfile.Q3_132).entries())
      if (entry.policy().kind() == Policy.Kind.STUB && entry instanceof QagameImport)
        assertEquals(0, h.game.sys(entry, 0, 0, 0));
    assertEquals(0, h.server.degradationTotal());
  }

  @Test
  void nullFileHandlesAndUsercmdsAreBenign() {
    Harness h = new Harness().spawn();
    int buffer = h.game.string("          ");
    assertEquals(0, h.game.sys(QagameImport.G_FS_READ, buffer, 4, 0));
    assertEquals(0, h.game.sys(QagameImport.G_FS_WRITE, buffer, 4, 0));
    assertEquals(0, h.game.sys(QagameImport.G_FS_FCLOSE_FILE, 0));
    assertEquals(0, h.server.degradationTotal());
    assertEquals(
        -1, h.game.sys(QagameImport.G_FS_FOPEN_FILE, h.game.string("nothing.cfg"), buffer, 0));
  }

  @Test
  void withoutBotlibTheGameSeesALibraryThatIsNotSetUp() {
    Harness h = new Harness().spawn();
    assertEquals(BotlibImport.BLERR_LIBRARY_NOT_SETUP, h.game.sys(BotlibImport.BOTLIB_SETUP));
    assertEquals(0, h.game.sys(BotlibImport.BOTLIB_AAS_INITIALIZED));
  }
}
