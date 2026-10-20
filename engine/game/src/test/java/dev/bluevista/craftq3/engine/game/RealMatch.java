package dev.bluevista.craftq3.engine.game;

import dev.bluevista.craftq3.engine.formats.FileViewAssets;
import dev.bluevista.craftq3.engine.game.bot.BotlibAdapter;
import dev.bluevista.craftq3.engine.vmabi.layout.PlayerStateLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One headless bot match on a real installation: spawn a map, {@code addbot} four bots from the
 * game's own bot list, run game time as fast as possible and record what happened.
 */
final class RealMatch {
  /** What a match produced. */
  record Result(
      String game,
      String map,
      String profile,
      List<String> bots,
      int botsActive,
      int botsMoved,
      int scoreChanges,
      long degradations,
      String drop,
      long frames,
      long millis,
      String notes,
      String log) {
    String line() {
      return String.format(
          Locale.ROOT,
          "%-13s %-14s %-6s bots=%d/%d moved=%d scores=%d degraded=%d frames=%d %.1fs %s%s",
          game,
          map,
          profile,
          botsActive,
          bots.size(),
          botsMoved,
          scoreChanges,
          degradations,
          frames,
          millis / 1000.0,
          drop.isEmpty() ? "ok" : "DROPPED: " + drop,
          notes.isEmpty() ? "" : " (" + notes + ")");
    }
  }

  private RealMatch() {}

  /** Bot names from {@code scripts/bots.txt} and {@code scripts/*.bot}, in file order. */
  static List<String> botNames(FileViewAssets files) {
    Set<String> names = new LinkedHashSet<>();
    List<String> sources = new ArrayList<>();
    sources.add("scripts/bots.txt");
    sources.addAll(files.list("scripts", "bot"));
    Pattern name = Pattern.compile("(?i)\\bname\\s+\"?([^\"\\s}]+)\"?");
    for (String source : sources) {
      byte[] bytes = files.read(source).orElse(null);
      if (bytes == null) continue;
      Matcher m = name.matcher(new String(bytes, StandardCharsets.ISO_8859_1));
      while (m.find()) names.add(m.group(1));
    }
    return new ArrayList<>(names);
  }

