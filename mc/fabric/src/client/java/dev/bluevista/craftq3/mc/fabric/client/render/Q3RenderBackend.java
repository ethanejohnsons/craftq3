package dev.bluevista.craftq3.mc.fabric.client.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.DeviceFeatures;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.logging.LogUtils;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.AssetLookup;
import dev.bluevista.craftq3.engine.formats.image.ImageLoader;
import dev.bluevista.craftq3.engine.formats.image.Q3Image;
import dev.bluevista.craftq3.engine.formats.md3.Md3Model;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition;
import dev.bluevista.craftq3.engine.render.AreaMask;
import dev.bluevista.craftq3.engine.render.DrawBatch;
import dev.bluevista.craftq3.engine.render.DynamicLight;
import dev.bluevista.craftq3.engine.render.Plane;
import dev.bluevista.craftq3.engine.render.PortalViews;
import dev.bluevista.craftq3.engine.render.RenderScene;
import dev.bluevista.craftq3.engine.render.SceneTextures;
import dev.bluevista.craftq3.engine.render.ViewBasis;
import dev.bluevista.craftq3.engine.render.ViewDefinition;
import dev.bluevista.craftq3.engine.render.VisibleSet;
import dev.bluevista.craftq3.engine.render.WorldGeometry;
import dev.bluevista.craftq3.engine.render.frame.CgameFrame;
import dev.bluevista.craftq3.engine.render.frame.DynamicDraw;
import dev.bluevista.craftq3.engine.render.frame.FrameBuilder;
import dev.bluevista.craftq3.engine.render.frame.FrameGeometry;
import dev.bluevista.craftq3.engine.render.frame.SceneAssets;
import dev.bluevista.craftq3.engine.render.material.MaterialProgram;
import dev.bluevista.craftq3.engine.render.material.MaterialRegistry;
import dev.bluevista.craftq3.engine.render.material.ShadingInputs;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * The Quake III renderer: what a session hands its frames to. One backend serves one client session
 * and renders into a Minecraft {@link RenderTarget} (the main target by default).
 *
 * <pre>
 *   backend.loadMap(scene, assets);          // once per map: static buffers, textures, materials
 *   backend.beginFrame(width, height, time); // every frame
 *   backend.render(cgameFrame);              // views, HUD quads and cinematics in cgame order
 *   backend.drawOverlay(quads);              // optional extra 2D quads (console, menus)
 *   backend.endFrame();                      // uploads the frame's dynamic data, runs the passes
 * </pre>
 *
 * <p>Per view, {@link dev.bluevista.craftq3.engine.render.Visibility#select} fills a reused {@link
 * VisibleSet}; visible world batches are drawn straight from the static buffers (index runs, one
 * multi-draw per stage where the device supports it), sorted by Q3 sort order. Every stage is a
 * cached pipeline variant whose shading runs on the GPU from static blocks and the per-view time.
 * Models, sprites, beams, polys and CPU-deformed surfaces come from {@link FrameBuilder} and are
 * copied into the frame rings. Portals and mirrors are rendered first into offscreen targets of the
 * view's size and sampled in screen space by the portal surface's backdrop pass.
 *
 * <p>All methods run on the render thread. Failures inside a frame are the caller's to handle
 * ({@link Q3FullscreenView} returns to Minecraft).
 */
public final class Q3RenderBackend implements AutoCloseable {
  private static final Logger LOGGER = LogUtils.getLogger();
  private static final int MAX_PORTAL_VIEWS = 2;
  private static final int MAX_VIEWS = 8;
  private static final Vector4f CLEAR = new Vector4f(0, 0, 0, 1);
  private static final float DEPTH_NEAR = (float) ViewDefinition.NEAR;

  /** {@code MaterialProgram.sortRank()} of Q3's opaque sort (3). */
  private static final long OPAQUE_RANK = 3 * 64;

  // Item kinds.
  private static final int WORLD = 0, DYNAMIC = 1, INLINE = 2;
  // Geometry kinds of a command.
  private static final int GEO_FRONT = 0, GEO_BACK = 1, GEO_RING = 2, GEO_MESH = 3;

  private final Q3RenderContext context;
  private final MaterialRegistry.Source baseShaders;
  private AssetLookup assets;
  private SceneAssets frameAssets = SceneAssets.EMPTY;
  private final MaterialRegistry.Source shaders =
      (name, lightmapped) -> frameAssets.over(this::resolveBase).resolve(name, lightmapped);

  // Per map.
  private Q3WorldRenderer world;
  private SceneTextures sceneTextures;
  private FrameBuilder builder;
  // Without a map: the 2D materials and those of views without a world (menu player models).
  private Q3MaterialTable overlayMaterials;
  private FrameBuilder noWorldBuilder;
  private final Q3VideoMaps videoMaps;
  private boolean skyNearBox;
  private boolean warnedWorldlessView;

  // Per frame.
  private Q3FrameBuffers frame;
  private CommandEncoder encoder;
  private RenderTarget target;
  private int targetWidth, targetHeight;
  private float scaleX = 1, scaleY = 1;
  private int frameTime;
  private boolean inFrame;
  private boolean mainCleared;
  private long frameStart;
  private final List<PassRecord> passes = new ArrayList<>();
  private final List<Cmd> cmds = new ArrayList<>();
  private int cmdCount;
  private int[] runFirst = new int[1024];
  private int[] runCount = new int[1024];
  private int runTotal;
  private PointerBuffer runOffsets;
  private IntBuffer runCounts;
  private IntBuffer runZeros;
  private IntBuffer runInterleaved;
  private final Map<Long, CinematicTexture> cinematics = new HashMap<>();
  private final List<Long> cinematicsSeen = new ArrayList<>();
  private GpuBuffer staticBlocks;
  private GpuBufferSlice emptyFog;
  private GpuBufferSlice identityDraw;
  private int viewsThisFrame;

  // Reused per-view scratch.
  private final VisibleSet[] visibleSets = new VisibleSet[MAX_VIEWS];
  private final FrameGeometry[] geometries = new FrameGeometry[MAX_VIEWS];
  private final Items[] itemLists = new Items[MAX_VIEWS];
  private final float[] matrix = new float[16];
  private final float[] modelMatrix = new float[16];

  private final Stats stats = new Stats();

  /** Frame counters, reset by {@link #beginFrame}. */
  public static final class Stats {
    public int views;
    public int passes;
    public int draws;
    public int multiDraws;
    public int worldBatches;
    public int dynamicDraws;
    public int triangles;
    public int portalViews;
    public long cpuNanos;
    public long prepareNanos;

    void reset() {
      views =
          passes = draws = multiDraws = worldBatches = dynamicDraws = triangles = portalViews = 0;
      cpuNanos = prepareNanos = 0;
    }

    @Override
    public String toString() {
      return "views="
          + views
          + " passes="
          + passes
          + " draws="
          + draws
          + " multiDraws="
          + multiDraws
          + " worldBatches="
          + worldBatches
          + " dynamic="
          + dynamicDraws
          + " triangles="
          + triangles
          + " portals="
          + portalViews
          + " cpu="
          + cpuNanos / 1000
          + "us";
    }
  }

  /** One render pass of the frame: a target and a command range. */
  private static final class PassRecord {
    GpuTextureView color;
    GpuTextureView depth;
    boolean clearColor;
    // RDF_HYPERSPACE: grey level to clear the view's region to, or negative.
    float hyperspace = -1;
    int[] region;
    int viewBlock;
    GpuBufferSlice fog;
    int from, to;
    int[] scissor;
    String label;
  }

  /** One draw; pooled and reused across frames. */
  private static final class Cmd {
    RenderPipeline pipeline;
    GpuBufferSlice stage;
    GpuBufferSlice draw;
    int drawOffset;
    GpuTextureView diffuse;
    GpuSampler sampler;
    GpuTextureView lightmap;
    int geometry;
    Q3WorldRenderer.SkyMesh mesh;
    int firstIndex, indexCount, baseVertex;
    int firstRun, runs;
  }

