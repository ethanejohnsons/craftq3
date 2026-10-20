package dev.bluevista.craftq3.mc.fabric.client.input;

import static org.lwjgl.glfw.GLFW.*;

import dev.bluevista.craftq3.engine.console.KeyNames;
import java.util.Arrays;
import net.minecraft.util.Util;

/**
 * GLFW keys, mouse buttons and the wheel as Quake III key numbers ({@link KeyNames}).
 *
 * <p><b>Positional keys.</b> GLFW key codes name physical positions on a US layout on every
 * platform (XKB key names on X11 and Wayland, scancodes on Windows, virtual key codes on macOS).
 * Quake III key events are positional too: the original Windows engine read scancodes through a US
 * table. So the key labelled {@code Z} on an AZERTY keyboard sends {@code w}, and WASD binds stay
 * where the player's fingers are. Text is unaffected: the console and UI fields read character
 * events, which follow the active layout. Printable keys arrive unshifted and with letters
 * lower-cased, as Quake III key events do.
 *
 * <p><b>Console key.</b> The key below Escape and left of 1 always sends {@code `}, which Quake III
 * binds to {@code toggleconsole}, whatever the layout prints on it ({@code ^} on German, {@code ²}
 * on French, 半角/全角 on Japanese keyboards). It is matched by GLFW key ({@link
 * org.lwjgl.glfw.GLFW#GLFW_KEY_GRAVE_ACCENT}) and, for keyboards where GLFW has no key token for
 * it, by the platform's fixed scancode for that position: {@code 0x29} on Windows, keycode 49
 * (evdev 41 + 8) under X11 and Wayland, and {@code kVK_ANSI_Grave} (50) or {@code kVK_ISO_Section}
 * (10) on macOS.
 *
 * <p>Keys Quake III has no number for (Scroll Lock, Print Screen, Menu, F16-F25, the ISO "world"
 * keys) are {@link #UNMAPPED}.
 */
public final class Q3KeyMap {
  /** Returned for input Quake III has no key number for. */
  public static final int UNMAPPED = -1;

  /** The keyboard scancode family of the host platform. */
  public enum Platform {
    WINDOWS(0x29),
    MACOS(0x32, 0x0A),
    /** X11 and Wayland (XKB keycodes); also the BSDs. */
    LINUX(49),
    /** No fixed console scancode is known; only the GLFW key is matched. */
    OTHER;

    private final int[] consoleScancodes;

    Platform(int... consoleScancodes) {
      this.consoleScancodes = consoleScancodes;
    }

    /** Scancodes of the key below Escape on this platform. */
    public int[] consoleScancodes() {
      return consoleScancodes.clone();
    }
  }

  /** The console toggle key number ({@code `}). */
  public static final int CONSOLE = '`';

  private static final int[] KEYS = new int[GLFW_KEY_LAST + 1];

