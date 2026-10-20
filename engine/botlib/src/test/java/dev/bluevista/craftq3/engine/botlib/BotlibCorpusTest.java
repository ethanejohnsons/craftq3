package dev.bluevista.craftq3.engine.botlib;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.botlib.aas.AasAlternativeRoutes;
import dev.bluevista.craftq3.engine.botlib.aas.AasNavigation;
import dev.bluevista.craftq3.engine.botlib.aas.AasRoutePredictor;
import dev.bluevista.craftq3.engine.botlib.aas.AasRouteTimes;
import dev.bluevista.craftq3.engine.botlib.aas.AasWorld;
import dev.bluevista.craftq3.engine.botlib.aas.TravelFlags;
import dev.bluevista.craftq3.engine.botlib.aas.TravelPolicy;
import dev.bluevista.craftq3.engine.botlib.character.BotCharacters;
import dev.bluevista.craftq3.engine.botlib.chat.ChatLibrary;
import dev.bluevista.craftq3.engine.botlib.item.ItemConfig;
import dev.bluevista.craftq3.engine.botlib.script.ScriptSources;
import dev.bluevista.craftq3.engine.botlib.testing.StubWorld;
import dev.bluevista.craftq3.engine.botlib.weapon.WeaponConfig;
import dev.bluevista.craftq3.engine.botlib.weight.WeightConfig;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.AssetLookup;
import dev.bluevista.craftq3.engine.formats.FileViewAssets;
import dev.bluevista.craftq3.engine.formats.aas.AasReader;
import dev.bluevista.craftq3.engine.formats.bsp.BspReader;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Opt-in sweep over the user's Quake III installation ({@code CRAFTQ3_Q3ROOT}): every AAS file of
 * baseq3 and each mod is routed with seeded random queries, every map is loaded through the {@link
 * BotLib} facade, and every bot script is preprocessed and parsed. Reports land in {@code
 * build/reports/botlib-corpus-*.txt}; no game data is written or committed.
 */
@RealData
final class BotlibCorpusTest {
  private static final int PAIRS = 500;

  /** Tiny maps (bananna.aas routes between two areas) make the asymmetry median meaningless. */
  private static final int MIN_SYMMETRY_AREAS = 16;

  private static final int FACADE_QUERIES = 50;
  private static final TravelPolicy POLICY = TravelPolicy.ofFlags(TravelFlags.DEFAULT);

  @Test
  void routesEveryAasMapWithSeededQueries() throws Exception {
    var report = new StringBuilder();
    var failures = new ArrayList<String>();
    int maps = 0;
    long queries = 0;
    for (String game : games()) {
      try (var search = SearchPath.mount(RealDataRoot.require(), game)) {
        var files = new FileViewAssets(search.view());
        for (String path : own(search, game, files.list("maps", "aas"))) {
          maps++;
          try {
            queries += routeMap(game, path, files.read(path).orElseThrow(), report, failures);
          } catch (RuntimeException failure) {
            failures.add(game + "/" + path + ": " + failure);
          }
        }
      }
    }
    report.append(
        String.format(
            Locale.ROOT, "maps=%d queries=%d failures=%d%n", maps, queries, failures.size()));
    failures.forEach(failure -> report.append("FAIL ").append(failure).append('\n'));
    write("botlib-corpus-routes.txt", report);
    assertTrue(maps > 0, "no AAS files found");
    assertEquals(List.of(), failures);
  }

