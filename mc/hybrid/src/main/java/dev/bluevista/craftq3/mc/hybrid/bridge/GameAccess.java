package dev.bluevista.craftq3.mc.hybrid.bridge;

import dev.bluevista.craftq3.engine.game.GameServer;
import dev.bluevista.craftq3.engine.game.world.GameEntities;
import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.layout.EntityStateLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.PlayerStateLayout;
import java.util.Objects;

/**
 * The few writes the host makes into the running game, all at ABI-defined offsets (the shared
 * entity and playerState layouts of both profiles), on the simulation thread between game calls.
 */
final class GameAccess {
  /** {@code MAX_CLIENTS}: entities below this are client slots. */
  static final int MAX_CLIENTS = 64;

  /** {@code PERS_ATTACKER}. */
  static final int PERS_ATTACKER = 6;

  /** {@code PM_DEAD}. */
  static final int PM_DEAD = 3;

  private final GameServer server;

  GameAccess(GameServer server) {
    this.server = Objects.requireNonNull(server);
  }

  GameServer server() {
    return server;
  }

  GameEntities entities() {
    return server
        .entities()
        .orElseThrow(() -> new IllegalStateException("The game has not located its entities"));
  }

  boolean ready() {
    return server.state() == GameServer.State.RUNNING
        && server.entities().map(GameEntities::located).orElse(false);
  }

  QvmMemory memory() {
    return entities().memory();
  }

  int playerState(int client) {
    return entities().playerState(client);
  }

  int stat(int client, int index) {
    return memory().readInt(playerState(client) + PlayerStateLayout.STATS + index * 4);
  }

  int persistant(int client, int index) {
    return memory().readInt(playerState(client) + PlayerStateLayout.PERSISTANT + index * 4);
  }

  int pmType(int client) {
    return memory().readInt(playerState(client) + PlayerStateLayout.PM_TYPE);
  }

  boolean alive(int client) {
    return stat(client, Loadout.STAT_HEALTH) > 0 && pmType(client) != PM_DEAD;
  }

  void playerOrigin(int client, float[] out) {
    entities().playerVec(client, PlayerStateLayout.ORIGIN, out);
  }

  void playerVelocity(int client, float[] out) {
    entities().playerVec(client, PlayerStateLayout.VELOCITY, out);
  }

  void setPlayerVelocity(int client, float x, float y, float z) {
    int a = playerState(client) + PlayerStateLayout.VELOCITY;
    QvmMemory m = memory();
    m.writeFloat(a, x);
    m.writeFloat(a + 4, y);
    m.writeFloat(a + 8, z);
  }

  /** Writes {@code r.currentOrigin} and the state's {@code origin} and {@code pos.trBase}. */
  void setEntityOrigin(int e, float x, float y, float z) {
    GameEntities g = entities();
    QvmMemory m = g.memory();
    int base = g.pointer(e);
    writeVec(m, base + g.layout().currentOrigin(), x, y, z);
    writeVec(m, base + EntityStateLayout.ORIGIN, x, y, z);
    writeVec(m, base + EntityStateLayout.POS + EntityStateLayout.TrajectoryLayout.TR_BASE, x, y, z);
  }

  /** Writes only the state's {@code origin}: what spawn-point selection reads. */
  void setStateOrigin(int e, float[] origin) {
    GameEntities g = entities();
    writeVec(g.memory(), g.pointer(e) + EntityStateLayout.ORIGIN, origin[0], origin[1], origin[2]);
  }

  /** Places a client entity: playerState origin (velocity cleared) and the entity's origins. */
  void placeClient(int client, float x, float y, float z) {
    QvmMemory m = memory();
    int ps = playerState(client);
    writeVec(m, ps + PlayerStateLayout.ORIGIN, x, y, z);
    writeVec(m, ps + PlayerStateLayout.VELOCITY, 0, 0, 0);
    setEntityOrigin(client, x, y, z);
  }

  void link(int e) {
    server.world().link(e);
  }

  void unlink(int e) {
    server.world().unlink(e);
  }

  /** Acknowledges every reliable command of a client nobody reads (puppets, headless players). */
  void discardReliable(int client) {
    var slot = server.client(client);
    slot.acknowledgeReliable(slot.reliableSequence());
  }

  private static void writeVec(QvmMemory m, int address, float x, float y, float z) {
    m.writeFloat(address, x);
    m.writeFloat(address + 4, y);
    m.writeFloat(address + 8, z);
  }
}
