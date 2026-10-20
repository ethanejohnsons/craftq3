package dev.bluevista.craftq3.mc.fabric.client.session;

/**
 * One Quake III client session as {@code Q3Screen} drives it: Minecraft-free events in, frames out
 * through the host's {@link dev.bluevista.craftq3.engine.client.RenderSink}. Every method runs on
 * the render thread. Implementations never throw for game-level failures (an {@code ERR_DROP}
 * returns to the menu inside the session); an exception out of a method is an unexpected failure
 * the screen reports and closes on.
 */
public interface Q3Session extends AutoCloseable {
  /**
   * Runs one client frame at {@code realMillis} (monotonic): user commands, network, the cgame and
   * ui VMs, sound; the frame goes to the host's render sink.
   */
  void frame(int realMillis);

  /** {@code SE_KEY}: a {@code keyNum_t} went down or up at {@code time}. */
  void keyEvent(int key, boolean down, int time);

  /** {@code SE_CHAR}: a typed character. */
  void charEvent(int character, int time);

  /** {@code SE_MOUSE}: relative motion. */
  void mouseEvent(int dx, int dy, int time);

  /** Console text, executed as if typed into the console ({@code Cbuf_AddText}). */
  void command(String text);

  /** The window's framebuffer changed size ({@code vid_restart} semantics for {@code glconfig}). */
  void videoResized(int width, int height);

  /** The window lost or regained focus. */
  default void focusChanged(boolean focused) {}

  /** A short state name for logs and tests (Menu, Loading, Local, Remote, Demo, Cinematic). */
  String stateName();

  /** Shuts the session down: writes {@code q3config.cfg}, stops threads, releases audio voices. */
  @Override
  void close();
}
