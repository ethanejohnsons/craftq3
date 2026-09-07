package dev.bluevista.craftq3.fabric;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.core.config.CraftQ3Config;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.render.BspSceneBuilder;
import dev.bluevista.craftq3.render.MaterialLibrary;
import dev.bluevista.craftq3.render.RenderScene;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Application orchestration; all filesystem replacement is transactional. */
final class CraftQ3Runtime implements AutoCloseable {
  private final Path configFile;
  private final Path defaultInstallation;
  private final Path developmentInstallation;
  private CraftQ3Config config;
  private Pk3FileSystem fs;
  private String mapName;
  private BspMap map;
  private boolean closed;

  CraftQ3Runtime(Path configFile, Path defaultInstallation) {
    this(configFile, defaultInstallation, null);
  }

  CraftQ3Runtime(Path configFile, Path defaultInstallation, Path developmentInstallation) {
    this.developmentInstallation = developmentInstallation;
    this.configFile = configFile;
    this.defaultInstallation = defaultInstallation;
  }

  synchronized void reload() throws IOException {
    replace(
        developmentInstallation == null
            ? CraftQ3Config.load(configFile, defaultInstallation)
            : new CraftQ3Config(developmentInstallation, "baseq3"),
        false);
  }

  synchronized Pk3FileSystem bridgeFiles() throws IOException {
    ready();
    return Pk3FileSystem.mount(config.installation(), config.game());
  }

  synchronized dev.bluevista.craftq3.core.fs.GameFileStore bridgeSettings() throws IOException {
    ready();
    Path root = defaultInstallation.toAbsolutePath().getParent().resolve("home").resolve("bridge");
    if (net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()
        && System.getProperty("craftq3.bridgeSmokeWorld") != null) {
      boolean loadout = dev.bluevista.craftq3.fabric.bridge.BridgeLoadoutSmoke.enabled();
      if (!loadout && System.getProperty("craftq3.bridgeSettingsSmoke", "").isEmpty()) return null;
      root =
          net.fabricmc.loader.api.FabricLoader.getInstance()
              .getGameDir()
              .resolve(loadout ? "craftq3-bridge-loadout-qa" : "craftq3-bridge-settings-qa");
    }
    try {
      return new dev.bluevista.craftq3.core.fs.GameFileStore(root.resolve(config.game()));
    } catch (IOException unavailable) {
      CraftQ3Client.LOGGER.warn(
          "Bridge settings unavailable; changes will not persist: {}", unavailable.getMessage());
      return null;
    }
  }

  synchronized Pk3FileSystem buildingFiles(String game) throws IOException {
    ready();
    return Pk3FileSystem.mount(config.installation(), game == null ? config.game() : game);
  }

  synchronized String gameName() throws IOException {
    ready();
    return config.game();
  }

  synchronized void game(String game) throws IOException {
    ready();
    replace(new CraftQ3Config(config.installation(), game), true);
  }

  synchronized void installation(Path installation) throws IOException {
    replace(new CraftQ3Config(installation, "baseq3"), true);
  }

  private void replace(CraftQ3Config next, boolean save) throws IOException {
    if (closed) throw new IOException("CraftQ3 is shutting down");
    Pk3FileSystem mounted = Pk3FileSystem.mount(next.installation(), next.game());
    try {
      if (save) next.save(configFile);
    } catch (IOException e) {
      mounted.close();
      throw e;
    }
    Pk3FileSystem previous = fs;
    fs = mounted;
    config = next;
    map = null;
    mapName = null;
    if (previous != null) {
      try {
        previous.close();
      } catch (IOException e) {
        CraftQ3Client.LOGGER.warn("Could not close previous mount", e);
      }
    }
    CraftQ3Client.LOGGER.info(
        "Mounted Q3 installation {} game {} with {} search paths",
        next.installation(),
        next.game(),
        mounted.searchOrder().size());
  }

  synchronized List<String> status() throws IOException {
    ready();
    List<String> lines = new ArrayList<>();
    lines.add("CraftQ3 | Minecraft 26.2 / Java 25 | game=" + config.game());
    lines.add("Installation: " + config.installation());
    lines.add("Search order (first wins):");
    fs.searchOrder()
        .forEach(origin -> lines.add((origin.archive() ? "PK3 " : "DIR ") + origin.container()));
    lines.add(
        "Indexed files: " + fs.list("").size() + " | map=" + (mapName == null ? "none" : mapName));
    return List.copyOf(lines);
  }

  synchronized List<String> list(String path) throws IOException {
    ready();
    var entries = fs.list(path);
    List<String> lines = new ArrayList<>();
    entries.stream().limit(100).forEach(entry -> lines.add(entry.value()));
    lines.add(entries.size() + " files (showing at most 100)");
    return List.copyOf(lines);
  }

  synchronized String which(String path) throws IOException {
    ready();
    VirtualPath virtual = new VirtualPath(path);
    return fs.which(virtual)
        .map(origin -> virtual.value() + " -> " + origin.container())
        .orElse("Not found: " + virtual.value());
  }

  synchronized RenderScene loadMap(String name) throws IOException {
    return loadWorld(name).scene();
  }

  record LoadedWorld(RenderScene scene, MaterialLibrary materials) {}

  synchronized dev.bluevista.craftq3.fabric.game.UiPreviewSession loadUiPreview(
      dev.bluevista.craftq3.platform.audio.AudioBackend audio, int width, int height)
      throws IOException {
    ready();
    var mounted = Pk3FileSystem.mount(config.installation(), config.game());
    return new dev.bluevista.craftq3.fabric.game.UiPreviewSession(mounted, audio, width, height);
  }

