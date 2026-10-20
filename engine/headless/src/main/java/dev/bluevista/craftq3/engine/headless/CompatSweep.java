package dev.bluevista.craftq3.engine.headless;

import dev.bluevista.craftq3.engine.client.host.UiHost;
import dev.bluevista.craftq3.engine.formats.FileViewAssets;
import dev.bluevista.craftq3.engine.formats.aas.AasReader;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.bsp.BspReader;
import dev.bluevista.craftq3.engine.formats.image.ImageLoader;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition;
import dev.bluevista.craftq3.engine.formats.shader.ShaderLibrary;
import dev.bluevista.craftq3.engine.fs.FileRef;
import dev.bluevista.craftq3.engine.fs.FileView;
import dev.bluevista.craftq3.engine.fs.MountReport;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import dev.bluevista.craftq3.engine.render.RenderScene;
import dev.bluevista.craftq3.engine.session.CommandLine;
import dev.bluevista.craftq3.engine.session.LocalServer;
import dev.bluevista.craftq3.engine.session.SessionState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The compatibility sweep of the rewrite plan (§4) through the engine session, for every game
 * directory of a Quake III installation: baseq3, the retail pak0-only profile and every mod with
 * PK3s.
 *
 * <ol>
 *   <li>Mount every PK3 (skipped archives are listed, never fatal).
 *   <li>Load every map the directory provides: BSP, AAS, the render scene and the resolution of
 *       every referenced shader, texture and model.
 *   <li>Boot the effective ui to its main menu, then play a local match with bots for {@code
 *       --seconds} of game time on {@code --maps} maps (cgame and qagame of the directory).
 *   <li>Play every demo the directory provides to its end ({@code timedemo 1}).
 *   <li>Play every RoQ the directory provides to its end.
 *   <li>Count syscall degradations of every VM under {@code vm_strictSyscalls 1}; any is a failure.
 * </ol>
 *
 * Writes {@code report.json} and {@code report.md} into {@code --out}. Arguments: {@code --install
 * <dir>} (default {@code $CRAFTQ3_Q3ROOT}), {@code --out <dir>}, {@code --seconds <n>} (30), {@code
 * --maps <n>} (1), {@code --bots <n>} (4), {@code --games a,b} (all), {@code --skip
 * maps,demos,cinematics,match}.
 */
public final class CompatSweep {
  /** Sweep settings. */
  public record Settings(
      Path install,
      Path output,
      int seconds,
      int matchMaps,
      int bots,
      Set<String> games,
      Set<String> skip,
      LocalServer.Factory servers,
      Consumer<String> progress) {}

  /** One mountable game profile. */
  record Profile(String name, Path root, String game, String note) {}

  private final Settings settings;

  public CompatSweep(Settings settings) {
    this.settings = settings;
  }

  public static void main(String[] args) throws Exception {
    Path install = null;
    Path out = Path.of("build/compat");
    int seconds = 30, maps = 1, bots = 4;
    Set<String> games = new LinkedHashSet<>(), skip = new LinkedHashSet<>();
    String env = System.getenv("CRAFTQ3_Q3ROOT");
    if (env != null && !env.isBlank()) install = Path.of(env);
    for (int i = 0; i < args.length; i++) {
      String value = i + 1 < args.length ? args[i + 1] : "";
      switch (args[i]) {
        case "--install" -> install = Path.of(args[++i]);
        case "--out" -> out = Path.of(args[++i]);
        case "--seconds" -> seconds = Integer.parseInt(args[++i]);
        case "--maps" -> maps = Integer.parseInt(args[++i]);
        case "--bots" -> bots = Integer.parseInt(args[++i]);
        case "--games" -> {
          for (String g : value.split(",")) if (!g.isBlank()) games.add(g.strip());
          i++;
        }
        case "--skip" -> {
          for (String g : value.split(",")) if (!g.isBlank()) skip.add(g.strip());
          i++;
        }
        default -> throw new IllegalArgumentException("Unknown argument " + args[i]);
      }
    }
    if (install == null || !Files.isDirectory(install.resolve("baseq3"))) {
      System.err.println("Set CRAFTQ3_Q3ROOT (or --install) to a Quake III installation");
      System.exit(2);
    }
    Settings settings =
        new Settings(
            install,
            out,
            seconds,
            maps,
            bots,
            games,
            skip,
            dev.bluevista.craftq3.engine.session.GameLocalServer.FACTORY,
            System.out::println);
    Report report = new CompatSweep(settings).run();
    System.out.println(report.summary());
    System.exit(report.failures() == 0 ? 0 : 1);
  }