  /** The sortable draw items of one view. */
  private static final class Items {
    int count;
    int[] kind = new int[256];
    int[] ref = new int[256];
    int[] ref2 = new int[256];
    int[] material = new int[256];
    long[] keys = new long[256];

    void clear() {
      count = 0;
    }

    void add(int kind, int ref, int ref2, int material, int rank) {
      if (count == this.kind.length) {
        int n = count * 2;
        this.kind = Arrays.copyOf(this.kind, n);
        this.ref = Arrays.copyOf(this.ref, n);
        this.ref2 = Arrays.copyOf(this.ref2, n);
        this.material = Arrays.copyOf(this.material, n);
        keys = Arrays.copyOf(keys, n);
      }
      this.kind[count] = kind;
      this.ref[count] = ref;
      this.ref2[count] = ref2;
      this.material[count] = material;
      keys[count] = (long) rank << 40 | (long) (material & 0xfffff) << 20 | count;
      count++;
    }
  }

  private record CinematicTexture(GpuTexture texture, GpuTextureView view, Q3Image pixels) {
    void close() {
      view.close();
      texture.close();
    }
  }

  /**
   * @param baseShaders resolves shader names (the game's shader scripts, implicit otherwise)
   * @param assets the game file view for images not decoded ahead of time
   */
  public Q3RenderBackend(
      Q3RenderContext context, MaterialRegistry.Source baseShaders, AssetLookup assets) {
    this.context = Objects.requireNonNull(context, "context");
    this.baseShaders = Objects.requireNonNull(baseShaders, "baseShaders");
    this.assets = Objects.requireNonNull(assets, "assets");
    this.videoMaps = new Q3VideoMaps(context.textures(), assets);
    for (int i = 0; i < MAX_VIEWS; i++) {
      visibleSets[i] = new VisibleSet();
      geometries[i] = new FrameGeometry();
      itemLists[i] = new Items();
    }
  }

  private dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition resolveBase(
      String name, boolean lightmapped) {
    return baseShaders.resolve(name, lightmapped);
  }

  // ---------------------------------------------------------------------------------------------
  // Map lifetime

  /** Loads {@code scene}, decoding its textures from {@code assets} on this thread. */
  public void loadMap(RenderScene scene, AssetLookup assets) {
    loadMap(scene, SceneTextures.load(assets, scene), assets);
  }

  /**
   * Loads {@code scene} with textures decoded ahead of time (for example on a loading worker).
   * Uploads the static world once; images the new map no longer uses leave the shared cache.
   */
  public void loadMap(RenderScene scene, SceneTextures textures, AssetLookup assets) {
    if (inFrame) throw new IllegalStateException("Cannot load a map inside a frame");
    unloadMap();
    this.assets = Objects.requireNonNull(assets, "assets");
    long start = System.nanoTime();
    int generation = context.textures().nextGeneration();
    CommandEncoder load = RenderSystem.getDevice().createCommandEncoder();
    MaterialRegistry registry = new MaterialRegistry(shaders, scene.definitions());
    sceneTextures = textures;
    prepareMips(textures);
    world = new Q3WorldRenderer(scene, registry, textures, this::image, context, load);
    builder = new FrameBuilder(scene, registry);
    videoMaps.assets(assets);
    preparedMips.clear();
    int evicted = context.textures().evictOlderThan(generation);
    // Compile every pipeline variant the map's materials (and common entity and 2D states) use
    // now, so the first frames do not stall compiling shaders inside their render passes.
    long compileStart = System.nanoTime();
    context.pipelines().prepareCommon();
    int compiled = context.pipelines().precompile();
    LOGGER.info(
        "CraftQ3: loaded {} in {} ms: {} vertices, {} batches, {} materials, {} lightmaps, {} textures resident ({} evicted), {} pipeline variants compiled in {} ms",
        scene.name(),
        (System.nanoTime() - start) / 1_000_000,
        scene.world().vertexCount(),
        scene.world().batches().size(),
        registry.size(),
        textures.lightmaps().size(),
        context.textures().size(),
        evicted,
        compiled,
        (System.nanoTime() - compileStart) / 1_000_000);
  }

  /** Releases the current map's GPU data. */
  public void unloadMap() {
    videoMaps.clear();
    if (world != null) world.close();
    world = null;
    builder = null;
    sceneTextures = null;
  }

  public boolean mapLoaded() {
    return world != null;
  }

  public RenderScene scene() {
    return world == null ? null : world.scene();
  }

  /** GPU objects owned by the loaded map (buffers, lightmaps, material blocks). */
  public int mapGpuObjects() {
    return world == null ? 0 : world.gpuObjects();
  }

  // Mip chains of the map's textures, generated in parallel before the sequential upload.
  private final Map<String, List<Q3Image>> preparedMips = new HashMap<>();

  private void prepareMips(SceneTextures textures) {
    List<Map.Entry<String, Q3Image>> todo = new ArrayList<>();
    for (Map.Entry<String, Q3Image> entry : textures.images().entrySet())
      if (!entry.getKey().startsWith("$") && context.textures().find(entry.getKey()) == null)
        todo.add(entry);
    List<List<Q3Image>> mips =
        todo.parallelStream()
            .map(e -> dev.bluevista.craftq3.engine.formats.image.Mipmaps.generate(e.getValue()))
            .toList();
    CommandEncoder upload = RenderSystem.getDevice().createCommandEncoder();
    for (int i = 0; i < todo.size(); i++)
      context.textures().put(upload, todo.get(i).getKey(), mips.get(i), false);
  }

  private Q3Image image(String name) {
    Q3Image image = frameAssets.images().get(name);
    if (image == null && sceneTextures != null) image = sceneTextures.images().get(name);
    if (image != null) return image;
    // Not decoded ahead of time (cgame registrations without a worker): decode now.
    return ImageLoader.load(assets, name).image();
  }

  private Q3MaterialTable materials() {
    if (world != null) return world.materials();
    if (overlayMaterials == null) {
      overlayMaterials = new Q3MaterialTable(new MaterialRegistry(shaders), context, this::image);
      noWorldBuilder = FrameBuilder.withoutWorld(overlayMaterials.registry());
    }
    return overlayMaterials;
  }

  /**
   * Whether the sky's inner box ({@code skyparms} near box) is drawn over the clouds. Off by
   * default: Quake III 1.32 loads the inner box images but never draws them.
   */
  public void drawSkyNearBox(boolean draw) {
    skyNearBox = draw;
  }

  /** {@code videoMap} streams currently playing. */
  public int videoStreams() {
    return videoMaps.size();
  }

  private MaterialRegistry registry() {
    return materials().registry();
  }

  // ---------------------------------------------------------------------------------------------
  // Frame

  /**
   * Starts a frame drawn into Minecraft's main target; {@code width}×{@code height} is the Q3
   * screen.
   */
  public void beginFrame(int width, int height, int timeMillis) {
    beginFrame(Minecraft.getInstance().gameRenderer.mainRenderTarget(), width, height, timeMillis);
  }

  /**
   * Starts a frame drawn into {@code target}. Refdef viewports and 2D coordinates are in a {@code
   * width}×{@code height} screen (Q3's vidWidth/vidHeight), scaled to the target.
   */
  public void beginFrame(RenderTarget target, int width, int height, int timeMillis) {
    if (inFrame) {
      // A previous frame failed between beginFrame and endFrame; drop what it staged.
      LOGGER.warn("CraftQ3: frame abandoned before endFrame; discarding it");
      inFrame = false;
    }
    frameStart = System.nanoTime();
    stats.reset();
    inFrame = true;
    this.target = target;
    targetWidth = target.width;
    targetHeight = target.height;
    scaleX = width > 0 ? (float) targetWidth / width : 1;
    scaleY = height > 0 ? (float) targetHeight / height : 1;
    frameTime = timeMillis;
    frame = context.frameBuffers();
    frame.begin();
    encoder = RenderSystem.getDevice().createCommandEncoder();
    passes.clear();
    cmdCount = 0;
    runTotal = 0;
    mainCleared = false;
    viewsThisFrame = 0;
    cinematicsSeen.clear();
    videoMaps.beginFrame();
    ensureStaticBlocks();
  }

