package dev.bluevista.craftq3.mc.fabric.bridge;

/**
 * Implemented on every {@code Entity} by {@code EntityAvatarMixin}: the Quake body that replaces
 * the entity's own dimensions while it is bridged. The server sets it on a bridged {@code
 * ServerPlayer} every tick, the client on its own player every frame; each side only ever touches
 * its own entities, so no lookup or lock is involved. Null means Minecraft's own dimensions.
 */
public interface BridgeAvatar {
  AvatarShape craftq3$avatar();

  void craftq3$setAvatar(AvatarShape shape);

  /** The entity's avatar shape, or null. */
  static AvatarShape of(Object entity) {
    return entity instanceof BridgeAvatar avatar ? avatar.craftq3$avatar() : null;
  }
}
