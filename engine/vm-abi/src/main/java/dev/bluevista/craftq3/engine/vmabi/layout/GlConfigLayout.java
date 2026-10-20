package dev.bluevista.craftq3.engine.vmabi.layout;

import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.AbiProfile;

/**
 * {@code glconfig_t}: three 1024-byte strings, the extensions string ({@code BIG_INFO_STRING} in
 * 1.32, 1024 bytes in retail) and seventeen words. 1.32: 11332 bytes; retail: 4164 bytes.
 */
public final class GlConfigLayout {
  public static final int RENDERER_STRING = 0;
  public static final int VENDOR_STRING = 1024;
  public static final int VERSION_STRING = 2048;
  public static final int EXTENSIONS_STRING = 3072;

  /** {@code MAX_STRING_CHARS}: capacity of the renderer, vendor and version strings. */
  public static final int STRING_BYTES = 1024;

  /** Offsets after the extensions string. */
  private static final int MAX_TEXTURE_SIZE = 0,
      NUM_TEXTURE_UNITS = 4,
      COLOR_BITS = 8,
      DEPTH_BITS = 12,
      STENCIL_BITS = 16,
      DRIVER_TYPE = 20,
      HARDWARE_TYPE = 24,
      DEVICE_SUPPORTS_GAMMA = 28,
      TEXTURE_COMPRESSION = 32,
      TEXTURE_ENV_ADD_AVAILABLE = 36,
      VID_WIDTH = 40,
      VID_HEIGHT = 44,
      WINDOW_ASPECT = 48,
      DISPLAY_FREQUENCY = 52,
      IS_FULLSCREEN = 56,
      STEREO_ENABLED = 60,
      SMP_ACTIVE = 64,
      TAIL_BYTES = 68;

  public static final GlConfigLayout Q3_132 = new GlConfigLayout(AbiProfile.Q3_132);
  public static final GlConfigLayout RETAIL_1999 = new GlConfigLayout(AbiProfile.RETAIL_1999);

  private final AbiProfile profile;
  private final int tail;

  private GlConfigLayout(AbiProfile profile) {
    this.profile = profile;
    tail = EXTENSIONS_STRING + profile.glExtensionsBytes();
  }

  public static GlConfigLayout of(AbiProfile profile) {
    return profile == AbiProfile.RETAIL_1999 ? RETAIL_1999 : Q3_132;
  }

  /** The values a renderer reports. Enum-typed fields are their C integer values. */
  public record GlConfig(
      String renderer,
      String vendor,
      String version,
      String extensions,
      int maxTextureSize,
      int numTextureUnits,
      int colorBits,
      int depthBits,
      int stencilBits,
      int driverType,
      int hardwareType,
      boolean deviceSupportsGamma,
      int textureCompression,
      boolean textureEnvAddAvailable,
      int vidWidth,
      int vidHeight,
      float windowAspect,
      int displayFrequency,
      boolean fullscreen,
      boolean stereo,
      boolean smpActive) {
    /** A generic hardware-accelerated configuration for a {@code width x height} view. */
    public static GlConfig standard(int width, int height) {
      if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid video size");
      return new GlConfig(
          "CraftQ3",
          "CraftQ3",
          "1.32 ABI",
          "",
          4096,
          2,
          32,
          24,
          8,
          0,
          0,
          true,
          0,
          true,
          width,
          height,
          (float) width / height,
          60,
          false,
          false,
          false);
    }
  }

  public int size() {
    return tail + TAIL_BYTES;
  }

  public int extensionsBytes() {
    return profile.glExtensionsBytes();
  }

  public int maxTextureSize() {
    return tail + MAX_TEXTURE_SIZE;
  }

  public int vidWidth() {
    return tail + VID_WIDTH;
  }

  public int vidHeight() {
    return tail + VID_HEIGHT;
  }

  public int windowAspect() {
    return tail + WINDOW_ASPECT;
  }