  /** Renders a captured cgame frame: views, 2D quads and cinematic frames in submission order. */
  public void render(CgameFrame cgame) {
    requireFrame();
    frameAssets = cgame.assets();
    List<CgameFrame.Quad> quads = new ArrayList<>();
    for (CgameFrame.Command command : cgame.commands()) {
      switch (command) {
        case CgameFrame.Quad quad -> quads.add(quad);
        case CgameFrame.Image image -> {
          flushQuads(quads);
          uploadCinematic(image);
          quads.add(
              new CgameFrame.Quad(
                  image.x(),
                  image.y(),
                  image.width(),
                  image.height(),
                  0,
                  0,
                  1,
                  1,
                  image.texture(),
                  -1));
          flushQuads(quads);
        }
        case CgameFrame.View view -> {
          flushQuads(quads);
          renderView(view, view.refdef().view());
        }
      }
    }
    flushQuads(quads);
  }

  /** Renders one view without a cgame frame (tools, the map viewer): world plus submissions. */
  public void render(
      ViewDefinition view,
      List<CgameFrame.RefEntity> entities,
      List<CgameFrame.Poly> polygons,
      List<DynamicLight> lights) {
    requireFrame();
    int x = view.width() > 0 ? view.x() : 0;
    int y = view.width() > 0 ? view.y() : 0;
    int w = view.width() > 0 ? view.width() : Math.round(targetWidth / scaleX);
    int h = view.height() > 0 ? view.height() : Math.round(targetHeight / scaleY);
    CgameFrame.Refdef refdef =
        new CgameFrame.Refdef(
            x,
            y,
            w,
            h,
            view.fovX(),
            view.fovY(),
            view.origin(),
            view.basis().forward(),
            view.basis().left(),
            view.basis().up(),
            view.timeMillis(),
            view.rdflags(),
            view.areaMask(),
            List.of());
    renderView(new CgameFrame.View(refdef, entities, polygons, lights), refdef.view());
  }

  /** Draws 2D quads ({@code drawStretchPic}) over everything drawn so far this frame. */
  public void drawOverlay(List<CgameFrame.Quad> quads) {
    requireFrame();
    flushQuads(new ArrayList<>(quads));
  }

  /** Uploads the frame's dynamic data and runs every pass. */
  public void endFrame() {
    requireFrame();
    try {
      materials().flush(encoder);
      long prepared = System.nanoTime();
      stats.prepareNanos = prepared - frameStart;
      frame.upload();
      fillRunBuffers();
      for (PassRecord pass : passes) execute(pass);
      stats.passes = passes.size();
      retireCinematics();
    } finally {
      frame.end();
      inFrame = false;
      frameAssets = SceneAssets.EMPTY;
      target = null;
      encoder = null;
      stats.cpuNanos = System.nanoTime() - frameStart;
    }
  }

  public Stats stats() {
    return stats;
  }

  @Override
  public void close() {
    if (inFrame) throw new IllegalStateException("close inside a frame");
    unloadMap();
    if (overlayMaterials != null) overlayMaterials.close();
    overlayMaterials = null;
    cinematics.values().forEach(CinematicTexture::close);
    cinematics.clear();
    videoMaps.close();
    if (staticBlocks != null) staticBlocks.close();
    staticBlocks = null;
    if (runOffsets != null) MemoryUtil.memFree(runOffsets);
    if (runCounts != null) MemoryUtil.memFree(runCounts);
    if (runZeros != null) MemoryUtil.memFree(runZeros);
    if (runInterleaved != null) MemoryUtil.memFree(runInterleaved);
    runOffsets = null;
    runCounts = runZeros = runInterleaved = null;
    context.textures().evictOlderThan(context.textures().nextGeneration());
  }

  private void requireFrame() {
    if (!inFrame) throw new IllegalStateException("Not inside beginFrame/endFrame");
  }

  private void ensureStaticBlocks() {
    if (staticBlocks != null) return;
    GpuDevice device = RenderSystem.getDevice();
    int alignment = Math.max(16, device.getDeviceInfo().limits().minUniformOffsetAlignment());
    int fogBytes = (Q3Blocks.FOG_BYTES + alignment - 1) / alignment * alignment;
    int drawBytes = (Q3Blocks.DRAW_BYTES + alignment - 1) / alignment * alignment;
    ByteBuffer data = MemoryUtil.memCalloc(fogBytes + drawBytes).order(ByteOrder.nativeOrder());
    try {
      Q3Blocks.draw(
          data,
          fogBytes,
          null,
          -1,
          ShadingInputs.DEFAULT_AMBIENT,
          ShadingInputs.DEFAULT_DIRECTED,
          ShadingInputs.UP,
          0,
          -1,
          0,
          0,
          0);
      staticBlocks =
          device.createBuffer(() -> "CraftQ3 backend blocks", GpuBuffer.USAGE_UNIFORM, data);
    } finally {
      MemoryUtil.memFree(data);
    }
    emptyFog = staticBlocks.slice(0, fogBytes);
    identityDraw = staticBlocks.slice(fogBytes, drawBytes);
  }

  // ---------------------------------------------------------------------------------------------
  // View preparation

  private void renderView(CgameFrame.View view, ViewDefinition definition) {
    if (viewsThisFrame >= MAX_VIEWS - MAX_PORTAL_VIEWS) {
      LOGGER.warn("CraftQ3: more than {} views in one frame; extra views skipped", MAX_VIEWS);
      return;
    }
    if (world == null && definition.worldModel()) {
      // Q3 drops to the console here ("R_RenderScene: NULL worldmodel"); skip the view instead.
      if (!warnedWorldlessView)
        LOGGER.warn("CraftQ3: a view without RDF_NOWORLDMODEL arrived with no map loaded; skipped");
      warnedWorldlessView = true;
      return;
    }
    int[] viewport =
        viewport(
            view.refdef().x(), view.refdef().y(), view.refdef().width(), view.refdef().height());
    PassRecord main = prepareView(view, definition, viewport, null);
    if (main != null) passes.add(main);
  }

