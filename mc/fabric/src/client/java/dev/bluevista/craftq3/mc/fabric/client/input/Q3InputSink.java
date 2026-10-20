package dev.bluevista.craftq3.mc.fabric.client.input;

/**
 * Where captured input goes: Quake III system events, free of Minecraft and GLFW types. The session
 * connects this to {@code engine:client}'s input. Every call is made on the render thread.
 */
public interface Q3InputSink {
  /**
   * A key event ({@code SE_KEY}). {@code q3Key} is a {@code keyNum_t} from {@link
   * dev.bluevista.craftq3.engine.console.KeyNames}. Auto-repeat delivers further {@code down}
   * events without an intervening release, as Quake III expects.
   *
   * @param time milliseconds from the capture's clock
   */
  void key(int q3Key, boolean down, long time);

  /**
   * A text event ({@code SE_CHAR}) for the console and UI fields: a Unicode code point, or a
   * control character the original engine relies on (8 for backspace, 1-26 for Ctrl+letter).
   */
  void character(int codepoint);

  /**
   * Relative mouse motion ({@code SE_MOUSE}) since the previous call, in window pixels (raw device
   * counts when Minecraft's raw-input option is on).
   *
   * @param time milliseconds from the capture's clock
   */
  void mouseMove(double dx, double dy, long time);
}
