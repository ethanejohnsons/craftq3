package dev.bluevista.craftq3.mc.fabric.client.building;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.FileViewAssets;
import dev.bluevista.craftq3.engine.formats.shader.ShaderLibrary;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import dev.bluevista.craftq3.engine.render.AreaMask;
import dev.bluevista.craftq3.engine.render.Camera;
import dev.bluevista.craftq3.engine.render.RenderScene;
import dev.bluevista.craftq3.engine.render.SceneTextures;
import dev.bluevista.craftq3.engine.render.ViewBasis;
import dev.bluevista.craftq3.engine.render.ViewDefinition;
import dev.bluevista.craftq3.mc.fabric.building.BuildingDimension;
import dev.bluevista.craftq3.mc.fabric.building.LevelBuilding;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3LevelOverlay;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3RenderBackend;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3RenderContext;
import dev.bluevista.craftq3.mc.fabric.config.CraftQ3Config;
import dev.bluevista.craftq3.mc.fabric.config.InstallationStatus;
import dev.bluevista.craftq3.mc.fabric.config.Q3Installation;
import dev.bluevista.craftq3.mc.fabric.level.CraftQ3Level;
import dev.bluevista.craftq3.mc.fabric.net.BuildingRegionsPayload;
import dev.bluevista.craftq3.mc.hybrid.CoordinateTransform;
import dev.bluevista.craftq3.mc.hybrid.building.BuildingRegion;
import dev.bluevista.craftq3.mc.hybrid.building.BuildingRegionCodec;
import dev.bluevista.craftq3.mc.hybrid.building.BuildingRegions;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.slf4j.Logger;

/**
 * Building mode on the client: the server's regions ({@link BuildingRegionsPayload}) are loaded
 * from the client's own installation on a virtual thread (BSP, checksum, queries, render scene and
 * textures) and become the client level's {@link LevelBuilding}, so the local player collides with
 * and picks the BSP as the server does. The region the camera is in is drawn inside Minecraft's
 * level with Minecraft's camera and depth ({@link Q3LevelOverlay}); placed blocks render normally
 * and occlude it. A region whose BSP the client lacks stays invisible here (the server keeps its
 * rules). While regions load the local player is held, so it cannot fall through a floor the client
 * does not know yet.
 */
public final class BuildingClient implements Q3LevelOverlay, AutoCloseable {
  private static final Logger LOGGER = LogUtils.getLogger();

  private final Q3Installation installation;
  private final Q3RenderContext renderContext;

  /** What a background load produced for one client level. */
  private record Loaded(
      ClientLevel level,
      List<BuildingRegion> regions,
      SearchPath search,
      FileViewAssets assets,
      ShaderLibrary library,
      List<BuildingRegions.Status> statuses,
      Map<Integer, RenderScene> scenes,
      Map<Integer, SceneTextures> textures) {}

  private ClientLevel requestedLevel;
  private List<BuildingRegion> requested = List.of();
  private CompletableFuture<Loaded> loading;
  private Loaded current;
  private Q3RenderBackend backend;
  private int backendSlot = -1;
  private long renderedFrames;
  private long lastCpuNanos;

  public BuildingClient(Q3Installation installation, Q3RenderContext renderContext) {
    this.installation = Objects.requireNonNull(installation);
    this.renderContext = Objects.requireNonNull(renderContext);
  }

  /** Registers the payload receiver (client initialisation). */
  public void register() {
    ClientPlayNetworking.registerGlobalReceiver(
        BuildingRegionsPayload.TYPE,
        (payload, context) -> {
          try {
            regions(context.client(), BuildingRegionCodec.decode(payload.regions()));
          } catch (IOException malformed) {
            LOGGER.warn("CraftQ3: unreadable building regions from the server", malformed);
          }
        });
    ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
  }

