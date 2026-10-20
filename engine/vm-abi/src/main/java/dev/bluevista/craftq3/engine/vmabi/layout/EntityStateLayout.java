package dev.bluevista.craftq3.engine.vmabi.layout;

import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.AbiProfile;
import java.util.Arrays;

/**
 * {@code entityState_t}. Every field up to {@code torsoAnim} has the same offset in both profiles;
 * retail ends there (204 bytes) and 1.32 adds {@code generic1} (208 bytes).
 *
 * <p>Canonical form: the 1.32 layout, 208 bytes. Retail guests convert by copying the shared 204
 * bytes and reading {@code generic1} as 0.
 */
public final class EntityStateLayout {
  public static final int NUMBER = 0;
  public static final int E_TYPE = 4;
  public static final int E_FLAGS = 8;

  /** {@code trajectory_t pos}. */
  public static final int POS = 12;

  /** {@code trajectory_t apos}. */
  public static final int APOS = POS + TrajectoryLayout.BYTES;

  public static final int TIME = 84;
  public static final int TIME2 = 88;
  public static final int ORIGIN = 92;
  public static final int ORIGIN2 = 104;
  public static final int ANGLES = 116;
  public static final int ANGLES2 = 128;
  public static final int OTHER_ENTITY_NUM = 140;
  public static final int OTHER_ENTITY_NUM2 = 144;
  public static final int GROUND_ENTITY_NUM = 148;
  public static final int CONSTANT_LIGHT = 152;
  public static final int LOOP_SOUND = 156;
  public static final int MODELINDEX = 160;
  public static final int MODELINDEX2 = 164;
  public static final int CLIENT_NUM = 168;
  public static final int FRAME = 172;
  public static final int SOLID = 176;
  public static final int EVENT = 180;
  public static final int EVENT_PARM = 184;
  public static final int POWERUPS = 188;
  public static final int WEAPON = 192;
  public static final int LEGS_ANIM = 196;
  public static final int TORSO_ANIM = 200;

  /** 1.32 only. */
  public static final int GENERIC1 = 204;

  /** Size of the canonical (1.32) form. */
  public static final int CANONICAL_BYTES = 208;

  public static final EntityStateLayout Q3_132 = new EntityStateLayout(AbiProfile.Q3_132);
  public static final EntityStateLayout RETAIL_1999 = new EntityStateLayout(AbiProfile.RETAIL_1999);

  private final AbiProfile profile;
  private final int size;

  private EntityStateLayout(AbiProfile profile) {
    this.profile = profile;
    this.size = profile.entityStateBytes();
  }

  public static EntityStateLayout of(AbiProfile profile) {
    return profile == AbiProfile.RETAIL_1999 ? RETAIL_1999 : Q3_132;
  }

  public AbiProfile profile() {
    return profile;
  }

  public int size() {
    return size;
  }

  /** Offset of {@code generic1}, or -1 when the profile lacks it. */
  public int generic1() {
    return size > GENERIC1 ? GENERIC1 : -1;
  }

  /** Reads a guest entityState into canonical bytes. */
  public byte[] readCanonical(QvmMemory memory, int address) {
    return Arrays.copyOf(memory.readBytes(address, size), CANONICAL_BYTES);
  }

  /** Writes canonical bytes as this profile's guest struct; retail drops {@code generic1}. */
  public void writeCanonical(QvmMemory memory, int address, byte[] canonical) {
    if (canonical.length != CANONICAL_BYTES)
      throw new IllegalArgumentException("Canonical entityState is " + CANONICAL_BYTES + " bytes");
    memory.writeBytes(
        address, size == CANONICAL_BYTES ? canonical : Arrays.copyOf(canonical, size));
  }

  /** {@code trajectory_t}. */
  public static final class TrajectoryLayout {
    public static final int TR_TYPE = 0;
    public static final int TR_TIME = 4;
    public static final int TR_DURATION = 8;
    public static final int TR_BASE = 12;
    public static final int TR_DELTA = 24;
    public static final int BYTES = 36;

    private TrajectoryLayout() {}
  }
}