  static {
    Arrays.fill(KEYS, UNMAPPED);
    // Printable keys: GLFW names them by their US ASCII character.
    for (int key : new int[] {GLFW_KEY_SPACE, GLFW_KEY_APOSTROPHE, GLFW_KEY_SEMICOLON})
      KEYS[key] = key;
    for (int key = GLFW_KEY_COMMA; key <= GLFW_KEY_9; key++) KEYS[key] = key; // , - . / 0-9
    KEYS[GLFW_KEY_EQUAL] = '=';
    for (int key = GLFW_KEY_A; key <= GLFW_KEY_Z; key++) KEYS[key] = key + ('a' - 'A');
    KEYS[GLFW_KEY_LEFT_BRACKET] = '[';
    KEYS[GLFW_KEY_BACKSLASH] = '\\';
    KEYS[GLFW_KEY_RIGHT_BRACKET] = ']';
    KEYS[GLFW_KEY_GRAVE_ACCENT] = CONSOLE;

    KEYS[GLFW_KEY_ESCAPE] = KeyNames.ESCAPE;
    KEYS[GLFW_KEY_ENTER] = KeyNames.ENTER;
    KEYS[GLFW_KEY_TAB] = KeyNames.TAB;
    KEYS[GLFW_KEY_BACKSPACE] = KeyNames.BACKSPACE;
    KEYS[GLFW_KEY_INSERT] = KeyNames.INS;
    KEYS[GLFW_KEY_DELETE] = KeyNames.DEL;
    KEYS[GLFW_KEY_RIGHT] = KeyNames.RIGHTARROW;
    KEYS[GLFW_KEY_LEFT] = KeyNames.LEFTARROW;
    KEYS[GLFW_KEY_DOWN] = KeyNames.DOWNARROW;
    KEYS[GLFW_KEY_UP] = KeyNames.UPARROW;
    KEYS[GLFW_KEY_PAGE_UP] = KeyNames.PGUP;
    KEYS[GLFW_KEY_PAGE_DOWN] = KeyNames.PGDN;
    KEYS[GLFW_KEY_HOME] = KeyNames.HOME;
    KEYS[GLFW_KEY_END] = KeyNames.END;
    KEYS[GLFW_KEY_CAPS_LOCK] = KeyNames.CAPSLOCK;
    KEYS[GLFW_KEY_NUM_LOCK] = KeyNames.KP_NUMLOCK;
    KEYS[GLFW_KEY_PAUSE] = KeyNames.PAUSE;
    for (int i = 0; i < 15; i++) KEYS[GLFW_KEY_F1 + i] = KeyNames.F1 + i;

    // The keypad by position, independent of Num Lock, as Quake III names it.
    KEYS[GLFW_KEY_KP_0] = KeyNames.KP_INS;
    KEYS[GLFW_KEY_KP_1] = KeyNames.KP_END;
    KEYS[GLFW_KEY_KP_2] = KeyNames.KP_DOWNARROW;
    KEYS[GLFW_KEY_KP_3] = KeyNames.KP_PGDN;
    KEYS[GLFW_KEY_KP_4] = KeyNames.KP_LEFTARROW;
    KEYS[GLFW_KEY_KP_5] = KeyNames.KP_5;
    KEYS[GLFW_KEY_KP_6] = KeyNames.KP_RIGHTARROW;
    KEYS[GLFW_KEY_KP_7] = KeyNames.KP_HOME;
    KEYS[GLFW_KEY_KP_8] = KeyNames.KP_UPARROW;
    KEYS[GLFW_KEY_KP_9] = KeyNames.KP_PGUP;
    KEYS[GLFW_KEY_KP_DECIMAL] = KeyNames.KP_DEL;
    KEYS[GLFW_KEY_KP_DIVIDE] = KeyNames.KP_SLASH;
    KEYS[GLFW_KEY_KP_MULTIPLY] = KeyNames.KP_STAR;
    KEYS[GLFW_KEY_KP_SUBTRACT] = KeyNames.KP_MINUS;
    KEYS[GLFW_KEY_KP_ADD] = KeyNames.KP_PLUS;
    KEYS[GLFW_KEY_KP_ENTER] = KeyNames.KP_ENTER;
    KEYS[GLFW_KEY_KP_EQUAL] = KeyNames.KP_EQUALS;

    // Quake III has one key per modifier; left and right share it.
    KEYS[GLFW_KEY_LEFT_SHIFT] = KEYS[GLFW_KEY_RIGHT_SHIFT] = KeyNames.SHIFT;
    KEYS[GLFW_KEY_LEFT_CONTROL] = KEYS[GLFW_KEY_RIGHT_CONTROL] = KeyNames.CTRL;
    KEYS[GLFW_KEY_LEFT_ALT] = KEYS[GLFW_KEY_RIGHT_ALT] = KeyNames.ALT;
    KEYS[GLFW_KEY_LEFT_SUPER] = KEYS[GLFW_KEY_RIGHT_SUPER] = KeyNames.COMMAND;
  }

  private final Platform platform;
  private final int[] consoleScancodes;

  private Q3KeyMap(Platform platform) {
    this.platform = platform;
    this.consoleScancodes = platform.consoleScancodes;
  }

  /** The map for an explicit platform (tests, or a host that knows better). */
  public static Q3KeyMap forPlatform(Platform platform) {
    return new Q3KeyMap(platform);
  }

  /** The map for the platform Minecraft is running on. */
  public static Q3KeyMap forHost() {
    return forPlatform(
        switch (Util.getPlatform()) {
          case WINDOWS -> Platform.WINDOWS;
          case OSX -> Platform.MACOS;
          case LINUX, SOLARIS -> Platform.LINUX;
          default -> Platform.OTHER;
        });
  }

  public Platform platform() {
    return platform;
  }

  /**
   * The Quake III key for a GLFW key event.
   *
   * @param glfwKey the GLFW key token, or {@code GLFW_KEY_UNKNOWN}
   * @param scancode the platform scancode from the same event
   * @return the key number, or {@link #UNMAPPED}
   */
  public int key(int glfwKey, int scancode) {
    if (isConsoleKey(glfwKey, scancode)) return CONSOLE;
    return glfwKey >= 0 && glfwKey < KEYS.length ? KEYS[glfwKey] : UNMAPPED;
  }

  /** Whether a key event comes from the console key (the key below Escape). */
  public boolean isConsoleKey(int glfwKey, int scancode) {
    if (glfwKey == GLFW_KEY_GRAVE_ACCENT) return true;
    // A key GLFW maps to some other token sits elsewhere; only tokenless keys and the ISO
    // section key (GLFW_KEY_WORLD_1 on macOS) fall back to the scancode.
    if (glfwKey != GLFW_KEY_UNKNOWN && glfwKey != GLFW_KEY_WORLD_1) return false;
    for (int code : consoleScancodes) if (code == scancode) return true;
    return false;
  }

  /**
   * The Quake III key for a GLFW mouse button: left, right and middle are MOUSE1-3, the two side
   * buttons MOUSE4 and MOUSE5.
   *
   * @return the key number, or {@link #UNMAPPED} for buttons 6-8
   */
  public static int mouseButton(int button) {
    return button >= GLFW_MOUSE_BUTTON_1 && button <= GLFW_MOUSE_BUTTON_5
        ? KeyNames.MOUSE1 + button
        : UNMAPPED;
  }

  /** {@code MWHEELUP} for a wheel step away from the user, {@code MWHEELDOWN} towards. */
  public static int wheel(boolean up) {
    return up ? KeyNames.MWHEELUP : KeyNames.MWHEELDOWN;
  }
}
