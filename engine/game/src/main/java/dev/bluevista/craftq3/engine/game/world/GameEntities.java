package dev.bluevista.craftq3.engine.game.world;

import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.AbiProfile;
import dev.bluevista.craftq3.engine.vmabi.layout.EntityStateLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.PlayerStateLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.SharedEntityLayout;
import java.util.Objects;

/**
 * The game's entity and client arrays as {@code trap_LocateGameData} announced them: typed access
 * to {@code sharedEntity_t} and {@code playerState_t} fields in guest memory through the profile's
 * layouts. Every access is bounds-checked by the guest memory.
 */
public final class GameEntities {
  /** {@code MAX_GENTITIES}. */
  public static final int MAX_GENTITIES = 1024;

  private final QvmMemory memory;
  private final AbiProfile profile;
  private final SharedEntityLayout layout;
  private final EntityStateLayout stateLayout;
  private final PlayerStateLayout playerLayout;
  private int base;
  private int count;
  private int stride;
  private int clients;
  private int clientStride;
  private int maxClients;
  private boolean located;

  public GameEntities(QvmMemory memory, AbiProfile profile) {
    this.memory = Objects.requireNonNull(memory);
    this.profile = Objects.requireNonNull(profile);
    this.layout = SharedEntityLayout.of(profile);
    this.stateLayout = EntityStateLayout.of(profile);
    this.playerLayout = PlayerStateLayout.of(profile);
  }

  public QvmMemory memory() {
    return memory;
  }

  public AbiProfile profile() {
    return profile;
  }

  public SharedEntityLayout layout() {
    return layout;
  }

  public PlayerStateLayout playerLayout() {
    return playerLayout;
  }

  public EntityStateLayout stateLayout() {
    return stateLayout;
  }

  /**
   * {@code trap_LocateGameData}. The arrays are checked against guest memory and the profile's
   * struct sizes; the entity count may change between calls (the game grows {@code
   * level.num_entities}) but never beyond {@link #MAX_GENTITIES}.
   */
  public void locate(
      int entities, int numEntities, int entitySize, int clientArray, int clientSize, int slots) {
    if (numEntities < 0 || numEntities > MAX_GENTITIES)
      throw new IllegalArgumentException("Invalid entity count " + numEntities);
    if (!layout.acceptsGameEntitySize(entitySize))
      throw new IllegalArgumentException(
          "sizeof(gentity_t) "
              + entitySize
              + " is smaller than the "
              + profile
              + " sharedEntity_t ("
              + layout.size()
              + ")");
    if (!layout.acceptsGameClientSize(clientSize))
      throw new IllegalArgumentException(
          "sizeof(gclient_t) " + clientSize + " is smaller than the " + profile + " playerState_t");
    memory.resolve(entities, Math.multiplyExact(Math.max(numEntities, 1), entitySize));
    memory.resolve(clientArray, Math.multiplyExact(Math.max(slots, 1), clientSize));
    base = entities;
    count = numEntities;
    stride = entitySize;
    clients = clientArray;
    clientStride = clientSize;
    maxClients = slots;
    located = true;
  }

  public boolean located() {
    return located;
  }

  /** Forgets the arrays (the VM was reset). */
  public void reset() {
    located = false;
    base = count = stride = clients = clientStride = maxClients = 0;
  }

  /** {@code sv.num_entities}. */
  public int count() {
    return count;
  }

  /** {@code SV_GentityNum}: the address of entity {@code number} (0..MAX_GENTITIES-1). */
  public int pointer(int number) {
    if (!located || number < 0 || number >= MAX_GENTITIES)
      throw new IllegalArgumentException("Invalid game entity " + number);
    return base + number * stride;
  }

  /** {@code SV_NumForGentity}: the entity number at a guest address. */
  public int number(int pointer) {
    if (!located || stride == 0) throw new IllegalStateException("Game data is not located");
    long offset = (long) pointer - base;
    if (offset < 0 || offset % stride != 0 || offset / stride >= MAX_GENTITIES)
      throw new IllegalArgumentException("Not a game entity pointer: " + pointer);
    return (int) (offset / stride);
  }

  /** {@code SV_GameClientNum}: the address of client {@code number}'s {@code playerState_t}. */
  public int playerState(int number) {
    if (!located || number < 0 || number >= maxClients)
      throw new IllegalArgumentException("Invalid game client " + number);
    return clients + number * clientStride;
  }

  // ---- entityState_t s -------------------------------------------------------------------------

  public int stateNumber(int e) {
    return memory.readInt(pointer(e) + EntityStateLayout.NUMBER);
  }

  public void setStateNumber(int e, int value) {
    memory.writeInt(pointer(e) + EntityStateLayout.NUMBER, value);
  }

  public void setSolid(int e, int value) {
    memory.writeInt(pointer(e) + EntityStateLayout.SOLID, value);
  }

  public int modelIndex(int e) {
    return memory.readInt(pointer(e) + EntityStateLayout.MODELINDEX);
  }

