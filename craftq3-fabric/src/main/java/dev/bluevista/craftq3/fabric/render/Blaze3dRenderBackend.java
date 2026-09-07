package dev.bluevista.craftq3.fabric.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.assets.image.Mipmaps;
import dev.bluevista.craftq3.assets.image.Q3Image;
import dev.bluevista.craftq3.assets.shader.ShaderDefinition;
import dev.bluevista.craftq3.assets.shader.ShaderDefinition.*;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.fabric.CraftQ3Client;
import dev.bluevista.craftq3.platform.RenderBackend;
import dev.bluevista.craftq3.render.BspVisibility;
import dev.bluevista.craftq3.render.CgameFrame;
import dev.bluevista.craftq3.render.DebugGeometry;
import dev.bluevista.craftq3.render.DynamicLighting;
import dev.bluevista.craftq3.render.FogVolumes;
import dev.bluevista.craftq3.render.LightGrid;
import dev.bluevista.craftq3.render.MaterialLibrary;
import dev.bluevista.craftq3.render.RenderScene;
import dev.bluevista.craftq3.render.SceneAssets;
import dev.bluevista.craftq3.render.SubmittedMaterials;
import dev.bluevista.craftq3.render.material.PortalView;
import dev.bluevista.craftq3.render.material.SkyGeometry;
import dev.bluevista.craftq3.render.material.StageEvaluator;
import dev.bluevista.craftq3.render.material.VertexDeformer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

/** Backend-neutral Q3 stage renderer. All PK3 IO and image decoding precedes this GPU boundary. */
public final class Blaze3dRenderBackend implements RenderBackend {
  private static final int STRIDE = 28;
  private static final VertexFormat FORMAT =
      VertexFormat.builder(0)
          .addAttribute("Position", GpuFormat.RGB32_FLOAT)
          .addAttribute("UV0", GpuFormat.RG32_FLOAT)
          .addAttribute("Color", GpuFormat.RGBA8_UNORM)
          .addAttribute("Fog", GpuFormat.R32_FLOAT)
          .build();
  private static final BindGroupLayout LAYOUT =
      BindGroupLayout.builder()
          .withUniform("Q3Camera", UniformType.UNIFORM_BUFFER)
          .withUniform("Q3Stage", UniformType.UNIFORM_BUFFER)
          .withSampler("StageTexture")
          .withSampler("SkyMask")
          .build();
  private static final Map<PipelineKey, RenderPipeline> PIPELINES = new HashMap<>();
  private static final PipelineKey OPAQUE =
      new PipelineKey(Blend.OPAQUE, DepthFunc.LEQUAL, true, false, false, false);
  private static final Stage DEBUG_STAGE =
      new Stage(
          new TextureMap(List.of("$whiteimage"), 0, false),
          Blend.OPAQUE,
          AlphaFunc.NONE,
          RgbGen.of(RgbGenType.EXACT_VERTEX),
          AlphaGen.of(AlphaGenType.VERTEX),
          TcGen.of(TcGenType.BASE),
          List.of(),
          DepthFunc.LEQUAL,
          true,
          false);
  private static final Stage FOG_STAGE =
      new Stage(
          new TextureMap(List.of("$whiteimage"), 0, true),
          Blend.ALPHA,
          AlphaFunc.NONE,
          RgbGen.of(RgbGenType.EXACT_VERTEX),
          AlphaGen.of(AlphaGenType.VERTEX),
          TcGen.of(TcGenType.BASE),
          List.of(),
          DepthFunc.EQUAL,
          false,
          false);

  /**
   * Register shader source identities before Minecraft's initial shader discovery (also Vulkan).
   */
  public static void registerPipelines() {
    pipeline(OPAQUE);
  }

  public enum Mode {
    MATERIALS,
    WIREFRAME,
    SURFACES,
    PATCHES,
    NORMALS,
    NODES,
    LEAVES,
    PVS,
    LIGHTMAP_INDICES,
    LIGHTMAPS,
    BRUSHES;

    public Mode next() {
      return values()[(ordinal() + 1) % values().length];
    }
  }

  public record Statistics(
      int visibleSurfaces,
      int totalSurfaces,
      int triangles,
      int drawCalls,
      int leaf,
      int cluster,
      boolean pvsApplied,
      int images,
      int lightmaps,
      int warnings,
      double prepareMs) {
    static Statistics empty() {
      return new Statistics(0, 0, 0, 0, -1, -1, false, 0, 0, 0, 0);
    }
  }

  private record PipelineKey(
      Blend blend,
      DepthFunc depth,
      boolean write,
      boolean cull,
      boolean offset,
      boolean lines,
      boolean reversed) {
    PipelineKey(
        Blend blend, DepthFunc depth, boolean write, boolean cull, boolean offset, boolean lines) {
      this(blend, depth, write, cull, offset, lines, false);
    }
  }

  private record Texture(GpuTexture image, GpuTextureView view) implements AutoCloseable {
    @Override
    public void close() {
      view.close();
      image.close();
    }
  }

  private record Draw(
      RenderScene.Surface surface,
      ShaderDefinition material,
      Stage stage,
      List<BspMap.Vertex> source,
      int first,
      int count,
      boolean dynamic,
      boolean fog,
      boolean lines,
      boolean sky) {}

  private record MaskRange(int surface, int first, int count) {}

  private static final class SkyMask implements AutoCloseable {
    private final List<MaskRange> ranges = new ArrayList<>();
    private Texture target, remoteTarget;

    @Override
    public void close() {
      if (target != null) target.close();
      if (remoteTarget != null) remoteTarget.close();
      target = null;
      remoteTarget = null;
    }
  }

  private final Map<String, SkyMask> skyMasks = new HashMap<>();
  private GpuBuffer skyMaskVertices;

  private final MaterialLibrary materials;
  private final Map<String, Texture> textures = new HashMap<>();
  private final Map<String, MovieTexture> movieTextures = new HashMap<>();
  private long movieUploads, movieAllocations;

  private record MovieTexture(Texture texture, Q3Image pixels) {}

  record MovieDiagnostics(int textures, long bytes, long uploads, long allocations) {}

  MovieDiagnostics movieDiagnostics() {
    long bytes =
        movieTextures.values().stream()
            .mapToLong(movie -> (long) movie.pixels().width() * movie.pixels().height() * 4)
            .sum();
    return new MovieDiagnostics(movieTextures.size(), bytes, movieUploads, movieAllocations);
  }

  private final List<Texture> lightmaps = new ArrayList<>();
  private final List<Draw> draws = new ArrayList<>();
  private final GpuBuffer[] maskedUniforms = new GpuBuffer[AlphaFunc.values().length * 5];
  private final GpuBuffer[] alphaUniforms = new GpuBuffer[AlphaFunc.values().length * 5];
  private Matrix4f minecraftProjection;
  private GpuTexture depthTexture;
  private GpuTextureView depthView;
  private int depthWidth, depthHeight;
  private GpuBuffer vertices, camera;
  private GpuSampler repeat, clamp, repeatMips, clampMips;
  private RenderScene.Camera previousCamera;
  private List<RenderScene.DynamicLight> lights = List.of(), previousLights = List.of();
  private PortalView.Basis portalBasis;
  private double previousTime = Double.NaN;
  private RenderScene uploaded;
  private BspVisibility visibility;
  private FogVolumes fog;
  private LightGrid lightGrid;
  private final Map<Integer, PortalView> portals = new HashMap<>();
  private Texture portalTexture;
  private GpuTexture portalDepth;
  private GpuTextureView portalDepthView;
  private GpuBuffer portalVertices, portalCamera, portalUniform;
  private int portalWidth, portalHeight, activePortal = -1, bufferBytes;
  private Mode mode = Mode.MATERIALS;
  private Mode uploadedMode;
  private boolean pvs = true, frozen;
  private double frozenSeconds;
  private final long started = System.nanoTime();
  private Statistics statistics = Statistics.empty();
  private boolean geometryTruncated;
  private final SubmittedMaterials submittedMaterials = new SubmittedMaterials();
  private final SubmittedGpuBuffer submittedBuffer = new SubmittedGpuBuffer();
  private final SubmittedGpuBuffer hudBuffer = new SubmittedGpuBuffer();
  private final SubmittedGpuBuffer remoteSubmittedBuffer = new SubmittedGpuBuffer();
  private final Map<String, Q3Image> submittedImages = new HashMap<>();
  private final Map<Integer, BitSet> surfaceAreas = new HashMap<>();
  private final Set<String> submissionWarnings = new java.util.LinkedHashSet<>();
  private CgameFrame.View submittedView;
  private SceneAssets submittedAssets = SceneAssets.EMPTY;
  private PortalView.Basis viewBasis;
  private GpuBuffer depthHackCamera, hudCamera;
  private boolean previousMirrored;

