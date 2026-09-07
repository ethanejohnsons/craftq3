package dev.bluevista.craftq3.core.cvar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Session-owned cvars. Handles remain stable across registration and level restarts. */
public final class CvarSystem {
  public static final int ARCHIVE = 1,
      USERINFO = 2,
      SERVERINFO = 4,
      SYSTEMINFO = 8,
      INIT = 16,
      LATCH = 32,
      ROM = 64,
      USER_CREATED = 128,
      TEMP = 256,
      CHEAT = 512,
      NORESTART = 1024,
      SERVER_CREATED = 2048,
      VM_CREATED = 4096,
      PROTECTED = 8192;
  private static final int INFO_FLAGS = USERINFO | SERVERINFO | SYSTEMINFO;
  private static final int MAX_CVARS = 4096, MAX_VALUE = 8191;
  private static final Pattern NUMBER =
      Pattern.compile(
          "^[\\x00-\\x20]*([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)");

  public enum Source {
    CONSOLE,
    VM,
    ENGINE
  }

  public enum Change {
    CHANGED,
    UNCHANGED,
    LATCHED,
    READ_ONLY,
    CHEAT_PROTECTED,
    PROTECTED
  }

  public record Snapshot(
      int handle,
      String name,
      String value,
      String resetValue,
      Optional<String> latchedValue,
      int flags,
      int modificationCount) {
    public float floatValue() {
      return numeric(value);
    }

    public int intValue() {
      int index = 0, sign = 1, result = 0;
      while (index < value.length() && value.charAt(index) <= ' ') index++;
      if (index < value.length() && (value.charAt(index) == '-' || value.charAt(index) == '+')) {
        if (value.charAt(index) == '-') sign = -1;
        index++;
      }
      while (index < value.length()) {
        char digit = value.charAt(index++);
        if (digit < '0' || digit > '9') break;
        result = result * 10 + digit - '0';
      }
      return result * sign;
    }
  }

  private static final class Variable {
    final int handle;
    final String name;
    String value, reset, latched;
    int flags, modificationCount = 1;

    Variable(int handle, String name, String value, int flags) {
      this.handle = handle;
      this.name = name;
      this.value = value;
      reset = value;
      this.flags = flags;
    }

    Snapshot snapshot() {
      return new Snapshot(
          handle, name, value, reset, Optional.ofNullable(latched), flags, modificationCount);
    }
  }

  private final Map<String, Variable> variables = new LinkedHashMap<>();
  private final List<Variable> handles = new ArrayList<>();
  private boolean cheats;

  public Snapshot register(String name, String defaultValue, int flags) {
    String key = key(name);
    validateValue(defaultValue, flags);
    Variable variable = variables.get(key);
    if (variable == null) {
      if (variables.size() >= MAX_CVARS) throw new IllegalStateException("Cvar limit exceeded");
      variable = new Variable(handles.size(), name, defaultValue, flags);
      variables.put(key, variable);
      handles.add(variable);
    } else {
      validateValue(variable.value, variable.flags | flags);
      if (variable.latched != null) validateValue(variable.latched, variable.flags | flags);
      if ((variable.flags & USER_CREATED) != 0 && (flags & USER_CREATED) == 0) {
        variable.reset = defaultValue;
        variable.flags &= ~USER_CREATED;
      }
      variable.flags |= flags;
      // Registration at a map restart is where Q3 consumes a latched value.
      applyLatch(variable);
    }
    return variable.snapshot();
  }

  public Optional<Snapshot> find(String name) {
    Variable variable = variables.get(key(name));
    return variable == null ? Optional.empty() : Optional.of(variable.snapshot());
  }

  public Snapshot byHandle(int handle) {
    if (handle < 0 || handle >= handles.size())
      throw new IllegalArgumentException("Invalid cvar handle: " + handle);
    return handles.get(handle).snapshot();
  }

