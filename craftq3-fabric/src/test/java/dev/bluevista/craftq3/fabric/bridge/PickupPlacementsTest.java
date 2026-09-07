package dev.bluevista.craftq3.fabric.bridge;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.fs.*;
import dev.bluevista.craftq3.core.math.Vec3;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PickupPlacementsTest {
  @TempDir Path directory;
  private static final String DIMENSION = "minecraft:overworld";

  @Test
  void persistsIdsAcrossReopenAndSeparatesWorldGameAndDimension() throws Exception {
    var point = new Vec3(-12.25, 65, 8.5);
    var first =
        PickupPlacements.add(directory, "baseq3", DIMENSION, "weapon_rocketlauncher", point);
    var second = PickupPlacements.add(directory, "baseq3", DIMENSION, "item_health_large", point);
    assertEquals(List.of(first, second), PickupPlacements.read(directory, "baseq3", DIMENSION));
    assertTrue(PickupPlacements.read(directory, "baseq3", "minecraft:the_nether").isEmpty());
    assertTrue(PickupPlacements.read(directory, "anothergame", DIMENSION).isEmpty());
    assertTrue(
        PickupPlacements.read(directory.resolve("other-world"), "baseq3", DIMENSION).isEmpty());
    assertFalse(PickupPlacements.remove(directory, "baseq3", DIMENSION, 200));
    assertTrue(PickupPlacements.remove(directory, "baseq3", DIMENSION, first.id()));
    assertEquals(List.of(second), PickupPlacements.read(directory, "baseq3", DIMENSION));
    var replacement = PickupPlacements.add(directory, "baseq3", DIMENSION, "ammo_rockets", point);
    assertEquals(first.id(), replacement.id());
  }

  @Test
  void rejectsMalformedRecordsWithoutChangingTheirBytes() throws Exception {
    try (var files = new GameFileStore(directory)) {
      PickupPlacements.write(files, DIMENSION, List.of());
      Path actual;
      try (var paths = Files.list(directory)) {
        actual = paths.findFirst().orElseThrow();
      }
      var virtual = new VirtualPath(actual.getFileName().toString());
      var valid = Files.readAllBytes(actual);
      var invalid = new ArrayList<byte[]>();
      invalid.add(Arrays.copyOf(valid, 3));
      invalid.add(Arrays.copyOf(valid, valid.length + 1));
      invalid.add(encoded("minecraft:the_nether", 1, "ammo_rockets", 2, false));
      invalid.add(encoded(DIMENSION, 257, "ammo_rockets", 2, false));
      invalid.add(encoded(DIMENSION, 1, "target_kill", 2, false));
      invalid.add(encoded(DIMENSION, 1, "ammo_rockets", Double.NaN, false));
      invalid.add(encoded(DIMENSION, 1, "ammo_rockets", 2, true));
      for (var bytes : invalid) {
        files.write(virtual, bytes);
        assertThrows(IOException.class, () -> PickupPlacements.read(files, DIMENSION));
        assertArrayEquals(bytes, Files.readAllBytes(actual));
      }
    }
  }

  private byte[] encoded(String dimension, int id, String classname, double x, boolean duplicate)
      throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(bytes)) {
      out.writeInt(0x43513350);
      out.writeInt(1);
      out.writeUTF(dimension);
      out.writeInt(duplicate ? 2 : 1);
      for (int i = 0; i < (duplicate ? 2 : 1); i++) {
        out.writeInt(id);
        out.writeUTF(classname);
        out.writeDouble(x);
        out.writeDouble(65);
        out.writeDouble(3);
      }
    }
    return bytes.toByteArray();
  }
}
