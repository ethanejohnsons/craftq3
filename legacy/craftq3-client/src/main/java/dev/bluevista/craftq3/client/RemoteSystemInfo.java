package dev.bluevista.craftq3.client;

import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.cvar.InfoString;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Native-observed cvar permissions for consumed remote systeminfo; never an engine command buffer.
 */
public final class RemoteSystemInfo {
  public record Settings(
      int serverId, boolean cheats, boolean pure, String game, Map<String, String> values) {
    public Settings {
      values = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values));
    }

    /** A host must mount the requested game before allowing any of its modules to initialize. */
    public void requireGame(String mountedGame) {
      String mounted =
          mountedGame == null || mountedGame.isEmpty()
              ? "baseq3"
              : VirtualPath.gameDirectory(mountedGame);
      if (!mounted.equals(game))
        throw new UnsupportedOperationException(
            "Server requires game directory "
                + game
                + "; the current installation has mounted "
                + mounted);
    }
  }

  private final CvarSystem cvars;
  private final Consumer<String> output;

  public RemoteSystemInfo(CvarSystem cvars, Consumer<String> output) {
    this.cvars = Objects.requireNonNull(cvars);
    this.output = Objects.requireNonNull(output);
  }

  /** Parses content requirements separately, so a host can validate its mounted files first. */
  public static Settings parse(String text) {
    var values = InfoString.parse(text, 8192);
    int id = Integer.parseInt(values.getOrDefault("sv_serverid", ""));
    boolean cheats = integer(values, "sv_cheats") != 0;
    boolean pure = integer(values, "sv_pure") != 0;
    String game = values.getOrDefault("fs_game", "");
    game = game.isEmpty() ? "baseq3" : VirtualPath.gameDirectory(game);
    return new Settings(id, cheats, pure, game, values);
  }

  /** Apply on gamestate initialization or consumed cs1 changes, not on arbitrary wire reception. */
  public Settings apply(String text) {
    Settings settings = parse(text);
    // Validate every name before resetting cheats or changing any cvar.
    settings.values().keySet().forEach(cvars::find);
    // Native resets cheat variables before applying the new server-provided values.
    cvars.cheatsEnabled(settings.cheats());
    for (var entry : settings.values().entrySet()) {
      String name = entry.getKey(), value = entry.getValue();
      var existing = cvars.find(name);
      if (existing.isEmpty()) {
        cvars.register(name, value, CvarSystem.SERVER_CREATED | CvarSystem.ROM);
      } else if ((existing.orElseThrow().flags()
              & (CvarSystem.SYSTEMINFO | CvarSystem.SERVER_CREATED | CvarSystem.USER_CREATED))
          != 0) {
        // The existing protected-source path also prevents a server from modifying protected cvars.
        if (cvars.set(name, value, CvarSystem.Source.VM) == CvarSystem.Change.PROTECTED)
          output.accept("Server cannot change protected cvar " + name);
      } else {
        output.accept("Server is not allowed to set " + name + "=" + value);
      }
    }
    return settings;
  }

  private static int integer(Map<String, String> values, String name) {
    return Integer.parseInt(values.getOrDefault(name, "0"));
  }
}
