package dev.bluevista.craftq3.engine.vmabi;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ImportTableTest {
  @Test
  void modernTablesUseTheEnumNumbers() {
    for (VmModule module : VmModule.values()) {
      ImportTable table = ImportTable.of(module, AbiProfile.Q3_132);
      List<VmImport> entries = ImportTable.canonicalEntries(module);
      assertEquals(entries.size(), table.entries().size());
      for (VmImport entry : entries) {
        assertEquals(entry, table.get(entry.number()), entry.name());
        assertEquals(entry.number(), table.numberOf(entry));
        assertEquals(module, entry.module());
        assertNotNull(entry.policy());
        assertTrue(entry.argCount() >= 0 && entry.argCount() <= 16, entry.name());
      }
    }
  }

  @Test
  void numbersMatchThe132Headers() {
    assertEquals(45, QagameImport.G_FS_SEEK.number());
    assertEquals(43, QagameImport.G_TRACECAPSULE.number());
    assertEquals(113, QagameImport.TRAP_TESTPRINTFLOAT.number());
    assertEquals(211, BotlibImport.BOTLIB_USER_COMMAND.number());
    assertEquals(318, BotlibImport.BOTLIB_AAS_PREDICT_CLIENT_MOVEMENT.number());
    assertEquals(423, BotlibImport.BOTLIB_EA_RESET_INPUT.number());
    assertEquals(575, BotlibImport.BOTLIB_AAS_ALTERNATIVE_ROUTE_GOAL.number());
    assertEquals(581, BotlibImport.BOTLIB_PC_SOURCE_FILE_AND_LINE.number());
    assertEquals(89, CgameImport.CG_FS_SEEK.number());
    assertEquals(107, CgameImport.CG_FLOOR.number());
    assertEquals(111, CgameImport.CG_ACOS.number());
    assertEquals(87, UiImport.UI_SET_PBCLSTATUS.number());
    assertEquals(108, UiImport.UI_CEIL.number());
    assertEquals(46 + 14, QagameImport.values().length);
    assertEquals(12 + 19 + 24 + 82, BotlibImport.values().length);
    assertEquals(90 + 12, CgameImport.values().length);
    assertEquals(88 + 9, UiImport.values().length);
  }

  @Test
  void retailTablesRemapTheChangedSlots() {
    ImportTable game = ImportTable.of(VmModule.QAGAME, AbiProfile.RETAIL_1999);
    assertEquals(BotlibImport.BOTLIB_EA_GESTURE, game.get(406));
    assertEquals(BotlibImport.BOTLIB_EA_COMMAND, game.get(407));
    assertEquals(BotlibImport.BOTLIB_EA_SELECT_WEAPON, game.get(408));
    assertEquals(BotlibImport.BOTLIB_EA_ATTACK, game.get(410));
    assertEquals(BotlibImport.BOTLIB_EA_CROUCH, game.get(415));
    assertEquals(BotlibImport.BOTLIB_EA_VIEW, game.get(423));
    assertEquals(BotlibImport.BOTLIB_EA_GET_INPUT, game.get(425));
    assertEquals(BotlibImport.BOTLIB_EA_RESET_INPUT, game.get(426));
    assertEquals(RetailImport.RETAIL_BOTLIB_EA_USE_ITEM, game.get(402));
    assertEquals(RetailImport.RETAIL_BOTLIB_AAS_ENTITY_VISIBLE, game.get(300));
    assertFalse(game.contains(BotlibImport.BOTLIB_EA_ACTION));
    assertFalse(game.knows(43));
    assertFalse(game.knows(573));
    assertEquals(BotlibImport.BOTLIB_AI_PREDICT_VISIBLE_POSITION, game.get(572));

    ImportTable ui = ImportTable.of(VmModule.UI, AbiProfile.RETAIL_1999);
    assertEquals(RetailImport.RETAIL_UI_LAN_GETLOCALSERVERCOUNT, ui.get(46));
    assertEquals(UiImport.UI_CVAR_REGISTER, ui.get(54));
    assertEquals(UiImport.UI_MEMORY_REMAINING, ui.get(56));
    assertEquals(UiImport.UI_SET_CDKEY, ui.get(58));
    assertFalse(ui.knows(59));
    assertEquals(UiImport.UI_GETCONFIGSTRING, ui.get(45));

    ImportTable cgame = ImportTable.of(VmModule.CGAME, AbiProfile.RETAIL_1999);
    assertEquals(CgameImport.CG_MEMORY_REMAINING, cgame.get(58));
    assertFalse(cgame.knows(59));
    assertFalse(cgame.knows(107));
  }

  @Test
  void everyTableMapsEachEntryOnce() {
    for (VmModule module : VmModule.values())
      for (AbiProfile profile : AbiProfile.values()) {
        ImportTable table = ImportTable.of(module, profile);
        Set<VmImport> seen = new HashSet<>();
        for (VmImport entry : table.entries()) assertTrue(seen.add(entry), entry.name());
        assertNull(table.get(-1));
        assertNull(table.get(table.span()));
      }
  }

  @Test
  void stubsAndFatalImportsDocumentTheirReason() {
    for (VmModule module : VmModule.values())
      for (VmImport entry : ImportTable.canonicalEntries(module)) {
        Policy policy = entry.policy();
        if (policy.kind() == Policy.Kind.STUB || policy.kind() == Policy.Kind.FATAL)
          assertFalse(policy.reason().isBlank(), entry.name());
        if (!policy.degradable() && policy.kind() != Policy.Kind.FATAL)
          assertFalse(policy.reason().isBlank(), entry.name());
      }
    assertEquals(ImportConstants.CD_KEY_VALID, UiImport.UI_VERIFY_CDKEY.policy().value());
    assertEquals(Policy.Kind.STUB, UiImport.UI_VERIFY_CDKEY.policy().kind());
    assertThrows(IllegalArgumentException.class, () -> Policy.stub(0, " "));
    assertThrows(IllegalArgumentException.class, () -> Policy.HOST.clearing(16, 4));
  }

  @Test
  void moduleNamesAreRecognised() {
    assertEquals(VmModule.CGAME, VmModule.fromName("baseq3/pak8.pk3!vm/CGAME.QVM").orElseThrow());
    assertEquals(VmModule.UI, VmModule.fromName("ui").orElseThrow());
    assertEquals(VmModule.QAGAME, VmModule.fromName("C:\\q3\\vm\\qagame.qvm").orElseThrow());
    assertTrue(VmModule.fromName("synthetic").isEmpty());
    assertEquals("vm/ui.qvm", VmModule.UI.path());
  }
}
