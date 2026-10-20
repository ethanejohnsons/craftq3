package dev.bluevista.craftq3.engine.client.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import dev.bluevista.craftq3.engine.console.CvarTable;
import dev.bluevista.craftq3.engine.fs.DownloadCache;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.delta.SnapshotDeltaCodec;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The download policy: without {@code cl_allowDownload} missing packs only warn (as the original),
 * with it a missing custom pack is requested, and official packs are never downloaded.
 */
final class DownloadManagerTest {
  @TempDir Path install;
  @TempDir Path cacheDir;

  private final List<String> sent = new ArrayList<>();
  private final List<String> printed = new ArrayList<>();
  private int begins;

  private CvarTable cvars(int allow) {
    ThreadOwner owner = new ThreadOwner("test");
    owner.claim();
    CvarTable cvars = new CvarTable(owner);
    cvars.register("cl_allowDownload", Integer.toString(allow), 0);
    for (String name : List.of("cl_downloadName", "cl_downloadSize", "cl_downloadCount"))
      cvars.register(name, "", 0);
    cvars.register("cl_downloadTime", "0", 0);
    return cvars;
  }

  private static ServerMessageCodec.GameState gameState(String paks, String names) {
    return new ServerMessageCodec.GameState(
        0,
        Map.of(
            Protocol68.CS_SYSTEMINFO,
            "\\sv_referencedPaks\\" + paks + "\\sv_referencedPakNames\\" + names),
        SnapshotDeltaCodec.Baselines.EMPTY,
        0,
        0);
  }

  private DownloadManager manager(SearchPath path, DownloadCache cache, CvarTable cvars) {
    DownloadManager.Content content =
        new DownloadManager.Content() {
          @Override
          public SearchPath searchPath() {
            return path;
          }

          @Override
          public void mount(List<DownloadCache.Entry> archives) {
            fail("nothing is cached");
          }
        };
    return new DownloadManager(content, cache, cvars, sent::add, () -> begins++, printed::add);
  }

  @Test
  void missingPacksOnlyWarnWithoutPermission() throws Exception {
    Files.createDirectories(install.resolve("baseq3"));
    try (SearchPath path = SearchPath.mount(install, "baseq3");
        DownloadCache cache = DownloadCache.open(cacheDir)) {
      DownloadManager downloads = manager(path, cache, cvars(0));
      assertEquals(
          DownloadManager.Outcome.READY, downloads.plan(gameState("123", "baseq3/custom"), 0));
      assertTrue(printed.getFirst().contains("baseq3/custom.pk3"), "" + printed);
      assertTrue(sent.isEmpty());
      printed.clear();
      // An official pack the client lacks is a warning too while downloads are off.
      assertEquals(
          DownloadManager.Outcome.READY, downloads.plan(gameState("456", "baseq3/pak8"), 0));
      assertFalse(printed.isEmpty());
    }
  }

  @Test
  void aMissingCustomPackIsRequestedAndOfficialOnesNever() throws Exception {
    Files.createDirectories(install.resolve("baseq3"));
    try (SearchPath path = SearchPath.mount(install, "baseq3");
        DownloadCache cache = DownloadCache.open(cacheDir)) {
      DownloadManager downloads = manager(path, cache, cvars(1));
      assertEquals(
          DownloadManager.Outcome.DOWNLOADING,
          downloads.plan(gameState("123", "baseq3/custom"), 0));
      assertEquals(List.of("download baseq3/custom.pk3"), sent);
      assertEquals(1, begins);
      assertTrue(downloads.active());
      downloads.close();
      sent.clear();
      IOException refused =
          assertThrows(IOException.class, () -> downloads.plan(gameState("456", "baseq3/pak8"), 0));
      assertTrue(refused.getMessage().contains("pak8"), refused.getMessage());
      assertTrue(sent.isEmpty(), "an official pack is never requested");
    }
  }

  @Test
  void noUdpBitForbidsDownloadingFromTheGameServer() throws Exception {
    Files.createDirectories(install.resolve("baseq3"));
    try (SearchPath path = SearchPath.mount(install, "baseq3");
        DownloadCache cache = DownloadCache.open(cacheDir)) {
      DownloadManager downloads =
          manager(path, cache, cvars(DownloadManager.ALLOW | DownloadManager.NO_UDP));
      assertFalse(downloads.allowed());
      assertEquals(
          DownloadManager.Outcome.READY, downloads.plan(gameState("123", "baseq3/custom"), 0));
      assertTrue(sent.isEmpty());
    }
  }
}
