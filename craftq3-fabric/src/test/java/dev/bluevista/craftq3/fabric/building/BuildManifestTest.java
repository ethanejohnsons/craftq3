package dev.bluevista.craftq3.fabric.building;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildManifestTest {
  @TempDir Path directory;

  private BuildManifest.Entry entry(String hash, int slot, double y) {
    return new BuildManifest.Entry(hash.repeat(64), slot, "baseq3", "maps/q3dm17.bsp", y);
  }

  @Test
  void sourceDescriptorsRoundTripAndKeepTheirTransforms() throws IOException {
    var path = directory.resolve("worlds.properties");
    var a = entry("a", 0, 42);
    var b = entry("b", 1, -32);
    BuildManifest.remember(path, a);
    BuildManifest.remember(path, b);
    assertEquals(a, BuildManifest.read(path).get(a.hash()));
    assertEquals(b, BuildManifest.read(path).get(b.hash()));
    var previous = Files.readAllBytes(path);
    assertThrows(IOException.class, () -> BuildManifest.remember(path, entry("a", 0, 43)));
    assertThrows(IOException.class, () -> BuildManifest.remember(path, entry("a", 2, 42)));
    assertThrows(IOException.class, () -> BuildManifest.remember(path, entry("c", 1, 0)));
    assertArrayEquals(previous, Files.readAllBytes(path));
  }

  @Test
  void malformedIncompleteAndOversizedManifestsAreCheckedFailures() throws IOException {
    var path = directory.resolve("worlds.properties");
    for (var content :
        new String[] {
          "version=2\n",
          "version=1\nwrong=2\n",
          "version=1\n" + "a".repeat(64) + ".slot=0\n",
          "version=\\uZZZZ\n",
          "x".repeat(2_097_153)
        }) {
      Files.writeString(path, content);
      assertThrows(IOException.class, () -> BuildManifest.read(path));
      assertEquals(content, Files.readString(path));
    }
  }

  @Test
  void aliasedPersistedSlotsCannotLoadDifferentMapsIntoTheSameRegion() throws IOException {
    var path = directory.resolve("worlds.properties");
    BuildManifest.remember(path, entry("a", 0, 0));
    BuildManifest.remember(path, entry("b", 1, 0));
    var p = new Properties();
    try (var r = Files.newBufferedReader(path)) {
      p.load(r);
    }
    p.setProperty("b".repeat(64) + ".slot", "0");
    try (var w = Files.newBufferedWriter(path)) {
      p.store(w, "bad");
    }
    assertThrows(IOException.class, () -> BuildManifest.read(path));
  }

  @Test
  void descriptorsRejectNonfiniteTransformsAndUnsafeVirtualPaths() {
    assertThrows(IllegalArgumentException.class, () -> entry("a", 0, Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> entry("a", 4096, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BuildManifest.Entry("a".repeat(64), 0, "../baseq3", "maps/test.bsp", 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BuildManifest.Entry("a".repeat(64), 0, "baseq3", "maps/../test.bsp", 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BuildManifest.Entry("a".repeat(64), 0, "baseq3", "vm/qagame.qvm", 0));
  }
}