  private record FrameDraw(Draw world, SubmittedGpuBuffer.Draw submitted) {
    ShaderDefinition material() {
      return world != null ? world.material() : submitted.batch().material();
    }

    BspMap.Bounds bounds() {
      return world != null ? world.surface().bounds() : submitted.batch().bounds();
    }

    boolean sky() {
      return world != null && world.sky();
    }
  }

  public Blaze3dRenderBackend(MaterialLibrary materials) {
    this.materials = materials;
    if (net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment())
      mode =
          Mode.valueOf(
              System.getProperty("craftq3.debugMode", "MATERIALS")
                  .toUpperCase(java.util.Locale.ROOT));
  }

  public Statistics statistics() {
    return statistics;
  }

  public List<String> submissionDiagnostics() {
    return List.copyOf(submissionWarnings);
  }

  public Mode mode() {
    return mode;
  }

  public void cycleMode() {
    mode = mode.next();
  }

  public void togglePvs() {
    pvs = !pvs;
    previousCamera = null;
  }

  public boolean pvsEnabled() {
    return pvs;
  }

  public void toggleAnimation() {
    frozenSeconds = seconds();
    frozen = !frozen;
  }

  public boolean frozen() {
    return frozen;
  }

  private double seconds() {
    if (submittedView != null) return submittedView.refdef().timeMillis() / 1000.0;
    if (Boolean.getBoolean("craftq3.capture")) return 1.0;
    return frozen ? frozenSeconds : (System.nanoTime() - started) / 1_000_000_000.0;
  }

  /** Draw immutable BSP geometry into Minecraft's existing color/depth attachments. */
  public void renderMinecraftWorld(RenderScene scene, Matrix4f projection, int width, int height) {
    minecraftProjection = new Matrix4f(projection);
    try {
      renderView(scene, width, height, null, SceneAssets.EMPTY, false);
    } finally {
      minecraftProjection = null;
    }
  }

  /** Cgame world views share Minecraft depth before its later hand/HUD depth clear. */
  public void renderMinecraftWorld(
      RenderScene scene, CgameFrame frame, Matrix4f projection, int width, int height) {
    minecraftProjection = new Matrix4f(projection);
    try {
      render(scene, frame, width, height, false);
    } finally {
      minecraftProjection = null;
    }
  }

  @Override
  public void render(RenderScene scene, int width, int height) {
    retainMovies(java.util.Set.of());
    renderView(scene, width, height, null, SceneAssets.EMPTY, true);
  }

  /** Executes cgame's views and stretch-pics in submission order, sharing world depth/materials. */
  public void render(RenderScene scene, CgameFrame frame, int width, int height) {
    render(scene, frame, width, height, true);
  }

  /** An original UI overlay has its own asset handles and preserves the preceding game color. */
  public void render(
      RenderScene scene, CgameFrame frame, int width, int height, boolean clearFirst) {
    if (width <= 0 || height <= 0) return;
    initialize(scene);
    uploadSubmittedAssets(frame.assets());
    retainMovies(
        frame.commands().stream()
            .filter(CgameFrame.Image.class::isInstance)
            .map(CgameFrame.Image.class::cast)
            .map(CgameFrame.Image::texture)
            .collect(java.util.stream.Collectors.toSet()));
    boolean clear = clearFirst;
    double time = frame.timeMillis() / 1000.0;
    var quads = new ArrayList<SubmittedMaterials.Batch>();
    try {
      for (var command : frame.commands()) {
        if (command instanceof CgameFrame.Quad quad) {
          quads.addAll(SubmittedMaterials.quad(quad, frame.assets(), time));
          continue;
        }
        if (!quads.isEmpty()) {
          renderHud(quads, width, height, clear);
          clear = false;
          quads.clear();
        }
        if (command instanceof CgameFrame.Image movie) {
          uploadMovie(movie);
          renderHud(SubmittedMaterials.image(movie), width, height, clear);
          clear = false;
          continue;
        }
        var view = (CgameFrame.View) command;
        time = view.refdef().timeMillis() / 1000.0;
        renderView(
            scene.withCamera(view.refdef().camera()).withLights(view.lights()),
            width,
            height,
            view,
            frame.assets(),
            clear);
        clear = false;
      }
      if (!quads.isEmpty() || clear) renderHud(quads, width, height, clear);
    } finally {
      submittedView = null;
      submittedAssets = SceneAssets.EMPTY;
      viewBasis = null;
    }
  }

  private void retainMovies(java.util.Set<String> retained) {
    var entries = movieTextures.entrySet().iterator();
    while (entries.hasNext()) {
      var entry = entries.next();
      if (!retained.contains(entry.getKey())) {
        entry.getValue().texture().close();
        entries.remove();
      }
    }
  }

  private void uploadMovie(CgameFrame.Image command) {
    var pixels = command.pixels();
    String name = command.texture();
    var previous = movieTextures.get(name);
    if (previous != null && previous.pixels() == pixels) return;
    Texture texture;
    if (previous == null
        || previous.pixels().width() != pixels.width()
        || previous.pixels().height() != pixels.height()) {
      texture = uploadImage(name, pixels);
      if (previous != null) previous.texture().close();
      movieAllocations++;
    } else {
      texture = previous.texture();
      try (var image =
          new com.mojang.blaze3d.platform.NativeImage(pixels.width(), pixels.height(), false)) {
        image.getPixelBytes().put(pixels.rgba());
        RenderSystem.getDevice()
            .createCommandEncoder()
            .writeToTexture(texture.image(), image, 0, 0, 0, 0);
      }
    }
    movieTextures.put(name, new MovieTexture(texture, pixels));
    movieUploads++;
  }

  private void initialize(RenderScene scene) {
    if (visibility != null) return;
    uploadTextures();
    visibility = new BspVisibility(scene);
    fog = scene.bsp() == null ? null : FogVolumes.from(scene.bsp(), materials.effects());
    lightGrid = scene.bsp() == null ? null : new LightGrid(scene.bsp());
    buildSkyMasks(scene);
    if (scene.bsp() != null)
      for (var leaf : scene.bsp().leaves())
        if (leaf.area() >= 0)
          for (int i = 0; i < leaf.faceCount(); i++)
            surfaceAreas
                .computeIfAbsent(
                    scene.bsp().leafFaces().get(leaf.firstFace() + i), ignored -> new BitSet())
                .set(leaf.area());
    for (var surface : scene.surfaces()) {
      var material = materials.surfaces().get(surface.id());
      if (material != null && material.portal())
        PortalView.find(scene, surface).ifPresent(portal -> portals.put(surface.id(), portal));
    }
  }

