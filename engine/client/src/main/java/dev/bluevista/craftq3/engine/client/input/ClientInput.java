package dev.bluevista.craftq3.engine.client.input;

import dev.bluevista.craftq3.engine.console.CommandArgs;
import dev.bluevista.craftq3.engine.console.CommandBuffer;
import dev.bluevista.craftq3.engine.console.CommandRegistry;
import dev.bluevista.craftq3.engine.console.CvarFlags;
import dev.bluevista.craftq3.engine.console.CvarTable;
import dev.bluevista.craftq3.engine.console.KeyBindings;
import dev.bluevista.craftq3.engine.console.KeyNames;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout.UserCmd;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Platform-neutral client input: key and mouse events in, key catchers and bindings, and user
 * command generation out.
 *
 * <h2>Routing ({@code CL_KeyEvent})</h2>
 *
 * <ul>
 *   <li>The console key toggles the console.
 *   <li>Escape clears message mode, then the cgame catcher; without a ui catcher it asks the {@link
 *       Hooks} to open a menu; otherwise it goes to the ui.
 *   <li>Other key presses go, in order, to the console, ui, cgame or message target named by the
 *       catcher mask, to the console while disconnected, and otherwise to the key's binding, whose
 *       {@code +commands} carry the key number and time.
 *   <li>Releases always release a held binding (so a button never sticks) and are also reported to
 *       a catching ui or cgame.
 *   <li>Characters ({@link #charEvent}) go to the console, the ui (with {@link #K_CHAR_FLAG}) or
 *       message mode.
 *   <li>Mouse motion goes to a catching ui or cgame, and otherwise turns the view.
 * </ul>
 *
 * <h2>User commands ({@code CL_CreateCmd})</h2>
 *
 * The {@code +forward}.. buttons track every key holding them and the time each was held within the
 * frame, so a tap shorter than a frame still moves a fraction. Keyboard turning uses {@code
 * cl_yawspeed}/{@code cl_pitchspeed} ({@code cl_anglespeedkey} with {@code +speed}), mouse turning
 * {@code sensitivity} (times the cgame's zoom scale), {@code m_yaw}, {@code m_pitch}, {@code
 * m_filter} and {@code cl_mouseAccel}; {@code cl_run} selects running unless {@code +speed} inverts
 * it. Pitch is clamped against the server's delta angle.
 *
 * <p>Single owner thread (the render thread).
 */
public final class ClientInput {
  /** Key catcher bits ({@code KEYCATCH_*}). */
  public static final int KEYCATCH_CONSOLE = 1,
      KEYCATCH_UI = 2,
      KEYCATCH_MESSAGE = 4,
      KEYCATCH_CGAME = 8;

  /** Added to a character code delivered to the ui as a key event. */
  public static final int K_CHAR_FLAG = 1024;

  /** {@code usercmd_t.buttons} bits. */
  public static final int BUTTON_ATTACK = 1,
      BUTTON_TALK = 2,
      BUTTON_USE_HOLDABLE = 4,
      BUTTON_GESTURE = 8,
      BUTTON_WALKING = 16,
      BUTTON_ANY = 2048;

  /** The console toggle keys. */
  public static final int KEY_CONSOLE = '`', KEY_CONSOLE_ALT = '~';

  private static final int BUTTON_COUNT = 15;
  private static final String[] MOVES = {
    "forward",
    "back",
    "moveleft",
    "moveright",
    "moveup",
    "movedown",
    "left",
    "right",
    "lookup",
    "lookdown",
    "speed",
    "strafe",
    "mlook"
  };

  /** Session callbacks for keys the input layer cannot resolve itself. */
  public interface Hooks {
    /** Escape with no catcher: open the in-game menu, or disconnect to the main menu. */
    default void escapeToMenu() {}

    /** Escape cleared the cgame catcher ({@code CG_EVENT_HANDLING CGAME_EVENT_NONE}). */
    default void cgameCatcherCleared() {}

    /** True while disconnected: unbound presses then go to the console. */
    default boolean disconnected() {
      return false;
    }
  }

  private final CvarTable cvars;
  private final CommandRegistry commands;
  private final CommandBuffer buffer;
  private final KeyBindings bindings;
  private final Map<String, Button> moves = new LinkedHashMap<>();
  private final Button[] buttons = new Button[BUTTON_COUNT];
  private final boolean[] down = new boolean[KeyNames.MAX_KEYS];
  private final int[] repeats = new int[KeyNames.MAX_KEYS];
  private final List<String> registered = new ArrayList<>();
  private KeyTarget console = KeyTarget.NONE;
  private KeyTarget ui = KeyTarget.NONE;
  private KeyTarget cgame = KeyTarget.NONE;
  private KeyTarget message = KeyTarget.NONE;
  private Hooks hooks = new Hooks() {};
  private int catchers;
  private int anyKeyDown;
  private boolean overstrike;
  private int time;
  private int frameStart;
  private int frameMillis = 1;
  private double mouseX, mouseY, oldMouseX, oldMouseY;
  private double pitch, yaw, roll;

  /**
   * Registers the button commands and input cvars on a client console.
   *
   * @param bindings the console's key bindings
   */
  public ClientInput(CvarTable cvars, CommandBuffer buffer, KeyBindings bindings) {
    this.cvars = Objects.requireNonNull(cvars);
    this.buffer = Objects.requireNonNull(buffer);
    this.commands = buffer.registry();
    this.bindings = Objects.requireNonNull(bindings);
    for (String name : MOVES) {
      Button button = new Button();
      moves.put(name, button);
      registerButton(name, button);
    }
    for (int i = 0; i < BUTTON_COUNT; i++) {
      buttons[i] = new Button();
      registerButton("button" + i, buttons[i]);
    }
    registerButton("attack", buttons[0]);
    register("centerview", args -> pitch = 0);
    cvars.register("sensitivity", "5", CvarFlags.ARCHIVE);
    cvars.register("m_pitch", "0.022", CvarFlags.ARCHIVE);
    cvars.register("m_yaw", "0.022", CvarFlags.ARCHIVE);
    cvars.register("m_forward", "0.25", CvarFlags.ARCHIVE);
    cvars.register("m_side", "0.25", CvarFlags.ARCHIVE);
    cvars.register("m_filter", "0", CvarFlags.ARCHIVE);
    cvars.register("cl_mouseAccel", "0", CvarFlags.ARCHIVE);
    cvars.register("cl_run", "1", CvarFlags.ARCHIVE);
    cvars.register("cl_yawspeed", "140", CvarFlags.ARCHIVE);
    cvars.register("cl_pitchspeed", "140", CvarFlags.ARCHIVE);
    cvars.register("cl_anglespeedkey", "1.5", 0);
    cvars.register("cl_freelook", "1", CvarFlags.ARCHIVE);
  }

  private void registerButton(String name, Button button) {
    register("+" + name, args -> button.press(key(args), stamp(args)));
    register("-" + name, args -> button.release(key(args), stamp(args)));
  }

  private void register(String name, dev.bluevista.craftq3.engine.console.CommandHandler handler) {
    if (commands.register(name, handler)) registered.add(name);
  }

  /** Unregisters the commands this input added. */
  public void close() {
    for (String name : registered) commands.unregister(name);
    registered.clear();
  }

  public KeyBindings bindings() {
    return bindings;
  }

  public void setHooks(Hooks value) {
    hooks = Objects.requireNonNull(value);
  }

  public void setConsoleTarget(KeyTarget target) {
    console = Objects.requireNonNull(target);
  }

  public void setUiTarget(KeyTarget target) {
    ui = Objects.requireNonNull(target);
  }

  public void setCgameTarget(KeyTarget target) {
    cgame = Objects.requireNonNull(target);
  }

  public void setMessageTarget(KeyTarget target) {
    message = Objects.requireNonNull(target);
  }

  // ---------------------------------------------------------------- catchers and key state

  /** {@code trap_Key_GetCatcher}. */
  public int catchers() {
    return catchers;
  }

  /** {@code trap_Key_SetCatcher}; releasing every catcher also releases held bindings. */
  public void setCatchers(int value) {
    int previous = catchers;
    catchers = value;
    if (previous != 0 && value == 0) clearStates();
  }

  public void toggleConsole() {
    setCatchers(catchers ^ KEYCATCH_CONSOLE);
  }

  /** {@code trap_Key_IsDown}. */
  public boolean isDown(int key) {
    return key >= 0 && key < down.length && down[key];
  }

  public boolean overstrike() {
    return overstrike;
  }

  public void setOverstrike(boolean value) {
    overstrike = value;
  }

  /** {@code trap_Key_ClearStates}: forgets every held key and releases every binding. */
  public void clearStates() {
    anyKeyDown = 0;
    for (int key = 0; key < down.length; key++) {
      if (down[key]) {
        down[key] = false;
        repeats[key] = 0;
      }
    }
    for (String line : bindings.releaseAll(time)) buffer.add(line + "\n");
  }

  // ---------------------------------------------------------------- events

  /** A key went down or up at {@code eventTime} (milliseconds, monotonic). */
  public void keyEvent(int key, boolean pressed, int eventTime) {
    if (key < 0 || key >= KeyNames.MAX_KEYS) return;
    time = Math.max(time, eventTime);
    if (pressed) {
      repeats[key]++;
      if (repeats[key] == 1) anyKeyDown++;
    } else {
      repeats[key] = 0;
      if (down[key]) anyKeyDown = Math.max(0, anyKeyDown - 1);
    }
    down[key] = pressed;

    if (pressed && (key == KEY_CONSOLE || key == KEY_CONSOLE_ALT)) {
      toggleConsole();
      return;
    }
    if (pressed && key == KeyNames.ESCAPE) {
      if ((catchers & KEYCATCH_MESSAGE) != 0) {
        message.keyEvent(key, true, time);
        return;
      }
      if ((catchers & KEYCATCH_CGAME) != 0) {
        catchers &= ~KEYCATCH_CGAME;
        hooks.cgameCatcherCleared();
        return;
      }
      if ((catchers & KEYCATCH_UI) == 0) {
        hooks.escapeToMenu();
        return;
      }
      ui.keyEvent(key, true, time);
      return;
    }
    if (!pressed) {
      for (String line : bindings.release(key, time)) buffer.add(line + "\n");
      if ((catchers & KEYCATCH_UI) != 0) ui.keyEvent(key, false, time);
      else if ((catchers & KEYCATCH_CGAME) != 0) cgame.keyEvent(key, false, time);
      return;
    }
    if ((catchers & KEYCATCH_CONSOLE) != 0) console.keyEvent(key, true, time);
    else if ((catchers & KEYCATCH_UI) != 0) ui.keyEvent(key, true, time);
    else if ((catchers & KEYCATCH_CGAME) != 0) cgame.keyEvent(key, true, time);
    else if ((catchers & KEYCATCH_MESSAGE) != 0) message.keyEvent(key, true, time);
    else if (hooks.disconnected()) console.keyEvent(key, true, time);
    else for (String line : bindings.press(key, time)) buffer.add(line + "\n");
  }

  /** A typed character (after keyboard layout), for text fields. */
  public void charEvent(int character, int eventTime) {
    time = Math.max(time, eventTime);
    if (character == KEY_CONSOLE || character == KEY_CONSOLE_ALT) return;
    if ((catchers & KEYCATCH_CONSOLE) != 0) console.keyEvent(character | K_CHAR_FLAG, true, time);
    else if ((catchers & KEYCATCH_UI) != 0) ui.keyEvent(character | K_CHAR_FLAG, true, time);
    else if ((catchers & KEYCATCH_MESSAGE) != 0)
      message.keyEvent(character | K_CHAR_FLAG, true, time);
    else if (hooks.disconnected()) console.keyEvent(character | K_CHAR_FLAG, true, time);
  }

  /** Relative mouse motion. */
  public void mouseEvent(int dx, int dy, int eventTime) {
    time = Math.max(time, eventTime);
    if ((catchers & KEYCATCH_UI) != 0) ui.mouseEvent(dx, dy, time);
    else if ((catchers & KEYCATCH_CGAME) != 0) cgame.mouseEvent(dx, dy, time);
    else {
      mouseX += dx;
      mouseY += dy;
    }
  }

  // ---------------------------------------------------------------- user commands

  /** View angles in degrees (pitch, yaw, roll) the next commands start from. */
  public void setViewAngles(double newPitch, double newYaw, double newRoll) {
    pitch = newPitch;
    yaw = newYaw;
    roll = newRoll;
  }

  public double[] viewAngles() {
    return new double[] {pitch, yaw, roll};
  }

  /**
   * Builds the command for one client frame ({@code CL_CreateCmd}).
   *
   * @param serverTime the command's server time
   * @param frameTime the frame's real time, which bounds button hold fractions
   * @param weapon the weapon the cgame selected ({@code trap_SetUserCmdValue})
   * @param sensitivityScale the cgame's zoom sensitivity scale
   * @param deltaPitch the server's {@code delta_angles[PITCH]} as a short angle
   */
  public UserCmd createCommand(
      int serverTime, int frameTime, int weapon, float sensitivityScale, int deltaPitch) {
    time = Math.max(time, frameTime);
    frameMillis = Math.max(1, frameTime - frameStart);
    int duration = frameMillis;
    frameStart = frameTime;
    Map<String, Sample> sampled = new LinkedHashMap<>();
    for (var entry : moves.entrySet())
      sampled.put(entry.getKey(), entry.getValue().sample(frameTime, duration));
    Sample[] buttonSamples = new Sample[BUTTON_COUNT];
    for (int i = 0; i < BUTTON_COUNT; i++)
      buttonSamples[i] = buttons[i].sample(frameTime, duration);

    boolean speed = sampled.get("speed").active();
    boolean strafe = sampled.get("strafe").active();
    boolean running = speed != (cvars.integer("cl_run") != 0);
    int movespeed = running ? 127 : 64;

    // Keyboard turning.
    double seconds = Math.min(duration, 200) * 0.001 * (speed ? number("cl_anglespeedkey") : 1);
    if (!strafe) yaw -= seconds * number("cl_yawspeed") * axis(sampled, "right", "left");
    pitch += seconds * number("cl_pitchspeed") * axis(sampled, "lookdown", "lookup");

    // Mouse.
    double dx = mouseX, dy = mouseY;
    mouseX = mouseY = 0;
    if (cvars.integer("m_filter") != 0) {
      double fx = (dx + oldMouseX) * 0.5, fy = (dy + oldMouseY) * 0.5;
      oldMouseX = dx;
      oldMouseY = dy;
      dx = fx;
      dy = fy;
    } else {
      oldMouseX = dx;
      oldMouseY = dy;
    }
    double rate = Math.sqrt(dx * dx + dy * dy) / duration;
    double scale =
        (number("sensitivity") + rate * number("cl_mouseAccel"))
            * (Float.isFinite(sensitivityScale) ? sensitivityScale : 1);
    dx *= scale;
    dy *= scale;

    double forward = movespeed * axis(sampled, "forward", "back");
    double side = movespeed * axis(sampled, "moveright", "moveleft");
    double up = movespeed * axis(sampled, "moveup", "movedown");
    if (strafe) side += movespeed * axis(sampled, "right", "left");
    if (strafe) side += number("m_side") * dx;
    else yaw -= number("m_yaw") * dx;
    boolean look = cvars.integer("cl_freelook") != 0 || sampled.get("mlook").active();
    if (look && !strafe) pitch += number("m_pitch") * dy;
    else forward -= number("m_forward") * dy;

    // Clamp pitch against the server's delta angle.
    double delta = shortToAngle(deltaPitch);
    if (pitch + delta > 90) pitch = 90 - delta;
    else if (pitch + delta < -90) pitch = -90 - delta;

    int bits = 0;
    for (int i = 0; i < BUTTON_COUNT; i++) if (buttonSamples[i].active()) bits |= 1 << i;
    if (catchers != 0) bits |= BUTTON_TALK;
    if (anyKeyDown > 0 && catchers == 0) bits |= BUTTON_ANY;
    if (!running) bits |= BUTTON_WALKING;
    return new UserCmd(
        serverTime,
        angleToShort(pitch),
        angleToShort(yaw),
        angleToShort(roll),
        bits,
        Math.clamp(weapon, 0, 255),
        clampMove(forward),
        clampMove(side),
        clampMove(up));
  }

  /** {@code ANGLE2SHORT}. */
  public static int angleToShort(double degrees) {
    return ((int) (degrees * 65536 / 360)) & 65535;
  }

  /** {@code SHORT2ANGLE} of a signed or unsigned short. */
  public static double shortToAngle(int value) {
    return (short) value * (360.0 / 65536);
  }

  private static int clampMove(double value) {
    return (int) Math.clamp(value, -127, 127);
  }

  private double number(String name) {
    float value = cvars.value(name);
    return Float.isFinite(value) ? value : 0;
  }

  private static double axis(Map<String, Sample> values, String positive, String negative) {
    return values.get(positive).fraction() - values.get(negative).fraction();
  }

  private static int key(CommandArgs args) {
    if (args.argc() < 2) return -1;
    try {
      return Integer.parseInt(args.argv(1));
    } catch (NumberFormatException typed) {
      return -1;
    }
  }

  private int stamp(CommandArgs args) {
    if (args.argc() < 3) return time;
    try {
      return Integer.parseInt(args.argv(2));
    } catch (NumberFormatException invalid) {
      return time;
    }
  }

  /** What one button did during a frame: the held fraction and whether it was down at all. */
  private record Sample(double fraction, boolean active) {}

  /** {@code kbutton_t}: any number of keys may hold a button; hold time accumulates per frame. */
  private static final class Button {
    private final Set<Integer> keys = new HashSet<>();
    private int downTime;
    private int msec;
    private boolean wasPressed;
    private boolean typed;

    void press(int key, int at) {
      if (key == -1) typed = true;
      else if (!keys.add(key)) return;
      if (keys.size() + (typed ? 1 : 0) > 1) return;
      downTime = at;
      wasPressed = true;
    }

    void release(int key, int at) {
      boolean held = !keys.isEmpty() || typed;
      if (key == -1) {
        keys.clear();
        typed = false;
      } else keys.remove(key);
      if (held && keys.isEmpty() && !typed) {
        msec += Math.max(0, at - downTime);
      }
    }

    boolean held() {
      return !keys.isEmpty() || typed;
    }

    Sample sample(int now, int duration) {
      int total = msec;
      msec = 0;
      if (held()) {
        total += Math.max(0, now - downTime);
        downTime = now;
      }
      boolean active = wasPressed || held();
      wasPressed = false;
      return new Sample(Math.clamp((double) total / duration, 0, 1), active);
    }
  }
}
