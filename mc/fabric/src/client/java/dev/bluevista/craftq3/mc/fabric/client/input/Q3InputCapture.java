package dev.bluevista.craftq3.mc.fabric.client.input;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN;
import static org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT;

import dev.bluevista.craftq3.engine.common.time.Clock;
import dev.bluevista.craftq3.engine.console.KeyNames;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Turns the window's input into Quake III events while a Quake screen owns it. Render thread only.
 *
 * <ul>
 *   <li><b>Keys</b> are translated by {@link Q3KeyMap} and stamped with the injected clock. Each
 *       physical key is tracked, so releasing one of two keys that share a Quake key (left and
 *       right Shift) keeps it down, and a release whose press the capture never saw (the key that
 *       opened the screen) is ignored. Auto-repeat is passed on as further down events.
 *   <li><b>Text</b> goes to {@link Q3InputSink#character}. GLFW never reports control characters,
 *       so the ones the original console and UI fields act on are synthesised: backspace sends 8
 *       and Ctrl+letter sends 1-26 (Ctrl+V pastes, Ctrl+C clears, and so on). The character the
 *       console key types on the current layout is swallowed, so it never lands in the console it
 *       just opened.
 *   <li><b>Mouse</b> motion arrives from {@code MouseHandlerMixin} before Minecraft can use it to
 *       turn the camera, and is delivered only while the cursor is grabbed. Buttons are MOUSE1-5;
 *       the wheel becomes MWHEELUP/MWHEELDOWN press-release pairs, one per whole notch, with
 *       smooth-scrolling fractions carried over.
 *   <li><b>Cursor</b>: grabbed lazily on the first poll while focused (Minecraft releases the mouse
 *       right after {@code Screen.added}), again after focus returns, and released on focus loss
 *       and {@link #close}. {@link #setMouseCaptured} lets the session free it, for example for a
 *       windowed console.
 *   <li><b>Focus loss</b> (window unfocused or minimised, or a loading overlay up) releases every
 *       held key with an up event, so nothing stays stuck down, and drops input until focus
 *       returns.
 *   <li><b>Escape hatch</b>: Shift+Escape is never sent to Quake III. It releases every key and
 *       runs the escape hatch, which returns to Minecraft, so the player can always leave even when
 *       a QVM ignores Escape. Plain Escape is {@code K_ESCAPE}.
 * </ul>
 */
public final class Q3InputCapture {
  /** Wheel notches delivered per scroll event at most, as a guard against runaway deltas. */
  static final int MAX_WHEEL_STEPS = 10;

  /** {@code CTRL('h')}: the backspace character the original console and fields expect. */
  static final int BACKSPACE_CHAR = 8;

  private static final int SCANCODE_ID = 1 << 24;
  private static final int MOUSE_ID = 1 << 25;

  private final Q3InputSink sink;
  private final Q3KeyMap keyMap;
  private final Clock clock;
  private final InputHost host;
  private final Runnable escapeHatch;

  /** Physical key or button id (GLFW key, tagged scancode, tagged mouse button) to Quake key. */
  private final Map<Integer, Integer> held = new HashMap<>();

  private final int[] downCount = new int[KeyNames.MAX_KEYS];
  private boolean mouseWanted = true;
  private boolean grabbed;
  private boolean focused = true;
  private boolean closed;
  private boolean swallowChars;
  private double wheelRemainder;

  /**
   * @param escapeHatch run on Shift+Escape after every key was released; it should close the screen
   */
  public Q3InputCapture(
      Q3InputSink sink, Q3KeyMap keyMap, Clock clock, InputHost host, Runnable escapeHatch) {
    this.sink = Objects.requireNonNull(sink, "sink");
    this.keyMap = Objects.requireNonNull(keyMap, "keyMap");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.host = Objects.requireNonNull(host, "host");
    this.escapeHatch = Objects.requireNonNull(escapeHatch, "escapeHatch");
  }

  /**
   * (Re)starts capturing, for a screen that is opened again. The cursor is grabbed on the next
   * poll.
   */
  public void open() {
    closed = false;
    focused = true;
  }

  /**
   * Checks focus and applies the wanted cursor state. Call once per frame or tick; every event
   * entry point also polls first.
   *
   * @return whether input is currently accepted
   */
  public boolean poll() {
    if (closed) return false;
    if (!host.inputFocused()) {
      if (focused) {
        focused = false;
        releaseAll();
      }
      setGrab(false);
      return false;
    }
    focused = true;
    setGrab(mouseWanted);
    return true;
  }

  /** A GLFW key press or auto-repeat. */
  public void keyPressed(int glfwKey, int scancode, int modifiers) {
    if (!poll()) return;
    if (glfwKey == GLFW_KEY_ESCAPE && modifierDown(modifiers, GLFW_MOD_SHIFT, KeyNames.SHIFT)) {
      releaseAll();
      escapeHatch.run();
      return;
    }
    int key = keyMap.key(glfwKey, scancode);
    swallowChars = key == Q3KeyMap.CONSOLE;
    if (key == Q3KeyMap.UNMAPPED) return;
    press(physicalKey(glfwKey, scancode), key);
    if (key == KeyNames.BACKSPACE) sink.character(BACKSPACE_CHAR);
    else if (key >= 'a' && key <= 'z' && modifierDown(modifiers, GLFW_MOD_CONTROL, KeyNames.CTRL))
      sink.character(key - 'a' + 1);
  }

  /** A modifier is down if the event says so or the capture holds its key. */
  private boolean modifierDown(int modifiers, int glfwMod, int q3Key) {
    return (modifiers & glfwMod) != 0 || downCount[q3Key] > 0;
  }

  /** A GLFW key release. */
  public void keyReleased(int glfwKey, int scancode, int modifiers) {
    swallowChars = false;
    if (!poll()) return;
    release(physicalKey(glfwKey, scancode));
  }

  /** A GLFW character event. */
  public void charTyped(int codepoint) {
    if (!poll() || swallowChars) return;
    if (codepoint < ' ' || codepoint == 0x7f) return;
    sink.character(codepoint);
  }

  /** A GLFW mouse button press or release. */
  public void mouseButton(int button, boolean down) {
    if (!poll()) return;
    if (!down) {
      release(MOUSE_ID | button);
      return;
    }
    int key = Q3KeyMap.mouseButton(button);
    if (key != Q3KeyMap.UNMAPPED) press(MOUSE_ID | button, key);
  }

  /** Vertical scrolling, positive away from the user, in notches (fractions from touchpads). */
  public void mouseScrolled(double vertical) {
    if (!poll() || vertical == 0 || !Double.isFinite(vertical)) return;
    if (Math.signum(vertical) != Math.signum(wheelRemainder)) wheelRemainder = 0;
    wheelRemainder += vertical;
    int steps = (int) wheelRemainder;
    wheelRemainder -= steps;
    int key = Q3KeyMap.wheel(steps > 0);
    long time = clock.millis();
    for (int i = Math.min(Math.abs(steps), MAX_WHEEL_STEPS); i > 0; i--) {
      sink.key(key, true, time);
      sink.key(key, false, time);
    }
  }

  /** Relative motion accumulated since the last frame; dropped unless the cursor is grabbed. */
  public void mouseMoved(double dx, double dy) {
    if (!poll() || !grabbed || (dx == 0 && dy == 0)) return;
    sink.mouseMove(dx, dy, clock.millis());
  }

  /** Whether the cursor should be grabbed while focused. Applied immediately. */
  public void setMouseCaptured(boolean captured) {
    mouseWanted = captured;
    poll();
  }

  /** Sends an up event for every held key and forgets them. */
  public void releaseAll() {
    held.clear();
    wheelRemainder = 0;
    swallowChars = false;
    long time = clock.millis();
    for (int key = 0; key < downCount.length; key++) {
      if (downCount[key] == 0) continue;
      downCount[key] = 0;
      sink.key(key, false, time);
    }
  }

  /** Releases every key and the cursor; input is ignored until {@link #open}. Idempotent. */
  public void close() {
    if (closed) return;
    closed = true;
    try {
      releaseAll();
    } finally {
      setGrab(false);
    }
  }

  public boolean cursorGrabbed() {
    return grabbed;
  }

  public boolean mouseCaptured() {
    return mouseWanted;
  }

  public boolean closed() {
    return closed;
  }

  /** Whether the capture holds {@code q3Key} down. */
  public boolean isDown(int q3Key) {
    return q3Key >= 0 && q3Key < downCount.length && downCount[q3Key] > 0;
  }

  private void press(int physical, int key) {
    long time = clock.millis();
    Integer previous = held.put(physical, key);
    if (previous != null) {
      // Auto-repeat of a key already down.
      sink.key(key, true, time);
      return;
    }
    if (downCount[key]++ == 0) sink.key(key, true, time);
  }

  private void release(int physical) {
    Integer key = held.remove(physical);
    if (key == null) return;
    if (--downCount[key] == 0) sink.key(key, false, clock.millis());
  }

  private void setGrab(boolean grab) {
    if (grab == grabbed) return;
    grabbed = grab;
    if (grab) host.grabCursor();
    else host.releaseCursor();
  }

  private static int physicalKey(int glfwKey, int scancode) {
    return glfwKey != GLFW_KEY_UNKNOWN ? glfwKey : SCANCODE_ID | (scancode & 0xffffff);
  }
}