  public int displayFrequency() {
    return tail + DISPLAY_FREQUENCY;
  }

  public int smpActive() {
    return tail + SMP_ACTIVE;
  }

  public void write(QvmMemory memory, int address, GlConfig config) {
    memory.fill(address, size(), 0);
    memory.writeString(address + RENDERER_STRING, config.renderer(), STRING_BYTES);
    memory.writeString(address + VENDOR_STRING, config.vendor(), STRING_BYTES);
    memory.writeString(address + VERSION_STRING, config.version(), STRING_BYTES);
    memory.writeString(address + EXTENSIONS_STRING, config.extensions(), extensionsBytes());
    int t = address + tail;
    memory.writeInt(t + MAX_TEXTURE_SIZE, config.maxTextureSize());
    memory.writeInt(t + NUM_TEXTURE_UNITS, config.numTextureUnits());
    memory.writeInt(t + COLOR_BITS, config.colorBits());
    memory.writeInt(t + DEPTH_BITS, config.depthBits());
    memory.writeInt(t + STENCIL_BITS, config.stencilBits());
    memory.writeInt(t + DRIVER_TYPE, config.driverType());
    memory.writeInt(t + HARDWARE_TYPE, config.hardwareType());
    VmStructs.writeBoolean(memory, t + DEVICE_SUPPORTS_GAMMA, config.deviceSupportsGamma());
    memory.writeInt(t + TEXTURE_COMPRESSION, config.textureCompression());
    VmStructs.writeBoolean(memory, t + TEXTURE_ENV_ADD_AVAILABLE, config.textureEnvAddAvailable());
    memory.writeInt(t + VID_WIDTH, config.vidWidth());
    memory.writeInt(t + VID_HEIGHT, config.vidHeight());
    memory.writeFloat(t + WINDOW_ASPECT, config.windowAspect());
    memory.writeInt(t + DISPLAY_FREQUENCY, config.displayFrequency());
    VmStructs.writeBoolean(memory, t + IS_FULLSCREEN, config.fullscreen());
    VmStructs.writeBoolean(memory, t + STEREO_ENABLED, config.stereo());
    VmStructs.writeBoolean(memory, t + SMP_ACTIVE, config.smpActive());
  }

  public GlConfig read(QvmMemory memory, int address) {
    memory.resolve(address, size());
    int t = address + tail;
    return new GlConfig(
        VmStructs.readChars(memory, address + RENDERER_STRING, STRING_BYTES),
        VmStructs.readChars(memory, address + VENDOR_STRING, STRING_BYTES),
        VmStructs.readChars(memory, address + VERSION_STRING, STRING_BYTES),
        VmStructs.readChars(memory, address + EXTENSIONS_STRING, extensionsBytes()),
        memory.readInt(t + MAX_TEXTURE_SIZE),
        memory.readInt(t + NUM_TEXTURE_UNITS),
        memory.readInt(t + COLOR_BITS),
        memory.readInt(t + DEPTH_BITS),
        memory.readInt(t + STENCIL_BITS),
        memory.readInt(t + DRIVER_TYPE),
        memory.readInt(t + HARDWARE_TYPE),
        VmStructs.readBoolean(memory, t + DEVICE_SUPPORTS_GAMMA),
        memory.readInt(t + TEXTURE_COMPRESSION),
        VmStructs.readBoolean(memory, t + TEXTURE_ENV_ADD_AVAILABLE),
        memory.readInt(t + VID_WIDTH),
        memory.readInt(t + VID_HEIGHT),
        memory.readFloat(t + WINDOW_ASPECT),
        memory.readInt(t + DISPLAY_FREQUENCY),
        VmStructs.readBoolean(memory, t + IS_FULLSCREEN),
        VmStructs.readBoolean(memory, t + STEREO_ENABLED),
        VmStructs.readBoolean(memory, t + SMP_ACTIVE));
  }
}
