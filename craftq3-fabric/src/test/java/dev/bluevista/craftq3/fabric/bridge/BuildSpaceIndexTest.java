package dev.bluevista.craftq3.fabric.bridge;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.fabric.building.BuildSpaceIndex;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildSpaceIndexTest {
  @TempDir Path directory;

  @Test
  void regionLookupHandlesNegativeCoordinatesAndExactMapBoundaries() {
    assertEquals(0, BuildSpaceIndex.region(-2048));
    assertEquals(0, BuildSpaceIndex.region(-1));
    assertEquals(0, BuildSpaceIndex.region(2047.999));
    assertEquals(1, BuildSpaceIndex.region(2048));
    assertEquals(1, BuildSpaceIndex.region(4096));
    assertEquals(4095, BuildSpaceIndex.region(4095.0 * 4096 + 2047.999));
    assertEquals(-1, BuildSpaceIndex.region(-2048.001));
    assertEquals(-1, BuildSpaceIndex.region(4095.0 * 4096 + 2048));
    assertEquals(-1, BuildSpaceIndex.region(Double.NaN));
    assertEquals(-1, BuildSpaceIndex.region(Double.POSITIVE_INFINITY));
  }

  @Test
  void mapIdentitiesKeepTheirSlotsAcrossDiskReloadAndMapChanges() throws Exception {
    var path = directory.resolve("build-maps.properties");
    assertEquals(0, BuildSpaceIndex.slot(path, "a".repeat(64)));
    assertEquals(1, BuildSpaceIndex.slot(path, "b".repeat(64)));
    assertEquals(0, BuildSpaceIndex.slot(path, "a".repeat(64)));
    assertEquals(2, BuildSpaceIndex.slot(path, "c".repeat(64)));
  }

  @Test
  void malformedOrAliasedSavedSlotsCannotOverwriteAnotherMapsBlocks() throws Exception {
    var path = directory.resolve("build-maps.properties");
    Files.writeString(path, "a".repeat(64) + "=0\n" + "b".repeat(64) + "=0\n");
    assertThrows(java.io.IOException.class, () -> BuildSpaceIndex.slot(path, "c".repeat(64)));
    assertThrows(IllegalArgumentException.class, () -> BuildSpaceIndex.slot(path, "../map"));
  }
}