  private static long routeMap(
      String game, String path, byte[] data, StringBuilder report, List<String> failures) {
    long start = System.nanoTime();
    var world = AasWorld.of(AasReader.read(data, path));
    var routes = new AasRouteTimes(world);
    var predictor = new AasRoutePredictor(new AasNavigation(world), routes);
    long loaded = System.nanoTime();
    var areas = new ArrayList<Integer>();
    for (int area = 1; area < world.areaSettings().size(); area++)
      if (world.areaSettings().get(area).reachabilityCount() > 0) areas.add(area);
    if (areas.size() < 2) {
      report.append(
          String.format(Locale.ROOT, "%s/%s: %d routable areas%n", game, path, areas.size()));
      return 0;
    }
    var random = new Random(path.hashCode());
    int reachable = 0, both = 0, inconsistent = 0;
    var ratios = new ArrayList<Float>();
    long queries = 0;
    for (int i = 0; i < PAIRS; i++) {
      int a = areas.get(random.nextInt(areas.size())), b = areas.get(random.nextInt(areas.size()));
      Vec3f from = world.areas().get(a).center(), to = world.areas().get(b).center();
      int forward = routes.travelTime(a, from, b, POLICY);
      int backward = routes.travelTime(b, to, a, POLICY);
      int self = routes.travelTime(a, from, a, POLICY);
      var prediction =
          predictor.predict(new AasRoutePredictor.Request(a, from, b, POLICY, 0, 0, 0, 0, 0, 0));
      queries += 4;
      if (forward < 0 || backward < 0 || self != 1)
        failures.add(
            game + "/" + path + ": bad times " + a + "->" + b + " " + forward + "/" + backward);
      if (forward > 0) {
        reachable++;
        if (!prediction.success()) inconsistent++;
      }
      if (forward > 0 && backward > 0) {
        both++;
        ratios.add((float) Math.max(forward, backward) / Math.min(forward, backward));
      }
    }
    // Most areas of a playable map connect both ways at comparable cost (one-way drops and jump
    // pads are the exception), so a typical pair must not be wildly asymmetric.
    ratios.sort(null);
    float median = ratios.isEmpty() ? 1 : ratios.get(ratios.size() / 2);
    if (areas.size() >= MIN_SYMMETRY_AREAS && median > 3)
      failures.add(game + "/" + path + ": median travel time asymmetry " + median);
    // "short" is informational: from a cluster portal area the original follows the first stored
    // reachability whenever the portal cache has no entry, so a prediction can legitimately stop
    // before a goal that has a travel time.
    long done = System.nanoTime();
    report.append(
        String.format(
            Locale.ROOT,
            "%-12s %-40s areas=%5d reachable=%3d/%d both=%3d median=%.2f short=%d"
                + " load=%6.1fms route=%7.1fms%n",
            game,
            path,
            areas.size(),
            reachable,
            PAIRS,
            both,
            median,
            inconsistent,
            (loaded - start) / 1e6,
            (done - loaded) / 1e6));
    return queries;
  }

  @Test
  void loadsEveryMapThroughTheFacade() throws Exception {
    var report = new StringBuilder();
    var failures = new ArrayList<String>();
    int maps = 0;
    for (String game : games()) {
      try (var search = SearchPath.mount(RealDataRoot.require(), game)) {
        var files = new FileViewAssets(search.view());
        for (String path : own(search, game, files.list("maps", "aas"))) {
          String base = path.substring("maps/".length(), path.length() - ".aas".length());
          var bsp = files.read("maps/" + base + ".bsp");
          if (bsp.isEmpty()) {
            report.append(game).append(' ').append(base).append(": no BSP, skipped\n");
            continue;
          }
          maps++;
          try {
            facadeMap(game, base, files, bsp.get(), report, failures);
          } catch (RuntimeException failure) {
            failures.add(game + "/" + base + ": " + failure);
          }
        }
      }
    }
    report.append(String.format(Locale.ROOT, "maps=%d failures=%d%n", maps, failures.size()));
    failures.forEach(failure -> report.append("FAIL ").append(failure).append('\n'));
    write("botlib-corpus-facade.txt", report);
    assertTrue(maps > 0);
    assertEquals(List.of(), failures);
  }

