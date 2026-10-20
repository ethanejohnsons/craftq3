package dev.bluevista.craftq3.engine.vmabi;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.vm.QvmImage;
import java.util.SortedSet;
import org.junit.jupiter.api.Test;

final class AbiDetectorTest {
  private static QvmImage module(String name, int... imports) {
    TestVm code = new TestVm().name(name);
    for (int number : imports) code.imports(number);
    return code.image();
  }

  private static int[] range(int from, int to, int... extra) {
    int[] values = new int[to - from + 1 + extra.length];
    for (int i = from; i <= to; i++) values[i - from] = i;
    System.arraycopy(extra, 0, values, to - from + 1, extra.length);
    return values;
  }

  @Test
  void scansConstantCallsToNegativeAddresses() {
    SortedSet<Integer> imports = AbiDetector.imports(module("qagame", 0, 3, 3, 106, 576));
    assertEquals(java.util.List.of(0, 3, 106, 576), java.util.List.copyOf(imports));
  }

  @Test
  void retailEaNumbersSelectRetail() {
    var detection = AbiDetector.analyze(module("vm/qagame.qvm", 0, 2, 410, 425, 426), null);
    assertEquals(AbiProfile.RETAIL_1999, detection.profile());
    assertEquals(AbiDetector.Basis.STRUCTURAL, detection.basis());
    assertEquals(java.util.Set.of(425, 426), detection.unknown().get(AbiProfile.Q3_132));
    assertTrue(detection.unknownImports().isEmpty());
  }

  @Test
  void post127ImportsSelect132() {
    var detection = AbiDetector.analyze(module("qagame", 0, 422, 573, 574, 576), null);
    assertEquals(AbiProfile.Q3_132, detection.profile());
    assertTrue(detection.unknownImports().isEmpty());
    assertEquals(AbiProfile.Q3_132, AbiProfile.detect(module("qagame", 0, 43)));
  }

  @Test
  void uiServerBrowserImportsDecide() {
    assertEquals(AbiProfile.RETAIL_1999, AbiProfile.detect(module("ui", range(40, 56))));
    assertEquals(AbiProfile.Q3_132, AbiProfile.detect(module("ui", range(40, 56, 65, 66, 81))));
  }

  @Test
  void completePre127CgameIsRetailButTinyModulesDefaultTo132() {
    var full = AbiDetector.analyze(module("cgame", range(0, 58, 100, 101)), null);
    assertEquals(AbiProfile.RETAIL_1999, full.profile());
    assertEquals(AbiDetector.Basis.STRUCTURAL, full.basis());
    var modern = AbiDetector.analyze(module("cgame", range(0, 58, 71, 111)), null);
    assertEquals(AbiProfile.Q3_132, modern.profile());
    var tiny = AbiDetector.analyze(module("cgame", 0, 1, 2), null);
    assertEquals(AbiProfile.Q3_132, tiny.profile());
    assertEquals(AbiDetector.Basis.DEFAULT, tiny.basis());
  }

  @Test
  void unknownImportsAreReportedPerProfile() {
    var detection = AbiDetector.analyze(module("cgame", 0, 150, 199), null);
    assertEquals(java.util.Set.of(150, 199), detection.unknownImports());
  }

  @Test
  void hashTableRecognisesOnlyTheVerifiedRetailBuilds() {
    assertEquals(
        AbiProfile.RETAIL_1999,
        AbiDetector.verified(
                VmModule.UI, "826a342a108ac8a7fa45f4e752dfa5be50fa5ddf4fdb87d7c404d833c4989627")
            .orElseThrow());
    assertTrue(
        AbiDetector.verified(
                VmModule.CGAME, "826a342a108ac8a7fa45f4e752dfa5be50fa5ddf4fdb87d7c404d833c4989627")
            .isEmpty());
    byte[] bytes = new TestVm().name("qagame").bytes();
    var detection = AbiDetector.analyze(QvmImage.parse("qagame", bytes, TestVm.STACK), bytes);
    assertEquals(AbiDetector.sha256(bytes), detection.sha256());
    assertEquals(AbiDetector.Basis.DEFAULT, detection.basis());
  }

  @Test
  void overridesParse() {
    assertTrue(AbiProfile.parseOverride("auto").isEmpty());
    assertTrue(AbiProfile.parseOverride(null).isEmpty());
    assertEquals(AbiProfile.RETAIL_1999, AbiProfile.parseOverride(" Retail ").orElseThrow());
    assertEquals(AbiProfile.Q3_132, AbiProfile.parseOverride("1.32").orElseThrow());
    assertThrows(IllegalArgumentException.class, () -> AbiProfile.parseOverride("1.17"));
    QvmImage image = module("qagame", 425);
    assertEquals(AbiProfile.Q3_132, AbiProfile.resolve("1.32", image, null));
    assertEquals(AbiProfile.RETAIL_1999, AbiProfile.resolve("", image, null));
    assertThrows(IllegalArgumentException.class, () -> AbiProfile.detect(module("synthetic")));
  }
}
