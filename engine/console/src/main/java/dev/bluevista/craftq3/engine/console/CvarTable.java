package dev.bluevista.craftq3.engine.console;

import dev.bluevista.craftq3.engine.common.info.InfoString;
import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A Quake III cvar table with 1.32 semantics: {@code Cvar_Get} registration, forced and user sets,
 * latching, cheat protection, the global modified-flags accumulator and info strings.
 *
 * <p>Threading: a table has exactly one owner thread (the render thread for the client table, the
 * simulation or Minecraft server thread for the server table). Every entry point calls {@link
 * ThreadOwner#check()}; there is no locking. Another thread that wants to change a cvar posts a
 * command ({@code set name value}) to the owning console's {@link CommandInbox}, which the owner
 * executes at its next frame.
 *
 * <p>Handles are creation indices and stay valid for the life of the table, so the VM ABI layer can
 * map {@code vmCvar_t.handle} directly onto {@link #get(int)}.
 */
public final class CvarTable {
  /** Upper bound on distinct cvars; registering more is resource exhaustion and fails. */
  public static final int MAX_CVARS = 8192;

  /** Longest accepted name; longer names are treated as invalid. */
  public static final int MAX_NAME_LENGTH = 256;

  /** {@code MAX_INFO_STRING}: userinfo and serverinfo capacity including the terminator. */
  public static final int INFO_CAPACITY = 1024;

  /** {@code BIG_INFO_STRING}: systeminfo capacity including the terminator. */
  public static final int BIG_INFO_CAPACITY = 8192;

  /** Substituted for an invalid name, as the original engine does. */
  public static final String BAD_NAME = "BADNAME";

  /** Substituted for an invalid default value of an info cvar. */
  public static final String BAD_VALUE = "BADVALUE";

  /** The cvar consulted for cheat protection. */
  public static final String CHEATS = "sv_cheats";

  private static final Log LOG = Log.get(CvarTable.class);

  /** The outcome of a set. Only forced sets and unprotected user sets produce {@link #CHANGED}. */
  public enum SetResult {
    CHANGED(""),
    UNCHANGED(""),
    LATCHED(" will be changed upon restarting."),
    READ_ONLY(" is read only."),
    WRITE_PROTECTED(" is write protected."),
    CHEAT_PROTECTED(" is cheat protected."),
    /** A reset of a cvar that does not exist; nothing was created. */
    MISSING("");

    private final String suffix;

    SetResult(String suffix) {
      this.suffix = suffix;
    }

    /** The console message the original engine prints for this outcome, or empty. */
    public String message(String name) {
      return suffix.isEmpty() ? "" : name + suffix;
    }
  }

  private final ThreadOwner owner;
  private final Map<String, Cvar> byName = new HashMap<>();
  private final List<Cvar> byHandle = new ArrayList<>();
  private final Cvar cheats;
  private int modifiedFlags;

  /** Creates a table whose thread checks go through {@code owner}; registers {@code sv_cheats}. */
  public CvarTable(ThreadOwner owner) {
    this.owner = Objects.requireNonNull(owner);
    // Cvar_Init: cheats are allowed until a server says otherwise.
    cheats = register(CHEATS, "1", CvarFlags.ROM | CvarFlags.SYSTEMINFO);
  }

  public ThreadOwner owner() {
    return owner;
  }

  /**
   * {@code Cvar_Get}: creates the cvar or merges {@code flags} into an existing one. A value the
   * user set first is kept, the user-created flag is dropped and the default becomes the reset
   * value. A pending latched value is applied. Invalid names become {@link #BAD_NAME}; invalid
   * default values of info cvars become {@link #BAD_VALUE}.
   */
  public Cvar register(String name, String defaultValue, int flags) {
    owner.check();
    Objects.requireNonNull(defaultValue, "defaultValue");
    String validName = validName(name);
    String value = defaultValue;
    if ((flags & CvarFlags.INFO_MASK) != 0 && !validString(value)) {
      LOG.warnOnce("value:" + validName, "invalid cvar value string: " + value);
      value = BAD_VALUE;
    }
    Cvar cvar = byName.get(key(validName));
    if (cvar == null) return create(validName, value, flags);
    if ((cvar.flags & CvarFlags.USER_CREATED) != 0
        && (flags & CvarFlags.USER_CREATED) == 0
        && !value.isEmpty()) {
      cvar.flags &= ~CvarFlags.USER_CREATED;
      cvar.reset = value;
      // Code registering a user cvar as serverinfo must make it reach clients.
      modifiedFlags |= flags;
    }
    cvar.flags |= flags;
    if (cvar.reset.isEmpty()) cvar.reset = value;
    else if (!value.isEmpty() && !cvar.reset.equals(value))
      LOG.debug(
          "cvar \""
              + validName
              + "\" given initial values: \""
              + cvar.reset
              + "\" and \""
              + value
              + "\"");
    if (cvar.latched != null) {
      String latched = cvar.latched;
      cvar.latched = null;
      assign(cvar, latched);
    }
    return cvar;
  }

  public Optional<Cvar> find(String name) {
    owner.check();
    return Optional.ofNullable(byName.get(key(name)));
  }

  /** The cvar with this handle (creation index). */
  public Cvar get(int handle) {
    owner.check();
    if (handle < 0 || handle >= byHandle.size())
      throw new IllegalArgumentException("Invalid cvar handle " + handle);
    return byHandle.get(handle);
  }

  public int size() {
    owner.check();
    return byHandle.size();
  }

  /** Every cvar in creation (handle) order. */
  public List<Cvar> all() {
    owner.check();
    return Collections.unmodifiableList(new ArrayList<>(byHandle));
  }

  /** {@code Cvar_VariableString}: the value, or empty when the cvar does not exist. */
  public String string(String name) {
    owner.check();
    Cvar cvar = byName.get(key(name));
    return cvar == null ? "" : cvar.string;
  }

  /** {@code Cvar_VariableIntegerValue}. */
  public int integer(String name) {
    owner.check();
    Cvar cvar = byName.get(key(name));
    return cvar == null ? 0 : cvar.integer();
  }

  /** {@code Cvar_VariableValue}. */
  public float value(String name) {
    owner.check();
    Cvar cvar = byName.get(key(name));
    return cvar == null ? 0 : cvar.value();
  }

  /**
   * {@code Cvar_Set}: a forced engine or VM write that ignores ROM, INIT, LATCH and CHEAT and
   * discards any latched value. Creates a missing cvar with no flags.
   */
  public SetResult set(String name, String value) {
    owner.check();
    return set(name, Objects.requireNonNull(value, "value"), true);
  }

  /**
   * {@code Cvar_Set2(name, value, qfalse)}: a user write from the console or a config file that
   * honours ROM, INIT, LATCH and CHEAT. Creates a missing cvar flagged {@link
   * CvarFlags#USER_CREATED}.
   */
  public SetResult setUser(String name, String value) {
    owner.check();
    return set(name, Objects.requireNonNull(value, "value"), false);
  }

  /** {@code Cvar_SetValue}: integral values print as integers, others with six decimals. */
  public SetResult setValue(String name, float value) {
    owner.check();
    return set(name, formatValue(value), true);
  }

  /** {@code Cvar_Reset}: a user write of the reset value; does not create a missing cvar. */
  public SetResult reset(String name) {
    owner.check();
    return set(name, null, false);
  }

  /**
   * Adds flags to an existing cvar ({@code seta}/{@code sets}/{@code setu}). Added info and archive
   * flags are recorded in {@link #modifiedFlags()} so the info strings and config are rebuilt.
   *
   * @return false when the cvar does not exist
   */
  public boolean addFlags(String name, int flags) {
    owner.check();
    Cvar cvar = byName.get(key(name));
    if (cvar == null) return false;
    int added = flags & ~cvar.flags;
    cvar.flags |= flags;
    modifiedFlags |= added;
    return true;
  }

  /** Applies every latched value now (a map restart without re-registration). */
  public void applyLatched() {
    owner.check();
    for (Cvar cvar : byHandle) {
      if (cvar.latched == null) continue;
      String latched = cvar.latched;
      cvar.latched = null;
      assign(cvar, latched);
    }
  }

  /** True when {@code sv_cheats} is non-zero. */
  public boolean cheatsAllowed() {
    owner.check();
    return cheats.integer() != 0;
  }

  /**
   * {@code Cvar_SetCheatState}: when cheats are off, forces every {@link CvarFlags#CHEAT} cvar back
   * to its reset value. Called after the server or systeminfo changes {@code sv_cheats}.
   */
  public void applyCheatState() {
    owner.check();
    if (cheats.integer() != 0) return;
    for (Cvar cvar : byHandle)
      if ((cvar.flags & CvarFlags.CHEAT) != 0 && !cvar.reset.equals(cvar.string))
        set(cvar.name(), cvar.reset, true);
  }

  /** {@code cvar_modifiedFlags}: the union of the flags of every cvar changed since cleared. */
  public int modifiedFlags() {
    owner.check();
    return modifiedFlags;
  }

  /**
   * Records flags as modified without a cvar change; a key binding change marks {@link
   * CvarFlags#ARCHIVE} so the config is rewritten, as in the original.
   */
  public void markModified(int flags) {
    owner.check();
    modifiedFlags |= flags;
  }

  public void clearModifiedFlags(int mask) {
    owner.check();
    modifiedFlags &= ~mask;
  }

  /** Tests and clears {@code mask} in one step, e.g. {@code takeModified(USERINFO)}. */
  public boolean takeModified(int mask) {
    owner.check();
    boolean any = (modifiedFlags & mask) != 0;
    modifiedFlags &= ~mask;
    return any;
  }

  /** {@code Cvar_InfoString}: an info string of every cvar with a flag in {@code mask}. */
  public String info(int mask) {
    return info(mask, INFO_CAPACITY);
  }

  /**
   * Builds an info string through {@link InfoString} with original ordering: pairs appear in
   * creation order, empty values and values an info string cannot carry are skipped, and when the
   * capacity is reached the oldest cvars are the ones left out (the original engine prepends
   * newest-first).
   */
  public String info(int mask, int capacity) {
    owner.check();
    List<Cvar> included = new ArrayList<>();
    int length = 0;
    for (int i = byHandle.size() - 1; i >= 0; i--) {
      Cvar cvar = byHandle.get(i);
      if ((cvar.flags & mask) == 0 || cvar.string.isEmpty()) continue;
      if (!infoSafe(cvar.name()) || !infoSafe(cvar.string)) {
        LOG.warnOnce("info:" + cvar.name(), "Can't use keys or values with \\ ; or \": " + cvar);
        continue;
      }
      int pair = cvar.name().length() + cvar.string.length() + 2;
      if (length + pair >= capacity) {
        LOG.warnOnce("infolength:" + cvar.name(), "Info string length exceeded at " + cvar.name());
        continue;
      }
      length += pair;
      included.add(cvar);
    }
    Map<String, String> pairs = new LinkedHashMap<>();
    for (int i = included.size() - 1; i >= 0; i--)
      pairs.put(included.get(i).name(), included.get(i).string);
    return InfoString.encode(pairs, capacity);
  }

  private SetResult set(String name, String value, boolean force) {
    String validName = validName(name);
    Cvar cvar = byName.get(key(validName));
    if (cvar == null) {
      if (value == null) return SetResult.MISSING;
      create(validName, value, force ? 0 : CvarFlags.USER_CREATED);
      return SetResult.CHANGED;
    }
    String target = value == null ? cvar.reset : value;
    if (!force) {
      if ((cvar.flags & CvarFlags.ROM) != 0) return SetResult.READ_ONLY;
      if ((cvar.flags & CvarFlags.INIT) != 0) return SetResult.WRITE_PROTECTED;
      if ((cvar.flags & CvarFlags.LATCH) != 0) {
        String pending = cvar.latched != null ? cvar.latched : cvar.string;
        if (target.equals(pending)) return SetResult.UNCHANGED;
        cvar.latched = target;
        cvar.modified = true;
        cvar.modificationCount++;
        modifiedFlags |= cvar.flags;
        return SetResult.LATCHED;
      }
      if ((cvar.flags & CvarFlags.CHEAT) != 0 && cheats.integer() == 0)
        return SetResult.CHEAT_PROTECTED;
    } else {
      cvar.latched = null;
    }
    return assign(cvar, target);
  }

  private SetResult assign(Cvar cvar, String value) {
    if (value.equals(cvar.string)) return SetResult.UNCHANGED;
    cvar.assign(value);
    cvar.modified = true;
    cvar.modificationCount++;
    modifiedFlags |= cvar.flags;
    return SetResult.CHANGED;
  }

  private Cvar create(String name, String value, int flags) {
    if (byHandle.size() >= MAX_CVARS)
      throw new ConsoleException("Cvar limit of " + MAX_CVARS + " reached registering " + name);
    Cvar cvar = new Cvar(owner, byHandle.size(), name, value, flags);
    byHandle.add(cvar);
    byName.put(key(name), cvar);
    modifiedFlags |= flags;
    return cvar;
  }

  /** {@code Cvar_SetValue} formatting: {@code %i} when integral, else {@code %f}. */
  static String formatValue(float value) {
    if (value == (int) value) return Integer.toString((int) value);
    return String.format(Locale.ROOT, "%f", value);
  }

  /** {@code Cvar_ValidateString}: no backslash, quote or semicolon. */
  static boolean validString(String text) {
    return text.indexOf('\\') < 0 && text.indexOf('"') < 0 && text.indexOf(';') < 0;
  }

  private static String validName(String name) {
    Objects.requireNonNull(name, "name");
    if (!name.isEmpty() && name.length() <= MAX_NAME_LENGTH && validString(name)) return name;
    LOG.warnOnce("name:" + name, "invalid cvar name string: " + name);
    return BAD_NAME;
  }

  private static boolean infoSafe(String text) {
    try {
      InfoString.check(text);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** ASCII case folding, matching {@code Q_stricmp}. */
  static String key(String name) {
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c >= 'A' && c <= 'Z') return asciiLower(name);
    }
    return name;
  }

  static String asciiLower(String text) {
    char[] chars = text.toCharArray();
    for (int i = 0; i < chars.length; i++)
      if (chars[i] >= 'A' && chars[i] <= 'Z') chars[i] = (char) (chars[i] + ('a' - 'A'));
    return new String(chars);
  }

  static String asciiUpper(String text) {
    char[] chars = text.toCharArray();
    for (int i = 0; i < chars.length; i++)
      if (chars[i] >= 'a' && chars[i] <= 'z') chars[i] = (char) (chars[i] - ('a' - 'A'));
    return new String(chars);
  }
}
