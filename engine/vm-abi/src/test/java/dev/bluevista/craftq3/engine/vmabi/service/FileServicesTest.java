package dev.bluevista.craftq3.engine.vmabi.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.fs.HomeStore;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.TestVm;
import dev.bluevista.craftq3.engine.vmabi.VmModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileServicesTest {
  private static final int PATH = 0x100, HANDLE = 0x200, EXT = 0x300, BUFFER = 0x1000;

  @TempDir Path temp;
  private final QvmInstance vm = TestVm.instance();
  private final QvmMemory memory = vm.memory();
  private Log.Sink previous;

  @BeforeEach
  void quietLog() {
    previous = Log.install((name, level, message, error) -> {});
  }

  @AfterEach
  void restoreLog() {
    Log.install(previous);
  }

  private static int[] args(int... values) {
    int[] padded = new int[16];
    System.arraycopy(values, 0, padded, 0, values.length);
    return padded;
  }

  private void write(Path file, String text) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.ISO_8859_1);
  }

  private List<String> names(int count, int address) {
    List<String> result = new java.util.ArrayList<>();
    int offset = 0;
    for (int i = 0; i < count; i++) {
      String name = memory.readString(address + offset, 4096);
      result.add(name);
      offset += name.length() + 1;
    }
    return result;
  }

  @Test
  void opensReadsSeeksAndWritesThroughTheFileSystem() throws IOException {
    Path install = temp.resolve("install");
    write(install.resolve("baseq3/scripts/arena.txt"), "0123456789");
    write(install.resolve("baseq3/scripts/bots.txt"), "bots");
    write(install.resolve("baseq3/scripts/sub/deep.txt"), "x");
    HomeStore home = HomeStore.open(temp.resolve("home/baseq3"));
    try (SearchPath search = SearchPath.mount(install, "baseq3")) {
      FsVmFiles files = FsVmFiles.of(search, home, install);
      FileServices services = new FileServices(VmModule.QAGAME, files);

      memory.writeString(PATH, "scripts/ARENA.txt", 64);
      assertEquals(10, services.open(vm, args(PATH, 0, 0)), "length probe");
      assertEquals(10, services.open(vm, args(PATH, HANDLE, 0)));
      int handle = memory.readInt(HANDLE);
      assertTrue(handle > 0);
      assertEquals(4, services.read(vm, args(BUFFER, 4, handle)));
      assertEquals("0123", new String(memory.readBytes(BUFFER, 4), StandardCharsets.ISO_8859_1));
      assertEquals(0, services.seek(vm, args(handle, -2, 1)));
      assertEquals(2, services.read(vm, args(BUFFER, 8, handle)));
      assertEquals("89", new String(memory.readBytes(BUFFER, 2), StandardCharsets.ISO_8859_1));
      assertEquals(-1, services.seek(vm, args(handle, 50, 2)));
      services.close(vm, args(handle));
      assertThrows(UncheckedIOException.class, () -> services.read(vm, args(BUFFER, 1, handle)));

      memory.writeString(PATH, "missing.cfg", 64);
      memory.writeInt(HANDLE, 77);
      assertEquals(-1, services.open(vm, args(PATH, HANDLE, 0)));
      assertEquals(0, memory.readInt(HANDLE));
      memory.writeString(PATH, "../escape.cfg", 64);
      assertEquals(-1, services.open(vm, args(PATH, HANDLE, 0)));
      assertEquals(-1, services.open(vm, args(PATH, 0, 0)));

      memory.writeString(PATH, "saved.cfg", 64);
      assertEquals(0, services.open(vm, args(PATH, HANDLE, 1)));
      int out = memory.readInt(HANDLE);
      memory.writeString(BUFFER, "seta x 1\n", 64);
      assertEquals(9, services.write(vm, args(BUFFER, 9, out)));
      services.close(vm, args(out));
      assertEquals("seta x 1\n", Files.readString(temp.resolve("home/baseq3/saved.cfg")));
      assertEquals(9, services.open(vm, args(PATH, 0, 0)));

      memory.writeString(PATH, "scripts", 64);
      memory.writeString(EXT, ".txt", 64);
      int count = services.list(vm, args(PATH, EXT, BUFFER, 4096));
      assertEquals(
          List.of("arena.txt", "bots.txt"), names(count, BUFFER).stream().sorted().toList());
      memory.writeString(EXT, "/", 64);
      count = services.list(vm, args(PATH, EXT, BUFFER, 4096));
      assertEquals(List.of("sub/"), names(count, BUFFER), "directories keep a trailing slash");
      memory.writeString(PATH, "", 64);
      memory.writeString(EXT, "cfg", 64);
      count = services.list(vm, args(PATH, EXT, BUFFER, 4096));
      assertEquals(List.of("saved.cfg"), names(count, BUFFER));
      assertEquals(0, files.openCount());
      assertThrows(IllegalArgumentException.class, () -> services.open(vm, args(PATH, HANDLE, 4)));
      files.closeAll();
    } finally {
      home.close();
    }
  }

  /** Fails every handle operation, so only the handle-0 short cut can succeed. */
  private static VmFiles noHandles(List<String> directories) {
    return new VmFiles() {
      public int length(String path) {
        return -1;
      }

      public Opened open(String path, Mode mode) throws IOException {
        throw new IOException("none");
      }

      public byte[] read(int handle, int length) throws IOException {
        throw new IOException("bad handle " + handle);
      }

      public int write(int handle, byte[] data) throws IOException {
        throw new IOException("bad handle " + handle);
      }

      public boolean seek(int handle, int offset, Origin origin) throws IOException {
        throw new IOException("bad handle " + handle);
      }

      public void close(int handle) throws IOException {
        throw new IOException("bad handle " + handle);
      }

      public List<String> list(String directory, String extension) {
        return "/".equals(extension) ? directories : List.of("file.cfg");
      }
    };
  }

  @Test
  void handleZeroReadsWritesAndClosesNothing() {
    // CPMA passes the 0 a failed open stored; the original returns 0 without touching the buffer.
    FileServices services = new FileServices(VmModule.QAGAME, noHandles(List.of()));
    assertEquals(0, services.read(vm, args(BUFFER, 4, 0)));
    assertEquals(0, services.read(vm, args(-1, 4, 0)), "the buffer is not resolved");
    assertEquals(0, services.write(vm, args(BUFFER, 4, 0)));
    assertEquals(0, services.close(vm, args(0)));
    assertThrows(UncheckedIOException.class, () -> services.read(vm, args(BUFFER, 4, 3)));
    assertThrows(UncheckedIOException.class, () -> services.write(vm, args(BUFFER, 4, 3)));
    assertThrows(UncheckedIOException.class, () -> services.close(vm, args(3)));
  }

  @Test
  void directoryListingsNameEachSubdirectoryWithATrailingSlash() {
    // Combat's ui strips the last character of each "/" listing entry without checking it.
    FileServices services = new FileServices(VmModule.UI, noHandles(List.of("baseq3", "combat")));
    memory.writeString(PATH, "", 64);
    memory.writeString(EXT, "/", 64);
    assertEquals(2, services.list(vm, args(PATH, EXT, BUFFER, 4096)));
    assertEquals(List.of("baseq3/", "combat/"), names(2, BUFFER));
    // The slash counts toward the original packing bound: "baseq3/" and its NUL plus the spare.
    assertEquals(0, services.list(vm, args(PATH, EXT, BUFFER, 9)));
    assertEquals(1, services.list(vm, args(PATH, EXT, BUFFER, 10)));
    memory.writeString(EXT, "cfg", 64);
    assertEquals(1, services.list(vm, args(PATH, EXT, BUFFER, 4096)));
    assertEquals(List.of("file.cfg"), names(1, BUFFER), "other listings are unchanged");
  }

  @Test
  void fileListPackingReservesTheOriginalSpareByte() {
    List<String> names = List.of("abc", "de");
    // "abc\0" needs 4 bytes plus the spare: a 5-byte buffer is too small, 6 fits one name.
    assertEquals(0, FileServices.encodeNames(memory, BUFFER, 5, names));
    assertEquals(1, FileServices.encodeNames(memory, BUFFER, 6, names));
    assertEquals(1, FileServices.encodeNames(memory, BUFFER, 8, names));
    assertEquals(2, FileServices.encodeNames(memory, BUFFER, 9, names));
    assertEquals(List.of("abc", "de"), names(2, BUFFER));
    assertEquals(0, FileServices.encodeNames(memory, BUFFER, 0, names));
  }

  @Test
  void modListNamesGameDirectoriesWithPacks() throws IOException {
    Path install = temp.resolve("q3");
    write(install.resolve("baseq3/pak0.pk3"), "zip");
    write(install.resolve("cpma/z-cpma-pak146.pk3"), "zip");
    write(install.resolve("cpma/description.txt"), "Challenge ProMode Arena\r\nsecond line");
    write(install.resolve("Alternate Fire/z-af.PK3"), "zip");
    write(install.resolve("empty/readme.txt"), "no packs");
    write(install.resolve(".hidden/a.pk3"), "zip");
    var mods = new ModList(List.of(install)).scan();
    assertEquals(
        List.of(
            new VmFiles.ModInfo("Alternate Fire", "Alternate Fire"),
            new VmFiles.ModInfo("cpma", "Challenge ProMode Arena")),
        mods);

    VmFiles stub =
        new VmFiles() {
          public int length(String path) {
            return -1;
          }

          public Opened open(String path, Mode mode) throws IOException {
            throw new IOException("none");
          }

          public byte[] read(int handle, int length) {
            return new byte[0];
          }

          public int write(int handle, byte[] data) {
            return 0;
          }

          public boolean seek(int handle, int offset, Origin origin) {
            return false;
          }

          public void close(int handle) {}

          public List<String> list(String directory, String extension) {
            return List.of();
          }

          @Override
          public List<ModInfo> mods() {
            return mods;
          }
        };
    FileServices services = new FileServices(VmModule.UI, stub);
    memory.writeString(PATH, "$modlist", 64);
    memory.writeString(EXT, "", 64);
    assertEquals(2, services.list(vm, args(PATH, EXT, BUFFER, 4096)));
    assertEquals(
        List.of("Alternate Fire", "Alternate Fire", "cpma", "Challenge ProMode Arena"),
        names(4, BUFFER));
    // Each entry needs both strings, their terminators and two spare bytes.
    int first = "Alternate Fire".length() * 2 + 2;
    assertEquals(0, services.list(vm, args(PATH, EXT, BUFFER, first + 2)));
    assertEquals(1, services.list(vm, args(PATH, EXT, BUFFER, first + 3)));
  }
}
