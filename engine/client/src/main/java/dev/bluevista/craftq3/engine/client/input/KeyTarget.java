package dev.bluevista.craftq3.engine.client.input;

/** A receiver of routed key and mouse events (the ui or cgame VM, the console, message mode). */
public interface KeyTarget {
  /**
   * A key changed state. {@code key} is a Quake key number, or a character with {@link
   * ClientInput#K_CHAR_FLAG} set for text input.
   */
  void keyEvent(int key, boolean down, int time);

  /** Relative mouse motion in device units. */
  default void mouseEvent(int dx, int dy, int time) {}

  /** A target that ignores everything. */
  KeyTarget NONE = (key, down, time) -> {};
}