  // ================================================================ report model

  /** One problem in one game directory. */
  record Problem(String area, String subject, String message) {}

  /** What one game directory produced. */
  static final class GameReport {
    final Profile profile;
    final List<Problem> problems = new ArrayList<>();
    final Map<String, Object> mount = new LinkedHashMap<>();
    final Map<String, Object> maps = new LinkedHashMap<>();
    final Map<String, Object> boot = new LinkedHashMap<>();
    final List<Map<String, Object>> matches = new ArrayList<>();
    final List<Map<String, Object>> demos = new ArrayList<>();
    final List<Map<String, Object>> cinematics = new ArrayList<>();
    long degradations;
    long millis;

    GameReport(Profile profile) {
      this.profile = profile;
    }

    void problem(String area, String subject, String message) {
      problems.add(new Problem(area, subject, message));
    }
  }

  /** The whole sweep. */
  public static final class Report {
    final List<GameReport> games = new ArrayList<>();
    final Settings settings;

    Report(Settings settings) {
      this.settings = settings;
    }

    public int failures() {
      int count = 0;
      for (GameReport game : games) count += game.problems.size();
      return count;
    }

    public String summary() {
      StringBuilder text = new StringBuilder("Compatibility sweep: ");
      text.append(games.size())
          .append(" game directories, ")
          .append(failures())
          .append(" problems\n");
      for (GameReport game : games)
        text.append(
            String.format(
                Locale.ROOT,
                "  %-22s maps %s  match %s  demos %s  roq %s  degradations %d  problems %d%n",
                game.profile.name,
                game.maps.getOrDefault("loaded", "-") + "/" + game.maps.getOrDefault("total", "-"),
                matchSummary(game),
                passed(game.demos) + "/" + game.demos.size(),
                passed(game.cinematics) + "/" + game.cinematics.size(),
                game.degradations,
                game.problems.size()));
      return text.toString();
    }
  }

  private static String matchSummary(GameReport game) {
    if (game.matches.isEmpty()) return "-";
    return passed(game.matches) + "/" + game.matches.size();
  }

  private static long passed(List<Map<String, Object>> entries) {
    return entries.stream().filter(e -> "ok".equals(e.get("result"))).count();
  }

  // ================================================================ sweep

  public Report run() throws IOException {
    Files.createDirectories(settings.output());
    Report report = new Report(settings);
    for (Profile profile : profiles()) {
      if (!settings.games().isEmpty() && !settings.games().contains(profile.name())) continue;
      progress(
          "== " + profile.name() + (profile.note().isEmpty() ? "" : " (" + profile.note() + ")"));
      GameReport game = new GameReport(profile);
      long start = System.nanoTime();
      try {
        sweep(game);
      } catch (IOException | RuntimeException failure) {
        game.problem("sweep", profile.name(), failure.toString());
      }
      game.millis = (System.nanoTime() - start) / 1_000_000;
      report.games.add(game);
      write(report);
    }
    write(report);
    return report;
  }

  private void progress(String line) {
    if (settings.progress() != null) settings.progress().accept(line);
  }