  /**
   * Prepares one view's commands and returns its pass (portal passes it needs are appended to the
   * pass list first). {@code portalTarget} is null for views drawn into the frame's target.
   */
  private PassRecord prepareView(
      CgameFrame.View cgView,
      ViewDefinition definition,
      int[] viewport,
      Q3RenderTarget portalTarget) {
    int slot = viewsThisFrame++;
    stats.views++;
    VisibleSet visible = visibleSets[slot];
    FrameGeometry geometry = geometries[slot];
    Items items = itemLists[slot];
    items.clear();
    // Views without a world (menu and HUD models) also render while no map is loaded.
    boolean hasMap = world != null;
    boolean worldModel = definition.worldModel() && hasMap;
    RenderScene scene = hasMap ? world.scene() : null;
    Q3MaterialTable table = materials();
    MaterialRegistry registry = table.registry();
    boolean mirrored = definition.mirrored();
    double seconds = definition.seconds();

    if (worldModel) scene.visibility().select(definition, true, true, visible);
    (hasMap ? builder : noWorldBuilder)
        .build(cgView, definition, worldModel ? visible : null, geometry);

    // Portal and mirror views, rendered before this view into their own targets.
    Map<Integer, GpuTextureView> portalImages = Map.of();
    if (worldModel && definition.depth() == 0) {
      List<PortalViews.Derived> derived =
          PortalViews.collect(
              scene, definition, visible, cgView.portalEntities(), PortalViews.DEFAULT_MAX_DEPTH);
      if (!derived.isEmpty()) {
        portalImages = new HashMap<>();
        int n = 0;
        for (PortalViews.Derived d : derived) {
          if (n == MAX_PORTAL_VIEWS || viewsThisFrame >= MAX_VIEWS) break;
          Q3RenderTarget portal = context.renderTarget(1 + n, targetWidth, targetHeight, true);
          PassRecord pass = prepareView(cgView, d.view(), viewport, portal);
          if (pass != null) {
            passes.add(pass);
            portalImages.put(d.surface(), portal.colorView());
            stats.portalViews++;
          }
          n++;
        }
      }
    }

    // Items: visible world batches, dynamic draws, inline models.
    WorldGeometry worldGeometry = hasMap ? scene.world() : null;
    List<DrawBatch> batches = hasMap ? worldGeometry.batches() : List.of();
    int skyMaterial = -1;
    if (worldModel) {
      for (int i = 0; i < visible.batchCount(); i++) {
        DrawBatch batch = batches.get(visible.batch(i));
        if (batch.sky() && registry.program(batch.material()).isSky()) {
          if (skyMaterial < 0) skyMaterial = batch.material();
          continue;
        }
        items.add(WORLD, i, 0, batch.material(), batch.sortRank());
      }
    }
    List<DynamicDraw> draws = geometry.draws();
    for (int i = 0; i < draws.size(); i++) {
      DynamicDraw draw = draws.get(i);
      items.add(DYNAMIC, i, 0, draw.material(), registry.program(draw.material()).sortRank());
    }
    List<FrameGeometry.InlineInstance> inline = geometry.inlineModels();
    for (int i = 0; i < inline.size(); i++) {
      FrameGeometry.InlineInstance instance = inline.get(i);
      int first = worldGeometry.modelFirstBatch(instance.model());
      int count = worldGeometry.modelBatchCount(instance.model());
      for (int b = first; b < first + count; b++) {
        DrawBatch batch = batches.get(b);
        int material =
            instance.customMaterial() >= 0 ? instance.customMaterial() : batch.material();
        if (batch.sky()) continue;
        items.add(INLINE, i, b, material, registry.program(material).sortRank());
      }
    }
    Arrays.sort(items.keys, 0, items.count);

    // The view block and dynamic vertices.
    int viewBlock =
        viewBlock(
            definition, viewport, cgView.lights(), portalTarget != null || definition.depth() > 0);
    int baseVertex = 0;
    if (geometry.mesh().vertexCount() > 0) {
      ByteBuffer v = frame.vertices(geometry.mesh().vertexCount() * Q3WorldRenderer.VERTEX_BYTES);
      baseVertex = v.position() / Q3WorldRenderer.VERTEX_BYTES;
      Q3WorldRenderer.putVertices(v, geometry.mesh().arrays(), 0, geometry.mesh().vertexCount());
    }

    PassRecord pass = new PassRecord();
    pass.from = cmdCount;
    pass.viewBlock = viewBlock;
    pass.fog = hasMap ? world.fogBlock() : emptyFog;
    pass.label = "CraftQ3 view " + slot;
    if (definition.hyperspace() && portalTarget == null) {
      pass.hyperspace = definition.hyperspaceGrey();
      pass.region = scissor(viewport);
    }
    if (portalTarget != null) {
      pass.color = portalTarget.colorView();
      pass.depth = portalTarget.depthView();
      pass.clearColor = true;
    } else {
      pass.color = target.getColorTextureView();
      pass.depth = context.renderTarget(0, targetWidth, targetHeight, false).depthView();
      pass.clearColor = !mainCleared && worldModel;
      if (pass.clearColor) mainCleared = true;
    }
    pass.scissor = fullViewport(viewport) ? null : scissor(viewport);

    GpuTextureView white = context.textures().white(encoder);
    GpuTextureView black = context.textures().black(encoder);
    List<DynamicLight> lights = cgView.lights();

    // Q3 order: portals and opaque surfaces, then the sky (at the far plane, so only where nothing
    // was drawn), then everything blended in sort order.
    boolean skyDone = skyMaterial < 0;
    for (int k = 0; k <= items.count; k++) {
      if (!skyDone && (k == items.count || items.keys[k] >>> 40 > OPAQUE_RANK)) {
        emitSky(table.get(encoder, skyMaterial), definition, seconds);
        skyDone = true;
      }
      if (k == items.count) break;
      int item = (int) (items.keys[k] & 0xfffff);
      Q3MaterialTable.Material material = table.get(encoder, items.material[item]);
      MaterialProgram program = material.program;
      if (!material.draws() && material.backdrop == null) continue;
      if (program.isSky()) continue;
      boolean front = (program.cull() != MaterialProgram.CullMode.BACK_SIDED) ^ mirrored;
      switch (items.kind[item]) {
        case WORLD -> {
          int vi = items.ref[item];
          DrawBatch batch = batches.get(visible.batch(vi));
          GpuTextureView lightmap = world.lightmap(batch.lightmap());
          int fog = world.fogVolume(batch.fog());
          GpuBufferSlice drawBlock = world.worldDraw(fog);
          int firstRun = addRuns(visible, vi);
          int runs = visible.batchRunCount(vi);
          stats.worldBatches++;
          double time = program.shaderTime(seconds);
          if (material.backdrop != null) {
            GpuTextureView image = portalImage(portalImages, visible, batch, black);
            Cmd c =
                cmd(material.backdrop, image, context.q3Sampler(true, false), white, drawBlock, -1);
            staticGeometry(c, front, firstRun, runs);
          }
          for (Q3MaterialTable.Pass p : material.passes) {
            Cmd c =
                cmd(
                    p,
                    diffuse(p, time, lightmap, white),
                    p.sampler,
                    lightmapFor(lightmap, white),
                    drawBlock,
                    -1);
            staticGeometry(c, front, firstRun, runs);
          }
          if (material.dlight != null && touches(lights, batch.index())) {
            Cmd c = cmd(material.dlight, white, material.dlight.sampler, white, drawBlock, -1);
            staticGeometry(c, front, firstRun, runs);
          }
          if (material.fog != null && fog >= 0) {
            Cmd c = cmd(material.fog, white, material.fog.sampler, white, drawBlock, -1);
            staticGeometry(c, front, firstRun, runs);
          }
        }
        case DYNAMIC -> {
          DynamicDraw draw = draws.get(items.ref[item]);
          stats.dynamicDraws++;
          int fog = hasMap ? world.fogVolume(draw.fog()) : -1;
          int drawOffset =
              drawBlock(
                  null,
                  draw.rgba(),
                  draw.ambient(),
                  draw.directed(),
                  draw.lightDirection(),
                  (float) (seconds - draw.shaderSeconds()),
                  fog,
                  draw.depthHack() ? Q3Blocks.DRAW_DEPTH_HACK : 0,
                  draw.shaderS(),
                  draw.shaderT());
          int firstIndex = ringIndices(geometry, draw.firstIndex(), draw.indexCount(), front);
          GpuTextureView lightmap = hasMap ? world.lightmap(draw.lightmap()) : null;
          double time = program.shaderTime(draw.shaderSeconds());
          for (Q3MaterialTable.Pass p : material.passes) {
            Cmd c =
                cmd(
                    p,
                    diffuse(p, time, lightmap, white),
                    p.sampler,
                    lightmapFor(lightmap, white),
                    null,
                    drawOffset);
            ringGeometry(c, firstIndex, draw.indexCount(), baseVertex);
          }
          // Entities take dynamic lights through their lighting; only surfaces are projected.
          if (material.dlight != null
              && draw.source().projectsDynamicLights()
              && touches(lights, draw)) {
            Cmd c = cmd(material.dlight, white, material.dlight.sampler, white, null, drawOffset);
            ringGeometry(c, firstIndex, draw.indexCount(), baseVertex);
          }
          if (material.fog != null && fog >= 0) {
            Cmd c = cmd(material.fog, white, material.fog.sampler, white, null, drawOffset);
            ringGeometry(c, firstIndex, draw.indexCount(), baseVertex);
          }
        }
        default -> {
          FrameGeometry.InlineInstance instance = inline.get(items.ref[item]);
          DrawBatch batch = batches.get(items.ref2[item]);
          boolean side = front ^ instance.mirrored();
          int fog = world.fogVolume(instance.fog());
          tagMatrix(instance.transform(), modelMatrix);
          int drawOffset =
              drawBlock(
                  modelMatrix,
                  instance.rgba(),
                  instance.ambient(),
                  instance.directed(),
                  instance.lightDirection(),
                  (float) (seconds - instance.shaderSeconds()),
                  fog,
                  0,
                  instance.shaderS(),
                  instance.shaderT());
          GpuTextureView lightmap = world.lightmap(batch.lightmap());
          double time = program.shaderTime(instance.shaderSeconds());
          for (Q3MaterialTable.Pass p : material.passes) {
            Cmd c =
                cmd(
                    p,
                    diffuse(p, time, lightmap, white),
                    p.sampler,
                    lightmapFor(lightmap, white),
                    null,
                    drawOffset);
            staticRange(c, side, batch.firstIndex(), batch.indexCount());
          }
          if (material.fog != null && fog >= 0) {
            Cmd c = cmd(material.fog, white, material.fog.sampler, white, null, drawOffset);
            staticRange(c, side, batch.firstIndex(), batch.indexCount());
          }
        }
      }
    }
    pass.to = cmdCount;
    return pass;
  }

