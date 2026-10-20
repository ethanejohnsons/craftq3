package dev.bluevista.craftq3.mc.fabric.bridge;

/**
 * Implemented on {@code ServerPlayer} by {@code ServerPlayerMixin}: the player's bridge session
 * while it plays Quake in the bridge, else null. Server thread only.
 */
public interface BridgedPlayer {
  BridgeSession craftq3$bridgeSession();

  void craftq3$setBridgeSession(BridgeSession session);

  /** The bridge session of {@code player}, or null (also for non-server players). */
  static BridgeSession of(Object player) {
    return player instanceof BridgedPlayer bridged ? bridged.craftq3$bridgeSession() : null;
  }
}
