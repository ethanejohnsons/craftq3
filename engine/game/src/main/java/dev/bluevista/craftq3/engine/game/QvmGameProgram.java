package dev.bluevista.craftq3.engine.game;

import dev.bluevista.craftq3.engine.common.time.Clock;
import dev.bluevista.craftq3.engine.formats.AssetLookup;
import dev.bluevista.craftq3.engine.vm.QvmImage;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vm.Syscalls;
import dev.bluevista.craftq3.engine.vmabi.AbiDetector;
import dev.bluevista.craftq3.engine.vmabi.AbiProfile;
import dev.bluevista.craftq3.engine.vmabi.VmModule;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Objects;

/**
 * The original {@code vm/qagame.qvm} as the search path resolves it, with its ABI detected
 * structurally ({@link AbiDetector}) unless {@code vm_abi} overrides it.
 */
public final class QvmGameProgram implements GameProgram {
  /** Instruction and syscall budgets per {@code vmMain}: generous for map loads with bots. */
  public static final QvmInstance.Limits LIMITS =
      new QvmInstance.Limits(2_000_000_000L, 20_000_000L, 0, 1 << 16, 4096, 32);

  private final QvmImage image;
  private final AbiDetector.Detection detection;
  private final AbiProfile profile;
  private final QvmInstance.Limits limits;
  private final Clock clock;

  public QvmGameProgram(
      QvmImage image, byte[] bytes, String abiOverride, QvmInstance.Limits limits, Clock clock) {
    this.image = Objects.requireNonNull(image);
    this.detection = AbiDetector.analyze(VmModule.QAGAME, image, bytes);
    this.profile = AbiProfile.parseOverride(abiOverride).orElse(detection.profile());
    this.limits = Objects.requireNonNull(limits);
    this.clock = Objects.requireNonNull(clock);
  }

  /**
   * Reads {@code vm/qagame.qvm} from {@code files}.
   *
   * @throws IOException when the module is missing
   */
  public static QvmGameProgram load(AssetLookup files, String abiOverride, Clock clock)
      throws IOException {
    String path = VmModule.QAGAME.path();
    byte[] bytes = files.read(path).orElseThrow(() -> new NoSuchFileException(path));
    return new QvmGameProgram(
        QvmImage.parse("qagame", bytes, QvmImage.StackPolicy.NATIVE_EXTENT),
        bytes,
        abiOverride,
        LIMITS,
        clock);
  }

  @Override
  public String name() {
    return image.name();
  }

  @Override
  public AbiProfile profile() {
    return profile;
  }

  public AbiDetector.Detection detection() {
    return detection;
  }

  @Override
  public QvmInstance instantiate(Syscalls syscalls) {
    return new QvmInstance(image, syscalls, limits, clock);
  }
}
