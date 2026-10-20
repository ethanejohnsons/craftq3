package dev.bluevista.craftq3.engine.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ports the legacy GameFileStore and DemoFileStore suites onto the one portable store. */
class HomeStoreTest {
  @TempDir Path temporary;

  private static VirtualPath path(String name) {
    return VirtualPath.of(name);
  }

  private static VirtualPath demo(String name) {
    return VirtualPath.of("demos/" + name + ".dm_68");
  }

  private static void save(HomeStore store, VirtualPath path, byte[] bytes) throws IOException {
    try (var write = store.openAtomicWrite(path)) {
      write.write(bytes);
      write.commit();
    }
  }

  private static byte[] load(HomeStore store, VirtualPath path) throws IOException {
    try (var read = store.openRead(path).orElseThrow()) {
      return read.readAllBytes();
    }
  }

  private static long fileCount(Path directory) throws IOException {
    try (var files = Files.list(directory)) {
      return files.count();
    }
  }

  // ---- whole-file writes (legacy GameFileStore) ----

  @Test
  void writesCreateParentsReplaceAtomicallyAndReadBack() throws Exception {
    try (var store = HomeStore.open(temporary.resolve("game"))) {
      store.write(path("q3config.cfg"), "first".getBytes(StandardCharsets.US_ASCII));
      store.write(path("Screenshots\\Shot0001.TGA"), new byte[] {1, 2});
      store.write(path("q3config.cfg"), "second".getBytes(StandardCharsets.US_ASCII));
      assertEquals("second", store.readText(path("Q3CONFIG.CFG")));
      assertArrayEquals(new byte[] {1, 2}, store.read(path("screenshots/shot0001.tga")));
      assertTrue(Files.isRegularFile(temporary.resolve("game/screenshots/shot0001.tga")));
      assertEquals(new HomeStore.Usage(8, 2, 1), store.usage());
      assertTrue(store.readIfPresent(path("missing.cfg")).isEmpty());
      assertThrows(NoSuchFileException.class, () -> store.read(path("missing/file.cfg")));
      assertEquals(List.of("q3config.cfg"), store.list("", ".cfg"));
      try (var files = Files.list(temporary.resolve("game"))) {
        assertTrue(files.noneMatch(f -> f.getFileName().toString().endsWith(".tmp")));
      }
    }
  }

  @Test
  void existingFilesAreFoundAndReplacedCaseInsensitively() throws Exception {
    Path root = temporary.resolve("game");
    Files.createDirectories(root.resolve("Configs"));
    Files.writeString(root.resolve("Configs/Autoexec.CFG"), "user");
    try (var store = HomeStore.open(root)) {
      assertEquals("user", store.readText(path("configs/autoexec.cfg")));
      store.write(path("CONFIGS/AUTOEXEC.CFG"), "game".getBytes(StandardCharsets.US_ASCII));
      assertEquals("game", Files.readString(root.resolve("Configs/Autoexec.CFG")));
      assertEquals(1, fileCount(root.resolve("Configs")));
    }
  }

