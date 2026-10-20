package dev.bluevista.craftq3.engine.game.bot;

import dev.bluevista.craftq3.engine.formats.AssetLookup;
import dev.bluevista.craftq3.engine.game.map.GameMap;
import dev.bluevista.craftq3.engine.game.world.EntityWorld;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout.UserCmd;

/**
 * The server services the botlib marshalling needs ({@code sv_bot.c}): the world, the clients'
 * command and snapshot channels, and the game files. Implemented by the game server; called on the
 * simulation thread.
 */
public interface BotHost {
  /** The current level's world (traces, PVS, entity data). */
  EntityWorld world();

  /** The current level. */
  GameMap map();

  /** The game's read-only files (bot scripts, AAS). */
  AssetLookup files();

  int maxClients();

  /** {@code sv.time}, the level time in milliseconds. */
  int levelTime();

  /** {@code SV_ExecuteClientCommand(cl, command, qtrue)} for bot {@code client}. */
  void botClientCommand(int client, String command);

  /** {@code SV_ClientThink}: runs one user command for bot {@code client}. */
  void botUserCommand(int client, UserCmd command);

  /**
   * {@code SV_BotGetSnapshotEntity}: entity number {@code index} of the bot's latest snapshot, or
   * -1.
   */
  int snapshotEntity(int client, int index);

  /** {@code SV_BotGetConsoleMessage}: the bot's next reliable command, or null when none. */
  String consoleMessage(int client);

  /** Records a botlib failure for {@code client} (the bot is marked degraded). */
  void botFailed(int client, RuntimeException failure);

  /** Console text from botlib ({@code BotImport_Print}). */
  void print(String text);
}
