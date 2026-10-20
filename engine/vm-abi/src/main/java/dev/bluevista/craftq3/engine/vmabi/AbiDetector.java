package dev.bluevista.craftq3.engine.vmabi;

import dev.bluevista.craftq3.engine.vm.Opcode;
import dev.bluevista.craftq3.engine.vm.QvmImage;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Chooses the {@link AbiProfile} of a QVM build. Detection never relies on hashes alone, because
 * the corpus holds about fifty distinct builds:
 *
 * <ol>
 *   <li>{@link Basis#HASH}: the SHA-256 of the file is one of the verified retail builds.
 *   <li>{@link Basis#STRUCTURAL}: the import usage profile, i.e. every trap number the bytecode
 *       calls through a constant negative address ({@code CONST -1-n; CALL}). In order: the profile
 *       whose table leaves fewer of those numbers unknown wins; otherwise a build calling any
 *       number only the 1.32 table knows is 1.32; otherwise one calling a slot whose meaning
 *       differs between the tables (botlib actions, ui server browser) is retail; otherwise a
 *       complete build (at least {@link #FULL_BUILD_IMPORTS} distinct imports) that uses nothing
 *       added in 1.27 is retail.
 *   <li>{@link Basis#DEFAULT}: too little evidence (small synthetic modules): 1.32.
 * </ol>
 *
 * Hosts additionally sanity-check struct sizes at {@code LocateGameData}, and {@code vm_abi}
 * overrides detection ({@link AbiProfile#parseOverride}).
 */
public final class AbiDetector {
  /** Distinct imports above which a build that only uses pre-1.27 imports is taken as retail. */
  public static final int FULL_BUILD_IMPORTS = 32;

  /** How a profile was chosen. */
  public enum Basis {
    HASH,
    STRUCTURAL,
    DEFAULT
  }

  /**
   * The outcome for one build.
   *
   * @param sha256 lower-case hex of the file bytes, or empty when no bytes were given
   * @param imports every trap number the bytecode calls directly
   * @param unknown per profile, the imported numbers that profile's table does not know
   */
  public record Detection(
      VmModule module,
      AbiProfile profile,
      Basis basis,
      String reason,
      String sha256,
      SortedSet<Integer> imports,
      Map<AbiProfile, SortedSet<Integer>> unknown) {
    /** Imported numbers unknown to the chosen profile. */
    public SortedSet<Integer> unknownImports() {
      return unknown.get(profile);
    }
  }

  private static final Map<VmModule, String> RETAIL_SHA256 =
      Map.of(
          VmModule.QAGAME, "73d07e341bd21bff3e7ec2c961ea9cafe7ff9150e0ddcc5d8055757edece0f72",
          VmModule.CGAME, "ee31bdb9865c3e11afdff3b5f65dbe9599de9527f2493a25a347a8b0ce5eb098",
          VmModule.UI, "826a342a108ac8a7fa45f4e752dfa5be50fa5ddf4fdb87d7c404d833c4989627");

  private AbiDetector() {}

  /** The verified profile of a build with this SHA-256, if it is one of the known originals. */
  public static Optional<AbiProfile> verified(VmModule module, String sha256) {
    return RETAIL_SHA256.get(module).equalsIgnoreCase(sha256)
        ? Optional.of(AbiProfile.RETAIL_1999)
        : Optional.empty();
  }

  /** Every trap number called through {@code CONST -1-n; CALL}, ascending. */
  public static SortedSet<Integer> imports(QvmImage image) {
    TreeSet<Integer> numbers = new TreeSet<>();
    for (int pc = 1; pc < image.instructionCount(); pc++) {
      if (image.opcode(pc) != Opcode.CALL || image.opcode(pc - 1) != Opcode.CONST) continue;
      int address = image.operand(pc - 1);
      if (address < 0) numbers.add(-1 - address);
    }
    return Collections.unmodifiableSortedSet(numbers);
  }

  /**
   * Analyzes a build whose module kind is recognised from {@link QvmImage#name()}.
   *
   * @param fileBytes the original file for the hash check, or null to detect structurally only
   * @throws IllegalArgumentException when the name is not qagame, cgame or ui
   */
  public static Detection analyze(QvmImage image, byte[] fileBytes) {
    VmModule module =
        VmModule.fromName(image.name())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Cannot tell the module kind of " + image.name() + "; pass it explicitly"));
    return analyze(module, image, fileBytes);
  }

  public static Detection analyze(VmModule module, QvmImage image, byte[] fileBytes) {
    Objects.requireNonNull(module);
    SortedSet<Integer> imports = imports(image);
    String sha = fileBytes == null ? "" : sha256(fileBytes);
    ImportTable modern = ImportTable.of(module, AbiProfile.Q3_132);
    ImportTable retail = ImportTable.of(module, AbiProfile.RETAIL_1999);
    Map<AbiProfile, SortedSet<Integer>> unknown = new EnumMap<>(AbiProfile.class);
    unknown.put(AbiProfile.Q3_132, unknown(modern, imports));
    unknown.put(AbiProfile.RETAIL_1999, unknown(retail, imports));
    Map<AbiProfile, SortedSet<Integer>> frozen = Collections.unmodifiableMap(unknown);

    Optional<AbiProfile> byHash = sha.isEmpty() ? Optional.empty() : verified(module, sha);
    if (byHash.isPresent())
      return new Detection(
          module, byHash.get(), Basis.HASH, "verified original build", sha, imports, frozen);

    int modernUnknown = unknown.get(AbiProfile.Q3_132).size();
    int retailUnknown = unknown.get(AbiProfile.RETAIL_1999).size();
    if (modernUnknown != retailUnknown) {
      AbiProfile profile =
          modernUnknown < retailUnknown ? AbiProfile.Q3_132 : AbiProfile.RETAIL_1999;
      return new Detection(
          module,
          profile,
          Basis.STRUCTURAL,
          "fewest unknown imports (1.32: " + modernUnknown + ", retail: " + retailUnknown + ")",
          sha,
          imports,
          frozen);
    }
    int modernOnly = 0, differing = 0;
    for (int number : imports) {
      VmImport a = modern.get(number), b = retail.get(number);
      if (a != null && b == null) modernOnly++;
      else if (a != null && a != b) differing++;
    }
    if (modernOnly > 0)
      return new Detection(
          module,
          AbiProfile.Q3_132,
          Basis.STRUCTURAL,
          modernOnly + " imports exist only in 1.32",
          sha,
          imports,
          frozen);
    if (differing > 0)
      return new Detection(
          module,
          AbiProfile.RETAIL_1999,
          Basis.STRUCTURAL,
          differing + " imports use retail-only slot meanings and none are 1.32 additions",
          sha,
          imports,
          frozen);
    if (imports.size() >= FULL_BUILD_IMPORTS)
      return new Detection(
          module,
          AbiProfile.RETAIL_1999,
          Basis.STRUCTURAL,
          "a complete build that uses no import added in 1.27 or later",
          sha,
          imports,
          frozen);
    return new Detection(
        module, AbiProfile.Q3_132, Basis.DEFAULT, "too few imports to tell", sha, imports, frozen);
  }

  private static SortedSet<Integer> unknown(ImportTable table, SortedSet<Integer> imports) {
    TreeSet<Integer> missing = new TreeSet<>();
    for (int number : imports) if (!table.knows(number)) missing.add(number);
    return Collections.unmodifiableSortedSet(missing);
  }

  /** Lower-case hex SHA-256. */
  public static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError("Java requires SHA-256", impossible);
    }
  }
}
