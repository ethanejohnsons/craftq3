package dev.bluevista.craftq3.mc.fabric.client.session;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import dev.bluevista.craftq3.engine.formats.AssetLookup;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition;
import dev.bluevista.craftq3.engine.formats.shader.ShaderLibrary;
import dev.bluevista.craftq3.engine.render.RenderScene;
import dev.bluevista.craftq3.engine.render.SceneTextures;
import dev.bluevista.craftq3.engine.render.frame.CgameFrame;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3RenderBackend;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3RenderContext;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;

/**
 * The render side of a session: receives its {@link CgameFrame}s and draws the latest one through
 * one {@link Q3RenderBackend}, and loads each new map off the render thread.
 *
 * <ul>
 *   <li><b>Maps</b>: {@link #mapLoaded} builds the {@link RenderScene} and decodes its {@link
 *       SceneTextures} on a virtual thread; the next {@link #render} after it finishes uploads them
 *       ({@code loadMap}). While a load is pending, frames that contain a world view are held back
 *       and the last frame without one (the cgame's or ui's loading screen) stays up, so the first
 *       in-game frames never show an empty world.
 *   <li><b>Frames</b>: only the latest submitted frame is drawn; frames submitted by {@code
 *       trap_UpdateScreen} during a long VM call replace each other.
 * </ul>
 *
 * <p>Render thread only, except the worker that runs the load.
 */
public final class Q3FramePresenter implements AutoCloseable {
  private static final Logger LOGGER = LogUtils.getLogger();

  /** A map ready to upload. */
  private record Prepared(
      String name, RenderScene scene, SceneTextures textures, AssetLookup lookup) {}

  private final Q3RenderContext context;
  private Q3RenderBackend backend;
  private volatile AssetLookup lookup;
  private volatile ShaderLibrary library;
  private CgameFrame latest;
  private CgameFrame lastWithoutWorld;
  private CompletableFuture<Prepared> pending;
  private String pendingName = "";
  private String loadedName = "";
  private long framesDrawn;
  private long lastCpuNanos;
  private boolean closed;

  public Q3FramePresenter(Q3RenderContext context) {
    this.context = Objects.requireNonNull(context, "context");
  }

  /** The session's file view and shader scripts, for images and shaders not decoded ahead. */
  public void attachAssets(AssetLookup assets, ShaderLibrary shaders) {
    lookup = Objects.requireNonNull(assets, "assets");
    library = Objects.requireNonNull(shaders, "shaders");
  }

  /** {@link dev.bluevista.craftq3.engine.client.RenderSink#submit}. */
  public void submit(CgameFrame frame) {
    latest = frame;
    if (!hasWorldView(frame)) lastWithoutWorld = frame;
  }

  /**
   * The session loaded {@code name} ({@code maps/<map>.bsp}): starts building its scene with {@code
   * options} ({@code r_subdivisions}). Loading the map already shown, or already being loaded, does
   * nothing.
   */
  public void mapLoaded(String name, BspMap map, RenderScene.Options options) {
    if (closed) return;
    if (pending != null ? name.equals(pendingName) : name.equals(loadedName) && backendHasMap())
      return;
    AssetLookup assets = Objects.requireNonNull(lookup, "attachAssets first");
    ShaderLibrary shaders = Objects.requireNonNull(library, "attachAssets first");
    CompletableFuture<Prepared> load = new CompletableFuture<>();
    pending = load;
    pendingName = name;
    Thread.ofVirtual()
        .name("craftq3-scene-load")
        .start(
            () -> {
              try {
                long start = System.nanoTime();
                RenderScene scene = RenderScene.build(name, map, shaders::resolve, options);
                SceneTextures textures = SceneTextures.load(assets, scene);
                LOGGER.info(
                    "CraftQ3: prepared {} off-thread in {} ms ({} images, {} lightmaps)",
                    name,
                    (System.nanoTime() - start) / 1_000_000,
                    textures.images().size(),
                    textures.lightmaps().size());
                load.complete(new Prepared(name, scene, textures, assets));
              } catch (Throwable t) {
                load.completeExceptionally(t);
              }
            });
  }

  /** The session left its map (disconnect): the world is released on the next frame. */
  public void mapUnloaded() {
    if (pending != null) pending.cancel(false);
    pending = null;
    pendingName = "";
    loadedName = "";
    if (backend != null) backend.unloadMap();
  }

  /** Whether a map is being prepared. */
  public boolean loading() {
    return pending != null;
  }

  /** The map shown, as the session named it, or empty. */
  public String mapName() {
    return loadedName;
  }

  /** The map being prepared, else the map shown, else empty. */
  public String requestedMap() {
    return pending != null ? pendingName : loadedName;
  }

  /** Waits (blocking) for a pending load; for tests. */
  public void awaitLoad() {
    CompletableFuture<Prepared> load = pending;
    if (load != null) load.handle((p, e) -> null).join();
  }

