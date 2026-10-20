package dev.bluevista.craftq3.engine.client.render;

import dev.bluevista.craftq3.engine.client.RenderSink;
import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.md3.Md3Animation;
import dev.bluevista.craftq3.engine.formats.md3.Md3Model;
import dev.bluevista.craftq3.engine.render.AreaMask;
import dev.bluevista.craftq3.engine.render.DynamicLight;
import dev.bluevista.craftq3.engine.render.LightGrid;
import dev.bluevista.craftq3.engine.render.frame.CgameFrame;
import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.layout.OrientationLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.PolyVertLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.RefEntityLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.RefdefLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.VmStructs;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The renderer as the cgame and ui see it: one frame of renderer traps recorded by value into a
 * {@link CgameFrame}, in submission order, and handed to the {@link RenderSink} at {@link
 * #endFrame}. Both VMs draw into the same frame (cgame first, then ui, then the console), as they
 * share the original renderer.
 *
 * <p>The original renderer accepts whatever the guest submits. Values the frame types reject are
 * clamped (blend fractions, radii, field of view) and submissions beyond the frame budgets are
 * dropped with a logged warning, so no renderer trap degrades. Single owner thread.
 */
public final class ClientRenderer {
  private static final Log LOG = Log.get(ClientRenderer.class);
  private static final Vec3f X = new Vec3f(1, 0, 0), Y = new Vec3f(0, 1, 0), Z = new Vec3f(0, 0, 1);

  private final ClientAssets assets;
  private final RenderSink sink;
  private final List<CgameFrame.Command> commands = new ArrayList<>();
  private final List<CgameFrame.RefEntity> entities = new ArrayList<>();
  private final List<CgameFrame.Poly> polygons = new ArrayList<>();
  private final List<DynamicLight> lights = new ArrayList<>();
  private long polyVertices;
  private int color = -1;
  private int time;
  private int width = 640, height = 480;
  private long framesSubmitted;
  private LightGrid lightGrid = LightGrid.empty();

  public ClientRenderer(ClientAssets assets, RenderSink sink) {
    this.assets = Objects.requireNonNull(assets);
    this.sink = Objects.requireNonNull(sink);
  }

  public ClientAssets assets() {
    return assets;
  }

  /** Sets the video size reported by {@code glconfig_t} and used for cinematic extents. */
  public void setVideoSize(int newWidth, int newHeight) {
    if (newWidth < 1 || newHeight < 1) throw new IllegalArgumentException("Invalid video size");
    width = newWidth;
    height = newHeight;
  }

  public int width() {
    return width;
  }

  public int height() {
    return height;
  }

  /** Frames handed to the sink so far. */
  public long framesSubmitted() {
    return framesSubmitted;
  }

  /** {@code RE_LoadWorldMap}. */
  public void loadWorld(String name, BspMap map) {
    assets.loadWorld(name, map);
    lightGrid = map == null ? LightGrid.empty() : LightGrid.of(map);
  }

  // ---------------------------------------------------------------- frame lifecycle

  /** Starts a screen update at {@code milliseconds}, discarding anything not ended. */
  public void beginFrame(int milliseconds) {
    time = milliseconds;
    commands.clear();
    clearScene();
    color = -1;
  }

  /** Ends the screen update, sending its frame to the sink (also when empty). */
  public CgameFrame endFrame() {
    CgameFrame frame = new CgameFrame(commands, assets.snapshot(), time);
    commands.clear();
    clearScene();
    framesSubmitted++;
    sink.submit(frame);
    return frame;
  }

  /**
   * {@code trap_UpdateScreen} during a long VM call (loading): presents what has been drawn so far
   * and starts a fresh frame.
   */
  public void updateScreen() {
    if (!commands.isEmpty()) endFrame();
  }

  /** Commands recorded in the current frame. */
  public List<CgameFrame.Command> pendingCommands() {
    return List.copyOf(commands);
  }

  /** Adds a cinematic frame to the current frame. */
  public void image(CgameFrame.Image image) {
    if (budget()) commands.add(image);
  }

  // ---------------------------------------------------------------- scene traps

  /** {@code trap_R_ClearScene}. */
  public void clearScene() {
    entities.clear();
    polygons.clear();
    lights.clear();
    polyVertices = 0;
  }

  /** {@code trap_R_AddRefEntityToScene(refEntity_t *)}. */
  public void addRefEntity(QvmMemory memory, int address) {
    if (entities.size() >= CgameFrame.View.MAX_ENTITIES) {
      LOG.warnOnce(
          "entities", "More than " + CgameFrame.View.MAX_ENTITIES + " entities in a scene");
      return;
    }
    RefEntityLayout.RefEntity e = RefEntityLayout.read(memory, address);
    if (e.reType() < 0 || e.reType() >= CgameFrame.EntityType.values().length) {
      LOG.warnOnce("entity-type", "Ignored refEntity of type " + e.reType());
      return;
    }
    ClientAssets.Model model = assets.model(e.hModel());
    Vec3f[] axis = e.axis();
    try {
      entities.add(
          new CgameFrame.RefEntity(
              CgameFrame.EntityType.values()[e.reType()],
              e.renderfx(),
              model.md3(),
              model.inline(),
              new Md3Model.Tag("entity", finite(e.origin()), axis[0], axis[1], axis[2]),
              finite(e.oldOrigin()),
              e.frame(),
              e.oldFrame(),
              clamp(e.backlerp(), 0, 1),
              finite(e.lightingOrigin()),
              finite(e.shadowPlane()),
              e.skinNum(),
              assets.skin(e.customSkin()),
              e.customShader() == 0
                  ? Optional.empty()
                  : Optional.of(assets.shaderName(e.customShader())),
              Integer.reverseBytes(e.shaderRgba()),
              finite(e.shaderS()),
              finite(e.shaderT()),
              finite(e.shaderTime()),
              clamp(e.radius(), 0, CgameFrame.RefEntity.MAX_RADIUS),
              finite(e.rotation())));
    } catch (IllegalArgumentException invalid) {
      LOG.warnOnce("entity", "Ignored invalid refEntity: " + invalid.getMessage());
    }
  }

  /**
   * {@code trap_R_AddPolyToScene(shader, numVerts, verts)} and, with {@code count > 1}, {@code
   * trap_R_AddPolysToScene(shader, numVerts, verts, numPolys)}.
   */
  public void addPolys(QvmMemory memory, int shader, int vertexCount, int address, int count) {
    if (vertexCount < 3 || vertexCount > CgameFrame.Poly.MAX_VERTICES || count < 1) return;
    memory.resolve(
        address, Math.multiplyExact(Math.multiplyExact(vertexCount, count), PolyVertLayout.BYTES));
    String name = assets.shaderName(shader);
    for (int p = 0; p < count; p++) {
      if (polygons.size() >= CgameFrame.View.MAX_POLYS
          || polyVertices + vertexCount > CgameFrame.View.MAX_POLY_VERTICES) {
        LOG.warnOnce("polys", "Scene polygon budget exceeded; polygons dropped");
        return;
      }
      float[] positions = new float[vertexCount * 3];
      float[] texCoords = new float[vertexCount * 2];
      int[] colors = new int[vertexCount];
      int base = address + p * vertexCount * PolyVertLayout.BYTES;
      for (int v = 0; v < vertexCount; v++) {
        int at = base + v * PolyVertLayout.BYTES;
        positions[v * 3] = finite(memory.readFloat(at));
        positions[v * 3 + 1] = finite(memory.readFloat(at + 4));
        positions[v * 3 + 2] = finite(memory.readFloat(at + 8));
        texCoords[v * 2] = finite(memory.readFloat(at + PolyVertLayout.ST));
        texCoords[v * 2 + 1] = finite(memory.readFloat(at + PolyVertLayout.ST + 4));
        colors[v] = Integer.reverseBytes(memory.readInt(at + PolyVertLayout.MODULATE));
      }
      polygons.add(new CgameFrame.Poly(name, positions, texCoords, colors));
      polyVertices += vertexCount;
    }
  }

  /** {@code trap_R_AddLightToScene(org, intensity, r, g, b)} (and the additive variant). */
  public void addLight(QvmMemory memory, int origin, float intensity, float r, float g, float b) {
    if (lights.size() >= CgameFrame.View.MAX_LIGHTS || !(intensity > 0)) return;
    Vec3f position = VmStructs.readVec3(memory, origin);
    if (!position.isFinite()) return;
    lights.add(
        new DynamicLight(
            position, intensity, new Vec3f(clamp(r, 0, 1), clamp(g, 0, 1), clamp(b, 0, 1))));
  }

  /** {@code trap_R_RenderScene(refdef_t *)}. */
  public void renderScene(QvmMemory memory, int address) {
    if (!budget()) return;
    long views = commands.stream().filter(CgameFrame.View.class::isInstance).count();
    if (views >= CgameFrame.MAX_VIEWS) {
      LOG.warnOnce("views", "More than " + CgameFrame.MAX_VIEWS + " scenes in one frame");
      return;
    }
    RefdefLayout.Refdef r = RefdefLayout.read(memory, address);
    List<String> text = new ArrayList<>();
    for (String line : r.text()) if (!line.isEmpty()) text.add(line);
    try {
      CgameFrame.Refdef refdef =
          new CgameFrame.Refdef(
              r.x(),
              r.y(),
              Math.clamp(r.width(), 1, CgameFrame.Refdef.MAX_EXTENT),
              Math.clamp(r.height(), 1, CgameFrame.Refdef.MAX_EXTENT),
              clamp(r.fovX(), 1.01f, 178.99f),
              clamp(r.fovY(), 1.01f, 178.99f),
              finite(r.viewOrigin()),
              r.viewAxis()[0],
              r.viewAxis()[1],
              r.viewAxis()[2],
              r.time(),
              r.rdFlags(),
              AreaMask.of(r.areamask()),
              text);
      commands.add(new CgameFrame.View(refdef, entities, polygons, lights));
    } catch (IllegalArgumentException invalid) {
      LOG.warnOnce("refdef", "Ignored invalid scene: " + invalid.getMessage());
    }
  }

  // ---------------------------------------------------------------- 2D traps

  /** {@code trap_R_SetColor(float rgba[4])}; null resets to white. */
  public void setColor(QvmMemory memory, int address) {
    if (address == 0) {
      color = -1;
      return;
    }
    int packed = 0;
    for (int i = 0; i < 4; i++) {
      float value = memory.readFloat(address + i * 4);
      packed = (packed << 8) | Math.round(clamp(Float.isFinite(value) ? value : 1, 0, 1) * 255);
    }
    color = packed;
  }

  /** Sets the colour for following stretch pics ({@code 0xRRGGBBAA}; -1 is white). */
  public void setColor(int rgba) {
    color = rgba;
  }

  /** The current colour as {@code 0xRRGGBBAA}. */
  public int color() {
    return color;
  }

  /** {@code trap_R_DrawStretchPic(x, y, w, h, s1, t1, s2, t2, shader)}. */
  public void drawStretchPic(
      float x, float y, float w, float h, float s1, float t1, float s2, float t2, int shader) {
    if (!budget()) return;
    try {
      commands.add(
          new CgameFrame.Quad(x, y, w, h, s1, t1, s2, t2, assets.shaderName(shader), color));
    } catch (IllegalArgumentException invalid) {
      LOG.warnOnce("quad", "Ignored invalid stretch pic: " + invalid.getMessage());
    }
  }

  private boolean budget() {
    if (commands.size() < CgameFrame.MAX_COMMANDS) return true;
    LOG.warnOnce("commands", "Frame command budget exceeded; draws dropped");
    return false;
  }

  // ---------------------------------------------------------------- queries

  /** {@code trap_R_ModelBounds(model, mins, maxs)}. */
  public void modelBounds(QvmMemory memory, int handle, int minsAddress, int maxsAddress) {
    ClientAssets.Model model = assets.model(handle);
    Vec3f mins = Vec3f.ZERO, maxs = Vec3f.ZERO;
    if (model.inline() >= 0 && assets.world().isPresent()) {
      BspMap.Model inline = assets.world().get().models().get(model.inline());
      mins = inline.mins();
      maxs = inline.maxs();
    } else if (model.md3() != null && model.md3().frameCount() > 0) {
      Md3Model.Frame frame = model.md3().frames().getFirst();
      mins = frame.mins();
      maxs = frame.maxs();
    }
    VmStructs.writeVec3(memory, minsAddress, mins);
    VmStructs.writeVec3(memory, maxsAddress, maxs);
  }

  /**
   * {@code trap_R_LerpTag(orientation_t *, model, startFrame, endFrame, frac, tagName)}: 1 when the
   * tag exists; otherwise an identity orientation and 0.
   */
  public int lerpTag(
      QvmMemory memory, int address, int handle, int from, int to, float fraction, String tag) {
    Md3Model md3 = assets.model(handle).md3();
    Optional<Md3Model.Tag> value = Optional.empty();
    if (md3 != null && md3.frameCount() > 0) {
      int last = md3.frameCount() - 1;
      value =
          Md3Animation.interpolateTag(
              md3,
              tag,
              Math.clamp(from, 0, last),
              Math.clamp(to, 0, last),
              clamp(Float.isFinite(fraction) ? fraction : 0, 0, 1));
    }
    if (value.isEmpty()) {
      OrientationLayout.write(memory, address, Vec3f.ZERO, new Vec3f[] {X, Y, Z});
      return 0;
    }
    Md3Model.Tag t = value.get();
    OrientationLayout.write(
        memory, address, t.origin(), new Vec3f[] {t.axisX(), t.axisY(), t.axisZ()});
    return 1;
  }

  /**
   * {@code trap_R_LightForPoint(point, ambient, directed, dir)}: the light grid at {@code point}
   * (0..255 colours); 0 when the world has no grid.
   */
  public int lightForPoint(QvmMemory memory, int point, int ambient, int directed, int direction) {
    if (!lightGrid.available()) return 0;
    LightGrid.Sample sample = lightGrid.sample(VmStructs.readVec3(memory, point));
    VmStructs.writeVec3(memory, ambient, sample.ambient().scale(255));
    VmStructs.writeVec3(memory, directed, sample.directed().scale(255));
    VmStructs.writeVec3(memory, direction, sample.direction());
    return 1;
  }

  /** {@code trap_R_inPVS(p1, p2)}: whether the clusters of both points see each other. */
  public boolean inPvs(Vec3f a, Vec3f b) {
    Optional<BspMap> world = assets.world();
    if (world.isEmpty()) return true;
    BspMap map = world.get();
    int leafA = leaf(map, a), leafB = leaf(map, b);
    if (leafA < 0 || leafB < 0) return false;
    int clusterA = map.leaves().cluster(leafA), clusterB = map.leaves().cluster(leafB);
    if (clusterA < 0 || clusterB < 0) return false;
    BspMap.Visibility vis = map.visibility();
    if (vis.absent()) return true;
    return vis.visible(clusterA, clusterB);
  }

  private static int leaf(BspMap map, Vec3f p) {
    BspMap.Leaves leaves = map.leaves();
    BspMap.Nodes nodes = map.nodes();
    BspMap.Planes planes = map.planes();
    if (leaves.count() == 0) return -1;
    if (nodes.count() == 0) return 0;
    int index = 0;
    for (int visited = 0; index >= 0 && visited <= nodes.count(); visited++) {
      if (index >= nodes.count()) return -1;
      int plane = nodes.plane(index);
      double distance =
          (double) p.x() * planes.normal(plane, 0)
              + (double) p.y() * planes.normal(plane, 1)
              + (double) p.z() * planes.normal(plane, 2)
              - planes.distance(plane);
      index = distance >= 0 ? nodes.front(index) : nodes.back(index);
    }
    int leaf = -1 - index;
    return leaf >= 0 && leaf < leaves.count() ? leaf : -1;
  }

  private static float clamp(float value, float low, float high) {
    if (!(value >= low)) return low;
    return Math.min(value, high);
  }

  private static float finite(float value) {
    return Float.isFinite(value) ? value : 0;
  }

  private static Vec3f finite(Vec3f v) {
    return v.isFinite() ? v : new Vec3f(finite(v.x()), finite(v.y()), finite(v.z()));
  }
}