  private void emitSky(Q3MaterialTable.Material sky, ViewDefinition view, double seconds) {
    Vec3f o = view.origin();
    Arrays.fill(modelMatrix, 0);
    modelMatrix[0] = modelMatrix[5] = modelMatrix[10] = modelMatrix[15] = 1;
    modelMatrix[12] = o.x();
    modelMatrix[13] = o.y();
    modelMatrix[14] = o.z();
    int drawOffset =
        drawBlock(
            modelMatrix,
            -1,
            ShadingInputs.DEFAULT_AMBIENT,
            ShadingInputs.DEFAULT_DIRECTED,
            ShadingInputs.UP,
            0,
            -1,
            RenderSystem.getDevice().getDeviceInfo().isZZeroToOne()
                ? Q3Blocks.DRAW_FAR_ZERO_TO_ONE
                : Q3Blocks.DRAW_FAR_MINUS_ONE_TO_ONE,
            0,
            0);
    GpuTextureView white = context.textures().white(encoder);
    if (sky.skyBox != null) {
      Q3WorldRenderer.SkyMesh box = world.skyBox();
      for (int side = 0; side < sky.skySides.length; side++) {
        Cmd c =
            cmd(
                sky.skyBox,
                sky.skySides[side],
                context.q3Sampler(true, true),
                white,
                null,
                drawOffset);
        meshGeometry(c, box, side * 6, 6);
      }
    }
    if (sky.clouds.length > 0) {
      Q3WorldRenderer.SkyMesh dome = world.clouds(sky.program.sky().orElseThrow().cloudHeight());
      double time = sky.program.shaderTime(seconds);
      for (Q3MaterialTable.Pass p : sky.clouds) {
        Cmd c = cmd(p, texture(p, time, white), p.sampler, white, null, drawOffset);
        meshGeometry(c, dome, 0, dome.indexCount());
      }
    }
    // Q3 order: far box, then the cloud layers; the inner box would come last (see
    // drawSkyNearBox).
    if (skyNearBox && sky.nearBox != null) {
      Q3WorldRenderer.SkyMesh box = world.skyBox();
      for (int side = 0; side < sky.nearSides.length; side++) {
        Cmd c =
            cmd(
                sky.nearBox,
                sky.nearSides[side],
                context.q3Sampler(true, true),
                white,
                null,
                drawOffset);
        meshGeometry(c, box, side * 6, 6);
      }
    }
  }

  private GpuTextureView portalImage(
      Map<Integer, GpuTextureView> images,
      VisibleSet visible,
      DrawBatch batch,
      GpuTextureView none) {
    if (images.isEmpty()) return none;
    WorldGeometry geometry = world.scene().world();
    for (int k = batch.firstSurface(); k < batch.firstSurface() + batch.surfaceCount(); k++) {
      GpuTextureView image = images.get(geometry.batchSurface(k));
      if (image != null) return image;
    }
    // Surfaces sharing a portal plane share one view.
    return images.values().iterator().next();
  }

  private GpuTextureView texture(Q3MaterialTable.Pass pass, double time, GpuTextureView white) {
    if (pass.cinematic >= 0) {
      CinematicTexture movie = cinematics.get(pass.cinematic);
      return movie != null ? movie.view() : context.textures().black(encoder);
    }
    if (pass.video != null) return videoMaps.texture(encoder, pass.video, time);
    return pass.frame(time);
  }

  private GpuTextureView diffuse(
      Q3MaterialTable.Pass pass, double time, GpuTextureView lightmap, GpuTextureView white) {
    if (pass.lightmapTexture) return lightmap != null ? lightmap : white;
    return texture(pass, time, white);
  }

  private static GpuTextureView lightmapFor(GpuTextureView lightmap, GpuTextureView white) {
    return lightmap != null ? lightmap : white;
  }

  private boolean touches(List<DynamicLight> lights, int batch) {
    for (DynamicLight light : lights)
      if (world.batchTouches(
          batch, light.origin().x(), light.origin().y(), light.origin().z(), light.radius()))
        return true;
    return false;
  }

  private static boolean touches(List<DynamicLight> lights, DynamicDraw draw) {
    for (DynamicLight light : lights) {
      Vec3f o = light.origin();
      float r = light.radius();
      if (o.x() + r >= draw.bound(0)
          && o.y() + r >= draw.bound(1)
          && o.z() + r >= draw.bound(2)
          && o.x() - r <= draw.bound(3)
          && o.y() - r <= draw.bound(4)
          && o.z() - r <= draw.bound(5)) return true;
    }
    return false;
  }

  // ---------------------------------------------------------------------------------------------
  // 2D

  private void flushQuads(List<CgameFrame.Quad> quads) {
    if (quads.isEmpty()) return;
    Q3MaterialTable table = materials();
    MaterialRegistry registry = table.registry();
    int viewBlock = orthoBlock();
    PassRecord pass = new PassRecord();
    pass.from = cmdCount;
    pass.viewBlock = viewBlock;
    pass.fog = emptyFog;
    pass.color = target.getColorTextureView();
    pass.depth = context.renderTarget(0, targetWidth, targetHeight, false).depthView();
    pass.clearColor = false;
    pass.label = "CraftQ3 2D";
    GpuTextureView white = context.textures().white(encoder);
    double seconds = frameTime / 1000.0;
    int i = 0;
    while (i < quads.size()) {
      CgameFrame.Quad quad = quads.get(i);
      int materialIndex = overlayMaterial(registry, quad.shader());
      Q3MaterialTable.Material material = table.get(encoder, materialIndex);
      boolean front = material.program.cull() != MaterialProgram.CullMode.BACK_SIDED;
      int j = i;
      ByteBuffer v = frame.vertices(0);
      int baseVertex = v.position() / Q3WorldRenderer.VERTEX_BYTES;
      int firstIndex = frame.indexCount();
      int vertex = 0;
      // Consecutive quads with the same material are one draw (text, HUD digits).
      while (j < quads.size() && (j == i || quads.get(j).shader().equals(quad.shader()))) {
        CgameFrame.Quad q = quads.get(j);
        if (q.width() != 0 && q.height() != 0) {
          putQuad(q, vertex, front);
          vertex += 4;
        }
        j++;
      }
      int indexCount = frame.indexCount() - firstIndex;
      if (material.draws() && indexCount > 0) {
        double time = material.program.shaderTime(seconds);
        for (Q3MaterialTable.Pass p : material.passes) {
          Cmd c = cmd(p, texture(p, time, white), p.sampler, white, identityDraw, -1);
          ringGeometry(c, firstIndex, indexCount, baseVertex);
        }
      }
      i = j;
    }
    quads.clear();
    pass.to = cmdCount;
    if (pass.to > pass.from) passes.add(pass);
  }