  private void renderView(
      RenderScene scene,
      int width,
      int height,
      CgameFrame.View submission,
      SceneAssets assets,
      boolean clearColor) {
    if (width <= 0 || height <= 0) return;
    submittedView = submission;
    submittedAssets = assets;
    if (submission != null) diagnoseSubmission(submission);
    viewBasis = submission == null ? null : submission.refdef().basis();
    boolean worldEnabled = submission == null || submission.refdef().worldModel();
    boolean mirrored = submission != null && submission.refdef().mirrored();
    long begin = System.nanoTime();
    lights = scene.lights();
    initialize(scene);
    if (uploaded == null || uploaded.vertices() != scene.vertices() || uploadedMode != mode)
      build(scene);
    var device = RenderSystem.getDevice();
    ensureDepth(width, height);
    var encoder = device.createCommandEncoder();
    if (camera == null)
      camera =
          device.createBuffer(
              () -> "CraftQ3 camera", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 96);
    var projection =
        minecraftProjection != null
            ? minecraftProjection
            : submission == null
                ? matrix(scene.camera(), width, height)
                : SubmittedProjection.matrix(
                    submission.refdef(), width, height, device.getDeviceInfo().isZZeroToOne());
    writeCamera(camera, projection, null, width, height);
    if (submission != null) {
      if (depthHackCamera == null)
        depthHackCamera =
            device.createBuffer(
                () -> "CraftQ3 viewmodel camera",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                96);
      writeCamera(
          depthHackCamera,
          SubmittedProjection.depthHack(
              projection, device.getDeviceInfo().isZZeroToOne(), minecraftProjection != null),
          null,
          width,
          height);
    }
    var selection =
        visibility.select(scene.camera(), (double) width / height, 1, 131072, pvs, false);
    var frustum = new BspVisibility.Frustum(scene.camera(), (double) width / height, 1, 131072);
    Set<Integer> visible = new HashSet<>();
    for (var surface : selection.surfaces()) {
      if (!worldEnabled || !areaVisible(surface.id(), submission)) continue;
      var material = materials.surfaces().get(surface.id());
      if ((material != null && !material.deforms().isEmpty())
          || (submission == null
              ? frustum.intersects(surface.bounds())
              : SubmittedProjection.intersects(submission.refdef(), surface.bounds())))
        visible.add(surface.id());
    }
    double time = seconds();
    var orderedDraws = new ArrayList<>(draws);
    orderedDraws.sort(
        Comparator.comparingDouble((Draw d) -> debugLines(d) ? 1000 : d.material().sort())
            .thenComparingDouble(
                d ->
                    d.material().sort() > 3 && !d.sky()
                        ? -distanceSquared(d.surface().bounds(), scene.camera().origin())
                        : 0)
            .thenComparingInt(d -> d.surface().id())
            .thenComparingInt(Draw::first));
    int visibleTriangles = 0, calls = 0;
    for (var surface : scene.surfaces())
      if (visible.contains(surface.id())) visibleTriangles += surface.triangleCount();
    for (Draw draw : draws) {
      if (!visible(draw, visible)) continue;
      if ((previousMirrored != mirrored)
          || mirrored
          || draw.dynamic()
              && (!lights.equals(previousLights)
                  || submission != null
                  || !scene.camera().equals(previousCamera)
                  || (!draw.fog() && time != previousTime)
                  || VertexDeformer.isDynamic(draw.material()))) {
        ByteBuffer data = MemoryUtil.memAlloc(draw.count() * STRIDE);
        try {
          writeDraw(data, draw, scene.camera(), time, mirrored);
          data.flip();
          encoder.writeToBuffer(
              vertices.slice((long) draw.first() * STRIDE, data.remaining()), data);
        } finally {
          MemoryUtil.memFree(data);
        }
      }
    }
    activePortal = worldEnabled ? renderPortal(scene, visible, width, height, time) : -1;
    updateSkyMasks(camera, visible, width, height);
    previousCamera = scene.camera();
    previousTime = time;
    previousLights = lights;
    previousMirrored = mirrored;
    var entities =
        submission == null
            ? List.<SubmittedGpuBuffer.Draw>of()
            : submittedBuffer.upload(
                submittedMaterials.build(
                    scene,
                    submission,
                    assets,
                    worldEnabled ? lightGrid : null,
                    worldEnabled ? fog : null,
                    false));
    var frameDraws = new ArrayList<FrameDraw>();
    for (var draw : orderedDraws)
      if (worldEnabled
          && visible(draw, visible)
          && (!portalDraw(draw) || samePortal(draw.surface().id(), activePortal)))
        frameDraws.add(new FrameDraw(draw, null));
    for (var draw : entities) frameDraws.add(new FrameDraw(null, draw));
    frameDraws.sort(
        Comparator.comparingDouble(
                (FrameDraw d) ->
                    d.world() != null && debugLines(d.world()) ? 1000 : d.material().sort())
            .thenComparingDouble(
                d ->
                    d.material().sort() > 3 && !d.sky()
                        ? -distanceSquared(d.bounds(), scene.camera().origin())
                        : 0));
    double prepareMs = (System.nanoTime() - begin) / 1_000_000.0;
    // Explicit depth clears are required on MoltenVK; inline attachment clears failed regression.
    if (minecraftProjection == null) encoder.clearDepthTexture(depthTexture, 1.0);
    var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
    try (var pass =
        encoder.createRenderPass(
            () -> "CraftQ3 materials",
            target.getColorTextureView(),
            clearColor ? Optional.of(new Vector4f(0.025f, 0.03f, 0.04f, 1)) : Optional.empty(),
            minecraftProjection == null ? depthView : target.getDepthTextureView(),
            OptionalDouble.empty())) {
      RenderPipeline previous = null;
      Texture previousTexture = null;
      GpuSampler previousSampler = null;
      if (submission != null) scissor(pass, submission.refdef(), width, height);
      for (FrameDraw item : frameDraws) {
        if (item.submitted() != null) {
          drawSubmitted(
              pass,
              item.submitted(),
              submittedBuffer,
              item.submitted().batch().depthHack() ? depthHackCamera : camera);
          calls++;
          previous = null;
          continue;
        }
        Draw draw = item.world();
        PipelineKey key = key(draw);
        RenderPipeline pipeline = pipeline(key);
        if (pipeline != previous) {
          pass.setPipeline(pipeline);
          pass.setUniform("Q3Camera", camera);
          pass.setVertexBuffer(0, vertices.slice());
          previous = pipeline;
          previousTexture = null;
        }
        pass.setUniform("Q3Stage", portalDraw(draw) ? portalUniform : stageUniform(draw));
        String name = draw.stage().texture().atTime(materialTime(draw.material(), time));
        if (name.equals("$dlight")) name = "$whiteimage";
        Texture texture =
            name.equals("$portal")
                ? portalTexture
                : name.equals("$lightmap")
                        && draw.surface().lightmap() >= 0
                        && draw.surface().lightmap() < lightmaps.size()
                    ? lightmaps.get(draw.surface().lightmap())
                    : textures.getOrDefault(
                        name, textures.get(name.equals("$lightmap") ? "$whiteimage" : "$missing"));
        boolean clamped = draw.stage().texture().clamp() || name.equals("$lightmap");
        GpuSampler sampler =
            draw.material().noMipmaps() || name.startsWith("$")
                ? (clamped ? clamp : repeat)
                : (clamped ? clampMips : repeatMips);
        if (texture != previousTexture || sampler != previousSampler) {
          pass.bindTexture("StageTexture", texture.view(), sampler);
          previousTexture = texture;
          previousSampler = sampler;
        }
        pass.bindTexture("SkyMask", maskTexture(draw).view(), clamp);
        pass.draw(draw.count(), 1, draw.first(), 0);
        calls++;
      }
    }
    statistics =
        new Statistics(
            visible.size(),
            scene.surfaces().size(),
            visibleTriangles,
            calls,
            selection.cameraLeaf(),
            selection.cameraCluster(),
            selection.pvsApplied(),
            textures.size(),
            lightmaps.size(),
            materials.diagnostics().size()
                + submissionWarnings.size()
                + (geometryTruncated ? 1 : 0),
            prepareMs);
  }

  private boolean debugLines(Draw draw) {
    return draw.lines() && mode != Mode.WIREFRAME;
  }

  private void diagnoseSubmission(CgameFrame.View view) {
    if ((view.refdef().rdflags() & CgameFrame.RDF_HYPERSPACE) != 0)
      submissionWarning("The cgame hyperspace visual effect is not implemented.");
    for (var entity : view.entities()) {
      switch (entity.type()) {
        case MODEL -> {
          if (entity.model() == null && entity.inlineModel() < 0)
            submissionWarning("An unresolved cgame model was omitted from the view.");
        }
        case POLY ->
            submissionWarning(
                "RT_POLY entities have no vertices; use the cgame addPoly submission.");
        case PORTALSURFACE ->
            submissionWarning("Cgame portal metadata currently uses static BSP portal linkage.");
        case BEAM, RAIL_CORE, RAIL_RINGS, LIGHTNING ->
            submissionWarning("Beam and rail geometry uses provisional renderer dimensions.");
        case SPRITE -> {}
      }
      if ((entity.renderFx() & CgameFrame.RF_SHADOW_PLANE) != 0)
        submissionWarning("Projected model shadows are not implemented.");
    }
  }

  private void submissionWarning(String message) {
    if (submissionWarnings.add(message)) CraftQ3Client.LOGGER.warn("CraftQ3 renderer: {}", message);
  }

  private boolean areaVisible(int surface, CgameFrame.View view) {
    if (view == null) return true;
    var areas = surfaceAreas.get(surface);
    if (areas == null || areas.isEmpty()) return true;
    var mask = view.refdef().areaMask();
    for (int area = areas.nextSetBit(0); area >= 0; area = areas.nextSetBit(area + 1))
      if (area / 8 >= mask.size() || (mask.unsigned(area / 8) & (1 << (area & 7))) == 0)
        return true;
    return false;
  }

  private void uploadSubmittedAssets(SceneAssets assets) {
    for (var entry : assets.images().entrySet()) {
      // Cgame and the console own separate immutable copies of shared images. Compare pixels
      // so alternating overlays do not regenerate mipmaps and replace GPU textures every frame.
      if (entry.getValue().equals(submittedImages.get(entry.getKey()))) continue;
      Texture image = uploadImage(entry.getKey(), entry.getValue());
      Texture old = textures.put(entry.getKey(), image);
      if (old != null) old.close();
      submittedImages.put(entry.getKey(), entry.getValue());
    }
  }