  private Path gameStorage(String kind) {
    if (dev.bluevista.craftq3.fabric.bridge.BridgeTransferSmoke.enabled())
      return net.fabricmc.loader.api.FabricLoader.getInstance()
          .getGameDir()
          .resolve("craftq3-transfer-qa")
          .resolve(kind);
    return defaultInstallation.toAbsolutePath().getParent().resolve(kind);
  }

  synchronized dev.bluevista.craftq3.fabric.game.QuakeSession loadGame(
      String name,
      dev.bluevista.craftq3.platform.audio.AudioBackend audio,
      String playerName,
      int width,
      int height)
      throws IOException {
    return loadGame(name, audio, playerName, width, height, null, null);
  }

  synchronized dev.bluevista.craftq3.fabric.game.QuakeSession loadGame(
      String name,
      dev.bluevista.craftq3.platform.audio.AudioBackend audio,
      String playerName,
      int width,
      int height,
      dev.bluevista.craftq3.server.PlayerLoadout arriving,
      byte[] expectedModule)
      throws IOException {
    ready();
    // A running game owns its own mount, so a later asset reload cannot invalidate its VM reads.
    Pk3FileSystem gameFiles = Pk3FileSystem.mount(config.installation(), config.game());
    dev.bluevista.craftq3.core.fs.GameFileStore saves = null;
    dev.bluevista.craftq3.core.fs.DemoFileStore demos = null;
    dev.bluevista.craftq3.assets.fs.DownloadCache downloads = null;
    try {
      try {
        downloads =
            new dev.bluevista.craftq3.assets.fs.DownloadCache(
                defaultInstallation.toAbsolutePath().getParent().resolve("downloads"));
        downloads.mountCached(gameFiles);
      } catch (IOException | RuntimeException unavailable) {
        if (downloads != null) downloads.close();
        downloads = null;
        CraftQ3Client.LOGGER.warn("Quake download cache unavailable: {}", unavailable.getMessage());
      }
      RenderScene world = null;
      MaterialLibrary materials = null;
      if (name != null) {
        VirtualPath path =
            new VirtualPath(
                "maps/"
                    + name
                    + (name.toLowerCase(java.util.Locale.ROOT).endsWith(".bsp") ? "" : ".bsp"));
        BspMap loaded = BspReader.read(gameFiles.read(path));
        world = BspSceneBuilder.build(path.value(), loaded, 8);
        materials = MaterialLibrary.load(gameFiles, world);
      }
      try {
        saves =
            new dev.bluevista.craftq3.core.fs.GameFileStore(
                gameStorage("home").resolve(config.game()));
      } catch (IOException unavailable) {
        CraftQ3Client.LOGGER.warn(
            "Quake saved files unavailable; disk logs/config writes disabled: {}",
            unavailable.getMessage());
      }
      try {
        demos =
            new dev.bluevista.craftq3.core.fs.DemoFileStore(
                gameStorage("demos").resolve(config.game()));
      } catch (IOException unavailable) {
        CraftQ3Client.LOGGER.warn("Quake demo storage unavailable: {}", unavailable.getMessage());
      }
      return new dev.bluevista.craftq3.fabric.game.QuakeSession(
          gameFiles,
          saves,
          audio,
          world,
          materials,
          playerName,
          width,
          height,
          demos,
          downloads,
          arriving,
          expectedModule);
    } catch (IOException | RuntimeException e) {
      for (AutoCloseable resource :
          new AutoCloseable[] {downloads, demos, saves, gameFiles, audio}) {
        if (resource == null) continue;
        try {
          resource.close();
        } catch (Exception closeError) {
          e.addSuppressed(closeError);
        }
      }
      throw e;
    }
  }

  synchronized LoadedWorld loadWorld(String name) throws IOException {
    ready();
    VirtualPath path =
        new VirtualPath(
            "maps/"
                + name
                + (name.toLowerCase(java.util.Locale.ROOT).endsWith(".bsp") ? "" : ".bsp"));
    BspMap loaded = BspReader.read(fs.read(path));
    RenderScene scene = BspSceneBuilder.build(path.value(), loaded, 8);
    MaterialLibrary materials = MaterialLibrary.load(fs, scene);
    map = loaded;
    mapName = path.value();
    CraftQ3Client.LOGGER.info(
        "Loaded {}: {} vertices, {} faces, {} debug triangles",
        path.value(),
        map.vertices().size(),
        map.faces().size(),
        scene.triangles().size());
    CraftQ3Client.LOGGER.info(
        "Loaded {} material surfaces, {} images, {} lightmaps, {} diagnostics",
        materials.surfaces().size(),
        materials.images().size(),
        materials.lightmaps().size(),
        materials.diagnostics().size());
    materials.diagnostics().stream()
        .limit(20)
        .forEach(message -> CraftQ3Client.LOGGER.warn("Material: {}", message));
    return new LoadedWorld(scene, materials);
  }

  synchronized String bspStatus() throws IOException {
    ready();
    if (map == null) return "No BSP loaded. Use /q3 view <name>.";
    return mapName
        + ": entities="
        + map.entities().size()
        + ", vertices="
        + map.vertices().size()
        + ", faces="
        + map.faces().size()
        + ", nodes="
        + map.nodes().size()
        + ", leaves="
        + map.leaves().size()
        + ", clusters="
        + map.visibility().clusters()
        + ", lightmaps="
        + map.lightmaps().size();
  }

  private void ready() throws IOException {
    if (closed || fs == null)
      throw new IOException(
          "No Q3 filesystem mounted. Set /q3 path <installation> or fix "
              + configFile
              + " and /q3 reload.");
  }

  @Override
  public synchronized void close() throws IOException {
    closed = true;
    if (fs != null) fs.close();
  }
}
