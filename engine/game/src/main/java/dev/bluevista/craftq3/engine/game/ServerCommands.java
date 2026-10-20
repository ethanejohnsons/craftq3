package dev.bluevista.craftq3.engine.game;

import dev.bluevista.craftq3.engine.console.CommandArgs;
import dev.bluevista.craftq3.engine.console.Console;
import dev.bluevista.craftq3.engine.console.CvarFlags;
import dev.bluevista.craftq3.engine.console.CvarTable;
import dev.bluevista.craftq3.engine.game.map.BspMapSource;
import java.util.Locale;

/**
 * The operator commands of {@code sv_ccmds.c}: {@code map}/{@code devmap}/{@code spmap}/{@code
 * spdevmap}, {@code map_restart}, {@code killserver}, {@code kick}, {@code clientkick}, {@code
 * status}, {@code serverinfo}, {@code systeminfo} and {@code dumpuser}. Game commands such as
 * {@code addbot} reach the game through the console fallback.
 */
final class ServerCommands {
  /** {@code GT_FFA} and {@code GT_SINGLE_PLAYER}. */
  private static final int GT_FFA = 0, GT_SINGLE_PLAYER = 2;

  private ServerCommands() {}

  static void install(GameServer server, Console console) {
    var registry = console.commands();
    for (String name : new String[] {"map", "devmap", "spmap", "spdevmap"})
      registry.register(name, args -> map(server, args));
    registry.register(
        "map_restart", args -> server.mapRestart(args.argc() > 1 ? parse(args.argv(1)) : 5));
    registry.register("killserver", args -> server.shutdown("Server was killed"));
    registry.register("kick", args -> kick(server, args));
    registry.register("clientkick", args -> clientKick(server, args));
    registry.register("status", args -> status(server));
    registry.register(
        "serverinfo",
        args -> {
          server.output().print("Server info settings:");
          printInfo(server, server.cvars().info(CvarFlags.SERVERINFO));
        });
    registry.register(
        "systeminfo",
        args -> {
          server.output().print("System info settings:");
          printInfo(server, server.cvars().info(CvarFlags.SYSTEMINFO, CvarTable.BIG_INFO_CAPACITY));
        });
    registry.register("dumpuser", args -> dumpUser(server, args));
  }

  private static int parse(String text) {
    try {
      return Integer.parseInt(text.strip());
    } catch (NumberFormatException invalid) {
      return 0;
    }
  }

  /** {@code SV_Map_f}. */
  private static void map(GameServer server, CommandArgs args) {
    if (args.argc() < 2) return;
    String name = BspMapSource.normalize(args.argv(1));
    String command = args.argv(0).toLowerCase(Locale.ROOT);
    CvarTable cvars = server.cvars();
    cvars.register("g_gametype", "0", CvarFlags.SERVERINFO | CvarFlags.USERINFO | CvarFlags.LATCH);
    boolean cheat, killBots;
    if (command.startsWith("sp")) {
      cvars.setValue("g_gametype", GT_SINGLE_PLAYER);
      cvars.setValue("g_doWarmup", 0);
      cvars.setUser("sv_maxclients", "8");
      cheat = command.equals("spdevmap");
      killBots = true;
    } else {
      cheat = command.equals("devmap");
      killBots = cheat;
      if (cvars.integer("g_gametype") == GT_SINGLE_PLAYER) cvars.setValue("g_gametype", GT_FFA);
    }
    if (!server.spawn(name, killBots)) return;
    cvars.set("sv_cheats", cheat ? "1" : "0");
  }

  /** {@code SV_Kick_f}: by name, {@code all} or {@code allbots}. */
  private static void kick(GameServer server, CommandArgs args) {
    if (server.state() != GameServer.State.RUNNING) {
      server.output().print("Server is not running.");
      return;
    }
    if (args.argc() != 2) {
      server
          .output()
          .print(
              "Usage: kick <player name>\nkick all = kick everyone\nkick allbots = kick all bots");
      return;
    }
    String who = args.argv(1);
    boolean all = who.equalsIgnoreCase("all"), allBots = who.equalsIgnoreCase("allbots");
    if (all || allBots) {
      for (int i = 0; i < server.maxClients(); i++) {
        ServerClient client = server.client(i);
        if (client.state() == ServerClient.State.FREE
            || client.state() == ServerClient.State.ZOMBIE) continue;
        if (client.isLocal()) continue;
        if (allBots && !client.isBot()) continue;
        server.drop(i, "was kicked");
      }
      return;
    }
    int number = server.clientByName(who);
    if (number < 0) {
      server.output().print("Player " + who + " is not on the server");
      return;
    }
    kickClient(server, number);
  }

  /** {@code SV_KickNum_f}. */
  private static void clientKick(GameServer server, CommandArgs args) {
    if (server.state() != GameServer.State.RUNNING) {
      server.output().print("Server is not running.");
      return;
    }
    if (args.argc() != 2) {
      server.output().print("Usage: clientkick <client number>");
      return;
    }
    int number = parse(args.argv(1));
    if (number < 0
        || number >= server.maxClients()
        || server.client(number).state() == ServerClient.State.FREE) {
      server.output().print("Bad client slot: " + number);
      return;
    }
    kickClient(server, number);
  }

  private static void kickClient(GameServer server, int number) {
    if (server.client(number).isLocal()) {
      server.sendServerCommandToAll("print \"Cannot kick host player\n\"");
      return;
    }
    server.drop(number, "was kicked");
  }

  /** {@code SV_Status_f}. */
  private static void status(GameServer server) {
    if (server.state() != GameServer.State.RUNNING) {
      server.output().print("Server is not running.");
      return;
    }
    var out = server.output();
    out.print("map: " + server.cvars().string("mapname"));
    out.print("num score ping name            lastmsg address               qport rate");
    out.print("--- ----- ---- --------------- ------- --------------------- ----- -----");
    for (int i = 0; i < server.maxClients(); i++) {
      ServerClient client = server.client(i);
      if (client.state() == ServerClient.State.FREE) continue;
      String ping =
          client.state() == ServerClient.State.CONNECTED
              ? "CNCT"
              : client.state() == ServerClient.State.ZOMBIE
                  ? "ZMBI"
                  : Integer.toString(Math.min(client.ping(), 9999));
      int score = server.entities().isPresent() ? server.score(i) : 0;
      out.print(
          String.format(
              Locale.ROOT,
              "%3d %5d %4s %-15s %7d %-21s %5d %5d",
              i,
              score,
              ping,
              GameServer.cleanName(client.name()),
              server.serverTime() - client.lastPacketTime(),
              client.isBot() ? "bot" : client.address(),
              0,
              client.rate()));
    }
  }

  private static void printInfo(GameServer server, String info) {
    int i = info.startsWith("\\") ? 1 : 0;
    String[] fields = info.substring(i).split("\\\\", -1);
    for (int f = 0; f + 1 < fields.length; f += 2)
      server.output().print(String.format(Locale.ROOT, "%-20s%s", fields[f], fields[f + 1]));
  }

  /** {@code SV_DumpUser_f}. */
  private static void dumpUser(GameServer server, CommandArgs args) {
    if (server.state() != GameServer.State.RUNNING) {
      server.output().print("Server is not running.");
      return;
    }
    if (args.argc() != 2) {
      server.output().print("Usage: info <userid>");
      return;
    }
    int number = server.clientByName(args.argv(1));
    if (number < 0) {
      server.output().print("Player " + args.argv(1) + " is not on the server");
      return;
    }
    server.output().print("userinfo");
    server.output().print("--------");
    printInfo(server, server.client(number).userinfo());
  }
}
