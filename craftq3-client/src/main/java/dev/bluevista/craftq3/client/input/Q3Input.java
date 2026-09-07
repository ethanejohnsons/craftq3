package dev.bluevista.craftq3.client.input;

import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.core.command.CommandSystem;
import dev.bluevista.craftq3.core.command.KeyBindings;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.server.UserCommand;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Engine input only: produces commands; original game VMs perform all movement and weapon rules.
 */
public final class Q3Input {
  public static final int MOUSE1 = 178, MOUSE2 = 179, MOUSE3 = 180;
  public static final int WHEEL_DOWN = 183, WHEEL_UP = 184;
  private static final Map<String, Integer> NAMED_KEYS = namedKeys();
  private final CvarSystem cvars;
  private final CommandSystem commands;
  private final KeyBindings bindings = new KeyBindings();
  private final Map<String, Button> actions = new LinkedHashMap<>();
  private final Set<Integer> physicalKeys = new HashSet<>();
  private int time, previousSample;
  private double pitch, yaw, roll, mouseX, mouseY;

  public Q3Input(CvarSystem cvars, CommandSystem commands, int startTime) {
    this.cvars = cvars;
    this.commands = commands;
    time = previousSample = startTime;
    for (String name :
        new String[] {
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
          "mlook",
          "attack",
          "button2",
          "gesture"
        }) {
      Button button = new Button();
      actions.put(name, button);
      commands.register("+" + name, command -> button.press(key(command), timestamp(command)));
      commands.register("-" + name, command -> button.release(key(command), timestamp(command)));
    }
    for (int i = 0; i < 15; i++) {
      if (i == 2) continue; // +button2 is the holdable-item action above.
      String name = "button" + i;
      Button button =
          i == 0 ? actions.get("attack") : i == 3 ? actions.get("gesture") : new Button();
      if (i != 0 && i != 3) actions.put(name, button);
      commands.register("+" + name, command -> button.press(key(command), timestamp(command)));
      commands.register("-" + name, command -> button.release(key(command), timestamp(command)));
    }
    commands.register(
        "bind",
        command -> {
          int key = keyNumber(command.argument(1));
          if (command.arguments().size() < 3) return;
          bindings.bind(key, command.argumentsFrom(2));
        });
    commands.register("unbind", command -> bindings.bind(keyNumber(command.argument(1)), ""));
    commands.register("unbindall", command -> bindings.unbindAll());
    cvars.register("sensitivity", "5", CvarSystem.ARCHIVE);
    cvars.register("m_pitch", "0.022", CvarSystem.ARCHIVE);
    cvars.register("m_yaw", "0.022", CvarSystem.ARCHIVE);
    cvars.register("m_forward", "0.25", CvarSystem.ARCHIVE);
    cvars.register("m_side", "0.25", CvarSystem.ARCHIVE);
    cvars.register("cl_run", "1", CvarSystem.ARCHIVE);
    cvars.register("cl_yawspeed", "140", CvarSystem.ARCHIVE);
    cvars.register("cl_pitchspeed", "140", CvarSystem.ARCHIVE);
    cvars.register("cl_anglespeedkey", "1.5", CvarSystem.ARCHIVE);
    cvars.register("cl_freelook", "1", CvarSystem.ARCHIVE);
    bind('w', "+forward");
    bind('s', "+back");
    bind('a', "+moveleft");
    bind('d', "+moveright");
    bind(32, "+moveup");
    bind('c', "+movedown");
    bind(138, "+speed");
    bind(137, "+movedown");
    bind(134, "+left");
    bind(135, "+right");
    bind(132, "+forward");
    bind(133, "+back");
    bind(MOUSE1, "+attack");
    bind(MOUSE2, "+moveup");
    bind(13, "+button2");
    bind('g', "+gesture");
    bind(9, "+scores");
    bind(WHEEL_UP, "weapprev");
    bind(WHEEL_DOWN, "weapnext");
    for (int i = 1; i <= 9; i++) bind('0' + i, "weapon " + i);
  }

