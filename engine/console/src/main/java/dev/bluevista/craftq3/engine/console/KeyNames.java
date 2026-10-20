package dev.bluevista.craftq3.engine.console;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Quake III key numbers ({@code keyNum_t}) and their console names, for {@code bind} and the config
 * file. Printable ASCII keys use their character; other unnamed keys use {@code 0xNN}.
 */
public final class KeyNames {
  public static final int TAB = 9;
  public static final int ENTER = 13;
  public static final int ESCAPE = 27;
  public static final int SPACE = 32;
  public static final int BACKSPACE = 127;
  public static final int COMMAND = 128;
  public static final int CAPSLOCK = 129;
  public static final int POWER = 130;
  public static final int PAUSE = 131;
  public static final int UPARROW = 132;
  public static final int DOWNARROW = 133;
  public static final int LEFTARROW = 134;
  public static final int RIGHTARROW = 135;
  public static final int ALT = 136;
  public static final int CTRL = 137;
  public static final int SHIFT = 138;
  public static final int INS = 139;
  public static final int DEL = 140;
  public static final int PGDN = 141;
  public static final int PGUP = 142;
  public static final int HOME = 143;
  public static final int END = 144;

  /** F1; F2..F15 follow consecutively. */
  public static final int F1 = 145;

  public static final int KP_HOME = 160;
  public static final int KP_UPARROW = 161;
  public static final int KP_PGUP = 162;
  public static final int KP_LEFTARROW = 163;
  public static final int KP_5 = 164;
  public static final int KP_RIGHTARROW = 165;
  public static final int KP_END = 166;
  public static final int KP_DOWNARROW = 167;
  public static final int KP_PGDN = 168;
  public static final int KP_ENTER = 169;
  public static final int KP_INS = 170;
  public static final int KP_DEL = 171;
  public static final int KP_SLASH = 172;
  public static final int KP_MINUS = 173;
  public static final int KP_PLUS = 174;
  public static final int KP_NUMLOCK = 175;
  public static final int KP_STAR = 176;
  public static final int KP_EQUALS = 177;

  /** MOUSE1; MOUSE2..MOUSE5 follow consecutively. */
  public static final int MOUSE1 = 178;

  public static final int MOUSE2 = 179;
  public static final int MOUSE3 = 180;
  public static final int MWHEELDOWN = 183;
  public static final int MWHEELUP = 184;

  /** JOY1; JOY2..JOY32 follow consecutively. */
  public static final int JOY1 = 185;

  /** AUX1; AUX2..AUX16 follow consecutively. */
  public static final int AUX1 = 217;

  /** Number of key slots (bindings array size). */
  public static final int MAX_KEYS = 256;

  private static final List<String> NAMES;
  private static final Map<String, Integer> BY_NAME;
  private static final String[] BY_KEY = new String[MAX_KEYS];

  static {
    List<String> names = new ArrayList<>();
    Map<String, Integer> byName = new HashMap<>();
    Object[] table = {
      "TAB",
      TAB,
      "ENTER",
      ENTER,
      "ESCAPE",
      ESCAPE,
      "SPACE",
      SPACE,
      "BACKSPACE",
      BACKSPACE,
      "UPARROW",
      UPARROW,
      "DOWNARROW",
      DOWNARROW,
      "LEFTARROW",
      LEFTARROW,
      "RIGHTARROW",
      RIGHTARROW,
      "ALT",
      ALT,
      "CTRL",
      CTRL,
      "SHIFT",
      SHIFT,
      "COMMAND",
      COMMAND,
      "CAPSLOCK",
      CAPSLOCK
    };
    for (int i = 0; i < table.length; i += 2)
      put(names, byName, (String) table[i], (int) table[i + 1]);
    for (int i = 0; i < 15; i++) put(names, byName, "F" + (i + 1), F1 + i);
    Object[] editing = {
      "INS", INS, "DEL", DEL, "PGDN", PGDN, "PGUP", PGUP, "HOME", HOME, "END", END
    };
    for (int i = 0; i < editing.length; i += 2)
      put(names, byName, (String) editing[i], (int) editing[i + 1]);
    for (int i = 0; i < 5; i++) put(names, byName, "MOUSE" + (i + 1), MOUSE1 + i);
    put(names, byName, "MWHEELUP", MWHEELUP);
    put(names, byName, "MWHEELDOWN", MWHEELDOWN);
    for (int i = 0; i < 32; i++) put(names, byName, "JOY" + (i + 1), JOY1 + i);
    for (int i = 0; i < 16; i++) put(names, byName, "AUX" + (i + 1), AUX1 + i);
    String[] keypad = {
      "KP_HOME",
      "KP_UPARROW",
      "KP_PGUP",
      "KP_LEFTARROW",
      "KP_5",
      "KP_RIGHTARROW",
      "KP_END",
      "KP_DOWNARROW",
      "KP_PGDN",
      "KP_ENTER",
      "KP_INS",
      "KP_DEL",
      "KP_SLASH",
      "KP_MINUS",
      "KP_PLUS",
      "KP_NUMLOCK",
      "KP_STAR",
      "KP_EQUALS"
    };
    for (int i = 0; i < keypad.length; i++) put(names, byName, keypad[i], KP_HOME + i);
    put(names, byName, "PAUSE", PAUSE);
    put(names, byName, "SEMICOLON", ';');
    NAMES = Collections.unmodifiableList(names);
    BY_NAME = Map.copyOf(byName);
  }

  private KeyNames() {}

  private static void put(List<String> names, Map<String, Integer> byName, String name, int key) {
    names.add(name);
    byName.put(name, key);
    if (BY_KEY[key] == null) BY_KEY[key] = name;
  }

  /**
   * {@code Key_StringToKeynum}: a single character is that key (letters folded to lower case, as
   * key events deliver them), {@code 0xNN} is a raw key number, otherwise a case-insensitive name.
   *
   * @return the key number, or -1 when unknown
   */
  public static int toKey(String name) {
    if (name == null || name.isEmpty()) return -1;
    if (name.length() == 1) {
      char c = name.charAt(0);
      if (c >= 'A' && c <= 'Z') return c + ('a' - 'A');
      return c < MAX_KEYS ? c : -1;
    }
    if (name.length() == 4
        && name.charAt(0) == '0'
        && (name.charAt(1) == 'x' || name.charAt(1) == 'X')) {
      int high = Character.digit(name.charAt(2), 16);
      int low = Character.digit(name.charAt(3), 16);
      if (high >= 0 && low >= 0) return high * 16 + low;
    }
    Integer key = BY_NAME.get(CvarTable.asciiUpper(name));
    return key == null ? -1 : key;
  }

  /**
   * {@code Key_KeynumToString}: printable ASCII other than quote and semicolon as itself, then the
   * name table, then {@code 0xNN}.
   */
  public static String toName(int key) {
    if (key == -1) return "<KEY NOT FOUND>";
    if (key < 0 || key >= MAX_KEYS) return "<OUT OF RANGE>";
    if (key > ' ' && key < 127 && key != '"' && key != ';') return String.valueOf((char) key);
    if (BY_KEY[key] != null) return BY_KEY[key];
    return String.format(java.util.Locale.ROOT, "0x%02x", key);
  }

  /** Every key name in the original table order, for completion. */
  public static List<String> names() {
    return NAMES;
  }
}