  /** The server's regions for the level the player is in. */
  void regions(Minecraft client, List<BuildingRegion> regions) {
    ClientLevel level = client.level;
    if (level == null) return;
    if (level == requestedLevel && regions.equals(requested)) return;
    requestedLevel = level;
    requested = List.copyOf(regions);
    if (regions.isEmpty()) {
      CraftQ3Level.of(level).craftq3$setBuilding(LevelBuilding.NONE);
      loading = null;
      replace(null);
      return;
    }
    CraftQ3Config config = installation.config();
    Optional<Path> root = config.installationPath();
    CompletableFuture<Loaded> load = new CompletableFuture<>();
    loading = load;
    Thread.ofVirtual()
        .name("craftq3-building-load")
        .start(
            () -> {
              SearchPath search = null;
              try {
                if (root.isEmpty())
                  throw new IOException("No Quake III installation is configured");
                search =
                    SearchPath.mount(
                        InstallationStatus.installationRoot(root.get()), config.game());
                SearchPath mounted = search;
                FileViewAssets assets = new FileViewAssets(search.view());
                ShaderLibrary library = ShaderLibrary.load(assets);
                List<BuildingRegions.Status> statuses = new ArrayList<>();
                Map<Integer, RenderScene> scenes = new HashMap<>();
                Map<Integer, SceneTextures> textures = new HashMap<>();
                for (BuildingRegion region : regions) {
                  BuildingRegions.Status status =
                      BuildingRegions.load(
                          region,
                          (game, map) -> {
                            Optional<VirtualPath> path = VirtualPath.parse(map);
                            if (!game.equalsIgnoreCase(mounted.game())
                                || path.isEmpty()
                                || !mounted.exists(path.get())) return Optional.empty();
                            return Optional.of(mounted.read(path.get()));
                          });
                  statuses.add(status);
                  if (status instanceof BuildingRegions.Enabled e) {
                    RenderScene scene = RenderScene.build(region.map(), e.map(), library::resolve);
                    scenes.put(region.slot(), scene);
                    textures.put(region.slot(), SceneTextures.load(assets, scene));
                  }
                }
                load.complete(
                    new Loaded(
                        level, regions, search, assets, library, statuses, scenes, textures));
              } catch (Throwable t) {
                if (search != null)
                  try {
                    search.close();
                  } catch (IOException e) {
                    t.addSuppressed(e);
                  }
                load.completeExceptionally(t);
              }
            });
    load.whenCompleteAsync((loaded, error) -> finish(client, load, loaded, error), client);
  }

  private void finish(
      Minecraft client, CompletableFuture<Loaded> load, Loaded loaded, Throwable error) {
    if (loading != load) {
      if (loaded != null) closeQuietly(loaded.search());
      return;
    }
    loading = null;
    if (error != null) {
      Throwable cause = error.getCause() != null ? error.getCause() : error;
      LOGGER.warn("CraftQ3: building regions could not load: {}", cause.toString());
      chat(client, "building regions are not shown: " + cause.getMessage());
      CraftQ3Level.of(requestedLevel).craftq3$setBuilding(LevelBuilding.NONE);
      return;
    }
    if (client.level != loaded.level()) {
      closeQuietly(loaded.search());
      return;
    }
    CraftQ3Level.of(loaded.level()).craftq3$setBuilding(new LevelBuilding(loaded.statuses()));
    for (BuildingRegions.Status s : loaded.statuses())
      if (s instanceof BuildingRegions.Disabled d)
        chat(client, "region " + d.region().mapName() + " is not shown here: " + d.reason());
    replace(loaded);
    LOGGER.info("CraftQ3: {} building region(s) ready", loaded.scenes().size());
  }

  private static void chat(Minecraft client, String text) {
    client.gui.hud.getChat().addClientSystemMessage(Component.literal("CraftQ3: " + text));
  }

  private void replace(Loaded next) {
    if (backend != null) {
      backend.close();
      backend = null;
      backendSlot = -1;
    }
    if (current != null) closeQuietly(current.search());
    current = next;
  }