  /** baseq3, the retail pak0 alone, and every directory with PK3s (nested layouts linked). */
  List<Profile> profiles() throws IOException {
    Path install = settings.install();
    List<Profile> profiles = new ArrayList<>();
    profiles.add(new Profile("baseq3", install, "baseq3", "effective pak8 VMs"));
    Path pak0 = install.resolve("baseq3/pak0.pk3");
    Path roots = Files.createDirectories(settings.output().resolve("roots"));
    if (Files.isRegularFile(pak0)) {
      Path retail = recreate(roots.resolve("retail"));
      Files.createDirectories(retail.resolve("baseq3"));
      Files.createSymbolicLink(retail.resolve("baseq3/pak0.pk3"), pak0.toRealPath());
      profiles.add(new Profile("baseq3-pak0", retail, "baseq3", "retail pak0 alone"));
    }
    try (var dirs = Files.list(install)) {
      for (Path dir : dirs.sorted().toList()) {
        if (!Files.isDirectory(dir)) continue;
        String name = dir.getFileName().toString();
        if (name.equalsIgnoreCase("baseq3")) continue;
        if (hasPacks(dir)) {
          profiles.add(new Profile(name, install, name, ""));
          continue;
        }
        Path nested = dir.resolve(name);
        if (Files.isDirectory(nested) && hasPacks(nested)) {
          // A game directory one level down (cpma/cpma) with its maps beside it ("cpma baseq3"):
          // presented as a root of links, as the user would copy them.
          Path root = recreate(roots.resolve(name));
          Path base = Files.createDirectories(root.resolve("baseq3"));
          linkPacks(install.resolve("baseq3"), base);
          try (var siblings = Files.list(dir)) {
            for (Path sibling : siblings.toList())
              if (!sibling.equals(nested) && Files.isDirectory(sibling) && hasPacks(sibling))
                linkPacks(sibling, base);
          }
          Files.createSymbolicLink(root.resolve(name), nested.toRealPath());
          profiles.add(new Profile(name, root, name, "nested layout, mounted through links"));
        }
      }
    }
    return profiles;
  }

