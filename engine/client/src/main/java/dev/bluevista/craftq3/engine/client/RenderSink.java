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
}