  private void bind(int key, String text) {
    bindings.bind(key, text);
  }

  public KeyBindings bindings() {
    return bindings;
  }

  public void key(int key, boolean down, int milliseconds) {
    if (milliseconds < time) milliseconds = time;
    time = milliseconds;
    if (down) {
      physicalKeys.add(key);
      bindings.press(key, time).forEach(this::execute);
    } else {
      physicalKeys.remove(key);
      bindings.release(key, time).forEach(this::execute);
    }
  }

  public void mouse(double dx, double dy) {
    if (!Double.isFinite(dx) || !Double.isFinite(dy)) return;
    mouseX = Math.clamp(mouseX + dx, -100000, 100000);
    mouseY = Math.clamp(mouseY + dy, -100000, 100000);
  }

  public void releaseAll(int milliseconds) {
    time = Math.max(time, milliseconds);
    bindings.releaseAll(time).forEach(this::execute);
    actions.values().forEach(Button::clear);
    physicalKeys.clear();
    mouseX = mouseY = 0;
    previousSample = time;
  }

  /** Sets command angles at initial connection. Server delta angles remain game-owned. */
  public void angles(double pitch, double yaw, double roll) {
    if (!Double.isFinite(pitch) || !Double.isFinite(yaw) || !Double.isFinite(roll))
      throw new IllegalArgumentException("Nonfinite input angles");
    this.pitch = pitch;
    this.yaw = yaw;
    this.roll = roll;
  }

  public UserCommand sample(int serverTime, int weapon, float sensitivityScale, int deltaPitch) {
    if (serverTime < previousSample)
      throw new IllegalArgumentException("Input time moved backwards");
    int duration = Math.max(1, serverTime - previousSample);
    time = Math.max(time, serverTime);
    Map<String, Sample> values = new HashMap<>();
    actions.forEach((name, button) -> values.put(name, button.sample(serverTime, duration)));
    previousSample = serverTime;
    boolean speed = values.get("speed").active(), strafe = values.get("strafe").active();
    boolean walking = speed == (cvars.integer("cl_run") != 0);
    double seconds = Math.min(duration, 200) * .001 * (speed ? number("cl_anglespeedkey", 1.5) : 1);
    if (!strafe) yaw += seconds * number("cl_yawspeed", 140) * axis(values, "left", "right");
    pitch += seconds * number("cl_pitchspeed", 140) * axis(values, "lookdown", "lookup");
    double scale = Float.isFinite(sensitivityScale) ? Math.clamp(sensitivityScale, 0, 100) : 1;
    double dx = mouseX * number("sensitivity", 5) * scale;
    double dy = mouseY * number("sensitivity", 5) * scale;
    mouseX = mouseY = 0;
    double forward = axis(values, "forward", "back");
    double right = axis(values, "moveright", "moveleft");
    int move = walking ? 64 : 127;
    if (strafe) right += axis(values, "right", "left") + dx * number("m_side", .25) / move;
    else yaw -= dx * number("m_yaw", .022);
    if (!strafe && (cvars.integer("cl_freelook") != 0 || values.get("mlook").active()))
      pitch += dy * number("m_pitch", .022);
    else forward -= dy * number("m_forward", .25) / move;
    double delta = signedAngle(deltaPitch * (360.0 / 65536));
    pitch = Math.clamp(signedAngle(pitch + delta), -89.99, 89.99) - delta;
    yaw = signedAngle(yaw);
    int buttons =
        (values.get("attack").active() ? 1 : 0)
            | (values.get("button2").active() ? 4 : 0)
            | (values.get("gesture").active() ? 8 : 0)
            | (walking ? 16 : 0)
            | (physicalKeys.isEmpty() ? 0 : 2048);
    for (int i = 1; i < 15; i++) {
      if (i == 2 || i == 3) continue;
      if (values.get("button" + i).active()) buttons |= 1 << i;
    }
    return new UserCommand(
        serverTime,
        angleShort(pitch),
        angleShort(yaw),
        angleShort(roll),
        buttons,
        Math.clamp(weapon, 0, 255),
        movement(forward * move),
        movement(right * move),
        movement(axis(values, "moveup", "movedown") * move));
  }