  private static Path recreate(Path dir) throws IOException {
    if (Files.exists(dir)) {
      try (var walk = Files.walk(dir)) {
        for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList())
          Files.delete(path);
      }
    }
    return Files.createDirectories(dir);
  }

  private static boolean hasPacks(Path dir) throws IOException {
    try (var files = Files.list(dir)) {
      return files.anyMatch(
          f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pk3"));
    }
  }

  private static void linkPacks(Path from, Path into) throws IOException {
    try (var files = Files.list(from)) {
      for (Path file : files.toList()) {
        String name = file.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".pk3") || !Files.isRegularFile(file)) continue;
        if (Files.exists(into.resolve(name))) continue;
        Files.createSymbolicLink(into.resolve(name), file.toRealPath());
      }
    }
  }

  private void sweep(GameReport game) throws IOException {
    Profile profile = game.profile;
    List<String> mapNames, demoNames, videoNames, botNames;
    try (SearchPath path = SearchPath.mount(profile.root(), profile.game())) {
      MountReport mounted = path.report();
      game.mount.put("packs", path.packs().size());
      List<String> skipped = new ArrayList<>();
      for (var archive : mounted.skippedArchives())
        skipped.add(archive.file().getFileName() + ": " + archive.reason());
      game.mount.put("skipped", skipped);
      game.mount.put("skippedEntries", mounted.skippedEntries());
      FileView view = path.view();
      String own = VirtualPath.gameDirectory(profile.game());
      boolean base = profile.game().equalsIgnoreCase("baseq3");
      mapNames = owned(view, "maps", ".bsp", own, base);
      demoNames = owned(view, "demos", "", own, base).stream().filter(CompatSweep::isDemo).toList();
      videoNames = owned(view, "video", ".roq", own, base);
      botNames = botNames(view);
      if (!settings.skip().contains("maps")) checkMaps(game, view, mapNames);
      if (mapNames.isEmpty()) mapNames = owned(view, "maps", ".bsp", own, true);
      mapNames = matchMaps(view, mapNames);
    }
    HeadlessClient.Options options =
        HeadlessClient.options(profile.root())
            .withGame(profile.game())
            .withServers(settings.servers())
            .withCommandLine(
                CommandLine.parse(
                    "+set vm_strictSyscalls 1 +set sv_pure 0 +set bot_enable 1 +set sv_maxclients 12"))
            .withHome(settings.output().resolve("home").resolve(profile.name()));
    try (HeadlessClient client = HeadlessClient.start(options)) {
      client.run(500);
      game.boot.put("state", client.state().toString());
      UiHost ui = client.session().ui();
      game.boot.put("ui", ui.state().toString());
      if (ui.state() == UiHost.State.RUNNING) {
        game.boot.put("uiAbi", ui.profile().key());
        game.boot.put("uiApi", ui.apiVersion());
      } else game.problem("boot", "ui", "ui did not boot: " + client.tail(5));
      if (!settings.skip().contains("match")) {
        int played = 0;
        for (String map : mapNames) {
          if (played++ >= settings.matchMaps()) break;
          match(game, client, map, botNames);
        }
      }
      if (!settings.skip().contains("demos"))
        for (String demo : demoNames) demo(game, client, demo);
      if (!settings.skip().contains("cinematics"))
        for (String video : videoNames) cinematic(game, client, video);
      game.degradations = client.session().degradations();
      if (game.degradations > 0)
        game.problem("degradations", profile.name(), game.degradations + " syscall degradations");
    }
  }

  private static boolean isDemo(String name) {
    return name.toLowerCase(Locale.ROOT).matches(".*\\.(dm_\\d+|dm3)");
  }

  /** Names in {@code directory} that the profile's own game directory provides. */
  private static List<String> owned(
      FileView view, String directory, String extension, String own, boolean all) {
    List<String> names = new ArrayList<>();
    for (String name : view.list(directory, extension)) {
      if (name.contains("/")) continue;
      if (!extension.isEmpty() && !name.toLowerCase(Locale.ROOT).endsWith(extension)) continue;
      if (!all) {
        Optional<FileRef> ref = view.find(VirtualPath.of(directory + "/" + name));
        if (ref.isEmpty() || !ref.get().mount().game().equalsIgnoreCase(own)) continue;
      }
      names.add(name);
    }
    return names;
  }

  /** Bot names from {@code scripts/bots.txt} (the first {@code --bots} distinct ones). */
  private List<String> botNames(FileView view) {
    List<String> names = new ArrayList<>();
    try {
      VirtualPath path = VirtualPath.of("scripts/bots.txt");
      if (view.exists(path)) {
        String text = new String(view.read(path), StandardCharsets.ISO_8859_1);
        Matcher m = Pattern.compile("(?i)\\bname\\s+\"?([^\"\\s]+)\"?").matcher(text);
        while (m.find() && names.size() < settings.bots())
          if (!names.contains(m.group(1))) names.add(m.group(1));
      }
    } catch (IOException | RuntimeException unreadable) {
      // Fall back to the retail names.
    }
    for (String fallback : List.of("sarge", "major", "visor", "grunt", "anarki", "doom"))
      if (names.size() < settings.bots() && !names.contains(fallback)) names.add(fallback);
    return names;
  }

  /** Maps that have an AAS first (bots need one), q3dm17 first for baseq3. */
  private static List<String> matchMaps(FileView view, List<String> maps) {
    List<String> withAas = new ArrayList<>(), without = new ArrayList<>();
    for (String map : maps) {
      String base = map.substring(0, map.length() - 4);
      if (view.exists(VirtualPath.of("maps/" + base + ".aas"))) withAas.add(base);
      else without.add(base);
    }
    withAas.sort(
        (a, b) -> Boolean.compare(!a.equalsIgnoreCase("q3dm17"), !b.equalsIgnoreCase("q3dm17")));
    withAas.addAll(without);
    return withAas;
  }

  // ---------------------------------------------------------------- maps

  private void checkMaps(GameReport game, FileView view, List<String> maps) {
    FileViewAssets assets = new FileViewAssets(view);
    ShaderLibrary shaders = ShaderLibrary.load(assets);
    int loaded = 0, aasMissing = 0, aasLoaded = 0, scenes = 0;
    Set<String> missingTextures = new LinkedHashSet<>();
    Set<String> missingModels = new LinkedHashSet<>();
    Map<String, Boolean> imageCache = new java.util.HashMap<>();
    for (String name : maps) {
      String path = "maps/" + name;
      BspMap bsp;
      try {
        bsp = BspReader.read(view.read(VirtualPath.of(path)), path);
        loaded++;
      } catch (IOException | RuntimeException failure) {
        game.problem("map", name, "BSP: " + failure.getMessage());
        continue;
      }
      String aas = path.substring(0, path.length() - 4) + ".aas";
      if (!view.exists(VirtualPath.of(aas))) aasMissing++;
      else {
        try {
          AasReader.read(view.read(VirtualPath.of(aas)), aas);
          aasLoaded++;
        } catch (IOException | RuntimeException failure) {
          game.problem("map", name, "AAS: " + failure.getMessage());
        }
      }
      try {
        RenderScene.build(path, bsp, shaders::resolve);
        scenes++;
      } catch (RuntimeException failure) {
        game.problem("map", name, "render scene: " + failure.getMessage());
      }
      for (var texture : bsp.textures()) {
        String shader = texture.name();
        if (shader.equalsIgnoreCase("noshader")) continue;
        Optional<ShaderDefinition> scripted = shaders.find(shader);
        if (scripted.isEmpty()) {
          if (!image(assets, shader, imageCache)) missingTextures.add(shader);
          continue;
        }
        for (ShaderDefinition.Stage stage : scripted.get().stages()) {
          if (stage.texture() == null) continue;
          for (String frame : stage.texture().frames()) {
            if (frame.startsWith("$") || frame.startsWith("*")) continue;
            if (!image(assets, frame, imageCache)) missingTextures.add(shader + " -> " + frame);
          }
        }
      }
      for (String model : entityModels(bsp))
        if (!view.exists(VirtualPath.parse(model).orElse(VirtualPath.of("missing"))))
          missingModels.add(name + ": " + model);
    }
    game.maps.put("total", maps.size());
    game.maps.put("loaded", loaded);
    game.maps.put("aas", aasLoaded);
    game.maps.put("aasMissing", aasMissing);
    game.maps.put("scenes", scenes);
    game.maps.put("missingTextures", List.copyOf(missingTextures));
    game.maps.put("missingModels", List.copyOf(missingModels));
    game.maps.put("shaderDiagnostics", shaders.diagnostics().size());
    progress(
        "  maps "
            + loaded
            + "/"
            + maps.size()
            + ", aas "
            + aasLoaded
            + " (+"
            + aasMissing
            + " without), scenes "
            + scenes
            + ", missing textures "
            + missingTextures.size()
            + ", missing models "
            + missingModels.size());
  }

  private static boolean image(FileViewAssets assets, String name, Map<String, Boolean> cache) {
    return cache.computeIfAbsent(
        name.toLowerCase(Locale.ROOT), key -> ImageLoader.find(assets, key).isPresent());
  }

  /** {@code misc_model} and other file models named in the entity lump. */
  private static List<String> entityModels(BspMap bsp) {
    List<String> models = new ArrayList<>();
    for (var entity : bsp.entities()) {
      // q3map compiles misc_model geometry into the BSP; only runtime models count.
      if (entity.classname().orElse("").equalsIgnoreCase("misc_model")) continue;
      String model = entity.getOrDefault("model", "").replace('\\', '/');
      if (model.isEmpty() || model.startsWith("*") || models.contains(model)) continue;
      models.add(model);
    }
    return models;
  }

  // ---------------------------------------------------------------- session checks

  private void match(GameReport game, HeadlessClient client, String map, List<String> bots) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("map", map);
    entry.put("seconds", settings.seconds());
    game.matches.add(entry);
    long degradationsBefore = client.session().degradations();
    long start = System.nanoTime();
    client.command("map " + map);
    if (!client.runUntil(SessionState.LOCAL, 120_000)) {
      fail(
          game,
          entry,
          "match",
          map,
          "did not reach LOCAL: " + client.state() + "\n" + client.tail(8));
      client.command("disconnect");
      client.runUntil(SessionState.MENU, 2_000);
      return;
    }
    StringBuilder add = new StringBuilder();
    for (String bot : bots) add.append("addbot ").append(bot).append(" 3\n");
    client.command(add.toString());
    int step = 1000;
    for (int t = 0; t < settings.seconds() * 1000; t += step) {
      client.run(step);
      if (client.state() != SessionState.LOCAL) break;
    }
    var status = client.session().serverStatus();
    entry.put("bots", status.map(LocalServer.Status::bots).orElse(0));
    entry.put("serverFrames", status.map(LocalServer.Status::frames).orElse(0L));
    entry.put("abi", client.session().cgame().map(c -> c.profile().key()).orElse("-"));
    entry.put("wallSeconds", (System.nanoTime() - start) / 1e9);
    if (client.state() != SessionState.LOCAL) {
      fail(
          game,
          entry,
          "match",
          map,
          "left the game: "
              + client.session().cvars().string("com_errorMessage")
              + "\n"
              + client.tail(8));
    } else if (status.map(LocalServer.Status::bots).orElse(0) < bots.size()) {
      fail(
          game,
          entry,
          "match",
          map,
          "only " + entry.get("bots") + " of " + bots.size() + " bots joined\n" + client.tail(8));
    } else entry.put("result", "ok");
    entry.put("degradations", client.session().degradations() - degradationsBefore);
    client.command("disconnect");
    client.runUntil(SessionState.MENU, 5_000);
    progress("  match " + map + ": " + entry.getOrDefault("result", "FAILED") + " " + entry);
  }

  private void demo(GameReport game, HeadlessClient client, String demo) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("demo", demo);
    game.demos.add(entry);
    long finished = client.session().demosFinished();
    long frames = client.frames().frames();
    long start = System.nanoTime();
    client.session().cvars().set("com_errorMessage", "");
    client.command("timedemo 1; demo " + demo);
    boolean started =
        client.runUntil(
            () ->
                client.state() == SessionState.DEMO
                    || client.session().demosFinished() > finished
                    || !client.session().cvars().string("com_errorMessage").isEmpty(),
            60_000);
    boolean ended =
        started
            && client.runUntil(
                () ->
                    client.state() == SessionState.MENU
                            && client.session().demosFinished() > finished
                        || !client.session().cvars().string("com_errorMessage").isEmpty(),
                3_600_000);
    String error = client.session().cvars().string("com_errorMessage");
    entry.put("frames", client.frames().frames() - frames);
    entry.put("wallSeconds", (System.nanoTime() - start) / 1e9);
    String lower = demo.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".dm_48") || lower.endsWith(".dm3")) {
      // Quake III 1.27 (48) and pre-1.17 (.dm3) demos: 1.32 cannot play them either.
      boolean refused = !error.isEmpty() && client.session().demosFinished() == finished;
      entry.put("result", refused ? "ok" : "unexpected");
      entry.put("note", "refused by design: " + error);
      if (!refused) game.problem("demo", demo, "an unsupported demo protocol was accepted");
    } else if (ended && error.isEmpty() && client.session().demosFinished() > finished) {
      entry.put("result", "ok");
    } else
      fail(
          game, entry, "demo", demo, error.isEmpty() ? "did not finish: " + client.state() : error);
    client.command("timedemo 0; disconnect");
    client.runUntil(SessionState.MENU, 2_000);
    progress("  demo " + demo + ": " + entry);
  }

  private void cinematic(GameReport game, HeadlessClient client, String video) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("video", video);
    game.cinematics.add(entry);
    long images = client.frames().images();
    long start = System.nanoTime();
    client.command("cinematic " + video);
    boolean started = client.runUntil(SessionState.CINEMATIC, 1_000);
    String lower = video.toLowerCase(Locale.ROOT);
    boolean hold = lower.equals("end.roq") || lower.equals("demoend.roq");
    boolean ended;
    if (hold) {
      // These hold their last frame until a key, as in the original: play, then press one.
      long last = -1;
      for (int i = 0; i < 30 * 60 && client.state() == SessionState.CINEMATIC; i++) {
        client.run(1_000);
        long now = client.frames().images();
        if (now == last) break;
        last = now;
      }
      boolean held = client.state() == SessionState.CINEMATIC;
      client.tap(dev.bluevista.craftq3.engine.console.KeyNames.ESCAPE);
      ended = held && client.runUntil(SessionState.MENU, 1_000);
      entry.put("note", "held at its end until a key");
    } else ended = started && client.runUntil(SessionState.MENU, 30 * 60_000);
    long drawn = client.frames().images() - images;
    entry.put("frames", drawn);
    entry.put("wallSeconds", (System.nanoTime() - start) / 1e9);
    if (ended && drawn > 0) entry.put("result", "ok");
    else
      fail(
          game,
          entry,
          "cinematic",
          video,
          started ? "did not end (" + drawn + " frames)" : "did not start: " + client.tail(3));
    client.command("disconnect");
    client.runUntil(SessionState.MENU, 1_000);
    progress("  roq " + video + ": " + entry);
  }

  private static void fail(
      GameReport game, Map<String, Object> entry, String area, String subject, String message) {
    entry.put("result", "failed");
    entry.put("error", message);
    game.problem(area, subject, message);
  }

  // ---------------------------------------------------------------- output

  private void write(Report report) throws IOException {
    Files.writeString(
        settings.output().resolve("report.json"), json(report), StandardCharsets.UTF_8);
    Files.writeString(
        settings.output().resolve("report.md"), markdown(report), StandardCharsets.UTF_8);
  }

  private static String json(Report report) {
    List<Object> games = new ArrayList<>();
    for (GameReport game : report.games) {
      Map<String, Object> g = new LinkedHashMap<>();
      g.put("name", game.profile.name);
      g.put("game", game.profile.game);
      g.put("note", game.profile.note);
      g.put("mount", game.mount);
      g.put("maps", game.maps);
      g.put("boot", game.boot);
      g.put("matches", game.matches);
      g.put("demos", game.demos);
      g.put("cinematics", game.cinematics);
      g.put("degradations", game.degradations);
      g.put("seconds", game.millis / 1000.0);
      List<Object> problems = new ArrayList<>();
      for (Problem problem : game.problems)
        problems.add(
            Map.of(
                "area",
                problem.area(),
                "subject",
                problem.subject(),
                "message",
                problem.message()));
      g.put("problems", problems);
      games.add(g);
    }
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("matchSeconds", report.settings.seconds());
    root.put("problems", report.failures());
    root.put("games", games);
    StringBuilder out = new StringBuilder();
    Json.write(out, root, 0);
    return out.append('\n').toString();
  }

  private static String markdown(Report report) {
    StringBuilder md = new StringBuilder("# CraftQ3 compatibility sweep\n\n");
    md.append("Match length: ")
        .append(report.settings.seconds())
        .append(" s of game time with ")
        .append(report.settings.bots())
        .append(" bots. Problems: ")
        .append(report.failures())
        .append(".\n\n");
    md.append(
        "| Game | PK3s | Skipped | Maps | AAS | Scenes | Missing textures | ui | Match | Demos | RoQ | Degradations | Problems |\n");
    md.append(
        "| --- | ---: | ---: | --- | ---: | ---: | ---: | --- | --- | --- | --- | ---: | ---: |\n");
    for (GameReport game : report.games) {
      Object skipped = game.mount.get("skipped");
      md.append("| ")
          .append(game.profile.name)
          .append(" | ")
          .append(game.mount.getOrDefault("packs", "-"))
          .append(" | ")
          .append(skipped instanceof List<?> list ? list.size() : 0)
          .append(" | ")
          .append(game.maps.getOrDefault("loaded", "-"))
          .append('/')
          .append(game.maps.getOrDefault("total", "-"))
          .append(" | ")
          .append(game.maps.getOrDefault("aas", "-"))
          .append(" | ")
          .append(game.maps.getOrDefault("scenes", "-"))
          .append(" | ")
          .append(game.maps.get("missingTextures") instanceof List<?> l ? l.size() : "-")
          .append(" | ")
          .append(game.boot.getOrDefault("uiAbi", game.boot.getOrDefault("ui", "-")))
          .append(" | ")
          .append(matchSummary(game))
          .append(" | ")
          .append(passed(game.demos))
          .append('/')
          .append(game.demos.size())
          .append(" | ")
          .append(passed(game.cinematics))
          .append('/')
          .append(game.cinematics.size())
          .append(" | ")
          .append(game.degradations)
          .append(" | ")
          .append(game.problems.size())
          .append(" |\n");
    }
    for (GameReport game : report.games) {
      if (game.problems.isEmpty()) continue;
      md.append("\n## ").append(game.profile.name).append("\n\n");
      int shown = 0;
      for (Problem problem : game.problems) {
        if (shown++ == 60) {
          md.append("- ... ").append(game.problems.size() - 60).append(" more in report.json\n");
          break;
        }
        String message = problem.message().replace("\n", " / ");
        if (message.length() > 400) message = message.substring(0, 400) + "...";
        md.append("- **")
            .append(problem.area())
            .append("** ")
            .append(problem.subject())
            .append(": ")
            .append(message)
            .append('\n');
      }
    }
    return md.toString();
  }

  /** A minimal JSON writer for the report's maps, lists, strings and numbers. */
  static final class Json {
    private Json() {}

    static void write(StringBuilder out, Object value, int indent) {
      switch (value) {
        case null -> out.append("null");
        case Map<?, ?> map -> {
          out.append("{");
          boolean first = true;
          for (var entry : map.entrySet()) {
            out.append(first ? "\n" : ",\n");
            first = false;
            pad(out, indent + 2);
            string(out, String.valueOf(entry.getKey()));
            out.append(": ");
            write(out, entry.getValue(), indent + 2);
          }
          if (!first) {
            out.append('\n');
            pad(out, indent);
          }
          out.append("}");
        }
        case List<?> list -> {
          out.append("[");
          boolean first = true;
          for (Object item : list) {
            out.append(first ? "\n" : ",\n");
            first = false;
            pad(out, indent + 2);
            write(out, item, indent + 2);
          }
          if (!first) {
            out.append('\n');
            pad(out, indent);
          }
          out.append("]");
        }
        case Number number -> {
          double d = number.doubleValue();
          out.append(Double.isFinite(d) ? number.toString() : "null");
        }
        case Boolean bool -> out.append(bool);
        default -> string(out, value.toString());
      }
    }

    private static void pad(StringBuilder out, int count) {
      out.append(" ".repeat(count));
    }

    private static void string(StringBuilder out, String text) {
      out.append('"');
      for (int i = 0; i < text.length(); i++) {
        char c = text.charAt(i);
        switch (c) {
          case '"' -> out.append("\\\"");
          case '\\' -> out.append("\\\\");
          case '\n' -> out.append("\\n");
          case '\r' -> out.append("\\r");
          case '\t' -> out.append("\\t");
          default -> {
            if (c < 0x20 || c > 0x7e) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
          }
        }
      }
      out.append('"');
    }
  }
}
