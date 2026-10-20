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
      String notes) {
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
      server.cvars().set("fraglimit", "0");
      server.cvars().set("timelimit", "0");
      server.cvars().set("capturelimit", "0");
      server.cvars().set("g_warmup", "0");
      server.cvars().set("g_doWarmup", "0");
      server.cvars().set("bot_minplayers", "0");
      server.cvars().set("bot_nochat", "1");
      if (!server.spawn(map, false))
        return failed(game, map, "spawn failed: " + server.dropReason() + " / " + real.tail(12));
      FileViewAssets files = new FileViewAssets(real.searchPath.view());
      List<String> available = botNames(files);
      List<String> bots = new ArrayList<>();
      for (String wanted : preferredBots)
        for (String name : available)
          if (name.equalsIgnoreCase(wanted) && bots.size() < 4 && !bots.contains(name))
            bots.add(name);
      for (String name : available) if (bots.size() < 4 && !bots.contains(name)) bots.add(name);
      for (int i = 0; i < bots.size(); i++) {
        String team = gametype >= 3 ? (i % 2 == 0 ? " red" : " blue") : "";
        server.execute("addbot " + bots.get(i) + " 3" + team);
      }
      // Bots enter after their spawn delay; run a moment before sampling.
      real.run(3000);
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
      String drop = server.state() == GameServer.State.RUNNING ? "" : server.dropReason();
      String notes = "";
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
          notes.strip());
    }
  }

  private static boolean active(GameServer server, int c) {
    ServerClient client = server.client(c);
    return client.isBot() && client.state() == ServerClient.State.ACTIVE;
  }

  private static void sample(GameServer server, int[] scores, float[][] origins) {
    var entities = server.entities().orElseThrow();
    for (int c = 0; c < 8; c++) {
      if (!active(server, c)) continue;
      scores[c] = entities.playerInt(c, PlayerStateLayout.PERSISTANT);
      entities.playerVec(c, PlayerStateLayout.ORIGIN, origins[c]);
    }
  }

  private static Result failed(String game, String map, String why) {
    return new Result(game, map, "?", List.of(), 0, 0, 0, 0, why, 0, 0, "");
  }
}
