package dev.bluevista.craftq3.engine.vmabi.layout;

import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.AbiProfile;
import java.util.List;

/**
 * {@code snapshot_t}: header, a profile-sized {@code playerState_t}, then up to {@link
 * #MAX_ENTITIES} profile-sized {@code entityState_t}s and the server command counters.
 */
public final class SnapshotLayout {
  public static final int SNAP_FLAGS = 0;
  public static final int PING = 4;
  public static final int SERVER_TIME = 8;
  public static final int AREAMASK = 12;

  /** {@code MAX_MAP_AREA_BYTES}. */
  public static final int AREAMASK_BYTES = 32;

  public static final int PS = AREAMASK + AREAMASK_BYTES;

  /** {@code MAX_ENTITIES_IN_SNAPSHOT}. */
  public static final int MAX_ENTITIES = 256;

  public static final SnapshotLayout Q3_132 = new SnapshotLayout(AbiProfile.Q3_132);
  public static final SnapshotLayout RETAIL_1999 = new SnapshotLayout(AbiProfile.RETAIL_1999);

  private final AbiProfile profile;
  private final int numEntities;
  private final int entities;
  private final int numServerCommands;

  private SnapshotLayout(AbiProfile profile) {
    this.profile = profile;
    numEntities = PS + profile.playerStateBytes();
    entities = numEntities + 4;
    numServerCommands = entities + MAX_ENTITIES * profile.entityStateBytes();
  }

  public static SnapshotLayout of(AbiProfile profile) {
    return profile == AbiProfile.RETAIL_1999 ? RETAIL_1999 : Q3_132;
  }

  public int numEntities() {
    return numEntities;
  }

  public int entities() {
    return entities;
  }

  /** Offset of entity {@code index} in {@code entities[]}. */
  public int entity(int index) {
    if (index < 0 || index >= MAX_ENTITIES)
      throw new IndexOutOfBoundsException("Snapshot entity " + index);
    return entities + index * profile.entityStateBytes();
  }

  public int numServerCommands() {
    return numServerCommands;
  }

  public int serverCommandSequence() {
    return numServerCommands + 4;
  }

  public int size() {
    return numServerCommands + 8;
  }

  /**
   * Writes a whole snapshot from canonical states (see {@link PlayerStateLayout} and {@link
   * EntityStateLayout}). Unused entity slots are zeroed.
   */
  public void write(
      QvmMemory memory,
      int address,
      int snapFlags,
      int ping,
      int serverTime,
      byte[] areamask,
      byte[] canonicalPlayerState,
      List<byte[]> canonicalEntities,
      int numServerCommands,
      int serverCommandSequence) {
    if (areamask.length > AREAMASK_BYTES)
      throw new IllegalArgumentException("Area mask exceeds " + AREAMASK_BYTES + " bytes");
    if (canonicalEntities.size() > MAX_ENTITIES)
      throw new IllegalArgumentException("Snapshot holds at most " + MAX_ENTITIES + " entities");
    memory.fill(address, size(), 0);
    memory.writeInt(address + SNAP_FLAGS, snapFlags);
    memory.writeInt(address + PING, ping);
    memory.writeInt(address + SERVER_TIME, serverTime);
    memory.writeBytes(address + AREAMASK, areamask);
    PlayerStateLayout.of(profile).writeCanonical(memory, address + PS, canonicalPlayerState);
    memory.writeInt(address + this.numEntities, canonicalEntities.size());
    EntityStateLayout entity = EntityStateLayout.of(profile);
    for (int i = 0; i < canonicalEntities.size(); i++)
      entity.writeCanonical(memory, address + entity(i), canonicalEntities.get(i));
    memory.writeInt(address + this.numServerCommands, numServerCommands);
    memory.writeInt(address + serverCommandSequence(), serverCommandSequence);
  }
}
