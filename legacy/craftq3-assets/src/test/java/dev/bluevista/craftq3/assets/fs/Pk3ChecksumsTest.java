package dev.bluevista.craftq3.assets.fs;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class Pk3ChecksumsTest {
  private static final int[] ABC_XYZ = {0x352441c2, 0xeb8eba67};

  @Test
  void matchesNativeFilesystemNormalAndPureChecksumsForAuthoredEntries() {
    assertEquals(1225466421, Pk3Checksums.normal(ABC_XYZ));
    assertEquals(-388958916, Pk3Checksums.pure(ABC_XYZ, 0));
    assertEquals(442607164, Pk3Checksums.pure(ABC_XYZ, 42));
    assertEquals(-852403165, Pk3Checksums.pure(ABC_XYZ, -1));
    assertEquals(
        1225466421, Pk3Checksums.normal(ABC_XYZ), "Feed use cannot alter the normal checksum");
  }

  @Test
  void preservesCentralDirectoryOrderWithoutSortingCrcValues() {
    int[] reverse = {ABC_XYZ[1], ABC_XYZ[0]};
    assertEquals(1603507484, Pk3Checksums.normal(reverse));
    assertEquals(1191489873, Pk3Checksums.pure(reverse, 0));
    assertEquals(-2106548469, Pk3Checksums.pure(reverse, 42));
    assertEquals(1997913271, Pk3Checksums.pure(reverse, -1));
  }

  @Test
  void preservesEmptyListNativeBoundaryAndStillIncludesZeroOrRepeatedCrcValues() {
    assertEquals(0x9868a6bf, Pk3Checksums.normal(new int[0]));
    assertEquals(1290185885, Pk3Checksums.pure(new int[0], 0));
    assertEquals(-1864024385, Pk3Checksums.pure(new int[0], 42));
    assertEquals(0x4ce6ac9d, Pk3Checksums.normal(new int[] {0}));
    assertEquals(0x0f088652, Pk3Checksums.normal(new int[] {0, 0}));
    assertEquals(0x0f088652, Pk3Checksums.pure(new int[] {0}, 0));
  }

  @Test
  void preservesHighCrcBitsAndLittleEndianSignedFeed() {
    int[] crcs = {0x80000000, 0xffffffff, 0x12345678};
    assertEquals(0x003e5d14, Pk3Checksums.normal(crcs));
    assertEquals(0x23e31c10, Pk3Checksums.pure(crcs, 0));
    assertEquals(0x27ecace4, Pk3Checksums.pure(crcs, 42));
    assertEquals(0x6fd0facf, Pk3Checksums.pure(crcs, -1));
    assertEquals(0xcd6ae5b3, Pk3Checksums.pure(crcs, 0x12345678));
    assertEquals(0xcfced275, Pk3Checksums.pure(crcs, Integer.MIN_VALUE));
  }

  @Test
  void checksBoundsBeforeAllocatingAndNeverChangesCallerMetadata() {
    int[] crcs = ABC_XYZ.clone(), before = crcs.clone();
    Pk3Checksums.normal(crcs);
    Pk3Checksums.pure(crcs, 42);
    assertArrayEquals(before, crcs);
    assertThrows(NullPointerException.class, () -> Pk3Checksums.normal(null));
    int[] overBudget = new int[Pk3Checksums.MAX_ENTRIES + 1];
    assertThrows(IllegalArgumentException.class, () -> Pk3Checksums.normal(overBudget));
    assertThrows(IllegalArgumentException.class, () -> Pk3Checksums.pure(overBudget, 0));
  }
}