  private static int movement(double value) {
    return (int) Math.clamp(value, -127, 127);
  }

  public static int angleShort(double value) {
    return (int) (value * (65536.0 / 360)) & 65535;
  }

  private static double signedAngle(double value) {
    return value - Math.floor((value + 180) / 360) * 360;
  }

  private double number(String name, double fallback) {
    float value = cvars.number(name);
    return Float.isFinite(value) ? Math.clamp(value, -10000, 10000) : fallback;
  }

  private static double axis(Map<String, Sample> values, String positive, String negative) {
    return values.get(positive).fraction() - values.get(negative).fraction();
  }

  private void execute(String text) {
    commands.submit(text, CommandSystem.Execution.NOW);
  }

  private static int key(CommandParser.Command command) {
    return command.arguments().size() > 1 ? Integer.parseInt(command.argument(1)) : -1;
  }

  private int timestamp(CommandParser.Command command) {
    return command.arguments().size() > 2
        ? Math.clamp(Integer.parseInt(command.argument(2)), previousSample, time)
        : time;
  }

  public static int keyNumber(String name) {
    if (name.length() == 1) return Character.toLowerCase(name.charAt(0));
    Integer key = NAMED_KEYS.get(name.toUpperCase(Locale.ROOT));
    if (key != null) return key;
    if (name.matches("0[xX][0-9a-fA-F]{2}")) return Integer.parseInt(name.substring(2), 16);
    throw new IllegalArgumentException("Unknown Quake key " + name);
  }

  private static Map<String, Integer> namedKeys() {
    Map<String, Integer> keys = new HashMap<>();
    keys.put("TAB", 9);
    keys.put("ENTER", 13);
    keys.put("ESCAPE", 27);
    keys.put("SPACE", 32);
    keys.put("BACKSPACE", 127);
    keys.put("UPARROW", 132);
    keys.put("DOWNARROW", 133);
    keys.put("LEFTARROW", 134);
    keys.put("RIGHTARROW", 135);
    keys.put("ALT", 136);
    keys.put("CTRL", 137);
    keys.put("SHIFT", 138);
    keys.put("INS", 139);
    keys.put("DEL", 140);
    keys.put("PGDN", 141);
    keys.put("PGUP", 142);
    keys.put("HOME", 143);
    keys.put("END", 144);
    for (int i = 1; i <= 15; i++) keys.put("F" + i, 144 + i);
    for (int i = 1; i <= 5; i++) keys.put("MOUSE" + i, MOUSE1 + i - 1);
    keys.put("MWHEELDOWN", WHEEL_DOWN);
    keys.put("MWHEELUP", WHEEL_UP);
    keys.put("SEMICOLON", 59);
    return Map.copyOf(keys);
  }

  private record Sample(double fraction, boolean active) {}

  private static final class Button {
    private final Set<Integer> held = new HashSet<>();
    private int pressedAt, elapsed;
    private boolean pressed;

    void press(int key, int time) {
      if (!held.add(key)) return;
      if (held.size() == 1) {
        pressedAt = time;
        pressed = true;
      }
    }

    void release(int key, int time) {
      boolean wasDown = !held.isEmpty();
      if (key == -1) held.clear();
      else held.remove(key);
      if (wasDown && held.isEmpty()) elapsed += Math.max(0, time - pressedAt);
    }

    Sample sample(int time, int duration) {
      if (!held.isEmpty()) {
        elapsed += Math.max(0, time - pressedAt);
        pressedAt = time;
      }
      Sample result =
          new Sample(Math.clamp((double) elapsed / duration, 0, 1), pressed || !held.isEmpty());
      elapsed = 0;
      pressed = false;
      return result;
    }

    void clear() {
      held.clear();
      elapsed = 0;
      pressed = false;
    }
  }
}