  /** Draws the latest frame into {@code target}, a {@code width}×{@code height} Q3 screen. */
  public void render(RenderTarget target, int width, int height) {
    if (closed) return;
    finishLoad();
    draw(target, width, height);
  }

  /**
   * As {@link #render}, for a loading frame presented inside a VM call: a map prepared meanwhile is
   * not uploaded here (a loading screen does not show it; the next {@link #render} uploads it), so
   * the upload never lengthens a loading step.
   */
  public void renderLoading(RenderTarget target, int width, int height) {
    if (closed) return;
    draw(target, width, height);
  }

  private void draw(RenderTarget target, int width, int height) {
    CgameFrame frame = latest;
    if (pending != null && hasWorldView(frame)) frame = lastWithoutWorld;
    if (frame == null) return;
    Q3RenderBackend renderer = backend();
    renderer.beginFrame(target, width, height, frame.timeMillis());
    renderer.render(frame);
    renderer.endFrame();
    lastCpuNanos = renderer.stats().cpuNanos;
    framesDrawn++;
  }

  /**
   * Draws the latest frame inside Minecraft's level (the bridge): world views use {@code
   * quakeToClip} (Quake world to Minecraft clip space) and Minecraft's {@code depth}, so Quake
   * entities and the weapon are hidden by blocks; 2D quads (the HUD) draw over everything. Nothing
   * is cleared.
   */
  public void renderInLevel(
      RenderTarget target,
      int width,
      int height,
      org.joml.Matrix4fc quakeToClip,
      com.mojang.blaze3d.textures.GpuTextureView depth) {
    if (closed) return;
    finishLoad();
    CgameFrame frame = latest;
    if (frame == null || (pending != null && hasWorldView(frame))) return;
    Q3RenderBackend renderer = backend();
    renderer.externalCamera(quakeToClip, depth);
    try {
      renderer.beginFrame(target, width, height, frame.timeMillis());
      renderer.render(frame);
      renderer.endFrame();
    } finally {
      renderer.externalCamera(null, null);
    }
    lastCpuNanos = renderer.stats().cpuNanos;
    framesDrawn++;
  }

  private void finishLoad() {
    CompletableFuture<Prepared> load = pending;
    if (load == null || !load.isDone()) return;
    pending = null;
    pendingName = "";
    if (load.isCancelled()) return;
    Prepared prepared;
    try {
      prepared = load.join();
    } catch (RuntimeException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      throw new IllegalStateException("Cannot prepare the map for rendering: " + cause, cause);
    }
    backend().loadMap(prepared.scene(), prepared.textures(), prepared.lookup());
    loadedName = prepared.name();
  }

  private boolean backendHasMap() {
    return backend != null && backend.mapLoaded();
  }

  private Q3RenderBackend backend() {
    if (backend == null) {
      // The session attaches its assets before its first frame; until then nothing resolves.
      backend = new Q3RenderBackend(context, this::resolveShader, new DeferredLookup());
    }
    return backend;
  }

  private ShaderDefinition resolveShader(String name, boolean lightmapped) {
    ShaderLibrary shaders = library;
    return shaders != null
        ? shaders.resolve(name, lightmapped)
        : ShaderDefinition.implicit(ShaderDefinition.canonicalName(name), lightmapped);
  }

  /** Reads through the attached lookup, which may arrive after the backend exists. */
  private final class DeferredLookup implements AssetLookup {
    @Override
    public Optional<byte[]> read(String path) {
      AssetLookup assets = lookup;
      return assets == null ? Optional.empty() : assets.read(path);
    }

    @Override
    public List<String> list(String directory, String extension) {
      AssetLookup assets = lookup;
      return assets == null ? List.of() : assets.list(directory, extension);
    }

    @Override
    public boolean exists(String path) {
      AssetLookup assets = lookup;
      return assets != null && assets.exists(path);
    }
  }

  /** The backend, or null before the first frame. */
  public Q3RenderBackend backendOrNull() {
    return backend;
  }

  public long framesDrawn() {
    return framesDrawn;
  }

  /** The backend's CPU time of the last frame drawn. */
  public long lastCpuNanos() {
    return lastCpuNanos;
  }

  /** The frame most recently submitted, or null. */
  public CgameFrame latestFrame() {
    return latest;
  }

  private static boolean hasWorldView(CgameFrame frame) {
    if (frame == null) return false;
    for (CgameFrame.Command command : frame.commands())
      if (command instanceof CgameFrame.View view && view.refdef().worldModel()) return true;
    return false;
  }

  /** Releases the backend's GPU objects (map buffers, textures this session uploaded). */
  @Override
  public void close() {
    if (closed) return;
    closed = true;
    if (pending != null) pending.cancel(false);
    pending = null;
    latest = lastWithoutWorld = null;
    if (backend != null) backend.close();
    backend = null;
  }
}