  private static void scissor(
      com.mojang.blaze3d.systems.RenderPass pass, CgameFrame.Refdef ref, int width, int height) {
    int left = Math.clamp(ref.x(), 0, width), top = Math.clamp(ref.y(), 0, height);
    int right = Math.clamp(ref.x() + ref.width(), 0, width),
        bottom = Math.clamp(ref.y() + ref.height(), 0, height);
    // Blaze3D scissors use the framebuffer's lower-left origin on both graphics backends.
    pass.enableScissor(left, height - bottom, Math.max(0, right - left), Math.max(0, bottom - top));
  }

  private void drawSubmitted(
      com.mojang.blaze3d.systems.RenderPass pass,
      SubmittedGpuBuffer.Draw draw,
      SubmittedGpuBuffer source,
      GpuBuffer projection) {
    var batch = draw.batch();
    var stage = batch.stage();
    var material = batch.material();
    var key =
        new PipelineKey(
            stage.blend(),
            batch.overlay() ? DepthFunc.DISABLE : stage.depthFunc(),
            !batch.overlay() && stage.depthWrite(),
            !batch.overlay() && material.cull() != Cull.NONE,
            material.polygonOffset() && !batch.overlay(),
            false,
            minecraftProjection != null && portalBasis == null);
    pass.setPipeline(pipeline(key));
    pass.setUniform("Q3Camera", projection);
    pass.setUniform(
        "Q3Stage", alphaUniforms[stage.alphaFunc().ordinal() * 5 + batch.fogAdjustment()]);
    pass.setVertexBuffer(0, source.buffer().slice());
    String name = batch.texture();
    Texture texture =
        movieTextures.containsKey(name)
            ? movieTextures.get(name).texture()
            : name.equals("$lightmap")
                    && batch.lightmap() >= 0
                    && batch.lightmap() < lightmaps.size()
                ? lightmaps.get(batch.lightmap())
                : textures.getOrDefault(
                    name, textures.get(name.equals("$lightmap") ? "$whiteimage" : "$missing"));
    boolean clamped = stage.texture().clamp() || name.startsWith("$");
    var sampler =
        material.noMipmaps() || name.startsWith("$")
            ? (clamped ? clamp : repeat)
            : (clamped ? clampMips : repeatMips);
    pass.bindTexture("StageTexture", texture.view(), sampler);
    pass.bindTexture("SkyMask", textures.get("$whiteimage").view(), clamp);
    pass.draw(draw.count(), 1, draw.first(), 0);
  }

  private void renderHud(
      List<SubmittedMaterials.Batch> batches, int width, int height, boolean clearColor) {
    var device = RenderSystem.getDevice();
    if (hudCamera == null)
      hudCamera =
          device.createBuffer(
              () -> "CraftQ3 HUD projection",
              GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
              96);
    writeCamera(
        hudCamera,
        new Matrix4f().ortho(0, width, height, 0, -1, 1, device.getDeviceInfo().isZZeroToOne()),
        null,
        width,
        height);
    var draws = hudBuffer.upload(batches);
    var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
    try (var pass =
        device
            .createCommandEncoder()
            .createRenderPass(
                () -> "CraftQ3 cgame HUD",
                target.getColorTextureView(),
                clearColor ? Optional.of(new Vector4f(.025f, .03f, .04f, 1)) : Optional.empty())) {
      for (var draw : draws) drawSubmitted(pass, draw, hudBuffer, hudCamera);
    }
  }

  private boolean visible(Draw draw, Set<Integer> visible) {
    if (lightDraw(draw) && lights.isEmpty()) return false;
    if (debugLines(draw)) return true;
    if (!draw.sky()) return visible.contains(draw.surface().id());
    for (int id : visible) {
      var material = materials.surfaces().get(id);
      if (material != null && material.name().equals(draw.material().name())) return true;
    }
    return false;
  }

  private static double distanceSquared(BspMap.Bounds bounds, Vec3 eye) {
    Vec3 center = bounds.min().add(bounds.max()).scale(0.5).add(eye.scale(-1));
    return center.x() * center.x() + center.y() * center.y() + center.z() * center.z();
  }

  private int addSky(RenderScene.Surface surface, ShaderDefinition material, int first) {
    var origin = new RenderScene.Camera(new Vec3(0, 0, 0), 0, 0, 90);
    var sky = material.sky().orElse(new Sky("-", 512, "-"));
    var box = SkyGeometry.box(origin, 32000);
    for (String boxName : List.of(sky.farBox())) {
      if (boxName.equals("-")) continue;
      for (var side : box.entrySet()) {
        var stage =
            new Stage(
                new TextureMap(List.of(boxName + "_" + side.getKey()), 0, true),
                Blend.OPAQUE,
                AlphaFunc.NONE,
                RgbGen.of(RgbGenType.IDENTITY),
                AlphaGen.of(AlphaGenType.IDENTITY),
                TcGen.of(TcGenType.BASE),
                List.of(),
                DepthFunc.LEQUAL,
                false,
                false);
        draws.add(
            new Draw(
                surface,
                material,
                stage,
                side.getValue(),
                first,
                side.getValue().size(),
                true,
                false,
                false,
                true));
        first += side.getValue().size();
      }
    }
    var clouds = SkyGeometry.clouds(origin, 31900, sky.cloudHeight() > 0 ? sky.cloudHeight() : 512);
    for (var stage : material.stages()) {
      var skyStage =
          new Stage(
              stage.texture(),
              stage.blend(),
              stage.alphaFunc(),
              stage.rgbGen(),
              stage.alphaGen(),
              stage.tcGen(),
              stage.tcMods(),
              DepthFunc.LEQUAL,
              false,
              stage.detail());
      draws.add(
          new Draw(
              surface, material, skyStage, clouds, first, clouds.size(), true, false, false, true));
      first += clouds.size();
    }
    // Q3 stores nearBox in the shader definition but its world sky pass does not draw it.
    return first;
  }

  private static double materialTime(ShaderDefinition material, double time) {
    return material.clampTime() > 0 ? Math.min(material.clampTime(), time) : time;
  }

