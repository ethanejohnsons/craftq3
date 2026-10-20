package dev.bluevista.craftq3.client.demo;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class DemoCatalogTest {
  @Test
  void mergesRealFilesAndPublishesOnlySupportedRetailAliases() {
    var mounted = List.of("z.dm_68", "a.dm_68", "legacy.dm3", "other.dm_71", "sub/hidden.dm_68");
    var host = List.of("b.dm_68", "a.dm_68", "ignored.txt");
    assertEquals(List.of("a.dm3", "b.dm3", "z.dm3"), DemoCatalog.files(mounted, host, "dm3", true));
    assertEquals(
        List.of("a.dm_68", "b.dm_68", "z.dm_68"),
        DemoCatalog.files(mounted, host, ".dm_68", false));
    assertEquals(List.of("other.dm_71"), DemoCatalog.files(mounted, host, ".dm_71", false));
    assertEquals("a.dm_68", DemoCatalog.resolveAlias("A.DM3", List.of("a.dm_68")).orElseThrow());
    assertTrue(DemoCatalog.resolveAlias("unknown.dm3", mounted).isEmpty());
    assertTrue(DemoCatalog.resolveAlias("a.dm_68", mounted).isEmpty());
  }

  @Test
  void existingPhysicalLegacyAndCaseAmbiguitiesBlockAliases() {
    var names =
        List.of("same.dm_68", "same.dm3", "one.dm_68", "ONE.dm_68", "okay.dm_68", "okay.dm_68");
    assertEquals(List.of("okay.dm3"), DemoCatalog.files(names, List.of(), ".dm3", true));
    assertEquals(
        List.of("okay.dm_68", "same.dm_68"), DemoCatalog.files(names, List.of(), ".dm_68", false));
    assertTrue(DemoCatalog.resolveAlias("same.dm3", names).isEmpty());
    assertTrue(DemoCatalog.resolveAlias("one.dm3", names).isEmpty());
    assertEquals("okay.dm_68", DemoCatalog.resolveAlias("OKAY.dm3", names).orElseThrow());
  }

  @Test
  void unquotedGuestCommandSyntaxCannotBeIntroducedByCatalogNames() {
    var unsafe =
        List.of(
            "space name.dm_68",
            "semi;quit.dm_68",
            "quote\"x.dm_68",
            "../x.dm_68",
            "x\\y.dm_68",
            "c:x.dm_68",
            "x\ny.dm_68",
            "caf\u00e9.dm_68",
            ".dm_68");
    assertTrue(DemoCatalog.files(unsafe, unsafe, ".dm_68", false).isEmpty());
    assertTrue(DemoCatalog.files(unsafe, unsafe, "dm3", true).isEmpty());
    for (String name : unsafe)
      assertTrue(DemoCatalog.resolveAlias(name.replace(".dm_68", ".dm3"), unsafe).isEmpty());
    assertEquals(
        List.of("a-b_(1).dm3"),
        DemoCatalog.files(List.of("a-b_(1).dm_68"), List.of(), "dm3", true));
  }

  @Test
  void outputsAreSortedAfterAliasTransformationAndKeepLongKnownNames() {
    String longName = "x".repeat(200) + ".dm_68";
    assertEquals(
        List.of("a.dm3", "a.dm5.dm3", "x".repeat(200) + ".dm3"),
        DemoCatalog.files(List.of(longName, "a.dm5.dm_68", "a.dm_68"), List.of(), "dm3", true));
    assertEquals(
        longName,
        DemoCatalog.resolveAlias("X".repeat(200) + ".DM3", List.of(longName)).orElseThrow());
    assertTrue(
        DemoCatalog.files(List.of("x".repeat(256) + ".dm_68"), List.of(), ".dm_68", false)
            .isEmpty());
  }

  @Test
  void excessiveCatalogWorkFailsBeforeProducingPartialResults() {
    var tooMany = Collections.nCopies(DemoCatalog.MAX_ENTRIES + 1, "x.dm_68");
    assertThrows(
        IllegalArgumentException.class, () -> DemoCatalog.files(tooMany, List.of(), "dm3", true));
    assertThrows(IllegalArgumentException.class, () -> DemoCatalog.resolveAlias("x.dm3", tooMany));
  }
}
