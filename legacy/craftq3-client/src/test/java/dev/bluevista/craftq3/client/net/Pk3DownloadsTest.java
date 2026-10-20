package dev.bluevista.craftq3.client.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.fs.*;
import dev.bluevista.craftq3.client.RemoteSystemInfo;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.Download;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings(
    "try") // Explicit close exercises connection cancellation while the cache stays open.
class Pk3DownloadsTest {
  @TempDir Path root;

  private record Fixture(byte[] bytes, int checksum) {}

  private Fixture fixture() throws Exception {
    Path games = Files.createDirectories(root.resolve("host/baseq3")).getParent();
    Path path = games.resolve("baseq3/custom.pk3");
    try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
      zip.putNextEntry(new ZipEntry("marker"));
      zip.write(new byte[] {1, 2, 3});
      zip.closeEntry();
    }
    try (var fs = Pk3FileSystem.mount(games, null)) {
      return new Fixture(Files.readAllBytes(path), fs.packs().getFirst().checksum());
    }
  }

  private RemoteSystemInfo.Settings info(int checksum) {
    return RemoteSystemInfo.parse(
        "\\sv_serverid\\1\\sv_pure\\1\\sv_referencedPaks\\"
            + checksum
            + "\\sv_referencedPakNames\\baseq3/custom");
  }

  @Test
  void transfersVerifiesMountsAndWaitsForFreshGamestate() throws Exception {
    var f = fixture();
    var commands = new ArrayList<String>();
    var cvars = new CvarSystem();
    int[] begins = {0}, recordingStops = {0};
    Path games = Files.createDirectories(root.resolve("client/baseq3")).getParent();
    try (var fs = Pk3FileSystem.mount(games, null);
        var cache = new DownloadCache(root.resolve("cache"));
        var downloads = new Pk3Downloads(fs, cache, cvars)) {
      cvars.set("cl_allowDownload", "1", CvarSystem.Source.ENGINE);
      assertFalse(
          downloads.prepare(
              1,
              info(f.checksum()),
              commands::add,
              () -> begins[0]++,
              () -> recordingStops[0]++,
              123));
      assertEquals(List.of("download \"baseq3/custom.pk3\""), commands);
      assertEquals(1, recordingStops[0]);
      assertEquals("baseq3/custom.pk3", cvars.string("cl_downloadName"));
      downloads.accept(new Download(0, f.bytes().length, f.bytes(), null), commands::add);
      downloads.accept(new Download(1, null, new byte[0], null), commands::add);
      long deadline = System.nanoTime() + 5_000_000_000L;
      while (downloads.verifying() && System.nanoTime() < deadline) {
        downloads.pump(commands::add, () -> begins[0]++, 123);
        Thread.sleep(1);
      }
      assertFalse(downloads.verifying());
      assertEquals(
          List.of("download \"baseq3/custom.pk3\"", "nextdl 0", "nextdl 1", "donedl"), commands);
      assertFalse(downloads.prepare(1, info(f.checksum()), commands::add, () -> {}, () -> {}, 123));
      assertTrue(downloads.prepare(2, info(f.checksum()), commands::add, () -> {}, () -> {}, 123));
      assertEquals(1, fs.packs().size());
      assertEquals(1, begins[0]);
      cvars.set("cl_allowDownload", "0", CvarSystem.Source.ENGINE);
      assertTrue(downloads.prepare(3, info(f.checksum()), commands::add, () -> {}, () -> {}, 123));
      assertEquals(4, commands.size());
    }
  }

  @Test
  void policyAndUnsolicitedPacketsNeverWriteFiles() throws Exception {
    var f = fixture();
    var commands = new ArrayList<String>();
    Path games = Files.createDirectories(root.resolve("client/baseq3")).getParent();
    try (var fs = Pk3FileSystem.mount(games, null);
        var cache = new DownloadCache(root.resolve("cache"));
        var downloads = new Pk3Downloads(fs, cache, new CvarSystem())) {
      assertThrows(
          IOException.class,
          () -> downloads.prepare(1, info(f.checksum()), commands::add, () -> {}, () -> {}, 0));
      downloads.close();
      downloads.accept(new Download(0, f.bytes().length, f.bytes(), null), commands::add);
      downloads.accept(new Download(0, f.bytes().length, f.bytes(), null), commands::add);
      assertEquals(List.of("stopdl"), commands);
      assertTrue(cache.entries().isEmpty());
    }
  }

  @Test
  void cancellationDuringReceiveClearsUiAndStaging() throws Exception {
    var f = fixture();
    var cvars = new CvarSystem();
    Path games = Files.createDirectories(root.resolve("client/baseq3")).getParent();
    try (var fs = Pk3FileSystem.mount(games, null);
        var cache = new DownloadCache(root.resolve("cache"));
        var downloads = new Pk3Downloads(fs, cache, cvars)) {
      cvars.set("cl_allowDownload", "1", CvarSystem.Source.ENGINE);
      downloads.prepare(1, info(f.checksum()), s -> {}, () -> {}, () -> {}, 0);
      downloads.accept(new Download(0, f.bytes().length, f.bytes(), null), s -> {});
      downloads.close();
      assertFalse(downloads.busy());
      assertEquals("", cvars.string("cl_downloadName"));
      assertTrue(cache.entries().isEmpty());
      try (var files = Files.list(root.resolve("cache"))) {
        assertEquals(1, files.count());
      }
    }
  }
}