  private static void facadeMap(
      String game,
      String base,
      AssetLookup files,
      byte[] bspData,
      StringBuilder report,
      List<String> failures) {
    long start = System.nanoTime();
    var bsp = BspReader.read(bspData, base);
    var models = new HashMap<Integer, BotWorld.ModelBounds>();
    for (int i = 0; i < bsp.models().size(); i++)
      models.put(
          i,
          new BotWorld.ModelBounds(
              bsp.models().get(i).mins(), bsp.models().get(i).maxs(), Vec3f.ZERO));
    var world = new StubWorld(files, bsp.entityText(), models);
    try (var bots = new BotLib(world)) {
      bots.setStrict(true);
      bots.libVarSet("maxclients", "8");
      int setup = bots.setup();
      if (setup != BotLib.NO_ERROR) {
        failures.add(game + "/" + base + ": setup " + setup + " " + world.printed);
        return;
      }
      int load = bots.loadMap(base);
      if (load != BotLib.NO_ERROR) {
        failures.add(game + "/" + base + ": loadMap " + load + " " + world.printed);
        return;
      }
      bots.startFrame(.1f);
      var navigation = bots.navigation().orElseThrow();
      var map = navigation.map();
      var random = new Random(base.hashCode());
      int alternatives = 0, items = 0;
      for (int i = 1; i < 256; i++) if (!bots.goalName(i).isEmpty()) items++;
      int count = bots.aasPointReachabilityAreaIndex(null);
      for (int i = 0; i < FACADE_QUERIES; i++) {
        int a = 1 + random.nextInt(map.areas().size() - 1);
        int b = 1 + random.nextInt(map.areas().size() - 1);
        Vec3f from = map.areas().get(a).center(), to = map.areas().get(b).center();
        int index = bots.aasPointReachabilityAreaIndex(from);
        if (index < 0 || index > count)
          failures.add(game + "/" + base + ": reachability index " + index + " of " + count);
        alternatives +=
            bots.aasAlternativeRouteGoals(
                    from, a, to, b, TravelFlags.DEFAULT, 8, AasAlternativeRoutes.CLUSTER_PORTALS)
                .size();
        bots.aasPredictRoute(a, from, b, TravelFlags.DEFAULT, 0, 0, 0, 0, 0, 0);
        bots.reachabilityArea(from, -1);
      }
      report.append(
          String.format(
              Locale.ROOT,
              "%-12s %-32s areas=%5d items=%3d reachIndexCount=%5d alternatives=%4d %6.1fms%n",
              game,
              base,
              map.areas().size(),
              items,
              count,
              alternatives,
              (System.nanoTime() - start) / 1e6));
    }
  }

  @Test
  void parsesEveryBotFileThroughTheScriptPreprocessor() throws Exception {
    var report = new StringBuilder();
    var failures = new ArrayList<String>();
    var unused = new ArrayList<String>();
    int total = 0;
    for (String game : games()) {
      try (var search = SearchPath.mount(RealDataRoot.require(), game)) {
        var files = new FileViewAssets(search.view());
        var paths = new ArrayList<String>();
        for (String directory : List.of("botfiles", "botfiles/bots"))
          for (String path : own(search, game, files.list(directory, "c")))
            if (path.toLowerCase(Locale.ROOT).endsWith(".c")) paths.add(path);
        var included = includedNames(files, paths);
        var used = usedBotFiles(files);
        int warnings = 0, templates = 0, checked = 0;
        try (var sources = new ScriptSources(files)) {
          for (String path : paths) {
            String name = fileName(path);
            // Templates such as fw_items.c expect macros their includer defines first.
            if (included.contains(name) && !libraryFile(name)) {
              templates++;
              continue;
            }
            checked++;
            try {
              int handle = sources.load(path);
              warnings += sources.warnings(handle).size();
              while (sources.read(handle).isPresent()) {}
              sources.free(handle);
              parse(sources, path);
            } catch (IOException | RuntimeException failure) {
              String message = game + "/" + path + ": " + failure.getMessage();
              if (path.contains("/bots/") && !used.contains(path.toLowerCase(Locale.ROOT)))
                unused.add(message);
              else failures.add(message);
            }
          }
          if (game.equals("baseq3") || !own(search, game, List.of("botfiles/syn.c")).isEmpty())
            try {
              ChatLibrary.load(sources);
            } catch (IOException | RuntimeException failure) {
              failures.add(game + "/chat library: " + failure.getMessage());
            }
        }
        total += checked;
        report.append(
            String.format(
                Locale.ROOT,
                "%-12s files=%4d checked=%4d templates=%d bot-referenced=%d tolerated-directives=%d%n",
                game,
                paths.size(),
                checked,
                templates,
                used.size(),
                warnings));
      }
    }
    report.append(
        String.format(
            Locale.ROOT,
            "files=%d failures=%d unreferenced-failures=%d%n",
            total,
            failures.size(),
            unused.size()));
    failures.forEach(failure -> report.append("FAIL ").append(failure).append('\n'));
    // Bot files no bot definition uses (left-over files such as cadaver_t.c in pak4) are
    // reported; the original engine rejects the same syntax errors but never loads them.
    unused.forEach(failure -> report.append("UNUSED ").append(failure).append('\n'));
    write("botlib-corpus-scripts.txt", report);
    assertTrue(total > 0);
    assertEquals(List.of(), failures);
  }

