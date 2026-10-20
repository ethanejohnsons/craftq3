package dev.bluevista.craftq3.engine.client.host;

import dev.bluevista.craftq3.engine.common.info.InfoString;
import dev.bluevista.craftq3.engine.console.Cvar;
import dev.bluevista.craftq3.engine.console.CvarFlags;
import dev.bluevista.craftq3.engine.console.CvarTable;
import dev.bluevista.craftq3.engine.net.Protocol68;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Applies a server's systeminfo configstring to the client's cvars ({@code CL_SystemInfoChanged}),
 * once the cgame has consumed it: {@code sv_cheats} first (cheat-protected cvars reset when it is
 * 0), then every key. Unlike 1.32, which sets any cvar the server names, only cvars that do not
 * exist yet (created read-only systeminfo cvars) and existing systeminfo or user-created ones
 * change; the server cannot change the client's own settings. Demo playback applies nothing.
 */
public final class SystemInfo {
  private SystemInfo() {}

  /** The {@code sv_serverid} value, or 0. */
  public static int serverId(String info) {
    try {
      String id = InfoString.parse(info, Protocol68.BIG_INFO_STRING).get("sv_serverid");
      return id == null ? 0 : Integer.parseInt(id.strip());
    } catch (IllegalArgumentException malformed) {
      return 0;
    }
  }

  public static void apply(CvarTable cvars, String info, Consumer<String> output) {
    Map<String, String> values;
    try {
      values = InfoString.parse(info, Protocol68.BIG_INFO_STRING);
    } catch (IllegalArgumentException malformed) {
      output.accept("Ignored a malformed systeminfo");
      return;
    }
    cvars.set("sv_cheats", values.getOrDefault("sv_cheats", "0"));
    cvars.applyCheatState();
    for (var entry : values.entrySet()) {
      String name = entry.getKey();
      Optional<Cvar> existing = cvars.find(name);
      if (existing.isEmpty())
        cvars.register(name, entry.getValue(), CvarFlags.ROM | CvarFlags.SYSTEMINFO);
      else if (existing.get().hasAny(CvarFlags.SYSTEMINFO | CvarFlags.USER_CREATED))
        cvars.set(name, entry.getValue());
      else output.accept("Server is not allowed to set " + name + "=" + entry.getValue());
    }
  }
}
