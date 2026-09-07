package dev.bluevista.craftq3.core.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DemoFileStoreTest {
  @TempDir Path temporary;

  private static VirtualPath path(String name) {
    return new VirtualPath(name + ".dm_68");
  }

  private static void save(DemoFileStore store, String name, byte[] bytes) throws IOException {
    try (var write = store.openAtomicWrite(path(name))) {
      write.write(bytes);
      write.commit();
    }
  }

  private static byte[] load(DemoFileStore store, String name) throws IOException {
    try (var read = store.openRead(path(name)).orElseThrow()) {
      return read.readAllBytes();
    }
  }

  @Test
  void streamsBeyondVmLimitWithoutBufferingTheWholeRecording() throws Exception {
    try (var store = new DemoFileStore(temporary.resolve("demos"))) {
      byte[] chunk = new byte[65536];
      Arrays.fill(chunk, (byte) 91);
      try (var write = store.openAtomicWrite(path("large"))) {
        for (int i = 0; i < 257; i++) write.write(chunk);
        assertEquals(257L * chunk.length, write.bytesWritten());
        assertTrue(store.list().isEmpty());
        write.commit();
        write.commit();
        assertTrue(write.committed());
      }
      long readBytes = 0;
      try (var read = store.openRead(path("large")).orElseThrow()) {
        for (int count; (count = read.read(chunk)) >= 0; ) {
          byte[] expected = new byte[chunk.length];
          Arrays.fill(expected, (byte) 91);
          assertArrayEquals(expected, chunk);
          readBytes += count;
        }
      }
      assertEquals(257L * chunk.length, readBytes);
      assertEquals(List.of(path("large")), store.list());
      assertEquals(16 * 1024 * 1024, GameFileStore.DEFAULT_LIMITS.maxFileBytes());
    }
  }

  @Test
  void closeAndQuotaFailureAbortWithoutReplacingExistingData() throws Exception {
    try (var store = new DemoFileStore(temporary, new DemoFileStore.Limits(16, 32, 2))) {
      save(store, "a", new byte[] {1, 2, 3});
      try (var write = store.openAtomicWrite(path("a"))) {
        write.write(new byte[] {4, 5});
      }
      assertArrayEquals(new byte[] {1, 2, 3}, load(store, "a"));
      try (var write = store.openAtomicWrite(path("a"))) {
        write.write(new byte[8]);
        assertThrows(IOException.class, () -> write.write(new byte[9]));
        assertEquals(8, write.bytesWritten());
        assertThrows(IOException.class, write::commit);
      }
      assertArrayEquals(new byte[] {1, 2, 3}, load(store, "a"));
      try (var files = Files.list(temporary)) {
        assertEquals(1, files.count());
      }
    }
  }

  @Test
  void replacementAllowanceTracksLargerSmallerAndTotalFileQuotas() throws Exception {
    try (var store = new DemoFileStore(temporary, new DemoFileStore.Limits(16, 20, 2))) {
      save(store, "a", new byte[12]);
      save(store, "b", new byte[8]);
      assertThrows(IOException.class, () -> store.openAtomicWrite(path("third")));
      try (var write = store.openAtomicWrite(path("a"))) {
        assertEquals(12, write.byteLimit());
        assertThrows(IOException.class, () -> store.openAtomicWrite(path("b")));
        assertThrows(IOException.class, () -> write.write(new byte[13]));
      }
      save(store, "a", new byte[4]);
      try (var write = store.openAtomicWrite(path("a"))) {
        assertEquals(12, write.byteLimit());
        write.write(new byte[12]);
        write.commit();
      }
      assertEquals(12, load(store, "a").length);
      assertEquals(8, load(store, "b").length);
      assertEquals(List.of(path("a"), path("b")), store.list());
    }
  }

  @Test
  void pinnedRootAndOpenReaderSurvivePathAndTargetReplacement() throws Exception {
    Path root = temporary.resolve("demos"), moved = temporary.resolve("retained");
    try (var store = new DemoFileStore(root)) {
      save(store, "a", new byte[] {1, 2, 3});
      try (var first = store.openRead(path("a")).orElseThrow()) {
        Files.move(root, moved);
        Files.createDirectory(root);
        Files.write(root.resolve("a.dm_68"), new byte[] {9});
        save(store, "a", new byte[] {4, 5});
        assertArrayEquals(new byte[] {1, 2, 3}, first.readAllBytes());
        assertArrayEquals(new byte[] {4, 5}, load(store, "a"));
        assertArrayEquals(new byte[] {9}, Files.readAllBytes(root.resolve("a.dm_68")));
        assertArrayEquals(new byte[] {4, 5}, Files.readAllBytes(moved.resolve("a.dm_68")));
      }
    }
  }

  @Test
  void flatNamesAndSymlinkTargetsCannotEscapeTheDemoRoot() throws Exception {
    Path root = temporary.resolve("demos"), outside = temporary.resolve("outside");
    Files.write(outside, new byte[] {91});
    try (var store = new DemoFileStore(root)) {
      assertThrows(
          IOException.class, () -> store.openAtomicWrite(new VirtualPath("nested/a.dm_68")));
      assertThrows(IOException.class, () -> store.openRead(new VirtualPath("a.pk3")));
      assertThrows(IOException.class, () -> store.openAtomicWrite(new VirtualPath(".dm_68")));
      Files.createSymbolicLink(root.resolve("linked.dm_68"), outside);
      assertThrows(IOException.class, () -> store.openRead(path("linked")));
      assertThrows(IOException.class, () -> store.openAtomicWrite(path("linked")));
      assertArrayEquals(new byte[] {91}, Files.readAllBytes(outside));
    }
  }

  @Test
  void changedTargetFailsCommitAndCleansOnlyTheTemporaryFile() throws Exception {
    Path target = temporary.resolve("a.dm_68");
    try (var store = new DemoFileStore(temporary)) {
      save(store, "a", new byte[] {1});
      try (var write = store.openAtomicWrite(path("a"))) {
        write.write(new byte[] {2});
        Files.write(target, new byte[] {8, 9});
        assertThrows(IOException.class, write::commit);
        assertFalse(write.committed());
      }
      assertArrayEquals(new byte[] {8, 9}, Files.readAllBytes(target));
      try (var files = Files.list(temporary)) {
        assertEquals(1, files.count());
      }
    }
  }

  @Test
  void storeCloseClosesReadersAndAbortsThePendingWriter() throws Exception {
    var store = new DemoFileStore(temporary);
    save(store, "a", new byte[] {1});
    var readers = new ArrayList<InputStream>();
    for (int i = 0; i < 16; i++) readers.add(store.openRead(path("a")).orElseThrow());
    assertThrows(IOException.class, () -> store.openRead(path("a")));
    readers.removeLast().close();
    readers.add(store.openRead(path("a")).orElseThrow());
    var write = store.openAtomicWrite(path("b"));
    write.write(new byte[] {9});
    store.close();
    store.close();
    write.close();
    for (var read : readers) {
      assertThrows(IOException.class, read::read);
      read.close();
    }
    assertThrows(IOException.class, write::commit);
    assertThrows(IOException.class, store::list);
    assertFalse(Files.exists(temporary.resolve("b.dm_68")));
    assertArrayEquals(new byte[] {1}, Files.readAllBytes(temporary.resolve("a.dm_68")));
    try (var files = Files.list(temporary)) {
      assertEquals(1, files.count());
    }
  }

  @Test
  void fileLengthAndStartupQuotasBoundImportedDataAndReaderGrowth() throws Exception {
    Files.write(temporary.resolve("a.dm_68"), new byte[] {1, 2});
    try (var store = new DemoFileStore(temporary, new DemoFileStore.Limits(4, 8, 2))) {
      assertTrue(store.openRead(path("missing")).isEmpty());
      try (var read = store.openRead(path("a")).orElseThrow()) {
        Files.write(temporary.resolve("a.dm_68"), new byte[] {1, 2, 3, 4});
        assertArrayEquals(new byte[] {1, 2}, read.readAllBytes());
      }
    }
    Files.write(temporary.resolve("a.dm_68"), new byte[5]);
    assertThrows(
        IOException.class, () -> new DemoFileStore(temporary, new DemoFileStore.Limits(4, 8, 2)));
    assertThrows(IllegalArgumentException.class, () -> new DemoFileStore.Limits(0, 8, 2));
    assertThrows(IllegalArgumentException.class, () -> new DemoFileStore.Limits(4, 3, 2));
    assertThrows(IllegalArgumentException.class, () -> new DemoFileStore.Limits(4, 8, 129));
  }
}