  @Test
  void quotasCountTheWholeRootAndAllowReplacement() throws Exception {
    Path root = temporary.resolve("quota");
    Files.createDirectories(root.resolve("demos"));
    try (var store = HomeStore.open(root, new HomeStore.Limits(8, 10, 2, 1, 1))) {
      store.write(path("one.cfg"), new byte[6]);
      store.write(path("demos/two.dm_68"), new byte[4]);
      assertEquals(new HomeStore.Usage(10, 2, 1), store.usage());
      assertThrows(IOException.class, () -> store.write(path("one.cfg"), new byte[7]));
      assertThrows(IOException.class, () -> store.write(path("third.cfg"), new byte[0]));
      store.write(path("one.cfg"), new byte[2]);
      assertEquals(new HomeStore.Usage(6, 2, 1), store.usage());
      assertEquals(2, store.read(path("one.cfg")).length);
      assertTrue(store.readIfPresent(path("missing/file.cfg")).isEmpty());
      // Creating a parent would exceed the one-directory quota.
      assertThrows(IOException.class, () -> store.write(path("missing/file.cfg"), new byte[1]));
      assertFalse(Files.exists(root.resolve("missing")));
      assertThrows(IOException.class, () -> store.write(path("one.cfg"), new byte[9]));
      assertThrows(IOException.class, () -> store.write(path("a/b/deep.cfg"), new byte[1]));
    }
    // A home that is already over quota still opens; it just cannot grow.
    try (var store = HomeStore.open(root, new HomeStore.Limits(8, 10, 2, 0, 1))) {
      assertEquals(2, store.read(path("one.cfg")).length);
      assertThrows(IOException.class, () -> store.write(path("x/y.cfg"), new byte[1]));
    }
    assertThrows(IllegalArgumentException.class, () -> new HomeStore.Limits(0, 8, 2, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new HomeStore.Limits(4, 3, 2, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new HomeStore.Limits(4, 8, 2, 1, 65));
    assertEquals(16 * 1024 * 1024, HomeStore.DEFAULT_LIMITS.maxFileBytes());
    assertEquals(256L * 1024 * 1024, HomeStore.DEFAULT_LIMITS.maxTotalBytes());
  }

  @Test
  void executablePackAndNonPortableNamesAreRefused() throws Exception {
    try (var store = HomeStore.open(temporary.resolve("game"))) {
      for (String name :
          List.of(
              "vm/qagame.qvm",
              "zz.PK3",
              "tool.exe",
              "lib.dll",
              "lib.so",
              "lib.dylib",
              "mod.jar",
              "x.class",
              "con.cfg",
              "LPT1",
              "dir./x.cfg",
              "trailing .cfg ",
              "what?.cfg",
              "pipe|.cfg",
              ".craftq3-0.tmp"))
        assertThrows(IOException.class, () -> store.write(path(name), new byte[1]), name);
      assertEquals(new HomeStore.Usage(0, 0, 0), store.usage());
    }
  }

  @Test
  void traversalSpellingsNeverReachTheStore() {
    for (String name : List.of("../escape.cfg", "a/../../escape.cfg", "/etc/passwd", "C:\\x.cfg"))
      assertThrows(IllegalArgumentException.class, () -> VirtualPath.of(name), name);
  }

  @Test
  void symlinkedDirectoriesAndFilesAreNeverFollowed() throws Exception {
    Path root = temporary.resolve("game"), outside = temporary.resolve("outside");
    Files.createDirectories(outside);
    Files.writeString(outside.resolve("important"), "keep");
    try (var store = HomeStore.open(root)) {
      if (!Links.create(root.resolve("escape"), outside)) return;
      Files.createSymbolicLink(root.resolve("alias"), outside.resolve("important"));
      assertThrows(IOException.class, () -> store.write(path("escape/file.cfg"), new byte[1]));
      assertThrows(IOException.class, () -> store.write(path("escape/important"), new byte[1]));
      assertThrows(IOException.class, () -> store.read(path("escape/important")));
      assertThrows(IOException.class, () -> store.write(path("alias"), new byte[1]));
      assertThrows(IOException.class, () -> store.read(path("alias")));
      assertThrows(IOException.class, () -> store.openAtomicWrite(path("alias")));
      assertFalse(store.exists(path("alias")));
      assertEquals("keep", Files.readString(outside.resolve("important")));
      assertFalse(Files.exists(outside.resolve("file.cfg")));
    }
  }

  @Test
  void aRootReplacedByALinkFailsClosed() throws Exception {
    Path root = temporary.resolve("game"),
        moved = temporary.resolve("renamed"),
        outside = temporary.resolve("outside");
    Files.createDirectory(outside);
    var store = HomeStore.open(root);
    try (store) {
      store.write(path("config.cfg"), "first".getBytes(StandardCharsets.US_ASCII));
      Files.move(root, moved);
      if (!Links.create(root, outside)) return;
      assertThrows(
          IOException.class,
          () -> store.write(path("config.cfg"), "second".getBytes(StandardCharsets.US_ASCII)));
      assertThrows(IOException.class, () -> store.read(path("config.cfg")));
      assertEquals("first", Files.readString(moved.resolve("config.cfg")));
      assertFalse(Files.exists(outside.resolve("config.cfg")));
    }
    assertThrows(IOException.class, () -> store.read(path("config.cfg")));
    assertThrows(IOException.class, () -> store.write(path("config.cfg"), new byte[0]));
  }

  @Test
  void aParentSwappedForALinkAfterCreationFailsClosed() throws Exception {
    Path root = temporary.resolve("game"), outside = temporary.resolve("outside");
    Files.createDirectory(outside);
    try (var store = HomeStore.open(root)) {
      store.write(path("demos/a.dm_68"), new byte[] {1});
      Files.move(root.resolve("demos"), temporary.resolve("old-demos"));
      if (!Links.create(root.resolve("demos"), outside)) return;
      assertThrows(IOException.class, () -> store.write(path("demos/b.dm_68"), new byte[1]));
      assertThrows(IOException.class, () -> store.openAtomicWrite(path("demos/b.dm_68")));
      assertEquals(0, fileCount(outside));
    }
  }

  @Test
  void otherFileSystemProvidersFailClosed() throws Exception {
    Path archive = temporary.resolve("unsupported.zip");
    try (var fs = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
      assertThrows(IOException.class, () -> HomeStore.open(fs.getPath("/saves")));
      assertFalse(Files.exists(fs.getPath("/saves")));
    }
  }

  // ---- streaming writes and reads (legacy DemoFileStore) ----

  @Test
  void streamsBeyondTheBufferedLimitWithoutHoldingTheRecording() throws Exception {
    var limits = new HomeStore.Limits(32L * 1024 * 1024, 64L * 1024 * 1024, 16, 4, 4);
    try (var store = HomeStore.open(temporary.resolve("game"), limits)) {
      byte[] chunk = new byte[65536];
      Arrays.fill(chunk, (byte) 91);
      try (var write = store.openAtomicWrite(demo("large"))) {
        for (int i = 0; i < 257; i++) write.write(chunk);
        assertEquals(257L * chunk.length, write.bytesWritten());
        assertTrue(store.list("demos", ".dm_68").isEmpty());
        write.commit();
        write.commit();
        assertTrue(write.committed());
      }
      long readBytes = 0;
      byte[] expected = chunk.clone();
      try (var read = store.openRead(demo("large")).orElseThrow()) {
        for (int count; (count = read.read(chunk)) >= 0; ) {
          assertArrayEquals(expected, chunk);
          readBytes += count;
        }
      }
      assertEquals(257L * chunk.length, readBytes);
      assertEquals(List.of("large.dm_68"), store.list("Demos", "DM_68"));
    }
  }

  @Test
  void closeAndQuotaFailureAbortWithoutReplacingExistingData() throws Exception {
    Path root = temporary.resolve("game");
    try (var store = HomeStore.open(root, new HomeStore.Limits(16, 32, 2, 1, 1))) {
      save(store, demo("a"), new byte[] {1, 2, 3});
      try (var write = store.openAtomicWrite(demo("a"))) {
        write.write(new byte[] {4, 5});
      }
      assertArrayEquals(new byte[] {1, 2, 3}, load(store, demo("a")));
      try (var write = store.openAtomicWrite(demo("a"))) {
        write.write(new byte[8]);
        assertThrows(IOException.class, () -> write.write(new byte[9]));
        assertEquals(8, write.bytesWritten());
        assertThrows(IOException.class, write::commit);
      }
      assertArrayEquals(new byte[] {1, 2, 3}, load(store, demo("a")));
      assertEquals(1, fileCount(root.resolve("demos")));
    }
  }

  @Test
  void replacementAllowanceTracksLargerSmallerAndTotalFileQuotas() throws Exception {
    try (var store =
        HomeStore.open(temporary.resolve("game"), new HomeStore.Limits(16, 20, 2, 1, 1))) {
      save(store, demo("a"), new byte[12]);
      save(store, demo("b"), new byte[8]);
      assertThrows(IOException.class, () -> store.openAtomicWrite(demo("third")));
      try (var write = store.openAtomicWrite(demo("a"))) {
        assertEquals(12, write.byteLimit());
        assertThrows(IOException.class, () -> store.openAtomicWrite(demo("b")));
        assertThrows(IOException.class, () -> write.write(new byte[13]));
      }
      save(store, demo("a"), new byte[4]);
      try (var write = store.openAtomicWrite(demo("a"))) {
        assertEquals(12, write.byteLimit());
        write.write(new byte[12]);
        write.commit();
      }
      assertEquals(12, load(store, demo("a")).length);
      assertEquals(8, load(store, demo("b")).length);
      assertEquals(List.of("a.dm_68", "b.dm_68"), store.list("demos", ".dm_68"));
      assertEquals(new HomeStore.Usage(20, 2, 1), store.usage());
    }
  }

  @Test
  void anOpenReaderSurvivesReplacementOfItsFile() throws Exception {
    try (var store = HomeStore.open(temporary.resolve("game"))) {
      save(store, demo("a"), new byte[] {1, 2, 3});
      try (var first = store.openRead(demo("a")).orElseThrow()) {
        save(store, demo("a"), new byte[] {4, 5});
        assertArrayEquals(new byte[] {1, 2, 3}, first.readAllBytes());
      }
      assertArrayEquals(new byte[] {4, 5}, load(store, demo("a")));
    }
  }

  @Test
  void changedTargetFailsCommitAndCleansOnlyTheTemporaryFile() throws Exception {
    Path root = temporary.resolve("game");
    Path target = root.resolve("demos/a.dm_68");
    try (var store = HomeStore.open(root)) {
      save(store, demo("a"), new byte[] {1});
      try (var write = store.openAtomicWrite(demo("a"))) {
        write.write(new byte[] {2});
        Files.write(target, new byte[] {8, 9});
        assertThrows(IOException.class, write::commit);
        assertFalse(write.committed());
      }
      assertArrayEquals(new byte[] {8, 9}, Files.readAllBytes(target));
      assertEquals(1, fileCount(root.resolve("demos")));
    }
  }

  @Test
  void storeCloseClosesReadersAndAbortsThePendingWriter() throws Exception {
    Path root = temporary.resolve("game");
    var store = HomeStore.open(root);
    save(store, demo("a"), new byte[] {1});
    var readers = new ArrayList<InputStream>();
    for (int i = 0; i < HomeStore.MAX_READERS; i++)
      readers.add(store.openRead(demo("a")).orElseThrow());
    assertThrows(IOException.class, () -> store.openRead(demo("a")));
    assertArrayEquals(new byte[] {1}, store.read(demo("a")), "whole reads are not tracked");
    readers.removeLast().close();
    readers.add(store.openRead(demo("a")).orElseThrow());
    var write = store.openAtomicWrite(demo("b"));
    write.write(new byte[] {9});
    store.close();
    store.close();
    write.close();
    for (var read : readers) {
      assertThrows(IOException.class, read::read);
      read.close();
    }
    assertThrows(IOException.class, write::commit);
    assertThrows(IOException.class, () -> store.list("demos", ""));
    assertFalse(Files.exists(root.resolve("demos/b.dm_68")));
    assertArrayEquals(new byte[] {1}, Files.readAllBytes(root.resolve("demos/a.dm_68")));
    assertEquals(1, fileCount(root.resolve("demos")));
  }

  @Test
  void readersAreBoundedToTheLengthSeenAtOpenAndOversizedFilesAreRefused() throws Exception {
    Path root = temporary.resolve("game");
    Files.createDirectories(root);
    Files.write(root.resolve("a.dm_68"), new byte[] {1, 2});
    try (var store = HomeStore.open(root, new HomeStore.Limits(4, 8, 2, 1, 1))) {
      assertTrue(store.openRead(path("missing.dm_68")).isEmpty());
      try (var read = store.openRead(path("a.dm_68")).orElseThrow()) {
        Files.write(root.resolve("a.dm_68"), new byte[] {1, 2, 3, 4});
        assertArrayEquals(new byte[] {1, 2}, read.readAllBytes());
      }
      Files.write(root.resolve("a.dm_68"), new byte[5]);
      assertThrows(IOException.class, () -> store.read(path("a.dm_68")));
    }
  }
}
