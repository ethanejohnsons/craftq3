package dev.bluevista.craftq3.engine.vmabi.layout;

import dev.bluevista.craftq3.engine.vmabi.AbiProfile;

/**
 * {@code sharedEntity_t}: {@code entityState_t s} followed by {@code entityShared_t r}. Note that
 * {@code entityShared_t} itself starts with an (unused) second {@code entityState_t}, so {@code
 * r.linked} follows two entity states. Retail has no {@code singleClient}.
 *
 * <pre>
 *   s | r.s | linked linkcount svFlags [singleClient] bmodel mins maxs contents absmin absmax
 *     currentOrigin currentAngles ownerNum
 * </pre>
 *
 * 1.32: 516 bytes, {@code linked} at 416. Retail: 504 bytes, {@code linked} at 408.
 */
public final class SharedEntityLayout {
  /** {@code s}, the state sent to clients. */
  public static final int S = 0;

  public static final SharedEntityLayout Q3_132 = new SharedEntityLayout(AbiProfile.Q3_132);
  public static final SharedEntityLayout RETAIL_1999 =
      new SharedEntityLayout(AbiProfile.RETAIL_1999);

  private final AbiProfile profile;
  private final int r;
  private final int linked;
  private final int singleClient;
  private final int bmodel;

  private SharedEntityLayout(AbiProfile profile) {
    this.profile = profile;
    r = profile.entityStateBytes();
    linked = r + profile.entityStateBytes();
    boolean hasSingleClient = profile == AbiProfile.Q3_132;
    singleClient = hasSingleClient ? linked + 12 : -1;
    bmodel = linked + (hasSingleClient ? 16 : 12);
  }

  public static SharedEntityLayout of(AbiProfile profile) {
    return profile == AbiProfile.RETAIL_1999 ? RETAIL_1999 : Q3_132;
  }

  public AbiProfile profile() {
    return profile;
  }

  /** {@code r}, the server-only part. */
  public int r() {
    return r;
  }

  public int linked() {
    return linked;
  }

  public int linkCount() {
    return linked + 4;
  }

  public int svFlags() {
    return linked + 8;
  }

  /** Offset of {@code singleClient}, or -1 in retail. */
  public int singleClient() {
    return singleClient;
  }

  public int bmodel() {
    return bmodel;
  }

  public int mins() {
    return bmodel + 4;
  }

  public int maxs() {
    return mins() + VmStructs.VEC3_BYTES;
  }

  public int contents() {
    return maxs() + VmStructs.VEC3_BYTES;
  }

  public int absmin() {
    return contents() + 4;
  }

  public int absmax() {
    return absmin() + VmStructs.VEC3_BYTES;
  }

  public int currentOrigin() {
    return absmax() + VmStructs.VEC3_BYTES;
  }

  public int currentAngles() {
    return currentOrigin() + VmStructs.VEC3_BYTES;
  }

  public int ownerNum() {
    return currentAngles() + VmStructs.VEC3_BYTES;
  }

  public int size() {
    return ownerNum() + 4;
  }

  /**
   * Checks the {@code sizeofGEntity_t} a qagame passes to {@code trap_LocateGameData}: the game
   * entity begins with this struct, so a smaller size means the wrong profile.
   */
  public boolean acceptsGameEntitySize(int sizeofGEntity) {
    return sizeofGEntity >= size() && (sizeofGEntity & 3) == 0;
  }

  /**
   * Checks {@code sizeofGClient}: {@code gclient_t} begins with a {@code playerState_t} of this
   * profile.
   */
  public boolean acceptsGameClientSize(int sizeofGClient) {
    return sizeofGClient >= profile.playerStateBytes() && (sizeofGClient & 3) == 0;
  }
}
