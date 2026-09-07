package dev.bluevista.craftq3.fabric.building;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildingWorldLoaderTest {
  @TempDir Path directory;

  private void pack(String name, Map<String, byte[]> files) throws IOException {
    var path = directory.resolve("baseq3").resolve(name);
    Files.createDirectories(path.getParent());
    try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
      for (var entry : files.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    }
  }

  @Test
  void savedIdentitySelectsOriginalShadowedBspWithoutChangingNormalReads() throws IOException {
    var original = new byte[] {1, 2, 3};
    var replacement = new byte[] {4, 5, 6};
    pack("pak0.pk3", Map.of("maps/test.bsp", original));
    pack("zz.pk3", Map.of("maps/test.bsp", replacement));
    try (var fs = Pk3FileSystem.mount(directory, "baseq3")) {
      var path = new VirtualPath("maps/test.bsp");
      assertArrayEquals(
          original, BuildingWorldLoader.find(fs, path, BuildingWorldLoader.hash(original)).bytes());
      assertArrayEquals(replacement, fs.read(path));
      assertThrows(IOException.class, () -> BuildingWorldLoader.find(fs, path, "0".repeat(64)));
    }
  }

  @Test
  void legacyIndexCanRecoverMultipleMapNamesAndShadowedVersions() throws IOException {
    var first = new byte[] {1};
    var second = new byte[] {2};
    var replacement = new byte[] {3};
    pack("pak0.pk3", Map.of("maps/old.bsp", first, "maps/second.bsp", second));
    pack("zz.pk3", Map.of("maps/old.bsp", replacement));
    var a = BuildingWorldLoader.hash(first);
    var b = BuildingWorldLoader.hash(second);
    try (var fs = Pk3FileSystem.mount(directory, "baseq3")) {
      var found = BuildingWorldLoader.discover(fs, Set.of(a, b));
      assertEquals("maps/old.bsp", found.get(a).path().value());
      assertEquals("maps/second.bsp", found.get(b).path().value());
      assertTrue(found.get(a).source().container().endsWith("pak0.pk3"));
      assertThrows(
          IOException.class, () -> BuildingWorldLoader.discover(fs, Set.of(a, "f".repeat(64))));
    }
  }

  @Test
  void emptyOrMissingSourceCannotStandInForSavedGeometry() throws IOException {
    Files.createDirectories(directory.resolve("baseq3"));
    try (var fs = Pk3FileSystem.mount(directory, "baseq3")) {
      assertThrows(
          IOException.class,
          () -> BuildingWorldLoader.find(fs, new VirtualPath("maps/missing.bsp"), "a".repeat(64)));
    }
  }
}