  /**
   * The registry index of a 2D shader. A name without a script gets Q3's default 2D material
   * ({@code LIGHTMAP_2D}): alpha-blended, vertex colour and alpha, two-sided, no depth test.
   */
  private int overlayMaterial(MaterialRegistry registry, String shader) {
    Map<String, Integer> known = overlayIndices.get(registry);
    if (known == null) {
      overlayIndices.clear();
      known = new HashMap<>();
      overlayIndices.put(registry, known);
    }
    Integer cached = known.get(shader);
    if (cached != null) return cached;
    int index = registry.index(shader, false);
    ShaderDefinition definition = registry.definition(index);
    if (definition.equals(ShaderDefinition.implicit(definition.name(), false))) {
      ShaderDefinition.Stage stage = definition.stages().getFirst();
      ShaderDefinition twoD =
          new ShaderDefinition(
              definition.name(),
              List.of(
                  new ShaderDefinition.Stage(
                      stage.texture(),
                      ShaderDefinition.Blend.ALPHA,
                      ShaderDefinition.AlphaFunc.NONE,
                      ShaderDefinition.RgbGen.of(ShaderDefinition.RgbGenType.VERTEX),
                      ShaderDefinition.AlphaGen.of(ShaderDefinition.AlphaGenType.VERTEX),
                      stage.tcGen(),
                      List.of(),
                      ShaderDefinition.DepthFunc.DISABLE,
                      false,
                      false)),
              ShaderDefinition.Cull.NONE,
              definition.sort(),
              false,
              true,
              true,
              java.util.Set.of(),
              Optional.empty(),
              Optional.empty(),
              List.of(),
              false,
              0);
      index = registry.add(twoD);
    }
    known.put(shader, index);
    return index;
  }

  private final Map<MaterialRegistry, Map<String, Integer>> overlayIndices = new HashMap<>();

  private void putQuad(CgameFrame.Quad q, int vertex, boolean front) {
    ByteBuffer v = frame.vertices(4 * Q3WorldRenderer.VERTEX_BYTES);
    float x0 = q.x() * scaleX, x1 = (q.x() + q.width()) * scaleX;
    float y0 = q.y() * scaleY, y1 = (q.y() + q.height()) * scaleY;
    int rgba = q.rgba();
    quadVertex(v, x0, y0, q.s1(), q.t1(), rgba);
    quadVertex(v, x1, y0, q.s2(), q.t1(), rgba);
    quadVertex(v, x1, y1, q.s2(), q.t2(), rgba);
    quadVertex(v, x0, y1, q.s1(), q.t2(), rgba);
    ByteBuffer ix = frame.indices(6);
    // On screen TL, TR, BR is clockwise; GL front faces are counter-clockwise.
    if (front)
      ix.putInt(vertex)
          .putInt(vertex + 2)
          .putInt(vertex + 1)
          .putInt(vertex)
          .putInt(vertex + 3)
          .putInt(vertex + 2);
    else
      ix.putInt(vertex)
          .putInt(vertex + 1)
          .putInt(vertex + 2)
          .putInt(vertex)
          .putInt(vertex + 2)
          .putInt(vertex + 3);
  }

  private static void quadVertex(ByteBuffer v, float x, float y, float s, float t, int rgba) {
    v.putFloat(x).putFloat(y).putFloat(0);
    v.putFloat(s).putFloat(t);
    v.putFloat(0).putFloat(0);
    v.putFloat(0).putFloat(0).putFloat(1);
    v.put((byte) (rgba >>> 24)).put((byte) (rgba >>> 16)).put((byte) (rgba >>> 8)).put((byte) rgba);
  }

  private void uploadCinematic(CgameFrame.Image image) {
    Q3Image pixels = image.pixels();
    CinematicTexture previous = cinematics.get(image.stream());
    cinematicsSeen.add(image.stream());
    if (previous != null && previous.pixels() == pixels) return;
    CinematicTexture movie;
    if (previous != null
        && previous.pixels().width() == pixels.width()
        && previous.pixels().height() == pixels.height()) {
      movie = new CinematicTexture(previous.texture(), previous.view(), pixels);
    } else {
      if (previous != null) previous.close();
      GpuDevice device = RenderSystem.getDevice();
      GpuTexture texture =
          device.createTexture(
              "CraftQ3 cinematic " + image.stream(),
              GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
              GpuFormat.RGBA8_UNORM,
              pixels.width(),
              pixels.height(),
              1,
              1);
      movie = new CinematicTexture(texture, device.createTextureView(texture), pixels);
    }
    Q3TextureCache.writeLevel(encoder, movie.texture(), 0, pixels);
    cinematics.put(image.stream(), movie);
  }

  private void retireCinematics() {
    if (cinematics.isEmpty()) return;
    cinematics
        .entrySet()
        .removeIf(
            e -> {
              if (cinematicsSeen.contains(e.getKey())) return false;
              e.getValue().close();
              return true;
            });
  }

  // ---------------------------------------------------------------------------------------------
  // Blocks, geometry and commands

  private int[] viewport(int x, int y, int w, int h) {
    int px = Math.round(x * scaleX), py = Math.round(y * scaleY);
    int pw = Math.max(1, Math.round(w * scaleX)), ph = Math.max(1, Math.round(h * scaleY));
    px = Math.clamp(px, 0, targetWidth - 1);
    py = Math.clamp(py, 0, targetHeight - 1);
    pw = Math.min(pw, targetWidth - px);
    ph = Math.min(ph, targetHeight - py);
    return new int[] {px, py, pw, ph};
  }

  private boolean fullViewport(int[] v) {
    return v[0] == 0 && v[1] == 0 && v[2] == targetWidth && v[3] == targetHeight;
  }

  /** Scissor in attachment coordinates: Blaze3D's origin is the bottom row on both backends. */
  private int[] scissor(int[] v) {
    return new int[] {v[0], targetHeight - v[1] - v[3], v[2], v[3]};
  }

  private int viewBlock(
      ViewDefinition view, int[] viewport, List<DynamicLight> lights, boolean portal) {
    viewProjection(view, viewport, matrix);
    float[] clip = null;
    if (portal || view.clipPlane().isPresent()) {
      Optional<Plane> plane = view.clipPlane();
      if (plane.isPresent())
        clip =
            new float[] {
              plane.get().normal().x(),
              plane.get().normal().y(),
              plane.get().normal().z(),
              plane.get().distance()
            };
    }
    ByteBuffer out = frame.uniform(Q3Blocks.VIEW_BYTES);
    int at = frame.lastUniformOffset();
    Q3Blocks.view(
        out,
        at,
        matrix,
        view.origin(),
        (float) view.seconds(),
        clip,
        1f / targetWidth,
        1f / targetHeight,
        lights);
    out.position(at + Q3Blocks.VIEW_BYTES);
    frame.endUniform();
    return at;
  }

