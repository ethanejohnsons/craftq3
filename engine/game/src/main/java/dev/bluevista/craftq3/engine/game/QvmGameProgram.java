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
        withNativeStack(QvmImage.parse("qagame", bytes), bytes), bytes, abiOverride, LIMITS, clock);
  }

  /**
   * The original interpreter has no stack-bottom check: the program stack starts at the top of the
   * power-of-two data segment and may use everything down to the end of the guest's globals (the 64
   * KiB q3asm reserves at the end of BSS plus the rounding slack). Some builds (Q3Rally) need more
   * than the reservation, so the image is re-parsed with that whole region as its stack.
   */
  static QvmImage withNativeStack(QvmImage image, byte[] bytes) {
    long total = (long) image.dataLength() + image.literalLength() + image.bssLength();
    long globalsEnd =
        image.bssLength() >= QvmImage.PROGRAM_STACK_SIZE
            ? total - QvmImage.PROGRAM_STACK_SIZE
            : total;
    long available = (image.memorySize() - globalsEnd) & ~3L;
    if (available <= image.programStackSize()) return image;
    if (image.bssLength() < available) available = (image.memorySize() - total) & ~3L;
    if (available <= image.programStackSize()) return image;
    try {
      return QvmImage.parse(image.name(), bytes, (int) available);
    } catch (RuntimeException rejected) {
      return image;
    }
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