  private static void closeQuietly(SearchPath search) {
    try {
      search.close();
    } catch (IOException e) {
      LOGGER.debug("CraftQ3: closing a building search path", e);
    }
  }

  /** Whether the local player waits for regions of the building dimension to load. */
  public boolean holdsPlayer(Minecraft client) {
    return loading != null && client.level != null && BuildingDimension.is(client.level);
  }

  @Override
  public void beforeCamera(Minecraft minecraft) {
    if (current != null && minecraft.level != current.level()) {
      replace(null);
      requestedLevel = null;
      requested = List.of();
    }
  }

  @Override
  public void renderInLevel(
      Minecraft minecraft, Matrix4fc projection, Matrix4fc viewRotation, Vec3 camera) {
    Loaded loaded = current;
    if (loaded == null || minecraft.level != loaded.level()) return;
    int slot = BuildingDimension.LAYOUT.slotAt(camera.x);
    RenderScene scene = loaded.scenes().get(slot);
    if (scene == null) return;
    BuildingRegions.Enabled region =
        CraftQ3Level.of(loaded.level()).craftq3$building().enabled(slot).orElse(null);
    if (region == null) return;
    try {
      if (backend == null || backendSlot != slot) {
        if (backend == null)
          backend = new Q3RenderBackend(renderContext, loaded.library()::resolve, loaded.assets());
        backend.loadMap(scene, loaded.textures().get(slot), loaded.assets());
        backendSlot = slot;
      }
      CoordinateTransform t = region.region().transform();
      RenderTarget target = minecraft.gameRenderer.mainRenderTarget();
      int time = (int) (System.nanoTime() / 1_000_000L & 0x3fffffff);
      backend.externalCamera(
          Q3LevelOverlay.quakeToClip(projection, viewRotation, camera, t),
          target.getDepthTextureView());
      try {
        backend.beginFrame(target, target.width, target.height, time);
        backend.render(view(minecraft, camera, t, target, time), List.of(), List.of(), List.of());
        backend.endFrame();
      } finally {
        backend.externalCamera(null, null);
      }
      lastCpuNanos = backend.stats().cpuNanos;
      renderedFrames++;
    } catch (RuntimeException e) {
      LOGGER.error("CraftQ3: drawing the building region failed; it is hidden", e);
      replace(null);
    }
  }

  /** Minecraft's camera as a Quake view (for visibility, shader time and fog). */
  private static ViewDefinition view(
      Minecraft minecraft, Vec3 camera, CoordinateTransform t, RenderTarget target, int time) {
    var mc = minecraft.gameRenderer.mainCamera();
    float aspect = (float) target.width / Math.max(1, target.height);
    float fovY = Math.clamp(mc.getFov(), 1f, 170f);
    float fovX = (float) Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(fovY) / 2) * aspect));
    fovX = Math.clamp(fovX, 2f, 178f);
    Vec3f origin = t.toQuake(camera.x, camera.y, camera.z);
    Camera q =
        new Camera(
            origin,
            CoordinateTransform.quakeYaw(mc.yRot()),
            CoordinateTransform.quakePitch(mc.xRot()),
            fovX);
    return new ViewDefinition(
        ViewDefinition.Kind.MAIN,
        origin,
        ViewBasis.of(q),
        fovX,
        fovY,
        0,
        0,
        target.width,
        target.height,
        time,
        0,
        AreaMask.NONE,
        Optional.empty(),
        origin,
        -1,
        0);
  }

  public long renderedFrames() {
    return renderedFrames;
  }

  public long lastCpuNanos() {
    return lastCpuNanos;
  }

  /** The regions shown, by status (tests). */
  public List<BuildingRegions.Status> statuses() {
    return current == null ? List.of() : current.statuses();
  }

  private void reset() {
    loading = null;
    requestedLevel = null;
    requested = List.of();
    replace(null);
  }

  @Override
  public void close() {
    reset();
  }
}