  private int orthoBlock() {
    Arrays.fill(matrix, 0);
    // x: [0, W] -> [-1, 1]; y: [0, H] (down) -> [1, -1]; depth 1 (nearest, reversed-Z).
    matrix[0] = 2f / targetWidth;
    matrix[5] = -2f / targetHeight;
    matrix[12] = -1;
    matrix[13] = 1;
    matrix[14] = 1;
    matrix[15] = 1;
    ByteBuffer out = frame.uniform(Q3Blocks.VIEW_BYTES);
    int at = frame.lastUniformOffset();
    Q3Blocks.view(
        out,
        at,
        matrix,
        Vec3f.ZERO,
        frameTime / 1000f,
        null,
        1f / targetWidth,
        1f / targetHeight,
        List.of());
    out.position(at + Q3Blocks.VIEW_BYTES);
    frame.endUniform();
    return at;
  }

  /**
   * The view-projection of {@code view} into {@code viewport}: eye axes right/up/-forward, an
   * infinite reversed-Z perspective from Q3's fovX/fovY, and the viewport baked into clip space
   * (Blaze3D always rasterises to the whole attachment). Column-major.
   */
  void viewProjection(ViewDefinition view, int[] viewport, float[] out) {
    ViewBasis b = view.basis();
    Vec3f o = view.origin(), r = b.right(), u = b.up(), f = b.forward();
    double tanX = Math.tan(Math.toRadians(view.fovX()) / 2);
    double tanY = Math.tan(Math.toRadians(view.fovY()) / 2);
    double sx = (double) viewport[2] / targetWidth, sy = (double) viewport[3] / targetHeight;
    double ox = (2.0 * viewport[0] + viewport[2]) / targetWidth - 1;
    // Q3 viewports are measured from the top; clip y grows upward.
    double oy = 1 - (2.0 * viewport[1] + viewport[3]) / targetHeight;
    double fo = -(f.x() * (double) o.x() + f.y() * (double) o.y() + f.z() * (double) o.z());
    double ro = -(r.x() * (double) o.x() + r.y() * (double) o.y() + r.z() * (double) o.z());
    double uo = -(u.x() * (double) o.x() + u.y() * (double) o.y() + u.z() * (double) o.z());
    double ax = sx / tanX, ay = sy / tanY;
    // Rows of the matrix.
    double[] row0 = {
      ax * r.x() + ox * f.x(), ax * r.y() + ox * f.y(), ax * r.z() + ox * f.z(), ax * ro + ox * fo
    };
    double[] row1 = {
      ay * u.x() + oy * f.x(), ay * u.y() + oy * f.y(), ay * u.z() + oy * f.z(), ay * uo + oy * fo
    };
    double[] row3 = {f.x(), f.y(), f.z(), fo};
    double[] row2;
    if (RenderSystem.getDevice().getDeviceInfo().isZZeroToOne()) {
      // depth = near / w
      row2 = new double[] {0, 0, 0, DEPTH_NEAR};
    } else {
      // ndc = 2 near / w - 1, so window depth = near / w
      row2 = new double[] {-f.x(), -f.y(), -f.z(), -fo + 2 * DEPTH_NEAR};
    }
    double[][] rows = {row0, row1, row2, row3};
    for (int c = 0; c < 4; c++)
      for (int rr = 0; rr < 4; rr++) out[c * 4 + rr] = (float) rows[rr][c];
  }

  private static void tagMatrix(Md3Model.Tag t, float[] out) {
    out[0] = t.axisX().x();
    out[1] = t.axisX().y();
    out[2] = t.axisX().z();
    out[3] = 0;
    out[4] = t.axisY().x();
    out[5] = t.axisY().y();
    out[6] = t.axisY().z();
    out[7] = 0;
    out[8] = t.axisZ().x();
    out[9] = t.axisZ().y();
    out[10] = t.axisZ().z();
    out[11] = 0;
    out[12] = t.origin().x();
    out[13] = t.origin().y();
    out[14] = t.origin().z();
    out[15] = 1;
  }

  private int drawBlock(
      float[] model,
      int rgba,
      Vec3f ambient,
      Vec3f directed,
      Vec3f lightDirection,
      float timeOffset,
      int fog,
      int flags,
      float shaderS,
      float shaderT) {
    ByteBuffer out = frame.uniform(Q3Blocks.DRAW_BYTES);
    int at = frame.lastUniformOffset();
    Q3Blocks.draw(
        out,
        at,
        model,
        rgba,
        ambient,
        directed,
        lightDirection,
        timeOffset,
        fog,
        flags,
        shaderS,
        shaderT);
    out.position(at + Q3Blocks.DRAW_BYTES);
    frame.endUniform();
    return at;
  }

  /** Copies a dynamic draw's indices into the ring, wound for GL front faces when {@code front}. */
  private int ringIndices(FrameGeometry geometry, int first, int count, boolean front) {
    int firstIndex = frame.indexCount();
    ByteBuffer out = frame.indices(count);
    int[] indices = geometry.mesh().indices();
    for (int i = first; i + 2 < first + count; i += 3) {
      out.putInt(indices[i]);
      out.putInt(front ? indices[i + 2] : indices[i + 1]);
      out.putInt(front ? indices[i + 1] : indices[i + 2]);
    }
    return firstIndex;
  }

  private int addRuns(VisibleSet visible, int batch) {
    int first = runTotal;
    int n = visible.batchRunCount(batch);
    if (runTotal + n > runFirst.length) {
      int size = Math.max(runFirst.length * 2, runTotal + n);
      runFirst = Arrays.copyOf(runFirst, size);
      runCount = Arrays.copyOf(runCount, size);
    }
    int from = visible.batchFirstRun(batch);
    for (int i = 0; i < n; i++) {
      runFirst[runTotal] = visible.runFirstIndex(from + i);
      runCount[runTotal] = visible.runIndexCount(from + i);
      runTotal++;
    }
    return first;
  }

  private Cmd cmd(
      Q3MaterialTable.Pass pass,
      GpuTextureView diffuse,
      GpuSampler sampler,
      GpuTextureView lightmap,
      GpuBufferSlice draw,
      int drawOffset) {
    if (cmdCount == cmds.size()) cmds.add(new Cmd());
    Cmd c = cmds.get(cmdCount++);
    c.pipeline = pass.pipeline;
    c.stage = pass.block;
    c.diffuse = diffuse;
    c.sampler = sampler;
    c.lightmap = lightmap;
    c.draw = draw;
    c.drawOffset = drawOffset;
    c.mesh = null;
    c.runs = 0;
    return c;
  }

  private static void staticGeometry(Cmd c, boolean front, int firstRun, int runs) {
    c.geometry = front ? GEO_FRONT : GEO_BACK;
    c.firstRun = firstRun;
    c.runs = runs;
  }

  private static void staticRange(Cmd c, boolean front, int firstIndex, int count) {
    c.geometry = front ? GEO_FRONT : GEO_BACK;
    c.firstIndex = firstIndex;
    c.indexCount = count;
    c.baseVertex = 0;
  }

  private static void ringGeometry(Cmd c, int firstIndex, int count, int baseVertex) {
    c.geometry = GEO_RING;
    c.firstIndex = firstIndex;
    c.indexCount = count;
    c.baseVertex = baseVertex;
  }

  private static void meshGeometry(Cmd c, Q3WorldRenderer.SkyMesh mesh, int firstIndex, int count) {
    c.geometry = GEO_MESH;
    c.mesh = mesh;
    c.firstIndex = firstIndex;
    c.indexCount = count;
    c.baseVertex = 0;
  }

