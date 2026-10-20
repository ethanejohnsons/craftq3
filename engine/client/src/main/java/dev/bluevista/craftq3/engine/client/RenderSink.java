package dev.bluevista.craftq3.engine.client;

import dev.bluevista.craftq3.engine.render.frame.CgameFrame;

/**
 * Receives one {@link CgameFrame} per screen update, on the render thread. A frame is immutable and
 * holds no VM memory, so an implementation may keep it until it has been drawn.
 */
@FunctionalInterface
public interface RenderSink {
  /** Discards every frame. */
  RenderSink DISCARD = frame -> {};

  void submit(CgameFrame frame);

  /**
   * A frame drawn by {@code trap_UpdateScreen} inside a long VM call (a cgame or ui loading
   * screen): the render thread does not return to the host until the call ends, so a host that can
   * present synchronously should draw it now. The default treats it as any other frame.
   */
  default void submitImmediate(CgameFrame frame) {
    submit(frame);
  }
}