  private void build(RenderScene scene) {
    if (vertices != null) {
      vertices.close();
      vertices = null;
    }
    draws.clear();
    geometryTruncated = false;
    int first = 0;
    var skies = new HashSet<String>();
    for (var surface : scene.surfaces()) {
      if (surface.vertexCount() == 0) continue;
      ShaderDefinition material =
          materials
              .surfaces()
              .getOrDefault(
                  surface.id(),
                  ShaderDefinition.implicit(surface.shaderName(), surface.lightmap() >= 0));
      if (mode == Mode.MATERIALS
          && (material.noDraw()
              || (scene.bsp().textures().get(surface.texture()).flags() & 0x80) != 0)) continue;
      if (mode == Mode.MATERIALS && material.skySurface()) {
        if (skies.add(material.name())) first = addSky(surface, material, first);
        continue;
      }
      int debugColor = mode == Mode.MATERIALS ? 0 : diagnosticColor(scene, surface);
      List<BspMap.Vertex> source = new ArrayList<>(surface.vertexCount());
      for (int i = 0; i < surface.vertexCount(); i++) {
        var v = scene.vertices().get(surface.firstVertex() + i);
        int color =
            mode == Mode.MATERIALS ? StageEvaluator.restoreMapLightColor(v.rgba(), 2) : debugColor;
        source.add(
            new BspMap.Vertex(v.position(), v.textureUv(), v.lightmapUv(), v.normal(), color));
      }
      List<Stage> stages = mode == Mode.MATERIALS ? material.stages() : List.of(DEBUG_STAGE);
      if (mode == Mode.MATERIALS && portals.containsKey(surface.id())) {
        stages = new ArrayList<>(stages);
        stages.addFirst(
            new Stage(
                new TextureMap(List.of("$portal"), 0, true),
                Blend.OPAQUE,
                AlphaFunc.NONE,
                RgbGen.of(RgbGenType.IDENTITY),
                AlphaGen.of(AlphaGenType.IDENTITY),
                TcGen.of(TcGenType.BASE),
                List.of(),
                DepthFunc.LEQUAL,
                true,
                false));
      }
      if (mode == Mode.LIGHTMAPS)
        stages =
            List.of(
                new Stage(
                    new TextureMap(List.of("$lightmap"), 0, true),
                    Blend.OPAQUE,
                    AlphaFunc.NONE,
                    RgbGen.of(RgbGenType.IDENTITY),
                    AlphaGen.of(AlphaGenType.IDENTITY),
                    TcGen.of(TcGenType.LIGHTMAP),
                    List.of(),
                    DepthFunc.LEQUAL,
                    true,
                    false));
      if (mode == Mode.PATCHES && surface.type() != 2) continue;
      boolean lines = mode == Mode.WIREFRAME;
      if (lines) source = wireframe(source);
      for (Stage stage : stages) {
        boolean dynamic =
            mode == Mode.MATERIALS
                && (StageEvaluator.isDynamic(stage)
                    || VertexDeformer.isDynamic(material)
                    || (fog != null && fog.count() > 0 && !opaqueStack(material)));
        Draw draw =
            new Draw(
                surface,
                material,
                stage,
                List.copyOf(source),
                first,
                source.size(),
                dynamic,
                false,
                lines,
                false);
        draws.add(draw);
        first = Math.addExact(first, source.size());
      }
      if (mode == Mode.MATERIALS
          && opaqueStack(material)
          && !material.surfaceParms().contains("nodlight")
          && (scene.bsp().textures().get(surface.texture()).flags() & 0x20000) == 0) {
        var stage =
            new Stage(
                new TextureMap(List.of("$dlight"), 0, true),
                new Blend(BlendFactor.DST_COLOR, BlendFactor.ONE),
                AlphaFunc.NONE,
                DEBUG_STAGE.rgbGen(),
                DEBUG_STAGE.alphaGen(),
                DEBUG_STAGE.tcGen(),
                List.of(),
                DepthFunc.EQUAL,
                false,
                false);
        draws.add(
            new Draw(
                surface,
                material,
                stage,
                List.copyOf(source),
                first,
                source.size(),
                true,
                false,
                false,
                false));
        first += source.size();
      }
      if (mode == Mode.MATERIALS
          && fog != null
          && fog.count() > 0
          && !material.skySurface()
          && !material.noDraw()
          && opaqueStack(material)) {
        draws.add(
            new Draw(
                surface,
                material,
                FOG_STAGE,
                List.copyOf(source),
                first,
                source.size(),
                true,
                true,
                false,
                false));
        first = Math.addExact(first, source.size());
      }
    }
    DebugGeometry.Mode lineMode =
        switch (mode) {
          case NODES -> DebugGeometry.Mode.NODES;
          case LEAVES -> DebugGeometry.Mode.LEAVES;
          case NORMALS -> DebugGeometry.Mode.NORMALS;
          case BRUSHES -> DebugGeometry.Mode.BRUSHES;
          default -> null;
        };
    if (lineMode != null) {
      var lines = DebugGeometry.build(scene, lineMode);
      geometryTruncated = lines.truncated();
      var source = new ArrayList<BspMap.Vertex>();
      for (var line : lines.lines()) {
        source.add(debugVertex(line.a(), line.rgba()));
        source.add(debugVertex(line.b(), line.rgba()));
      }
      if (!source.isEmpty() && !scene.surfaces().isEmpty()) {
        var surface = scene.surfaces().getFirst();
        var material = ShaderDefinition.implicit("debug/lines", false);
        var stage =
            new Stage(
                DEBUG_STAGE.texture(),
                Blend.OPAQUE,
                AlphaFunc.NONE,
                DEBUG_STAGE.rgbGen(),
                DEBUG_STAGE.alphaGen(),
                DEBUG_STAGE.tcGen(),
                List.of(),
                DepthFunc.DISABLE,
                false,
                false);
        draws.add(
            new Draw(
                surface, material, stage, source, first, source.size(), false, false, true, false));
        first += source.size();
      }
    }
    if ((long) first * STRIDE > 256L * 1024 * 1024)
      throw new IllegalArgumentException("Material vertex buffers exceed 256 MiB budget");
    // Materials keep their stage order. Transparent surfaces are subsequently sorted back to front.
    draws.sort(
        Comparator.comparingDouble((Draw d) -> d.material().sort())
            .thenComparingInt(d -> d.surface().id()));
    if (first > 0) {
      ByteBuffer data = MemoryUtil.memAlloc(first * STRIDE);
      try {
        for (Draw draw : draws) {
          data.position(draw.first() * STRIDE);
          writeDraw(data, draw, scene.camera(), seconds());
          pipeline(key(draw));
        }
        data.position(0).limit(first * STRIDE);
        bufferBytes = first * STRIDE;
        vertices =
            RenderSystem.getDevice()
                .createBuffer(
                    () -> "CraftQ3 stage vertices",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                    data);
      } finally {
        MemoryUtil.memFree(data);
      }
    }
    uploaded = scene;
    uploadedMode = mode;
    CraftQ3Client.LOGGER.info(
        "CraftQ3 {}: {} stage draws, {} vertices, backend={}",
        mode,
        draws.size(),
        first,
        RenderSystem.getDevice().getDeviceInfo().backendName());
  }

  private int diagnosticColor(RenderScene scene, RenderScene.Surface surface) {
    return switch (mode) {
      case LIGHTMAP_INDICES -> DebugGeometry.color(surface.lightmap());
      case PATCHES -> 0x65c5a8ff;
      case PVS -> DebugGeometry.color(visibility.clusterForSurface(surface.id()));
      case WIREFRAME -> 0x82e5bdff;
      default -> DebugGeometry.color(surface.id());
    };
  }

  private static List<BspMap.Vertex> wireframe(List<BspMap.Vertex> triangles) {
    var result = new ArrayList<BspMap.Vertex>(triangles.size() * 2);
    for (int i = 0; i < triangles.size(); i += 3) {
      result.add(triangles.get(i));
      result.add(triangles.get(i + 1));
      result.add(triangles.get(i + 1));
      result.add(triangles.get(i + 2));
      result.add(triangles.get(i + 2));
      result.add(triangles.get(i));
    }
    return result;
  }

  private static BspMap.Vertex debugVertex(Vec3 position, int rgba) {
    return new BspMap.Vertex(
        position, new BspMap.Uv(0, 0), new BspMap.Uv(0, 0), new Vec3(0, 0, 1), rgba);
  }

  private void writeDraw(ByteBuffer data, Draw draw, RenderScene.Camera view, double time) {
    writeDraw(data, draw, view, time, false);
  }

  private void writeDraw(
      ByteBuffer data, Draw draw, RenderScene.Camera view, double time, boolean mirrored) {
    List<BspMap.Vertex> source = draw.source();
    if (mode == Mode.MATERIALS && !draw.sky())
      source =
          portalBasis == null && viewBasis == null
              ? VertexDeformer.deform(source, draw.material(), time, view)
              : VertexDeformer.deform(
                  source,
                  draw.material(),
                  time,
                  view,
                  portalBasis == null ? viewBasis : portalBasis);
    var context = StageEvaluator.Context.defaults(view, materialTime(draw.material(), time));
    // Reverse Q3's opposite-cull materials explicitly so the GPU API only needs normal back
    // culling.
    boolean reverse =
        mode == Mode.MATERIALS
            && ((draw.material().cull() == Cull.FRONT) ^ mirrored)
            && !draw.lines()
            && !draw.sky();
    for (int i = 0; i < source.size(); i++) {
      int index = reverse ? i / 3 * 3 + (i % 3 == 0 ? 0 : 3 - i % 3) : i;
      var vertex = source.get(index);
      if (lightDraw(draw)) {
        var color = DynamicLighting.sample(lights, vertex);
        int rgba =
            (Math.clamp((int) (color.x() * 255), 0, 255) << 24)
                | (Math.clamp((int) (color.y() * 255), 0, 255) << 16)
                | (Math.clamp((int) (color.z() * 255), 0, 255) << 8)
                | 255;
        vertex =
            new BspMap.Vertex(
                vertex.position(), vertex.textureUv(), vertex.lightmapUv(), vertex.normal(), rgba);
      } else if (draw.fog()) {
        var sample = fog.sample(view.origin(), vertex.position());
        int rgba =
            (Math.clamp((int) (sample.color().x() * 255), 0, 255) << 24)
                | (Math.clamp((int) (sample.color().y() * 255), 0, 255) << 16)
                | (Math.clamp((int) (sample.color().z() * 255), 0, 255) << 8)
                | Math.clamp((int) (sample.opacity() * 255), 0, 255);
        vertex =
            new BspMap.Vertex(
                vertex.position(), vertex.textureUv(), vertex.lightmapUv(), vertex.normal(), rgba);
      } else {
        var vertexContext = context;
        if (lightGrid != null && draw.stage().rgbGen().type() == RgbGenType.LIGHTING_DIFFUSE) {
          var lighting = lightGrid.sample(vertex.position());
          vertexContext =
              new StageEvaluator.Context(
                  view,
                  context.seconds(),
                  context.entityColor(),
                  context.entityAlpha(),
                  lighting.direction(),
                  lighting.ambient(),
                  lighting.directed(),
                  context.fogColor(),
                  context.entityTexOffset(),
                  context.identityLight());
        }
        if (fog != null && draw.stage().rgbGen().type() == RgbGenType.FOG) {
          var sample = fog.sample(view.origin(), vertex.position());
          vertexContext =
              new StageEvaluator.Context(
                  view,
                  context.seconds(),
                  context.entityColor(),
                  context.entityAlpha(),
                  context.lightDirection(),
                  context.ambientLight(),
                  context.directedLight(),
                  sample.color(),
                  context.entityTexOffset(),
                  context.identityLight());
        }
        vertex = StageEvaluator.evaluate(vertex, draw.stage(), vertexContext);
      }
      if (draw.sky())
        vertex =
            new BspMap.Vertex(
                vertex.position().add(view.origin()),
                vertex.textureUv(),
                vertex.lightmapUv(),
                vertex.normal(),
                vertex.rgba());
      float fogAmount =
          fogAdjustment(draw) == 0 || fog == null
              ? 0
              : fog.sample(view.origin(), vertex.position()).opacity();
      put(data, vertex, fogAmount);
    }
  }