  /** Parses a bot file with the reader its name implies. */
  private static void parse(ScriptSources sources, String path) throws IOException {
    String name = fileName(path);
    if (name.endsWith("_c.c")) {
      var diagnostics = new ArrayList<String>();
      try (var characters = new BotCharacters(sources, diagnostics::add)) {
        for (float skill = 1; skill <= 5; skill++)
          if (characters.loadCharacter(path, skill) == 0)
            throw new IOException("character did not load at skill " + skill + ": " + diagnostics);
      }
      for (String diagnostic : diagnostics)
        if (diagnostic.startsWith("Character ")) throw new IOException(diagnostic);
    } else if (name.endsWith("_t.c")) {
      ChatLibrary.loadChats(sources, path);
    } else if (name.endsWith("_w.c") || name.endsWith("_i.c")) {
      WeightConfig.load(sources, path);
    } else if (name.equals("items.c")) {
      ItemConfig.load(sources, path);
    } else if (name.equals("weapons.c")) {
      WeaponConfig.load(sources, path);
    }
  }

  private static boolean libraryFile(String name) {
    return List.of("items.c", "weapons.c", "syn.c", "rnd.c", "match.c", "rchat.c").contains(name);
  }

  private static String fileName(String path) {
    return path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
  }

  private static final Pattern INCLUDE = Pattern.compile("#\\s*include\\s*[\"<]([^\">]+)[\">]");

  /** Names of the bot files other bot files include. */
  private static Set<String> includedNames(AssetLookup files, List<String> paths) {
    var names = new HashSet<String>();
    for (String path : paths) {
      var text = files.read(path).map(bytes -> new String(bytes, StandardCharsets.ISO_8859_1));
      if (text.isEmpty()) continue;
      var matcher = INCLUDE.matcher(text.get());
      while (matcher.find()) names.add(fileName(matcher.group(1).replace('\\', '/')));
    }
    return names;
  }

  /**
   * Bot files a playable bot loads: each {@code aifile} named by {@code scripts/bots.txt} or a
   * {@code scripts/*.bot} file, and the chat and weight files that character names.
   */
  private static Set<String> usedBotFiles(AssetLookup files) {
    var used = new HashSet<String>();
    var definitions = new ArrayList<>(files.list("scripts", "bot"));
    definitions.add("scripts/bots.txt");
    var aifile = Pattern.compile("(?i)aifile\\s+\"?([^\"\\s}]+)");
    try (var sources = new ScriptSources(files)) {
      for (String definition : definitions) {
        var text = files.read(definition).map(b -> new String(b, StandardCharsets.ISO_8859_1));
        if (text.isEmpty()) continue;
        var matcher = aifile.matcher(text.get());
        while (matcher.find()) {
          String character = matcher.group(1).replace('\\', '/').toLowerCase(Locale.ROOT);
          used.add("botfiles/" + character);
          try (var characters = new BotCharacters(sources, ignored -> {})) {
            int handle = characters.loadCharacter(character, 1);
            if (handle == 0) continue;
            for (int index : new int[] {3, 21, 40}) {
              String file = characters.characteristicString(handle, index);
              if (!file.isEmpty()) used.add("botfiles/" + file.toLowerCase(Locale.ROOT));
            }
          }
        }
      }
    }
    return used;
  }

  private static List<String> games() throws IOException {
    try (Stream<Path> entries = Files.list(RealDataRoot.require())) {
      return entries
          .filter(Files::isDirectory)
          .filter(BotlibCorpusTest::isGameDirectory)
          .map(path -> path.getFileName().toString())
          .sorted((a, b) -> a.equals("baseq3") ? -1 : b.equals("baseq3") ? 1 : a.compareTo(b))
          .toList();
    }
  }

  private static boolean isGameDirectory(Path directory) {
    if (directory.getFileName().toString().equalsIgnoreCase("baseq3")) return true;
    try (Stream<Path> files = Files.list(directory)) {
      return files.anyMatch(
          file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pk3"));
    } catch (IOException unreadable) {
      return false;
    }
  }

  /** The paths whose highest-priority copy belongs to {@code game} itself, not to baseq3. */
  private static List<String> own(SearchPath search, String game, List<String> paths) {
    String key = VirtualPath.gameDirectory(game);
    return paths.stream()
        .filter(
            path ->
                search
                    .view()
                    .find(VirtualPath.of(path))
                    .map(ref -> ref.mount().game().equals(key))
                    .orElse(false))
        .toList();
  }

  private static void write(String name, StringBuilder report) throws IOException {
    var directory = Path.of("build", "reports");
    Files.createDirectories(directory);
    Files.writeString(directory.resolve(name), report);
    System.out.print(report);
  }
}