  public String string(String name) {
    return find(name).map(Snapshot::value).orElse("");
  }

  public int integer(String name) {
    return find(name).map(Snapshot::intValue).orElse(0);
  }

  public float number(String name) {
    return find(name).map(Snapshot::floatValue).orElse(0f);
  }

  public Change set(String name, String value, Source source) {
    Variable variable = variables.get(key(name));
    if (variable == null) {
      register(
          name,
          value,
          source == Source.CONSOLE ? USER_CREATED : source == Source.VM ? VM_CREATED : 0);
      return Change.CHANGED;
    }
    validateValue(value, variable.flags);
    if (source == Source.VM && (variable.flags & PROTECTED) != 0) return Change.PROTECTED;
    if (source == Source.CONSOLE) {
      if ((variable.flags & (ROM | INIT)) != 0) return Change.READ_ONLY;
      if ((variable.flags & CHEAT) != 0 && !cheats) return Change.CHEAT_PROTECTED;
      if ((variable.flags & LATCH) != 0) {
        if (value.equals(variable.latched != null ? variable.latched : variable.value))
          return Change.UNCHANGED;
        variable.latched = value.equals(variable.value) ? null : value;
        variable.modificationCount++;
        return Change.LATCHED;
      }
    }
    boolean changed = !value.equals(variable.value) || variable.latched != null;
    variable.latched = null;
    variable.value = value;
    if (changed) variable.modificationCount++;
    return changed ? Change.CHANGED : Change.UNCHANGED;
  }

  public Change reset(String name, Source source) {
    return find(name).map(value -> set(name, value.resetValue(), source)).orElse(Change.UNCHANGED);
  }

  public void addFlags(String name, int flags) {
    Variable variable = variables.get(key(name));
    if (variable == null) throw new IllegalArgumentException("Unregistered cvar " + name);
    validateValue(variable.value, variable.flags | flags);
    if (variable.latched != null) validateValue(variable.latched, variable.flags | flags);
    variable.flags |= flags;
  }

  public void applyLatchedValues() {
    variables.values().forEach(CvarSystem::applyLatch);
  }

  public void cheatsEnabled(boolean enabled) {
    cheats = enabled;
    if (!enabled)
      for (Variable variable : variables.values()) {
        if ((variable.flags & CHEAT) != 0) set(variable.name, variable.reset, Source.ENGINE);
      }
  }

  public List<Snapshot> all() {
    return variables.values().stream().map(Variable::snapshot).toList();
  }

  public String infoString(int flag, int capacity) {
    Map<String, String> values = new LinkedHashMap<>();
    variables.values().stream()
        .filter(v -> (v.flags & flag) != 0)
        .forEach(v -> values.put(v.name, v.value));
    return InfoString.encode(values, capacity);
  }

  private static void applyLatch(Variable variable) {
    if (variable.latched != null) {
      variable.value = variable.latched;
      variable.latched = null;
      variable.modificationCount++;
    }
  }

  private static String key(String name) {
    if (name == null || name.isEmpty() || name.length() > 255)
      throw new IllegalArgumentException("Invalid cvar name length");
    InfoString.check(name);
    for (int i = 0; i < name.length(); i++)
      if (Character.isWhitespace(name.charAt(i)))
        throw new IllegalArgumentException("Whitespace in cvar name");
    return name.toLowerCase(Locale.ROOT);
  }

  private static void validateValue(String value, int flags) {
    if (value == null || value.length() > MAX_VALUE)
      throw new IllegalArgumentException("Invalid cvar value length");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == 0 || c == '\n' || c == '\r' || c > 255)
        throw new IllegalArgumentException("Invalid cvar value character");
    }
    if ((flags & INFO_FLAGS) != 0) InfoString.check(value);
  }

  private static float numeric(String value) {
    var matcher = NUMBER.matcher(value);
    if (!matcher.find()) return 0;
    try {
      return Float.parseFloat(matcher.group(1));
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