  static Result run(
      Path root, String game, String map, int gametype, int millis, List<String> preferredBots)
      throws Exception {
    long started = System.nanoTime();
    try (RealGame real = new RealGame(root, game, new BotlibAdapter())) {
      GameServer server = real.server;
      server.cvars().set("g_gametype", Integer.toString(gametype));
      server.cvars().set("sv_maxclients", "8");
      server.cvars().set("bot_enable", "1");
      server.cvars().set("g_warmup", "0");
      server.cvars().set("g_doWarmup", "0");
      server.cvars().set("bot_minplayers", "0");
      server.cvars().set("bot_nochat", "1");
      if (!server.spawn(map, false))
        return failed(game, map, "spawn failed: " + server.dropReason() + " / " + real.tail(40));
      FileViewAssets files = new FileViewAssets(real.searchPath.view());
      List<String> available = botNames(files);
      List<String> bots = new ArrayList<>();
      for (String wanted : preferredBots)
        for (String name : available)
          if (name.equalsIgnoreCase(wanted) && bots.size() < 4 && !bots.contains(name))
            bots.add(name);
      for (String name : available) if (bots.size() < 4 && !bots.contains(name)) bots.add(name);
      // Add four bots from the game's list at once (teams red, red, blue, blue: Jailbreak's bot AI
      // divides by its count of teammates, which it takes off by one client), then more one at a
      // time if the game refused some (their AI files fail to load); refusals go to the notes.
      List<String> added = new ArrayList<>();
      List<String> refused = new ArrayList<>();
      List<String> queue = new ArrayList<>(bots);
      for (String name : available) if (!queue.contains(name)) queue.add(name);
      for (int i = 0; i < Math.min(4, queue.size()); i++)
        server.execute("addbot " + queue.get(i) + " 3" + team(gametype, i));
      real.run(3000);
      for (int i = 0; i < Math.min(4, queue.size()); i++)
        (activeName(server, queue.get(i)) ? added : refused).add(queue.get(i));
      for (int i = 4; i < queue.size() && activeBots(server) < 4 && i < 16; i++) {
        int before = activeBots(server);
        server.execute("addbot " + queue.get(i) + " 3" + team(gametype, added.size()));
        real.run(1500);
        (activeBots(server) > before ? added : refused).add(queue.get(i));
      }
      bots = added;
      // Bots enter after their spawn delay; run a moment before sampling.
      real.run(3000);
      if (server.state() != GameServer.State.RUNNING)
        return failed(
            game, map, "dropped while bots joined: " + server.dropReason() + " / " + real.tail(40));
      int[] score0 = new int[8];
      float[][] origin0 = new float[8][3];
      sample(server, score0, origin0);
      int[] maxMove = new int[8];
      int scoreChanges = 0;
      int[] lastScore = score0.clone();
      for (int elapsed = 0;
          elapsed < millis && server.state() == GameServer.State.RUNNING;
          elapsed += 1000) {
        real.run(1000);
        if (server.state() != GameServer.State.RUNNING) break;
        if (server.entities().isEmpty()) break;
        int[] score = new int[8];
        float[][] origin = new float[8][3];
        sample(server, score, origin);
        for (int c = 0; c < 8; c++) {
          if (!active(server, c)) continue;
          float dx = origin[c][0] - origin0[c][0], dy = origin[c][1] - origin0[c][1];
          maxMove[c] = Math.max(maxMove[c], (int) Math.sqrt(dx * dx + dy * dy));
          if (score[c] != lastScore[c]) scoreChanges++;
        }
        lastScore = score;
      }
      int active = 0, moved = 0;
      for (int c = 0; c < 8; c++)
        if (active(server, c)) {
          active++;
          if (maxMove[c] > 64) moved++;
        }
      // Racing mods (Q3Rally) rank by race position, which the game prints on every change.
      String previous = null;
      int positionChanges = 0;
      synchronized (real.console) {
        for (String line : real.console)
          if (line.startsWith("positions ")) {
            if (previous != null && !previous.equals(line)) positionChanges++;
            previous = line;
          }
      }
      scoreChanges += positionChanges;
      String drop = server.state() == GameServer.State.RUNNING ? "" : server.dropReason();
      String notes = refused.isEmpty() ? "" : "refused " + refused + "; ";
      for (int c = 0; c < 8; c++)
        if (server.client(c).botDegraded()) notes += "bot " + c + " degraded; ";
      return new Result(
          game,
          map,
          server.profile().map(Object::toString).orElse("?"),
          bots,
          active,
          moved,
          scoreChanges,
          server.degradationTotal(),
          drop.isEmpty() ? "" : drop + " / " + real.tail(6),
          server.framesRun(),
          (System.nanoTime() - started) / 1_000_000,
          notes.strip(),
          real.tail(60));
    }
  }

  private static String team(int gametype, int index) {
    return gametype >= 3 ? (index < 2 ? " red" : " blue") : "";
  }

  private static boolean activeName(GameServer server, String name) {
    for (int c = 0; c < 8; c++)
      if (active(server, c) && GameServer.cleanName(server.client(c).name()).equalsIgnoreCase(name))
        return true;
    return false;
  }

  private static int activeBots(GameServer server) {
    int count = 0;
    for (int c = 0; c < 8; c++) if (active(server, c)) count++;
    return count;
  }

  private static boolean active(GameServer server, int c) {
    ServerClient client = server.client(c);
    return client.isBot() && client.state() == ServerClient.State.ACTIVE;
  }

  private static void sample(GameServer server, int[] scores, float[][] origins) {
    var entities = server.entities().orElseThrow();
    for (int c = 0; c < 8; c++) {
      if (!active(server, c)) continue;
      // PERS_SCORE plus PERS_RANK: frags, captures and (in racing mods) race positions.
      scores[c] =
          entities.playerInt(c, PlayerStateLayout.PERSISTANT) * 64
              + entities.playerInt(c, PlayerStateLayout.PERSISTANT + 8);
      entities.playerVec(c, PlayerStateLayout.ORIGIN, origins[c]);
    }
  }

  private static Result failed(String game, String map, String why) {
    return new Result(game, map, "?", List.of(), 0, 0, 0, 0, why, 0, 0, "", "");
  }
}