  private void fillRunBuffers() {
    if (runTotal == 0) return;
    DeviceFeatures features = RenderSystem.getDevice().getDeviceInfo().features();
    if (features.multiDrawDirectSeparate()) {
      if (runOffsets == null || runOffsets.capacity() < runTotal) {
        if (runOffsets != null) MemoryUtil.memFree(runOffsets);
        if (runCounts != null) MemoryUtil.memFree(runCounts);
        if (runZeros != null) MemoryUtil.memFree(runZeros);
        int size = Integer.highestOneBit(runTotal) * 2;
        runOffsets = MemoryUtil.memAllocPointer(size);
        runCounts = MemoryUtil.memAllocInt(size);
        runZeros = MemoryUtil.memCallocInt(size);
      }
      for (int i = 0; i < runTotal; i++) {
        runOffsets.put(i, (long) runFirst[i] * 4);
        runCounts.put(i, runCount[i]);
      }
    } else if (features.multiDrawDirectInterleaved()) {
      if (runInterleaved == null || runInterleaved.capacity() < runTotal * 3) {
        if (runInterleaved != null) MemoryUtil.memFree(runInterleaved);
        runInterleaved = MemoryUtil.memAllocInt(Integer.highestOneBit(runTotal * 3) * 2);
      }
      for (int i = 0; i < runTotal; i++) {
        runInterleaved.put(i * 3, runFirst[i]);
        runInterleaved.put(i * 3 + 1, runCount[i]);
        runInterleaved.put(i * 3 + 2, 0);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Execution

  private void execute(PassRecord record) {
    boolean clearColor = record.clearColor;
    if (record.hyperspace >= 0) {
      // RB_Hyperspace: the view's region flashes grey before (and under) whatever it draws.
      if (clearColor) encoder.clearColorTexture(record.color.texture(), CLEAR);
      clearColor = false;
      float g = record.hyperspace;
      int[] r = record.region;
      encoder.clearColorAndDepthTextures(
          record.color.texture(),
          new Vector4f(g, g, g, 1),
          record.depth.texture(),
          0,
          r[0],
          r[1],
          r[2],
          r[3]);
    }
    if (record.to == record.from && !clearColor) return;
    GpuBufferSlice view = frame.uniformSlice(record.viewBlock, Q3Blocks.VIEW_BYTES);
    DeviceFeatures features = RenderSystem.getDevice().getDeviceInfo().features();
    int maxInterleaved =
        RenderSystem.getDevice().getDeviceInfo().limits().maxMultiDrawDirectInterleavedDrawCount();
    GpuBuffer ringVertices = frame.vertexBuffer();
    GpuBuffer ringIndices = frame.indexBuffer();
    GpuBuffer worldVertices = world == null ? null : world.vertices();
    try (RenderPass pass =
        encoder.createRenderPass(
            () -> record.label,
            record.color,
            clearColor ? Optional.of(CLEAR) : Optional.empty(),
            record.depth,
            OptionalDouble.of(0))) {
      if (record.scissor != null)
        pass.enableScissor(
            record.scissor[0], record.scissor[1], record.scissor[2], record.scissor[3]);
      pass.setUniform(Q3Pipelines.VIEW, view);
      pass.setUniform(Q3Pipelines.FOG, record.fog);
      RenderPipeline pipeline = null;
      GpuBufferSlice stage = null, draw = null;
      int drawOffset = Integer.MIN_VALUE;
      GpuTextureView diffuse = null, lightmap = null;
      GpuSampler sampler = null;
      int geometry = -1;
      Q3WorldRenderer.SkyMesh mesh = null;
      for (int i = record.from; i < record.to; i++) {
        Cmd c = cmds.get(i);
        if (c.pipeline != pipeline) {
          pass.setPipeline(c.pipeline);
          pipeline = c.pipeline;
        }
        if (c.stage != stage) {
          pass.setUniform(Q3Pipelines.STAGE, c.stage);
          stage = c.stage;
        }
        if (c.draw != null) {
          if (c.draw != draw) {
            pass.setUniform(Q3Pipelines.DRAW, c.draw);
            draw = c.draw;
            drawOffset = Integer.MIN_VALUE;
          }
        } else if (c.drawOffset != drawOffset) {
          pass.setUniform(Q3Pipelines.DRAW, frame.uniformSlice(c.drawOffset, Q3Blocks.DRAW_BYTES));
          drawOffset = c.drawOffset;
          draw = null;
        }
        if (c.diffuse != diffuse || c.sampler != sampler) {
          pass.bindTexture(Q3Pipelines.DIFFUSE, c.diffuse, c.sampler);
          diffuse = c.diffuse;
          sampler = c.sampler;
        }
        if (c.lightmap != lightmap) {
          pass.bindTexture(Q3Pipelines.LIGHTMAP, c.lightmap, context.q3Sampler(true, false));
          lightmap = c.lightmap;
        }
        if (c.geometry != geometry || c.mesh != mesh) {
          switch (c.geometry) {
            case GEO_FRONT, GEO_BACK -> {
              pass.setVertexBuffer(0, worldVertices.slice());
              pass.setIndexBuffer(world.indices(c.geometry == GEO_FRONT), IndexType.INT);
            }
            case GEO_RING -> {
              pass.setVertexBuffer(0, ringVertices.slice());
              pass.setIndexBuffer(ringIndices, IndexType.INT);
            }
            default -> {
              pass.setVertexBuffer(0, c.mesh.vertices().slice());
              pass.setIndexBuffer(c.mesh.indices(), IndexType.INT);
            }
          }
          geometry = c.geometry;
          mesh = c.mesh;
        }
        if (c.runs > 0) {
          drawRuns(pass, c, features, maxInterleaved);
        } else {
          pass.drawIndexed(c.indexCount, 1, c.firstIndex, c.baseVertex, 0);
          stats.draws++;
          stats.triangles += c.indexCount / 3;
        }
      }
    }
  }

  private void drawRuns(RenderPass pass, Cmd c, DeviceFeatures features, int maxInterleaved) {
    if (c.runs == 1) {
      pass.drawIndexed(runCount[c.firstRun], 1, runFirst[c.firstRun], 0, 0);
      stats.draws++;
      stats.triangles += runCount[c.firstRun] / 3;
      return;
    }
    for (int i = 0; i < c.runs; i++) stats.triangles += runCount[c.firstRun + i] / 3;
    if (features.multiDrawDirectSeparate()) {
      runOffsets.position(c.firstRun).limit(c.firstRun + c.runs);
      runCounts.position(c.firstRun).limit(c.firstRun + c.runs);
      runZeros.position(0).limit(c.runs);
      pass.multiDrawIndexed(runOffsets, runCounts, runZeros, c.runs);
      runOffsets.clear();
      runCounts.clear();
      runZeros.clear();
      stats.multiDraws++;
      stats.draws++;
    } else if (features.multiDrawDirectInterleaved() && maxInterleaved > 0) {
      for (int done = 0; done < c.runs; done += maxInterleaved) {
        int n = Math.min(maxInterleaved, c.runs - done);
        runInterleaved.position((c.firstRun + done) * 3).limit((c.firstRun + done + n) * 3);
        pass.multiDrawIndexed(runInterleaved, 1, 0, n);
        runInterleaved.clear();
        stats.multiDraws++;
        stats.draws++;
      }
    } else {
      for (int i = 0; i < c.runs; i++) {
        pass.drawIndexed(runCount[c.firstRun + i], 1, runFirst[c.firstRun + i], 0, 0);
        stats.draws++;
      }
    }
  }

  /** A main view of {@code scene}'s spawn point for tools and tests. */
  public static ViewDefinition spawnView(RenderScene scene, int width, int height, int timeMillis) {
    ViewDefinition base = ViewDefinition.of(scene.spawnCamera(), (float) width / height);
    return new ViewDefinition(
        ViewDefinition.Kind.MAIN,
        base.origin(),
        base.basis(),
        base.fovX(),
        base.fovY(),
        0,
        0,
        width,
        height,
        timeMillis,
        0,
        AreaMask.NONE,
        Optional.empty(),
        base.origin(),
        -1,
        0);
  }
}