  private static void put(ByteBuffer data, BspMap.Vertex vertex, float fogAmount) {
    var p = vertex.position();
    data.putFloat((float) p.x()).putFloat((float) p.y()).putFloat((float) p.z());
    data.putFloat(vertex.textureUv().u()).putFloat(vertex.textureUv().v());
    int rgba = vertex.rgba();
    data.put((byte) (rgba >>> 24))
        .put((byte) (rgba >>> 16))
        .put((byte) (rgba >>> 8))
        .put((byte) rgba);
    data.putFloat(fogAmount);
  }

  private boolean samePortal(int a, int b) {
    if (a == b) return a >= 0;
    PortalView first = portals.get(a), second = portals.get(b);
    return first != null
        && second != null
        && first.sourcePlane().equals(second.sourcePlane())
        && first.sourceOrigin().equals(second.sourceOrigin())
        && first.destinationOrigin().equals(second.destinationOrigin())
        && first.mirrored() == second.mirrored();
  }

  private static boolean lightDraw(Draw draw) {
    return draw.stage().texture().frames().getFirst().equals("$dlight");
  }

  private static boolean portalDraw(Draw draw) {
    return draw.stage().texture().frames().getFirst().equals("$portal");
  }

  private void writeCamera(
      GpuBuffer buffer, Matrix4f matrix, PortalView.Plane clip, int width, int height) {
    ByteBuffer uniform = MemoryUtil.memAlloc(96);
    try {
      matrix.get(0, uniform);
      uniform.position(64);
      if (clip == null) uniform.putFloat(0).putFloat(0).putFloat(0).putFloat(1);
      else
        uniform
            .putFloat((float) clip.normal().x())
            .putFloat((float) clip.normal().y())
            .putFloat((float) clip.normal().z())
            .putFloat((float) -clip.distance());
      uniform.putFloat(width).putFloat(height).putFloat(0).putFloat(0).flip();
      RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), uniform);
    } finally {
      MemoryUtil.memFree(uniform);
    }
  }

  private int renderPortal(
      RenderScene scene, Set<Integer> visible, int width, int height, double time) {
    if (mode != Mode.MATERIALS || portals.isEmpty()) return -1;
    var selected =
        scene.surfaces().stream()
            .filter(surface -> visible.contains(surface.id()) && portals.containsKey(surface.id()))
            .filter(
                surface ->
                    portals.get(surface.id()).sourcePlane().signedDistance(scene.camera().origin())
                        > 0.1)
            .min(
                Comparator.comparingDouble(
                    surface -> distanceSquared(surface.bounds(), scene.camera().origin())));
    if (selected.isEmpty()) return -1;
    var surface = selected.orElseThrow();
    var portal = portals.get(surface.id());
    int w = Math.max(1, width / 2), h = Math.max(1, height / 2);
    var device = RenderSystem.getDevice();
    if (portalTexture == null || portalWidth != w || portalHeight != h) {
      closePortal();
      var color =
          device.createTexture(
              "CraftQ3 portal",
              GpuTexture.USAGE_RENDER_ATTACHMENT
                  | GpuTexture.USAGE_TEXTURE_BINDING
                  | GpuTexture.USAGE_COPY_DST,
              GpuFormat.RGBA8_UNORM,
              w,
              h,
              1,
              1);
      portalTexture = new Texture(color, device.createTextureView(color));
      portalDepth =
          device.createTexture(
              "CraftQ3 portal depth",
              GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST,
              GpuFormat.D32_FLOAT,
              w,
              h,
              1,
              1);
      portalDepthView = device.createTextureView(portalDepth);
      portalWidth = w;
      portalHeight = h;
      portalCamera =
          device.createBuffer(
              () -> "CraftQ3 portal camera",
              GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
              96);
      ByteBuffer config = MemoryUtil.memAlloc(16);
      try {
        config.putInt(0).putInt(1).putInt(0).putInt(0).flip();
        portalUniform =
            device.createBuffer(() -> "CraftQ3 portal projection", GpuBuffer.USAGE_UNIFORM, config);
      } finally {
        MemoryUtil.memFree(config);
      }
    }
    if (portalVertices == null || portalVertices.size() != bufferBytes) {
      if (portalVertices != null) portalVertices.close();
      portalVertices =
          device.createBuffer(
              () -> "CraftQ3 portal mesh",
              GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
              bufferBytes);
    }
    var view = portal.camera(scene.camera());
    var basis =
        submittedView == null
            ? portal.basis(scene.camera())
            : new PortalView.Basis(
                portal.transformDirection(submittedView.refdef().basis().forward()),
                portal.transformDirection(submittedView.refdef().basis().right()),
                portal.transformDirection(submittedView.refdef().basis().up()));
    portalBasis = basis;
    var eye = view.origin();
    var r = basis.right();
    var u = basis.up();
    var f = basis.forward();
    var viewMatrix =
        new Matrix4f()
            .m00((float) r.x())
            .m10((float) r.y())
            .m20((float) r.z())
            .m30((float) -dot(r, eye))
            .m01((float) u.x())
            .m11((float) u.y())
            .m21((float) u.z())
            .m31((float) -dot(u, eye))
            .m02((float) -f.x())
            .m12((float) -f.y())
            .m22((float) -f.z())
            .m32((float) dot(f, eye));
    float aspect = (float) width / height;
    float vfov =
        (float) (2 * Math.atan(Math.tan(Math.toRadians(view.horizontalFov()) / 2) / aspect));
    var projection =
        new Matrix4f()
            .perspective(vfov, aspect, 1, 131072, device.getDeviceInfo().isZZeroToOne())
            .mul(viewMatrix);
    CgameFrame.View remoteSubmission = null;
    if (submittedView != null) {
      var ref = submittedView.refdef();
      var remoteRef =
          new CgameFrame.Refdef(
              0,
              0,
              w,
              h,
              ref.fovX(),
              ref.fovY(),
              view.origin(),
              basis.forward(),
              basis.right().scale(-1),
              basis.up(),
              ref.timeMillis(),
              ref.rdflags(),
              ref.areaMask(),
              ref.text());
      remoteSubmission =
          new CgameFrame.View(
              remoteRef,
              submittedView.entities(),
              submittedView.polygons(),
              submittedView.lights());
      projection =
          SubmittedProjection.matrix(remoteRef, w, h, device.getDeviceInfo().isZZeroToOne());
    }
    writeCamera(portalCamera, projection, portal.destinationPlane(), w, h);
    // Mirror origins lie outside the map; conservative selection also retains remote geometry.
    var selection = visibility.select(view, aspect, 1, 131072, pvs, false);
    var remoteVisible = new HashSet<Integer>();
    for (var candidate : selection.surfaces()) remoteVisible.add(candidate.id());
    var remote =
        draws.stream()
            .filter(draw -> visible(draw, remoteVisible) && !portalDraw(draw))
            .sorted(
                Comparator.comparingDouble((Draw d) -> d.material().sort())
                    .thenComparingDouble(
                        d ->
                            d.material().sort() > 3 && !d.sky()
                                ? -distanceSquared(d.surface().bounds(), eye)
                                : 0)
                    .thenComparingInt(d -> d.surface().id())
                    .thenComparingInt(Draw::first))
            .toList();
    updateSkyMasks(portalCamera, remoteVisible, w, h);
    var encoder = device.createCommandEncoder();
    for (Draw draw : remote) {
      ByteBuffer data = MemoryUtil.memAlloc(draw.count() * STRIDE);
      try {
        writeDraw(
            data,
            draw,
            view,
            time,
            remoteSubmission == null ? portal.mirrored() : remoteSubmission.refdef().mirrored());
        data.flip();
        encoder.writeToBuffer(
            portalVertices.slice((long) draw.first() * STRIDE, data.remaining()), data);
      } finally {
        MemoryUtil.memFree(data);
      }
    }
    var remoteEntities =
        remoteSubmission == null
            ? List.<SubmittedGpuBuffer.Draw>of()
            : remoteSubmittedBuffer.upload(
                submittedMaterials.build(
                    scene, remoteSubmission, submittedAssets, lightGrid, fog, true));
    var remoteFrame = new ArrayList<FrameDraw>();
    for (var draw : remote) remoteFrame.add(new FrameDraw(draw, null));
    for (var draw : remoteEntities) remoteFrame.add(new FrameDraw(null, draw));
    remoteFrame.sort(
        Comparator.comparingDouble((FrameDraw d) -> d.material().sort())
            .thenComparingDouble(
                d -> d.material().sort() > 3 && !d.sky() ? -distanceSquared(d.bounds(), eye) : 0));
    encoder.clearDepthTexture(portalDepth, 1);
    try (var pass =
        encoder.createRenderPass(
            () -> "CraftQ3 portal view",
            portalTexture.view(),
            Optional.of(new Vector4f(.025f, .03f, .04f, 1)),
            portalDepthView,
            OptionalDouble.empty())) {
      for (FrameDraw item : remoteFrame) {
        if (item.submitted() != null) {
          drawSubmitted(pass, item.submitted(), remoteSubmittedBuffer, portalCamera);
          continue;
        }
        Draw draw = item.world();
        pass.setPipeline(pipeline(key(draw)));
        pass.setUniform("Q3Camera", portalCamera);
        pass.setUniform("Q3Stage", stageUniform(draw));
        pass.setVertexBuffer(0, portalVertices.slice());
        String name = draw.stage().texture().atTime(materialTime(draw.material(), time));
        if (name.equals("$dlight")) name = "$whiteimage";
        Texture texture =
            name.equals("$lightmap")
                    && draw.surface().lightmap() >= 0
                    && draw.surface().lightmap() < lightmaps.size()
                ? lightmaps.get(draw.surface().lightmap())
                : textures.getOrDefault(
                    name, textures.get(name.equals("$lightmap") ? "$whiteimage" : "$missing"));
        boolean clamped = draw.stage().texture().clamp() || name.startsWith("$");
        GpuSampler sampler =
            draw.material().noMipmaps() || name.startsWith("$")
                ? (clamped ? clamp : repeat)
                : (clamped ? clampMips : repeatMips);
        pass.bindTexture("StageTexture", texture.view(), sampler);
        pass.bindTexture("SkyMask", maskTexture(draw).view(), clamp);
        pass.draw(draw.count(), 1, draw.first(), 0);
      }
    }
    portalBasis = null;
    return surface.id();
  }

  private static double dot(Vec3 a, Vec3 b) {
    return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
  }

  private void closePortal() {
    if (portalTexture != null) portalTexture.close();
    if (portalDepthView != null) portalDepthView.close();
    if (portalDepth != null) portalDepth.close();
    if (portalVertices != null) portalVertices.close();
    if (portalCamera != null) portalCamera.close();
    if (portalUniform != null) portalUniform.close();
    portalTexture = null;
    portalDepthView = null;
    portalDepth = null;
    portalVertices = null;
    portalCamera = null;
    portalUniform = null;
  }

  private static boolean opaqueStack(ShaderDefinition material) {
    return !material.stages().isEmpty() && material.stages().getFirst().blend().opaque();
  }

  private int fogAdjustment(Draw draw) {
    if (mode != Mode.MATERIALS
        || draw.fog()
        || lightDraw(draw)
        || draw.sky()
        || opaqueStack(draw.material())
        || fog == null
        || fog.count() == 0) return 0;
    Blend b = draw.stage().blend();
    if (b.equals(Blend.ADD)
        || b.equals(new Blend(BlendFactor.ZERO, BlendFactor.ONE_MINUS_SRC_COLOR))) return 1;
    if (b.equals(Blend.ALPHA)) return 2;
    if (b.equals(new Blend(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA))) return 3;
    if (b.equals(Blend.FILTER) || b.equals(new Blend(BlendFactor.ZERO, BlendFactor.SRC_COLOR)))
      return 4;
    return 0;
  }

  private PipelineKey key(Draw draw) {
    return new PipelineKey(
        draw.stage().blend(),
        draw.stage().depthFunc(),
        draw.stage().depthWrite(),
        mode == Mode.MATERIALS && draw.material().cull() != Cull.NONE && !draw.sky(),
        mode == Mode.MATERIALS && draw.material().polygonOffset(),
        draw.lines(),
        minecraftProjection != null && portalBasis == null);
  }

  private static RenderPipeline pipeline(PipelineKey key) {
    return PIPELINES.computeIfAbsent(
        key,
        k -> {
          var color =
              k.blend().opaque()
                  ? ColorTargetState.DEFAULT
                  : new ColorTargetState(
                      new BlendFunction(
                          com.mojang.blaze3d.platform.BlendFactor.valueOf(
                              k.blend().source().name()),
                          com.mojang.blaze3d.platform.BlendFactor.valueOf(
                              k.blend().destination().name())));
          CompareOp depth =
              switch (k.depth()) {
                case LEQUAL ->
                    k.reversed() ? CompareOp.GREATER_THAN_OR_EQUAL : CompareOp.LESS_THAN_OR_EQUAL;
                case EQUAL -> CompareOp.EQUAL;
                case DISABLE -> CompareOp.ALWAYS_PASS;
              };
          return RenderPipelines.register(
              RenderPipeline.builder()
                  .withLocation(
                      Identifier.fromNamespaceAndPath(
                          "craftq3", "pipeline/material_" + PIPELINES.size()))
                  .withVertexShader(Identifier.fromNamespaceAndPath("craftq3", "core/material"))
                  .withFragmentShader(Identifier.fromNamespaceAndPath("craftq3", "core/material"))
                  .withVertexBinding(0, FORMAT)
                  .withPrimitiveTopology(
                      k.lines() ? PrimitiveTopology.DEBUG_LINES : PrimitiveTopology.TRIANGLES)
                  .withCull(k.cull())
                  .withColorTargetState(color)
                  .withDepthStencilState(
                      k.depth() == DepthFunc.DISABLE
                          ? Optional.empty()
                          : Optional.of(
                              new DepthStencilState(
                                  depth,
                                  k.write(),
                                  k.offset() ? (k.reversed() ? 1 : -1) : 0,
                                  k.offset() ? (k.reversed() ? 1 : -1) : 0)))
                  .withBindGroupLayout(LAYOUT)
                  .build());
        });
  }

  private void uploadTextures() {
    var device = RenderSystem.getDevice();
    repeat =
        device.createSampler(
            AddressMode.REPEAT,
            AddressMode.REPEAT,
            FilterMode.LINEAR,
            FilterMode.LINEAR,
            1,
            OptionalDouble.of(0));
    clamp =
        device.createSampler(
            AddressMode.CLAMP_TO_EDGE,
            AddressMode.CLAMP_TO_EDGE,
            FilterMode.LINEAR,
            FilterMode.LINEAR,
            1,
            OptionalDouble.of(0));
    repeatMips =
        device.createSampler(
            AddressMode.REPEAT,
            AddressMode.REPEAT,
            FilterMode.LINEAR,
            FilterMode.LINEAR,
            1,
            OptionalDouble.empty());
    clampMips =
        device.createSampler(
            AddressMode.CLAMP_TO_EDGE,
            AddressMode.CLAMP_TO_EDGE,
            FilterMode.LINEAR,
            FilterMode.LINEAR,
            1,
            OptionalDouble.empty());
    for (var entry : materials.images().entrySet())
      textures.put(entry.getKey(), uploadImage(entry.getKey(), entry.getValue()));
    for (var lightmap : materials.lightmaps())
      lightmaps.add(uploadImage("$lightmap" + lightmaps.size(), lightmap));
    for (AlphaFunc alpha : AlphaFunc.values())
      for (int adjustment = 0; adjustment < 5; adjustment++) {
        ByteBuffer data = MemoryUtil.memAlloc(16);
        try {
          data.putInt(alpha.ordinal()).putInt(0).putInt(adjustment).putInt(1).flip();
          maskedUniforms[alpha.ordinal() * 5 + adjustment] =
              device.createBuffer(() -> "CraftQ3 masked sky stage", GpuBuffer.USAGE_UNIFORM, data);
        } finally {
          MemoryUtil.memFree(data);
        }
      }
    for (AlphaFunc alpha : AlphaFunc.values())
      for (int adjustment = 0; adjustment < 5; adjustment++) {
        ByteBuffer data = MemoryUtil.memAlloc(16);
        try {
          data.putInt(alpha.ordinal()).putInt(0).putInt(adjustment).putInt(0).flip();
          alphaUniforms[alpha.ordinal() * 5 + adjustment] =
              device.createBuffer(
                  () -> "CraftQ3 alpha test " + alpha, GpuBuffer.USAGE_UNIFORM, data);
        } finally {
          MemoryUtil.memFree(data);
        }
      }
  }

  private GpuBuffer stageUniform(Draw draw) {
    int index = draw.stage().alphaFunc().ordinal() * 5 + fogAdjustment(draw);
    return draw.sky() ? maskedUniforms[index] : alphaUniforms[index];
  }

  private Texture maskTexture(Draw draw) {
    var mask = skyMasks.get(draw.material().name());
    Texture target = mask == null ? null : portalBasis == null ? mask.target : mask.remoteTarget;
    return draw.sky() && target != null ? target : textures.get("$whiteimage");
  }

  private void buildSkyMasks(RenderScene scene) {
    int count = 0;
    for (var surface : scene.surfaces()) {
      var material = materials.surfaces().get(surface.id());
      if (material == null || !material.skySurface() || surface.vertexCount() == 0) continue;
      var mask = skyMasks.computeIfAbsent(material.name(), ignored -> new SkyMask());
      mask.ranges.add(new MaskRange(surface.id(), count, surface.vertexCount()));
      count += surface.vertexCount();
    }
    if (skyMasks.size() > 32)
      throw new IllegalArgumentException("Map exceeds 32 distinct sky materials");
    if (count == 0) return;
    ByteBuffer data = MemoryUtil.memAlloc(count * STRIDE);
    try {
      for (var mask : skyMasks.values())
        for (var range : mask.ranges) {
          var surface =
              scene.surfaces().stream()
                  .filter(surf -> surf.id() == range.surface())
                  .findFirst()
                  .orElseThrow();
          data.position(range.first() * STRIDE);
          for (int i = 0; i < range.count(); i++) {
            var vertex = scene.vertices().get(surface.firstVertex() + i);
            put(
                data,
                new BspMap.Vertex(
                    vertex.position(),
                    vertex.textureUv(),
                    vertex.lightmapUv(),
                    vertex.normal(),
                    -1),
                0);
          }
        }
      data.position(0).limit(count * STRIDE);
      skyMaskVertices =
          RenderSystem.getDevice()
              .createBuffer(() -> "CraftQ3 sky masks", GpuBuffer.USAGE_VERTEX, data);
    } finally {
      MemoryUtil.memFree(data);
    }
  }

  private void updateSkyMasks(GpuBuffer viewUniform, Set<Integer> visible, int width, int height) {
    if (mode != Mode.MATERIALS || skyMasks.isEmpty()) return;
    var device = RenderSystem.getDevice();
    boolean remote = viewUniform == portalCamera;
    for (var mask : skyMasks.values()) {
      Texture target = remote ? mask.remoteTarget : mask.target;
      if (target != null
          && (target.image().getWidth(0) != width || target.image().getHeight(0) != height)) {
        target.close();
        target = null;
      }
      if (target == null) {
        var texture =
            device.createTexture(
                "CraftQ3 sky coverage",
                GpuTexture.USAGE_RENDER_ATTACHMENT
                    | GpuTexture.USAGE_TEXTURE_BINDING
                    | GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM,
                width,
                height,
                1,
                1);
        target = new Texture(texture, device.createTextureView(texture));
        if (remote) mask.remoteTarget = target;
        else mask.target = target;
      }
      var encoder = device.createCommandEncoder();
      try (var pass =
          encoder.createRenderPass(
              () -> "CraftQ3 sky coverage", target.view(), Optional.of(new Vector4f(0, 0, 0, 1)))) {
        pass.setPipeline(
            pipeline(new PipelineKey(Blend.OPAQUE, DepthFunc.DISABLE, false, false, false, false)));
        pass.setUniform("Q3Camera", viewUniform);
        pass.setUniform("Q3Stage", alphaUniforms[0]);
        pass.bindTexture("StageTexture", textures.get("$whiteimage").view(), clamp);
        pass.bindTexture("SkyMask", textures.get("$whiteimage").view(), clamp);
        pass.setVertexBuffer(0, skyMaskVertices.slice());
        for (var range : mask.ranges)
          if (visible.contains(range.surface())) pass.draw(range.count(), 1, range.first(), 0);
      }
    }
  }

  private static Texture uploadImage(String name, Q3Image image) {
    var device = RenderSystem.getDevice();
    var chain = name.startsWith("$") ? List.of(image) : Mipmaps.generate(image);
    // Blaze3D 26.2 reports mip extents by shifting without clamping to one; stop before
    // either dimension would become zero, even though GPU APIs permit a longer narrow tail.
    int validLevels = 32 - Integer.numberOfLeadingZeros(Math.min(image.width(), image.height()));
    var levels = chain.subList(0, Math.min(chain.size(), validLevels));
    GpuTexture texture =
        device.createTexture(
            "CraftQ3 " + name,
            GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
            GpuFormat.RGBA8_UNORM,
            image.width(),
            image.height(),
            1,
            levels.size());
    try {
      for (int level = 0; level < levels.size(); level++) {
        var mip = levels.get(level);
        try (var nativeImage =
            new com.mojang.blaze3d.platform.NativeImage(mip.width(), mip.height(), false)) {
          nativeImage.getPixelBytes().put(mip.rgba());
          device.createCommandEncoder().writeToTexture(texture, nativeImage, level, 0, 0, 0);
        }
      }
      return new Texture(texture, device.createTextureView(texture));
    } catch (RuntimeException e) {
      texture.close();
      throw e;
    }
  }

  static Matrix4f matrix(RenderScene.Camera view, int width, int height) {
    Vec3 eye = view.origin();
    double yaw = Math.toRadians(view.yaw()), pitch = Math.toRadians(view.pitch());
    float fx = (float) (Math.cos(yaw) * Math.cos(pitch)),
        fy = (float) (Math.sin(yaw) * Math.cos(pitch)),
        fz = (float) -Math.sin(pitch);
    float aspect = (float) width / height;
    float vfov =
        (float) (2 * Math.atan(Math.tan(Math.toRadians(view.horizontalFov()) / 2) / aspect));
    return new Matrix4f()
        .perspective(
            vfov, aspect, 1, 131072, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne())
        .lookAt(
            (float) eye.x(),
            (float) eye.y(),
            (float) eye.z(),
            (float) eye.x() + fx,
            (float) eye.y() + fy,
            (float) eye.z() + fz,
            0,
            0,
            1);
  }

  private void ensureDepth(int width, int height) {
    if (depthTexture != null && width == depthWidth && height == depthHeight) return;
    closeDepth();
    var device = RenderSystem.getDevice();
    depthTexture =
        device.createTexture(
            "CraftQ3 depth",
            GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST,
            GpuFormat.D32_FLOAT,
            width,
            height,
            1,
            1);
    depthView = device.createTextureView(depthTexture);
    depthWidth = width;
    depthHeight = height;
  }

  private void closeDepth() {
    if (depthView != null) depthView.close();
    if (depthTexture != null) depthTexture.close();
    depthView = null;
    depthTexture = null;
  }

  @Override
  public void close() {
    retainMovies(java.util.Set.of());
    submittedBuffer.close();
    hudBuffer.close();
    remoteSubmittedBuffer.close();
    if (depthHackCamera != null) depthHackCamera.close();
    if (hudCamera != null) hudCamera.close();
    depthHackCamera = null;
    hudCamera = null;
    submittedImages.clear();
    submissionWarnings.clear();
    surfaceAreas.clear();
    visibility = null;
    portals.clear();
    closeDepth();
    closePortal();
    skyMasks.values().forEach(SkyMask::close);
    skyMasks.clear();
    if (skyMaskVertices != null) skyMaskVertices.close();
    skyMaskVertices = null;
    for (int i = 0; i < maskedUniforms.length; i++) {
      if (maskedUniforms[i] != null) maskedUniforms[i].close();
      maskedUniforms[i] = null;
    }
    if (vertices != null) vertices.close();
    if (camera != null) camera.close();
    textures.values().forEach(Texture::close);
    textures.clear();
    lightmaps.forEach(Texture::close);
    lightmaps.clear();
    for (int i = 0; i < alphaUniforms.length; i++) {
      if (alphaUniforms[i] != null) alphaUniforms[i].close();
      alphaUniforms[i] = null;
    }
    if (repeat != null) repeat.close();
    if (clamp != null) clamp.close();
    if (repeatMips != null) repeatMips.close();
    if (clampMips != null) clampMips.close();
    repeatMips = null;
    clampMips = null;
    repeat = null;
    clamp = null;
    vertices = null;
    camera = null;
    uploaded = null;
  }
}
