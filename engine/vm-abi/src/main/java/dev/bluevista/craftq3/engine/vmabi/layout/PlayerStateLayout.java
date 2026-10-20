package dev.bluevista.craftq3.engine.vmabi.layout;

import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.AbiProfile;
import java.util.Arrays;

/**
 * {@code playerState_t}. Fields from {@code commandTime} to {@code ammo[16]} share offsets in both
 * profiles. After {@code ammo}, retail has only {@code ping} (444 bytes); 1.32 has {@code
 * generic1}, {@code loopSound}, {@code jumppad_ent}, {@code ping}, {@code pmove_framecount}, {@code
 * jumppad_frame} and {@code entityEventSequence} (468 bytes).
 *
 * <p>Canonical form: the 1.32 layout, 468 bytes. Converting from retail copies the first 440 bytes
 * and moves {@code ping} from 440 to 452; the six 1.32-only words read as 0. This is the only place
 * the 468-byte size is defined.
 */
public final class PlayerStateLayout {
  public static final int COMMAND_TIME = 0;
  public static final int PM_TYPE = 4;
  public static final int BOB_CYCLE = 8;
  public static final int PM_FLAGS = 12;
  public static final int PM_TIME = 16;
  public static final int ORIGIN = 20;
  public static final int VELOCITY = 32;
  public static final int WEAPON_TIME = 44;
  public static final int GRAVITY = 48;
  public static final int SPEED = 52;
  public static final int DELTA_ANGLES = 56;
  public static final int GROUND_ENTITY_NUM = 68;
  public static final int LEGS_TIMER = 72;
  public static final int LEGS_ANIM = 76;
  public static final int TORSO_TIMER = 80;
  public static final int TORSO_ANIM = 84;
  public static final int MOVEMENT_DIR = 88;
  public static final int GRAPPLE_POINT = 92;
  public static final int E_FLAGS = 104;
  public static final int EVENT_SEQUENCE = 108;

  /** {@code int events[MAX_PS_EVENTS]}. */
  public static final int EVENTS = 112;

  /** {@code int eventParms[MAX_PS_EVENTS]}. */
  public static final int EVENT_PARMS = 120;

  public static final int EXTERNAL_EVENT = 128;
  public static final int EXTERNAL_EVENT_PARM = 132;
  public static final int EXTERNAL_EVENT_TIME = 136;
  public static final int CLIENT_NUM = 140;
  public static final int WEAPON = 144;
  public static final int WEAPONSTATE = 148;
  public static final int VIEWANGLES = 152;
  public static final int VIEWHEIGHT = 164;
  public static final int DAMAGE_EVENT = 168;
  public static final int DAMAGE_YAW = 172;
  public static final int DAMAGE_PITCH = 176;
  public static final int DAMAGE_COUNT = 180;

  /** {@code int stats[MAX_STATS]}. */
  public static final int STATS = 184;

  /** {@code int persistant[MAX_PERSISTANT]}. */
  public static final int PERSISTANT = 248;

  /** {@code int powerups[MAX_POWERUPS]}. */
  public static final int POWERUPS = 312;

  /** {@code int ammo[MAX_WEAPONS]}. */
  public static final int AMMO = 376;

  /** End of the fields both profiles share. */
  public static final int SHARED_BYTES = 440;

  /** {@code MAX_PS_EVENTS}. */
  public static final int MAX_PS_EVENTS = 2;

  /** {@code MAX_STATS}, {@code MAX_PERSISTANT}, {@code MAX_POWERUPS}, {@code MAX_WEAPONS}. */
  public static final int ARRAY_LENGTH = 16;

  /** 1.32 offsets of the fields after {@code ammo}. */
  public static final int GENERIC1_132 = 440,
      LOOP_SOUND_132 = 444,
      JUMPPAD_ENT_132 = 448,
      PING_132 = 452,
      PMOVE_FRAMECOUNT_132 = 456,
      JUMPPAD_FRAME_132 = 460,
      ENTITY_EVENT_SEQUENCE_132 = 464;

  /** Retail offset of {@code ping}, directly after {@code ammo}. */
  public static final int PING_RETAIL = 440;

  /** Size of the canonical (1.32) form. */
  public static final int CANONICAL_BYTES = 468;

  public static final PlayerStateLayout Q3_132 = new PlayerStateLayout(AbiProfile.Q3_132);
  public static final PlayerStateLayout RETAIL_1999 = new PlayerStateLayout(AbiProfile.RETAIL_1999);

  private final AbiProfile profile;
  private final boolean modern;

  private PlayerStateLayout(AbiProfile profile) {
    this.profile = profile;
    this.modern = profile == AbiProfile.Q3_132;
    int expected = modern ? CANONICAL_BYTES : PING_RETAIL + 4;
    if (profile.playerStateBytes() != expected)
      throw new AssertionError("playerState size mismatch for " + profile);
  }

  public static PlayerStateLayout of(AbiProfile profile) {
    return profile == AbiProfile.RETAIL_1999 ? RETAIL_1999 : Q3_132;
  }

  public AbiProfile profile() {
    return profile;
  }

  public int size() {
    return profile.playerStateBytes();
  }

  public int ping() {
    return modern ? PING_132 : PING_RETAIL;
  }

  /** Offset of {@code generic1}, or -1 in retail. */
  public int generic1() {
    return modern ? GENERIC1_132 : -1;
  }

  public int loopSound() {
    return modern ? LOOP_SOUND_132 : -1;
  }

  public int jumppadEnt() {
    return modern ? JUMPPAD_ENT_132 : -1;
  }

  public int pmoveFramecount() {
    return modern ? PMOVE_FRAMECOUNT_132 : -1;
  }

  public int jumppadFrame() {
    return modern ? JUMPPAD_FRAME_132 : -1;
  }

  public int entityEventSequence() {
    return modern ? ENTITY_EVENT_SEQUENCE_132 : -1;
  }

  /** Reads a guest playerState as canonical bytes. */
  public byte[] readCanonical(QvmMemory memory, int address) {
    byte[] guest = memory.readBytes(address, size());
    if (modern) return guest;
    return toCanonical(guest);
  }

  /** Writes canonical bytes as this profile's guest struct. */
  public void writeCanonical(QvmMemory memory, int address, byte[] canonical) {
    memory.writeBytes(address, fromCanonical(canonical));
  }

  /** Converts a guest struct of this profile to canonical bytes. */
  public byte[] toCanonical(byte[] guest) {
    if (guest.length != size())
      throw new IllegalArgumentException("Guest playerState is " + size() + " bytes");
    if (modern) return guest.clone();
    byte[] canonical = new byte[CANONICAL_BYTES];
    System.arraycopy(guest, 0, canonical, 0, SHARED_BYTES);
    System.arraycopy(guest, PING_RETAIL, canonical, PING_132, 4);
    return canonical;
  }

  /** Converts canonical bytes to this profile's guest struct. */
  public byte[] fromCanonical(byte[] canonical) {
    if (canonical.length != CANONICAL_BYTES)
      throw new IllegalArgumentException("Canonical playerState is " + CANONICAL_BYTES + " bytes");
    if (modern) return canonical.clone();
    byte[] guest = Arrays.copyOf(canonical, size());
    System.arraycopy(canonical, PING_132, guest, PING_RETAIL, 4);
    return guest;
  }
}
