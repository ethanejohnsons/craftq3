package dev.bluevista.craftq3.fabric.render;

/** A screen that owns the host's world render slot and suppresses Minecraft's HUD. */
public interface QuakeView {
  default boolean replacesMinecraftWorld() {
    return true;
  }

  default void prepareFrame() {}

  void drawFrame();

  default void endFrame() {}
}
