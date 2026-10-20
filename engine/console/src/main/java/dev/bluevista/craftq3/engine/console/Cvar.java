package dev.bluevista.craftq3.engine.console;

import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import java.util.Optional;

/**
 * One console variable. Instances are owned by their {@link CvarTable} and change only through it;
 * every accessor checks the table's owner thread. The handle is the creation index and never
 * changes, so VM {@code vmCvar_t} handles can refer to it.
 */
public final class Cvar {
  private final ThreadOwner owner;
  private final int handle;
  private final String name;
  String string;
  String reset;
  String latched;
  int flags;
  int modificationCount;
  boolean modified;
  private float value;
  private int integer;

  Cvar(ThreadOwner owner, int handle, String name, String value, int flags) {
    this.owner = owner;
    this.handle = handle;
    this.name = name;
    this.reset = value;
    this.flags = flags;
    this.modificationCount = 1;
    this.modified = true;
    assign(value);
  }

  /** Stores a new value and refreshes the numeric views, like {@code atof}/{@code atoi}. */
  void assign(String newValue) {
    string = newValue;
    value = parseFloat(newValue);
    integer = parseInt(newValue);
  }

  public int handle() {
    owner.check();
    return handle;
  }

  /** The name as first registered; lookups are case-insensitive. */
  public String name() {
    owner.check();
    return name;
  }

  public String string() {
    owner.check();
    return string;
  }

  public String resetString() {
    owner.check();
    return reset;
  }

  public Optional<String> latchedString() {
    owner.check();
    return Optional.ofNullable(latched);
  }

  public int flags() {
    owner.check();
    return flags;
  }

  public boolean hasAny(int mask) {
    owner.check();
    return (flags & mask) != 0;
  }

  /** Starts at 1 and increments on every change or latch, as {@code vmCvar_t} tracking expects. */
  public int modificationCount() {
    owner.check();
    return modificationCount;
  }

  /** Set by every change or latch until {@link #clearModified()}, like {@code cvar_t.modified}. */
  public boolean modified() {
    owner.check();
    return modified;
  }

  public void clearModified() {
    owner.check();
    modified = false;
  }

  /** The {@code atof} view of the value. */
  public float value() {
    owner.check();
    return value;
  }

  /** The {@code atoi} view of the value. */
  public int integer() {
    owner.check();
    return integer;
  }

  @Override
  public String toString() {
    return name + " = \"" + string + "\"";
  }

  /**
   * C {@code atoi}: leading whitespace, an optional sign and decimal digits; anything else ends the
   * number. Overflow saturates at the int range.
   */
  static int parseInt(String text) {
    int i = skipSpace(text, 0);
    boolean negative = false;
    if (i < text.length() && (text.charAt(i) == '-' || text.charAt(i) == '+'))
      negative = text.charAt(i++) == '-';
    long result = 0;
    while (i < text.length()) {
      char c = text.charAt(i++);
      if (c < '0' || c > '9') break;
      result = Math.min(result * 10 + (c - '0'), 1L + Integer.MAX_VALUE);
    }
    return Math.clamp(negative ? -result : result, Integer.MIN_VALUE, Integer.MAX_VALUE);
  }

  /**
   * C {@code atof} for decimal input: whitespace, sign, digits with an optional fraction and
   * exponent. Text that is not a number gives 0.
   */
  static float parseFloat(String text) {
    int start = skipSpace(text, 0);
    int i = start;
    int n = text.length();
    if (i < n && (text.charAt(i) == '-' || text.charAt(i) == '+')) i++;
    int digits = 0;
    while (i < n && isDigit(text.charAt(i))) {
      i++;
      digits++;
    }
    if (i < n && text.charAt(i) == '.') {
      i++;
      while (i < n && isDigit(text.charAt(i))) {
        i++;
        digits++;
      }
    }
    if (digits == 0) return 0;
    int mantissaEnd = i;
    if (i < n && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
      int e = i + 1;
      if (e < n && (text.charAt(e) == '-' || text.charAt(e) == '+')) e++;
      int expStart = e;
      while (e < n && isDigit(text.charAt(e))) e++;
      if (e > expStart) mantissaEnd = e;
    }
    return Float.parseFloat(text.substring(start, mantissaEnd));
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static int skipSpace(String text, int i) {
    while (i < text.length() && text.charAt(i) <= ' ') i++;
    return i;
  }
}
