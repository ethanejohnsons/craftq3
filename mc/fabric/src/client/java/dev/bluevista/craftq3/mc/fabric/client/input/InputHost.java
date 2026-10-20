package dev.bluevista.craftq3.mc.fabric.client.input;

/**
 * The window side of {@link Q3InputCapture}: focus and the cursor. {@link MinecraftInputHost} in
 * the game.
 */
public interface InputHost {
  /**
   * True while the window may deliver input to the capture: focused, not minimised and not covered
   * by a loading overlay.
   */
  boolean inputFocused();

  /** Hides the cursor and switches to unbounded relative motion. */
  void grabCursor();

  /** Shows the cursor again. */
  void releaseCursor();
}