  public void setModelIndex(int e, int value) {
    memory.writeInt(pointer(e) + EntityStateLayout.MODELINDEX, value);
  }

  /** {@code s.generic1}, the portal range; 0 in retail builds without the field. */
  public int generic1(int e) {
    int offset = stateLayout.generic1();
    return offset < 0 ? 0 : memory.readInt(pointer(e) + offset);
  }

  public void stateOrigin(int e, float[] out) {
    vec(pointer(e) + EntityStateLayout.ORIGIN, out);
  }

  public void stateOrigin2(int e, float[] out) {
    vec(pointer(e) + EntityStateLayout.ORIGIN2, out);
  }

  public void stateAngles(int e, float[] out) {
    vec(pointer(e) + EntityStateLayout.ANGLES, out);
  }

  /** The entity's {@code entityState_t} in the canonical 1.32 form. */
  public byte[] canonicalState(int e) {
    return stateLayout.readCanonical(memory, pointer(e));
  }

  // ---- entityShared_t r ------------------------------------------------------------------------

  public boolean linked(int e) {
    return memory.readInt(pointer(e) + layout.linked()) != 0;
  }

  public void setLinked(int e, boolean value) {
    memory.writeInt(pointer(e) + layout.linked(), value ? 1 : 0);
  }

  public void incrementLinkCount(int e) {
    int address = pointer(e) + layout.linkCount();
    memory.writeInt(address, memory.readInt(address) + 1);
  }

  public int linkCount(int e) {
    return memory.readInt(pointer(e) + layout.linkCount());
  }

  public int svFlags(int e) {
    return memory.readInt(pointer(e) + layout.svFlags());
  }

  public void setSvFlags(int e, int value) {
    memory.writeInt(pointer(e) + layout.svFlags(), value);
  }

  /** {@code r.singleClient}; 0 in retail builds without the field. */
  public int singleClient(int e) {
    int offset = layout.singleClient();
    return offset < 0 ? 0 : memory.readInt(pointer(e) + offset);
  }

  public boolean bmodel(int e) {
    return memory.readInt(pointer(e) + layout.bmodel()) != 0;
  }

  public void setBmodel(int e, boolean value) {
    memory.writeInt(pointer(e) + layout.bmodel(), value ? 1 : 0);
  }

  public void mins(int e, float[] out) {
    vec(pointer(e) + layout.mins(), out);
  }

  public void maxs(int e, float[] out) {
    vec(pointer(e) + layout.maxs(), out);
  }

  public void setMins(int e, float[] value) {
    setVec(pointer(e) + layout.mins(), value);
  }

  public void setMaxs(int e, float[] value) {
    setVec(pointer(e) + layout.maxs(), value);
  }

  public int contents(int e) {
    return memory.readInt(pointer(e) + layout.contents());
  }

  public void setContents(int e, int value) {
    memory.writeInt(pointer(e) + layout.contents(), value);
  }

  public void absmin(int e, float[] out) {
    vec(pointer(e) + layout.absmin(), out);
  }

  public void absmax(int e, float[] out) {
    vec(pointer(e) + layout.absmax(), out);
  }

  public float absmin(int e, int axis) {
    return memory.readFloat(pointer(e) + layout.absmin() + 4 * axis);
  }

  public float absmax(int e, int axis) {
    return memory.readFloat(pointer(e) + layout.absmax() + 4 * axis);
  }

  public void setAbsBounds(int e, float[] min, float[] max) {
    setVec(pointer(e) + layout.absmin(), min);
    setVec(pointer(e) + layout.absmax(), max);
  }

  public void currentOrigin(int e, float[] out) {
    vec(pointer(e) + layout.currentOrigin(), out);
  }

  public void currentAngles(int e, float[] out) {
    vec(pointer(e) + layout.currentAngles(), out);
  }

  public int ownerNum(int e) {
    return memory.readInt(pointer(e) + layout.ownerNum());
  }

  // ---- playerState_t ---------------------------------------------------------------------------

  /** Client {@code c}'s player state in the canonical 1.32 form. */
  public byte[] canonicalPlayerState(int c) {
    return playerLayout.readCanonical(memory, playerState(c));
  }

  public int playerInt(int c, int offset) {
    return memory.readInt(playerState(c) + offset);
  }

  public void playerVec(int c, int offset, float[] out) {
    vec(playerState(c) + offset, out);
  }

  public void setPing(int c, int ping) {
    memory.writeInt(playerState(c) + playerLayout.ping(), ping);
  }

  private void vec(int address, float[] out) {
    memory.resolve(address, 12);
    out[0] = memory.readFloat(address);
    out[1] = memory.readFloat(address + 4);
    out[2] = memory.readFloat(address + 8);
  }

  private void setVec(int address, float[] value) {
    memory.resolve(address, 12);
    memory.writeFloat(address, value[0]);
    memory.writeFloat(address + 4, value[1]);
    memory.writeFloat(address + 8, value[2]);
  }
}
