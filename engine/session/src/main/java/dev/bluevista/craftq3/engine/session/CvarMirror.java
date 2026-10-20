package dev.bluevista.craftq3.engine.session;

import dev.bluevista.craftq3.engine.common.info.InfoString;
import dev.bluevista.craftq3.engine.console.Cvar;
import dev.bluevista.craftq3.engine.console.CvarFlags;
import dev.bluevista.craftq3.engine.console.CvarTable;
import dev.bluevista.craftq3.engine.net.Protocol68;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Keeps the client and server cvar tables consistent where the original shared one table.
 *
 * <p><b>Client to server.</b> A client cvar is <em>forwardable</em> unless it is read-only or
 * init-only, or it is one of the client's own engine cvars (renderer, sound, input, connection
 * settings registered before any VM ran) without a server or system info flag. A forwardable cvar
 * whose value (or pending latched value) changed is sent to the server as {@code set name "value"}
 * console text, so the server applies its own latch and protection rules. When a server starts it
 * receives every forwardable cvar once, so what the ui set before {@code map} (game type, limits,
 * bot skill) is in place when the qagame registers its cvars.
 *
 * <p><b>Server to client.</b> The local server's serverinfo configstring is mirrored into the
 * client table (existing client-owned cvars are never overwritten); systeminfo is applied by the
 * client's {@code SystemInfo} when the cgame consumes it. Mirrored values are not forwarded back.
 *
 * <p>Owned by the client thread.
 */
final class CvarMirror {
  private static final int NEVER_FORWARDED = CvarFlags.ROM | CvarFlags.INIT;
  private static final int INFO = CvarFlags.SERVERINFO | CvarFlags.SYSTEMINFO;
  private static final String PAUSED = "cl_paused";

  private final CvarTable cvars;
  private final Set<String> clientOwned = new HashSet<>();
  private int[] seen = new int[0];
  private String mirroredInfo = "";

  CvarMirror(CvarTable cvars) {
    this.cvars = cvars;
  }

  /** Marks every cvar registered so far as the client's own (call before any VM runs). */
  void captureClientOwned() {
    for (int i = 0; i < cvars.size(); i++) clientOwned.add(key(cvars.get(i).name()));
    baseline();
  }

  /** Forgets pending changes: what is set now counts as already forwarded. */
  void baseline() {
    int count = cvars.size();
    seen = Arrays.copyOf(seen, count);
    for (int i = 0; i < count; i++) seen[i] = cvars.get(i).modificationCount();
  }

  boolean forwardable(Cvar cvar) {
    // The ui pauses a single-player game by setting the client's read-only cl_paused.
    if (cvar.name().equalsIgnoreCase(PAUSED)) return true;
    if (cvar.hasAny(NEVER_FORWARDED)) return false;
    if (cvar.hasAny(INFO)) return true;
    return !clientOwned.contains(key(cvar.name()));
  }

  /** Every forwardable cvar as {@code set} commands (a server just started). */
  List<String> all() {
    List<String> commands = new ArrayList<>();
    for (int i = 0; i < cvars.size(); i++) {
      Cvar cvar = cvars.get(i);
      if (forwardable(cvar)) commands.add(command(cvar));
    }
    baseline();
    return commands;
  }

  /** The {@code set} commands for forwardable cvars changed since the last call. */
  void changes(Consumer<String> sink) {
    int count = cvars.size();
    if (count != seen.length) {
      int old = seen.length;
      seen = Arrays.copyOf(seen, count);
      // A cvar created since the last scan counts as changed unless it is new and empty.
      for (int i = old; i < count; i++) seen[i] = -1;
    }
    for (int i = 0; i < count; i++) {
      Cvar cvar = cvars.get(i);
      int modification = cvar.modificationCount();
      if (modification == seen[i]) continue;
      seen[i] = modification;
      if (forwardable(cvar)) sink.accept(command(cvar));
    }
  }

  /** Mirrors the local server's serverinfo into the client table. */
  void mirrorServerInfo(String info, Consumer<String> output) {
    if (info.equals(mirroredInfo)) return;
    mirroredInfo = info;
    Map<String, String> values;
    try {
      values = InfoString.parse(info, Protocol68.BIG_INFO_STRING);
    } catch (IllegalArgumentException malformed) {
      output.accept("Ignored a malformed serverinfo");
      return;
    }
    for (var entry : values.entrySet()) {
      String name = entry.getKey();
      var existing = cvars.find(name);
      if (existing.isPresent()) {
        Cvar cvar = existing.get();
        if (cvar.hasAny(CvarFlags.ROM) || (clientOwned.contains(key(name)) && !cvar.hasAny(INFO)))
          continue;
        if (cvar.latchedString().isPresent()) continue;
      }
      cvars.set(name, entry.getValue());
    }
    baseline();
  }

  /** Forget the mirrored serverinfo (the level ended). */
  void resetServerInfo() {
    mirroredInfo = "";
  }

  private static String command(Cvar cvar) {
    String value = cvar.latchedString().orElse(cvar.string());
    return "set "
        + cvar.name()
        + " \""
        + value.replace("\"", "").replace('\n', ' ').replace('\r', ' ')
        + "\"";
  }

  private static String key(String name) {
    return name.toLowerCase(Locale.ROOT);
  }
}
