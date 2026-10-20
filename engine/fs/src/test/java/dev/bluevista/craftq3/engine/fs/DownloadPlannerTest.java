package dev.bluevista.craftq3.engine.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DownloadPlannerTest {
  private static DownloadCache.Entry cached(String name, int checksum) {
    return new DownloadCache.Entry(new DownloadCache.Request(name, checksum), Path.of(name));
  }

  @Test
  void installedPacksNeedNothingAndCachedPacksAreReused() throws Exception {
    var cache = List.of(cached("baseq3/map.pk3", 7), cached("other/map.pk3", 8));
    var plan = DownloadPlanner.plan("mymod", "5 7", "baseq3/pak0 baseq3/map", Set.of(5), cache);
    assertTrue(plan.complete());
    assertEquals(List.of(cache.getFirst()), plan.fromCache());
  }

  @Test
  void missingPacksBecomeDeduplicatedDownloadsInServerOrder() throws Exception {
    var plan =
        DownloadPlanner.plan(
            "mymod",
            " 11 -12 11 5 ",
            "mymod/zz_new baseq3/maps baseq3/zz_new baseq3/pak0",
            Set.of(5),
            List.of(cached("other/x.pk3", -12)));
    assertFalse(plan.complete());
    assertEquals(
        List.of("mymod/zz_new.pk3", "baseq3/maps.pk3"),
        plan.downloads().stream().map(DownloadCache.Request::remoteName).toList());
    assertEquals(-12, plan.downloads().get(1).checksum());
    assertTrue(plan.fromCache().isEmpty());
  }

  @Test
  void rejectsMalformedHostileOrIncompleteAnnouncements() {
    assertThrows(
        IOException.class, () -> DownloadPlanner.plan("baseq3", "x", "", Set.of(), List.of()));
    assertThrows(
        IOException.class,
        () -> DownloadPlanner.plan("baseq3", "1 2", "baseq3/a", Set.of(), List.of()));
    assertThrows(
        IOException.class,
        () -> DownloadPlanner.plan("baseq3", "1", "othermod/a", Set.of(), List.of()));
    assertThrows(
        IOException.class,
        () -> DownloadPlanner.plan("baseq3", "1", "baseq3/pak3", Set.of(), List.of()));
    assertThrows(
        IOException.class,
        () -> DownloadPlanner.plan("baseq3", "1", "../../x", Set.of(), List.of()));
    String many = "1 ".repeat(DownloadPlanner.MAX_REFERENCED + 1);
    assertThrows(
        IOException.class, () -> DownloadPlanner.plan("baseq3", many, "", Set.of(1), List.of()));
    var sums = new StringBuilder();
    var names = new StringBuilder();
    for (int i = 0; i <= DownloadPlanner.MAX_MISSING; i++) {
      sums.append(i).append(' ');
      names.append("baseq3/m").append(i).append(' ');
    }
    assertThrows(
        IOException.class,
        () ->
            DownloadPlanner.plan("baseq3", sums.toString(), names.toString(), Set.of(), List.of()));
  }

  @Test
  void emptyAnnouncementsPlanNothing() throws Exception {
    assertTrue(DownloadPlanner.plan("baseq3", null, null, Set.of(), List.of()).complete());
    assertTrue(DownloadPlanner.plan("baseq3", "  ", "", Set.of(), List.of()).complete());
  }
}
