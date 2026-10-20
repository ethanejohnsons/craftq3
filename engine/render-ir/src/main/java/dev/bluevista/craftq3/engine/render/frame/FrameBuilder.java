package dev.bluevista.craftq3.engine.render.frame;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.md3.Md3Model;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition;
import dev.bluevista.craftq3.engine.render.DynamicLight;
import dev.bluevista.craftq3.engine.render.LightGrid;
import dev.bluevista.craftq3.engine.render.MeshBuffer;
import dev.bluevista.craftq3.engine.render.RenderScene;
import dev.bluevista.craftq3.engine.render.ViewBasis;
import dev.bluevista.craftq3.engine.render.ViewDefinition;
import dev.bluevista.craftq3.engine.render.VisibleSet;
import dev.bluevista.craftq3.engine.render.WorldGeometry;
import dev.bluevista.craftq3.engine.render.material.DeformProgram;
import dev.bluevista.craftq3.engine.render.material.MaterialProgram;
import dev.bluevista.craftq3.engine.render.material.MaterialRegistry;
import dev.bluevista.craftq3.engine.render.material.ShadingInputs;
import dev.bluevista.craftq3.engine.render.material.TextureBundle;
import dev.bluevista.craftq3.engine.render.material.VertexDeformer;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Turns one captured cgame scene into {@link FrameGeometry}: MD3 models (and their projected
 * shadows), sprites, beams, rail cores and rings, lightning bolts, polys, inline-model instances
 * and the visible world surfaces whose material deforms on the CPU. Submitted geometry has its own
 * identity and never enters the static world batches or PVS. Owned by the render thread; reuse one
 * builder and one {@link FrameGeometry} per view so steady state frames allocate only per-draw
 * records.
 *
 * <p>Entity geometry follows the original renderer's surface shapes:
 *
 * <ul>
 *   <li>{@code RT_RAIL_CORE}: one camera-facing quad from {@code oldorigin} to {@code origin},
 *       {@link Options#railCoreWidth} to each side, its texture running along the length (one
 *       repeat per 256 units) and across the width; the corner at the start's first side is dimmed
 *       to a quarter of the entity colour.
 *   <li>{@code RT_LIGHTNING}: four such quads from {@code origin} to {@code oldorigin}, 8 units to
 *       each side, the first facing the camera and each next one turned 45° about the bolt.
 *   <li>{@code RT_RAIL_RINGS}: square discs across the beam every {@link Options#railSegmentLength}
 *       units, corners a quarter of {@link Options#railWidth} from the axis; long beams skip the
 *       first and last segment.
 *   <li>{@code RT_BEAM}: a six-sided tube of radius 4 drawn additively in red, ignoring the entity
 *       shader's stages (they only order it).
 *   <li>{@code RT_SPRITE}: a camera-aligned quad of the entity radius, rotated by {@code rotation}
 *       and flipped in mirrors.
 * </ul>
 *
 * Render flags: {@code RF_FIRST_PERSON} entities are skipped in portal and mirror views; {@code
 * RF_THIRD_PERSON} sprites and beams are skipped in the main view, and third-person MD3 models then
 * only cast their projected shadow (brush models ignore the flag, as in Q3); {@code RF_DEPTHHACK}
 * marks the draw for the weapon depth range; {@code RF_LIGHTING_ORIGIN} moves the lighting sample;
 * {@code RF_SHADOW_PLANE} adds the flattened shadow; {@code RF_WRAP_FRAMES} wraps MD3 frames.
 * {@code RF_NOSHADOW} only affects stencil shadows, which this renderer does not draw.
 */
public final class FrameBuilder {
  private static final Log LOG = Log.get(FrameBuilder.class);

  /** Units of beam length per texture repeat along rail cores and lightning bolts. */
  private static final float BEAM_TEXTURE_LENGTH = 256;

  /** The dimmed corner of a rail core or lightning quad. */
  private static final float BEAM_START_DIM = 0.25f;

  private static final float LIGHTNING_WIDTH = 8;
  private static final int LIGHTNING_QUADS = 4;
  private static final double LIGHTNING_TURN = Math.toRadians(45);

  /** Disc corners sit this fraction of {@link Options#railWidth} from the axis. */
  private static final float RING_SCALE = 0.25f;

  private static final int MAX_RINGS = 1024;

  private static final float BEAM_RADIUS = 4;
  private static final int BEAM_SEGMENTS = 6;
  private static final int BEAM_RED = 0xff0000ff;

  /** Q3's sort for the additive beam when the entity names no shader ({@code SS_BLEND0}). */
  private static final float BLEND_SORT = 9;

  /** Q3's opaque sort; only opaque model surfaces cast projected shadows. */
  private static final float OPAQUE_SORT = 3;

  /** The projected shadow material ({@code tr.projectionShadowShader}). */
  public static final String PROJECTION_SHADOW_SHADER = "projectionShadow";

  // Entity lighting. Light values are in the renderer's display units (1 = full intensity after
  // the overbright gain); the original works in bytes before a hardware gain of 2.
  private static final float DISPLAY_GAIN = 2 / 255f;

  /** Ambient and directed light of entities without a light grid ({@code identityLight * 150}). */
  private static final float NO_GRID_LIGHT = 150 / 255f;

  /** The minimum ambient light every entity gets ({@code identityLight * 32}). */
  private static final float MIN_AMBIENT = 32 / 255f;

  /** The light direction without a grid: the default sun of a loaded world. */
  private static final Vec3f DEFAULT_SUN = new Vec3f(0.45f, 0.3f, 0.9f).normalize();

  /** {@code DLIGHT_AT_RADIUS}: a dynamic light's intensity at its radius, in bytes. */
  private static final float DLIGHT_AT_RADIUS = 16;

  /** {@code DLIGHT_MINIMUM_RADIUS}: closer lights count as this far away. */
  private static final float DLIGHT_MINIMUM_RADIUS = 16;

  /** The steepest light direction a projected shadow uses (cosine against the vertical). */
  private static final float SHADOW_MIN_ELEVATION = 0.5f;

  private static final Vec3f UP = new Vec3f(0, 0, 1);

  /**
   * @param expandInlineModels also copy inline BSP models into the frame mesh (CPU fallback);
   *     otherwise they are only listed as {@link FrameGeometry.InlineInstance}s
   * @param railWidth {@code r_railWidth}: rail ring size
   * @param railCoreWidth {@code r_railCoreWidth}: rail core half-width
   * @param railSegmentLength {@code r_railSegmentLength}: spacing of rail rings
   * @param projectionShadows draw {@code RF_SHADOW_PLANE} shadows; the cgame only sets the flag for
   *     {@code cg_shadows 3}, which is also what enables them in the original renderer
   */
  public record Options(
      boolean expandInlineModels,
      float railWidth,
      float railCoreWidth,
      float railSegmentLength,
      boolean projectionShadows) {
    public static final Options DEFAULT = new Options(false);

    public Options {
      if (!(railWidth >= 0 && railWidth <= 4096)
          || !(railCoreWidth >= 0 && railCoreWidth <= 4096)
          || !(railSegmentLength >= 1 && railSegmentLength <= 65536))
        throw new IllegalArgumentException("Invalid rail options");
    }

    /** Q3's defaults: {@code r_railWidth 16}, {@code r_railCoreWidth 6}, segments of 32. */
    public Options(boolean expandInlineModels) {
      this(expandInlineModels, 16, 6, 32, true);
    }
  }

  private final RenderScene scene;
  private final MaterialRegistry materials;
  private final Options options;
  private final VertexDeformer.Scratch deformScratch = new VertexDeformer.Scratch();
  private final ModelGeometry.Scratch modelScratch = new ModelGeometry.Scratch();
  private final float[] bounds = new float[6];
  private final float[] light = new float[9];
  private int shadowMaterial = -2;

  /**
   * @param scene the loaded map, or null for a builder that only draws views without a world
   *     ({@code RDF_NOWORLDMODEL}: menu and HUD models)
   */
  public FrameBuilder(RenderScene scene, MaterialRegistry materials, Options options) {
    this.scene = scene;
    this.materials = Objects.requireNonNull(materials, "materials");
    this.options = Objects.requireNonNull(options, "options");
  }

  public FrameBuilder(RenderScene scene, MaterialRegistry materials) {
    this(scene, materials, Options.DEFAULT);
  }

  /** A builder for views without a world, used while no map is loaded. */
  public static FrameBuilder withoutWorld(MaterialRegistry materials) {
    return new FrameBuilder(null, materials, Options.DEFAULT);
  }

  public MaterialRegistry materials() {
    return materials;
  }

  /**
   * Builds the dynamic geometry of {@code view} as seen in {@code definition} (the refdef's own
   * view, or a portal view derived from it). {@code visible} supplies the CPU-deformed world
   * surfaces and may be null. Without a scene every view is drawn as {@code RDF_NOWORLDMODEL}.
   */
  public FrameGeometry build(
      CgameFrame.View view, ViewDefinition definition, VisibleSet visible, FrameGeometry out) {
    out.clear();
    boolean world = definition.worldModel() && scene != null;
    boolean portalView = definition.depth() > 0;
    double seconds = view.refdef().timeMillis() / 1000.0;
    if (world && visible != null) worldSurfaces(definition, visible, seconds, out);
    List<CgameFrame.RefEntity> entities = view.entities();
    List<DynamicLight> lights = view.lights();
    for (int i = 0; i < entities.size(); i++) {
      CgameFrame.RefEntity entity = entities.get(i);
      int fx = entity.renderFx();
      // The view weapon's hacked position must not show in mirrors and portals.
      if ((fx & CgameFrame.RF_FIRST_PERSON) != 0 && portalView) continue;
      boolean personal = (fx & CgameFrame.RF_THIRD_PERSON) != 0 && !portalView;
      double entitySeconds = seconds - entity.shaderTime();
      if (entity.type() == CgameFrame.EntityType.MODEL) {
        model(entity, i, entitySeconds, world, personal, lights, out);
        continue;
      }
      if (personal) continue;
      switch (entity.type()) {
        case SPRITE -> sprite(entity, i, entitySeconds, definition, world, lights, out);
        case RAIL_CORE -> railCore(entity, i, entitySeconds, definition, world, lights, out);
        case LIGHTNING -> lightning(entity, i, entitySeconds, definition, world, lights, out);
        case RAIL_RINGS -> rings(entity, i, entitySeconds, world, lights, out);
        case BEAM -> beam(entity, i, entitySeconds, world, lights, out);
        case MODEL, POLY, PORTALSURFACE -> {
          // Poly traps provide vertices separately; portal entities only link portal views.
        }
      }
    }
    for (CgameFrame.Poly poly : view.polygons()) poly(poly, seconds, world, out);
    return out;
  }

  /** Appends a 2D {@code drawStretchPic} quad in screen space (z = 0). */
  public void overlay(CgameFrame.Quad quad, double seconds, FrameGeometry out) {
    int material = materials.index(quad.shader(), false);
    MaterialProgram program = materials.program(material);
    if (program.noDraw() || quad.width() == 0 || quad.height() == 0) return;
    MeshBuffer mesh = out.mesh();
    int first = mesh.vertexCount(), firstIndex = mesh.indexCount();
    float x0 = quad.x(), x1 = quad.x() + quad.width();
    float y0 = quad.y(), y1 = quad.y() + quad.height();
    mesh.addVertex(x0, y0, 0, 0, 0, 1, quad.s1(), quad.t1(), 0, 0, quad.rgba());
    mesh.addVertex(x1, y0, 0, 0, 0, 1, quad.s2(), quad.t1(), 0, 0, quad.rgba());
    mesh.addVertex(x1, y1, 0, 0, 0, 1, quad.s2(), quad.t2(), 0, 0, quad.rgba());
    mesh.addVertex(x0, y1, 0, 0, 0, 1, quad.s1(), quad.t2(), 0, 0, quad.rgba());
    mesh.addTriangle(first, first + 1, first + 2);
    mesh.addTriangle(first, first + 2, first + 3);
    mesh.bounds(first, 4, bounds, 0);
    out.add(
        new DynamicDraw(
            DynamicDraw.Source.POLY,
            material,
            -1,
            -1,
            first,
            4,
            firstIndex,
            6,
            -1,
            -1,
            false,
            seconds,
            quad.rgba(),
            0,
            0,
            ShadingInputs.WHITE,
            Vec3f.ZERO,
            UP,
            bounds));
  }

  /** Appends a cinematic frame as an opaque overlay quad bound to its {@code $cinematic} image. */
  public void overlay(CgameFrame.Image image, FrameGeometry out) {
    overlay(
        new CgameFrame.Quad(
            image.x(), image.y(), image.width(), image.height(), 0, 0, 1, 1, image.texture(), -1),
        0,
        out);
  }

  private void worldSurfaces(
      ViewDefinition view, VisibleSet visible, double seconds, FrameGeometry out) {
    WorldGeometry geometry = scene.world();
    MeshBuffer mesh = out.mesh();
    ViewBasis basis = view.basis();
    for (int i = 0; i < visible.cpuSurfaceCount(); i++) {
      int surface = visible.cpuSurface(i);
      int material = geometry.surfaceMaterial(surface);
      MaterialProgram program = scene.material(material);
      int count = geometry.surfaceVertexCount(surface);
      int indexCount = geometry.surfaceIndexCount(surface);
      int firstIndex = mesh.indexCount();
      int base =
          mesh.copyVertices(geometry.vertexArrays(), geometry.surfaceFirstVertex(surface), count);
      mesh.copyIndices(
          geometry.indices(),
          geometry.surfaceFirstIndex(surface),
          indexCount,
          base - geometry.surfaceFirstVertex(surface));
      VertexDeformer.deform(
          program,
          seconds,
          view.origin(),
          basis.right(),
          basis.up(),
          mesh.arrays(),
          base,
          count,
          mesh.indices(),
          firstIndex,
          indexCount,
          deformScratch);
      mesh.bounds(base, count, bounds, 0);
      gridLight(center(bounds));
      out.add(
          draw(
              DynamicDraw.Source.WORLD,
              registryIndex(material),
              geometry.surfaceLightmap(surface),
              geometry.surfaceFog(surface),
              base,
              count,
              firstIndex,
              indexCount,
              -1,
              surface,
              false,
              seconds,
              -1,
              0,
              0));
    }
  }

  /** World materials share indices with the registry seeded by {@link RenderScene}. */
  private int registryIndex(int sceneIndex) {
    var definition = scene.definitions().get(sceneIndex);
    if (sceneIndex < materials.size() && materials.definition(sceneIndex) == definition)
      return sceneIndex;
    return materials.add(definition);
  }

  private void model(
      CgameFrame.RefEntity entity,
      int index,
      double seconds,
      boolean world,
      boolean personal,
      List<DynamicLight> lights,
      FrameGeometry out) {
    if (entity.model() != null) {
      md3(entity, index, seconds, world, personal, lights, out);
      return;
    }
    if (scene == null) return;
    BspMap map = scene.bsp();
    int model = entity.inlineModel();
    if (model < 0 || model >= map.models().size()) return;
    Md3Model.Tag t = entity.transform();
    boolean mirrored = t.axisX().dot(t.axisY().cross(t.axisZ())) < 0;
    entityLight(entity, world, lights);
    int custom = entity.customShader().map(name -> materials.index(name, false)).orElse(-1);
    BspMap.Model extent = map.models().get(model);
    float[] box = {
      extent.mins().x(), extent.mins().y(), extent.mins().z(),
      extent.maxs().x(), extent.maxs().y(), extent.maxs().z()
    };
    out.add(
        new FrameGeometry.InlineInstance(
            index,
            model,
            t,
            mirrored,
            custom,
            entity.rgba(),
            seconds,
            entity.shaderS(),
            entity.shaderT(),
            world ? scene.fog().volumeContaining(box, 0) : -1,
            vec(0),
            vec(3),
            vec(6)));
    if (options.expandInlineModels())
      expandInline(entity, index, model, mirrored, custom, seconds, world, out);
  }

  private void md3(
      CgameFrame.RefEntity entity,
      int index,
      double seconds,
      boolean world,
      boolean personal,
      List<DynamicLight> lights,
      FrameGeometry out) {
    boolean shadow =
        options.projectionShadows() && (entity.renderFx() & CgameFrame.RF_SHADOW_PLANE) != 0;
    if (personal && !shadow) return;
    MeshBuffer mesh = out.mesh();
    List<ModelGeometry.Surface> surfaces =
        ModelGeometry.build(
            entity.model(),
            entity.oldFrame(),
            entity.frame(),
            entity.backlerp(),
            (entity.renderFx() & CgameFrame.RF_WRAP_FRAMES) != 0,
            entity.transform(),
            entity.skin(),
            entity.customShader(),
            entity.skinNumber(),
            entity.rgba(),
            mesh,
            modelScratch);
    entityLight(entity, world, lights);
    Vec3f ambient = vec(0), directed = vec(3), direction = vec(6);
    for (int s = 0; s < surfaces.size(); s++) {
      ModelGeometry.Surface surface = surfaces.get(s);
      int material = materials.index(surface.shader(), false);
      if (skipped(material)) continue;
      mesh.bounds(surface.firstVertex(), surface.vertexCount(), bounds, 0);
      int fog = fogFor(world);
      if (shadow && fog < 0 && materials.program(material).sort() == OPAQUE_SORT)
        projectionShadow(entity, index, surface, s, direction, seconds, out);
      if (personal) continue;
      mesh.bounds(surface.firstVertex(), surface.vertexCount(), bounds, 0);
      out.add(
          new DynamicDraw(
              DynamicDraw.Source.MODEL,
              material,
              -1,
              fog,
              surface.firstVertex(),
              surface.vertexCount(),
              surface.firstIndex(),
              surface.indexCount(),
              index,
              s,
              entity.depthHack(),
              seconds,
              entity.rgba(),
              entity.shaderS(),
              entity.shaderT(),
              ambient,
              directed,
              direction,
              bounds));
    }
  }

  /**
   * The flattened copy of one model surface: each vertex slides away from the entity's light onto
   * the horizontal plane at {@code shadowPlane}. Lights lower than 30° above the horizon are raised
   * so shadows never stretch to infinity.
   */
  private void projectionShadow(
      CgameFrame.RefEntity entity,
      int index,
      ModelGeometry.Surface surface,
      int surfaceIndex,
      Vec3f lightDirection,
      double seconds,
      FrameGeometry out) {
    int material = shadowMaterial();
    if (material < 0) return;
    Vec3f l = lightDirection;
    float elevation = l.dot(UP);
    if (elevation < SHADOW_MIN_ELEVATION) {
      l = l.multiplyAdd(SHADOW_MIN_ELEVATION - elevation, UP);
      elevation = l.dot(UP);
    }
    Vec3f slide = l.scale(1 / elevation);
    MeshBuffer mesh = out.mesh();
    float plane = entity.shadowPlane();
    int base = mesh.copyVertices(mesh.arrays(), surface.firstVertex(), surface.vertexCount());
    float[] p = mesh.positions();
    for (int v = base; v < base + surface.vertexCount(); v++) {
      float height = p[v * 3 + 2] - plane;
      p[v * 3] -= slide.x() * height;
      p[v * 3 + 1] -= slide.y() * height;
      p[v * 3 + 2] -= slide.z() * height;
    }
    int firstIndex = mesh.indexCount();
    mesh.copyIndices(
        mesh.indices(), surface.firstIndex(), surface.indexCount(), base - surface.firstVertex());
    mesh.bounds(base, surface.vertexCount(), bounds, 0);
    out.add(
        new DynamicDraw(
            DynamicDraw.Source.SHADOW,
            material,
            -1,
            -1,
            base,
            surface.vertexCount(),
            firstIndex,
            surface.indexCount(),
            index,
            surfaceIndex,
            false,
            seconds,
            -1,
            0,
            0,
            ShadingInputs.WHITE,
            Vec3f.ZERO,
            UP,
            bounds));
  }

  /** The registry index of the projection shadow material, or -1 when the game lacks it. */
  private int shadowMaterial() {
    if (shadowMaterial == -2) {
      int index = materials.index(PROJECTION_SHADOW_SHADER, false);
      boolean projects =
          materials.program(index).deforms().stream()
              .anyMatch(d -> d.op() == DeformProgram.Op.PROJECTION_SHADOW);
      if (!projects)
        LOG.warnOnce(
            "projection-shadow",
            "No projectionShadow shader with deformVertexes projectionShadow; RF_SHADOW_PLANE"
                + " shadows are not drawn");
      shadowMaterial = projects ? index : -1;
    }
    return shadowMaterial;
  }

  private void expandInline(
      CgameFrame.RefEntity entity,
      int index,
      int model,
      boolean mirrored,
      int custom,
      double seconds,
      boolean world,
      FrameGeometry out) {
    WorldGeometry geometry = scene.world();
    MeshBuffer mesh = out.mesh();
    Md3Model.Tag t = entity.transform();
    Vec3f ax = t.axisX(), ay = t.axisY(), az = t.axisZ(), o = t.origin();
    Vec3f yz = ay.cross(az), zx = az.cross(ax), xy = ax.cross(ay);
    float orientation = mirrored ? -1 : 1;
    int first = geometry.modelFirstBatch(model), batches = geometry.modelBatchCount(model);
    for (int b = first; b < first + batches; b++) {
      var batch = geometry.batches().get(b);
      for (int k = batch.firstSurface(); k < batch.firstSurface() + batch.surfaceCount(); k++) {
        int surface = geometry.batchSurface(k);
        int count = geometry.surfaceVertexCount(surface);
        int firstIndex = mesh.indexCount();
        int base =
            mesh.copyVertices(geometry.vertexArrays(), geometry.surfaceFirstVertex(surface), count);
        float[] p = mesh.positions(), n = mesh.normals();
        for (int v = base; v < base + count; v++) {
          float x = p[v * 3], y = p[v * 3 + 1], z = p[v * 3 + 2];
          p[v * 3] = o.x() + ax.x() * x + ay.x() * y + az.x() * z;
          p[v * 3 + 1] = o.y() + ax.y() * x + ay.y() * y + az.y() * z;
          p[v * 3 + 2] = o.z() + ax.z() * x + ay.z() * y + az.z() * z;
          float nx = n[v * 3], ny = n[v * 3 + 1], nz = n[v * 3 + 2];
          float tx = (yz.x() * nx + zx.x() * ny + xy.x() * nz) * orientation;
          float ty = (yz.y() * nx + zx.y() * ny + xy.y() * nz) * orientation;
          float tz = (yz.z() * nx + zx.z() * ny + xy.z() * nz) * orientation;
          double length = Math.sqrt((double) tx * tx + (double) ty * ty + (double) tz * tz);
          float inverse = length == 0 ? 0 : (float) (1 / length);
          n[v * 3] = tx * inverse;
          n[v * 3 + 1] = ty * inverse;
          n[v * 3 + 2] = tz * inverse;
        }
        int sourceIndex = geometry.surfaceFirstIndex(surface);
        int delta = base - geometry.surfaceFirstVertex(surface);
        int[] indices = geometry.indices();
        int indexCount = geometry.surfaceIndexCount(surface);
        for (int i = 0; i + 2 < indexCount; i += 3) {
          int a = indices[sourceIndex + i] + delta;
          int bb = indices[sourceIndex + i + 1] + delta;
          int c = indices[sourceIndex + i + 2] + delta;
          if (mirrored) mesh.addTriangle(a, c, bb);
          else mesh.addTriangle(a, bb, c);
        }
        int material = custom >= 0 ? custom : registryIndex(batch.material());
        mesh.bounds(base, count, bounds, 0);
        out.add(
            draw(
                DynamicDraw.Source.INLINE,
                material,
                batch.lightmap(),
                world ? batch.fog() : -1,
                base,
                count,
                firstIndex,
                mesh.indexCount() - firstIndex,
                index,
                surface,
                entity.depthHack(),
                seconds,
                entity.rgba(),
                entity.shaderS(),
                entity.shaderT()));
      }
    }
  }

  private void sprite(
      CgameFrame.RefEntity entity,
      int index,
      double seconds,
      ViewDefinition view,
      boolean world,
      List<DynamicLight> lights,
      FrameGeometry out) {
    double angle = Math.toRadians(entity.rotation());
    float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
    ViewBasis basis = view.basis();
    Vec3f r = basis.right().scale(c).add(basis.up().scale(s)).scale(entity.radius());
    Vec3f u = basis.up().scale(c).add(basis.right().scale(-s)).scale(entity.radius());
    // Mirrors flip the sprite so its reflection reads mirrored, as a real reflection would.
    if (view.mirrored()) r = r.negate();
    Vec3f o = entity.transform().origin();
    int first = out.mesh().vertexCount(), firstIndex = out.mesh().indexCount();
    quad(
        out.mesh(),
        o.subtract(r).add(u),
        o.add(r).add(u),
        o.add(r).subtract(u),
        o.subtract(r).subtract(u),
        basis.forward().negate(),
        entity.rgba());
    finish(
        DynamicDraw.Source.SPRITE,
        entity,
        customMaterial(entity),
        index,
        seconds,
        world,
        lights,
        first,
        firstIndex,
        out);
  }

  /** {@code RT_RAIL_CORE}: from {@code oldorigin} (the impact) to {@code origin}. */
  private void railCore(
      CgameFrame.RefEntity entity,
      int index,
      double seconds,
      ViewDefinition view,
      boolean world,
      List<DynamicLight> lights,
      FrameGeometry out) {
    Vec3f start = entity.oldOrigin(), end = entity.transform().origin();
    float length = end.subtract(start).length();
    Vec3f side = facingSide(start, end, view);
    int first = out.mesh().vertexCount(), firstIndex = out.mesh().indexCount();
    beamQuad(out.mesh(), start, end, side, length, options.railCoreWidth(), entity.rgba());
    finish(
        DynamicDraw.Source.BEAM,
        entity,
        customMaterial(entity),
        index,
        seconds,
        world,
        lights,
        first,
        firstIndex,
        out);
  }

  /** {@code RT_LIGHTNING}: from {@code origin} (the muzzle) to {@code oldorigin}. */
  private void lightning(
      CgameFrame.RefEntity entity,
      int index,
      double seconds,
      ViewDefinition view,
      boolean world,
      List<DynamicLight> lights,
      FrameGeometry out) {
    Vec3f start = entity.transform().origin(), end = entity.oldOrigin();
    Vec3f axis = end.subtract(start);
    float length = axis.length();
    axis = axis.normalize();
    Vec3f side = facingSide(start, end, view);
    int first = out.mesh().vertexCount(), firstIndex = out.mesh().indexCount();
    float c = (float) Math.cos(LIGHTNING_TURN), s = (float) Math.sin(LIGHTNING_TURN);
    for (int i = 0; i < LIGHTNING_QUADS; i++) {
      beamQuad(out.mesh(), start, end, side, length, LIGHTNING_WIDTH, entity.rgba());
      // Turn the side about the bolt; it stays perpendicular to the axis.
      side = side.scale(c).add(axis.cross(side).scale(s));
    }
    finish(
        DynamicDraw.Source.BEAM,
        entity,
        customMaterial(entity),
        index,
        seconds,
        world,
        lights,
        first,
        firstIndex,
        out);
  }

  /**
   * The unit side vector of a beam facing the eye: perpendicular to both eye-to-end directions.
   * Beams pointing straight at the eye use the view's right axis.
   */
  private static Vec3f facingSide(Vec3f start, Vec3f end, ViewDefinition view) {
    Vec3f toStart = start.subtract(view.origin()).normalize();
    Vec3f toEnd = end.subtract(view.origin()).normalize();
    Vec3f side = toStart.cross(toEnd);
    if (side.lengthSquared() < 1e-12f) {
      Vec3f axis = end.subtract(start).normalize();
      side = axis.cross(view.basis().up());
      if (side.lengthSquared() < 1e-12f) side = view.basis().right();
    }
    return side.normalize();
  }

  /**
   * One rail-core quad: corners start±side and end±side ({@code width} each way), s from 0 at the
   * start to {@code length / 256} at the end, t 0 on the +side edge and 1 on the −side edge. The
   * start's +side corner carries a quarter of the colour.
   */
  private static void beamQuad(
      MeshBuffer mesh, Vec3f start, Vec3f end, Vec3f side, float length, float width, int rgba) {
    float t = length / BEAM_TEXTURE_LENGTH;
    Vec3f offset = side.scale(width);
    Vec3f normal = end.subtract(start).cross(side).normalize();
    int v = mesh.vertexCount();
    int dim = scaleRgb(rgba, BEAM_START_DIM);
    addVertex(mesh, start.add(offset), normal, 0, 0, dim);
    addVertex(mesh, start.subtract(offset), normal, 0, 1, rgba);
    addVertex(mesh, end.add(offset), normal, t, 0, rgba);
    addVertex(mesh, end.subtract(offset), normal, t, 1, rgba);
    mesh.addTriangle(v, v + 1, v + 2);
    mesh.addTriangle(v + 2, v + 1, v + 3);
  }

  /** {@code RT_RAIL_RINGS}: discs from {@code oldorigin} toward {@code origin}. */
  private void rings(
      CgameFrame.RefEntity entity,
      int index,
      double seconds,
      boolean world,
      List<DynamicLight> lights,
      FrameGeometry out) {
    Vec3f start = entity.oldOrigin(), delta = entity.transform().origin().subtract(start);
    int length = (int) delta.length();
    Vec3f direction = delta.normalize();
    if (direction.lengthSquared() < 0.5f) direction = new Vec3f(1, 0, 0);
    Vec3f right = perpendicular(direction);
    Vec3f up = right.cross(direction);
    int segments = Math.max(1, (int) (length / options.railSegmentLength()));
    // Long shots start one segment in and leave out the last disc.
    if (segments > 1) segments--;
    segments = Math.min(segments, MAX_RINGS);
    Vec3f step = direction.scale(options.railSegmentLength());
    Vec3f origin = segments > 1 ? start.add(step) : start;
    float radius = RING_SCALE * options.railWidth();
    Vec3f[] corners = new Vec3f[4];
    for (int j = 0; j < 4; j++) {
      double angle = Math.toRadians(45 + j * 90);
      corners[j] =
          right.scale((float) Math.cos(angle)).add(up.scale((float) Math.sin(angle))).scale(radius);
    }
    MeshBuffer mesh = out.mesh();
    int first = mesh.vertexCount(), firstIndex = mesh.indexCount();
    for (int i = 0; i < segments; i++) {
      Vec3f center = origin.multiplyAdd(i, step);
      int v = mesh.vertexCount();
      for (int j = 0; j < 4; j++)
        addVertex(
            mesh,
            center.add(corners[j]),
            direction,
            j < 2 ? 1 : 0,
            j == 1 || j == 2 ? 1 : 0,
            entity.rgba());
      mesh.addTriangle(v, v + 1, v + 3);
      mesh.addTriangle(v + 3, v + 1, v + 2);
    }
    finish(
        DynamicDraw.Source.RAIL_RINGS,
        entity,
        customMaterial(entity),
        index,
        seconds,
        world,
        lights,
        first,
        firstIndex,
        out);
  }

  /** {@code RT_BEAM}: a red additive tube from {@code origin} to {@code oldorigin}. */
  private void beam(
      CgameFrame.RefEntity entity,
      int index,
      double seconds,
      boolean world,
      List<DynamicLight> lights,
      FrameGeometry out) {
    Vec3f start = entity.transform().origin(), end = entity.oldOrigin();
    Vec3f axis = end.subtract(start).normalize();
    if (axis.lengthSquared() < 0.5f) return;
    Vec3f radial = perpendicular(axis).scale(BEAM_RADIUS);
    MeshBuffer mesh = out.mesh();
    int first = mesh.vertexCount(), firstIndex = mesh.indexCount();
    double turn = 2 * Math.PI / BEAM_SEGMENTS;
    for (int i = 0; i <= BEAM_SEGMENTS; i++) {
      double angle = turn * (i % BEAM_SEGMENTS);
      Vec3f offset =
          radial
              .scale((float) Math.cos(angle))
              .add(axis.cross(radial).scale((float) Math.sin(angle)));
      Vec3f normal = offset.normalize();
      addVertex(mesh, start.add(offset), normal, 0, 0, BEAM_RED);
      addVertex(mesh, end.add(offset), normal, 0, 0, BEAM_RED);
    }
    for (int i = 0; i < BEAM_SEGMENTS; i++) {
      int v = first + i * 2;
      mesh.addTriangle(v, v + 1, v + 2);
      mesh.addTriangle(v + 2, v + 1, v + 3);
    }
    float sort =
        entity
            .customShader()
            .map(name -> materials.program(materials.index(name, false)).sort())
            .orElse(BLEND_SORT);
    finish(
        DynamicDraw.Source.BEAM,
        entity,
        materials.add(beamShader(sort)),
        index,
        seconds,
        world,
        lights,
        first,
        firstIndex,
        out);
  }

  /** The beam's fixed look: white texture, additive, vertex colour, two-sided, sorted as given. */
  static ShaderDefinition beamShader(float sort) {
    ShaderDefinition.Stage stage =
        new ShaderDefinition.Stage(
            new ShaderDefinition.TextureMap(List.of(TextureBundle.WHITE), 0, false),
            ShaderDefinition.Blend.ADD,
            ShaderDefinition.AlphaFunc.NONE,
            ShaderDefinition.RgbGen.of(ShaderDefinition.RgbGenType.EXACT_VERTEX),
            ShaderDefinition.AlphaGen.of(ShaderDefinition.AlphaGenType.IDENTITY),
            ShaderDefinition.TcGen.of(ShaderDefinition.TcGenType.BASE),
            List.of(),
            ShaderDefinition.DepthFunc.LEQUAL,
            false,
            false);
    return new ShaderDefinition(
        "$beam/" + Math.round(sort * 64),
        List.of(stage),
        ShaderDefinition.Cull.NONE,
        sort,
        false,
        true,
        true,
        Set.of("nodlight"),
        Optional.empty(),
        Optional.empty(),
        List.of(),
        false,
        0);
  }

  /**
   * A unit vector perpendicular to {@code v} (unit): {@code v}'s components rotated, with the
   * parallel part removed, so it is never colinear with {@code v}.
   */
  static Vec3f perpendicular(Vec3f v) {
    Vec3f r = new Vec3f(v.z(), -v.x(), v.y());
    r = r.multiplyAdd(-r.dot(v), v);
    if (r.lengthSquared() < 1e-12f)
      r = Math.abs(v.z()) < 0.9f ? new Vec3f(0, 0, 1).cross(v) : new Vec3f(1, 0, 0).cross(v);
    return r.normalize();
  }

  private int customMaterial(CgameFrame.RefEntity entity) {
    return materials.index(entity.customShader().orElse(ModelGeometry.MISSING_SHADER), false);
  }

  private void finish(
      DynamicDraw.Source source,
      CgameFrame.RefEntity entity,
      int material,
      int index,
      double seconds,
      boolean world,
      List<DynamicLight> lights,
      int first,
      int firstIndex,
      FrameGeometry out) {
    if (skipped(material)) return;
    MeshBuffer mesh = out.mesh();
    if (mesh.vertexCount() == first) return;
    mesh.bounds(first, mesh.vertexCount() - first, bounds, 0);
    entityLight(entity, world, lights);
    out.add(
        draw(
            source,
            material,
            -1,
            fogFor(world),
            first,
            mesh.vertexCount() - first,
            firstIndex,
            mesh.indexCount() - firstIndex,
            index,
            -1,
            entity.depthHack(),
            seconds,
            entity.rgba(),
            entity.shaderS(),
            entity.shaderT()));
  }

  private void poly(CgameFrame.Poly poly, double seconds, boolean world, FrameGeometry out) {
    int material = materials.index(poly.shader(), false);
    if (skipped(material)) return;
    MeshBuffer mesh = out.mesh();
    int count = poly.vertexCount();
    int first = mesh.vertexCount(), firstIndex = mesh.indexCount();
    Vec3f p0 = poly.position(0);
    Vec3f normal = poly.position(2).subtract(p0).cross(poly.position(1).subtract(p0)).normalize();
    mesh.reserveVertices(count);
    for (int v = 0; v < count; v++)
      mesh.addVertex(
          poly.positionAt(v * 3),
          poly.positionAt(v * 3 + 1),
          poly.positionAt(v * 3 + 2),
          normal.x(),
          normal.y(),
          normal.z(),
          poly.texCoordAt(v * 2),
          poly.texCoordAt(v * 2 + 1),
          0,
          0,
          poly.colorAt(v));
    for (int v = 1; v + 1 < count; v++) mesh.addTriangle(first, first + v, first + v + 1);
    mesh.bounds(first, count, bounds, 0);
    pointLight(center(bounds), world, List.of());
    out.add(
        draw(
            DynamicDraw.Source.POLY,
            material,
            -1,
            fogFor(world),
            first,
            count,
            firstIndex,
            mesh.indexCount() - firstIndex,
            -1,
            -1,
            false,
            seconds,
            -1,
            0,
            0));
  }

  /** Four corners with texture coordinates (0,0) (1,0) (1,1) (0,1), wound to face against n. */
  private static void quad(MeshBuffer mesh, Vec3f a, Vec3f b, Vec3f c, Vec3f d, Vec3f n, int rgba) {
    int v = mesh.vertexCount();
    mesh.addVertex(a.x(), a.y(), a.z(), n.x(), n.y(), n.z(), 0, 0, 0, 0, rgba);
    mesh.addVertex(b.x(), b.y(), b.z(), n.x(), n.y(), n.z(), 1, 0, 0, 0, rgba);
    mesh.addVertex(c.x(), c.y(), c.z(), n.x(), n.y(), n.z(), 1, 1, 0, 0, rgba);
    mesh.addVertex(d.x(), d.y(), d.z(), n.x(), n.y(), n.z(), 0, 1, 0, 0, rgba);
    // Q3 front faces wind clockwise as seen from their normal side.
    boolean reverse = b.subtract(a).cross(c.subtract(a)).dot(n) > 0;
    if (reverse) {
      mesh.addTriangle(v, v + 2, v + 1);
      mesh.addTriangle(v, v + 3, v + 2);
    } else {
      mesh.addTriangle(v, v + 1, v + 2);
      mesh.addTriangle(v, v + 2, v + 3);
    }
  }

  private static void addVertex(MeshBuffer mesh, Vec3f p, Vec3f n, float s, float t, int rgba) {
    mesh.addVertex(p.x(), p.y(), p.z(), n.x(), n.y(), n.z(), s, t, 0, 0, rgba);
  }

  /** {@code rgba} with red, green and blue scaled by {@code scale}; alpha kept. */
  private static int scaleRgb(int rgba, float scale) {
    int r = (int) ((rgba >>> 24) * scale), g = (int) (((rgba >>> 16) & 255) * scale);
    int b = (int) (((rgba >>> 8) & 255) * scale);
    return r << 24 | g << 16 | b << 8 | (rgba & 255);
  }

  private boolean skipped(int material) {
    MaterialProgram program = materials.program(material);
    return program.noDraw() || program.isSky();
  }

  private int fogFor(boolean world) {
    return world ? scene.fog().volumeContaining(bounds, 0) : -1;
  }

  private void entityLight(CgameFrame.RefEntity entity, boolean world, List<DynamicLight> lights) {
    pointLight(entity.lightOrigin(), world, lights);
  }

  /**
   * Q3's entity lighting at {@code point} into {@link #light}: the light grid (zero where every
   * neighbouring cell is solid), or without a world or grid equal ambient and directed light from
   * the default sun; then a minimum ambient for everything and each dynamic light's inverse-square
   * contribution added to the directed light and pulled into its direction.
   */
  private void pointLight(Vec3f point, boolean world, List<DynamicLight> lights) {
    LightGrid grid = world ? scene.lightGrid() : null;
    if (grid != null && grid.available()) {
      if (!grid.sample(point.x(), point.y(), point.z(), light)) {
        for (int i = 0; i < 8; i++) light[i] = 0;
        light[8] = 1;
      }
    } else {
      for (int i = 0; i < 6; i++) light[i] = NO_GRID_LIGHT;
      light[6] = DEFAULT_SUN.x();
      light[7] = DEFAULT_SUN.y();
      light[8] = DEFAULT_SUN.z();
    }
    for (int i = 0; i < 3; i++) light[i] += MIN_AMBIENT;
    double directed = Math.sqrt(light[3] * light[3] + light[4] * light[4] + light[5] * light[5]);
    double lx = light[6] * directed, ly = light[7] * directed, lz = light[8] * directed;
    for (int i = 0; i < lights.size(); i++) {
      DynamicLight dl = lights.get(i);
      double dx = dl.origin().x() - point.x();
      double dy = dl.origin().y() - point.y();
      double dz = dl.origin().z() - point.z();
      double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
      if (distance > 0) {
        dx /= distance;
        dy /= distance;
        dz /= distance;
      }
      double near = Math.max(distance, DLIGHT_MINIMUM_RADIUS);
      double power = DLIGHT_AT_RADIUS * (double) dl.radius() * dl.radius();
      double amount = power / (near * near) * DISPLAY_GAIN;
      light[3] += (float) (amount * dl.color().x());
      light[4] += (float) (amount * dl.color().y());
      light[5] += (float) (amount * dl.color().z());
      lx += amount * dx;
      ly += amount * dy;
      lz += amount * dz;
    }
    for (int i = 0; i < 3; i++) light[i] = Math.min(light[i], 1);
    double length = Math.sqrt(lx * lx + ly * ly + lz * lz);
    light[6] = length > 0 ? (float) (lx / length) : 0;
    light[7] = length > 0 ? (float) (ly / length) : 0;
    light[8] = length > 0 ? (float) (lz / length) : 0;
  }

  /** Light-grid sample for CPU-deformed world surfaces: the full-bright fallback without one. */
  private void gridLight(Vec3f point) {
    if (!scene.lightGrid().sample(point.x(), point.y(), point.z(), light)) {
      LightGrid.FALLBACK.ambient().copyTo(light);
      light[3] = light[4] = light[5] = 0;
      light[6] = 0;
      light[7] = 0;
      light[8] = 1;
    }
  }

  private Vec3f vec(int at) {
    return new Vec3f(light[at], light[at + 1], light[at + 2]);
  }

  private static Vec3f center(float[] b) {
    return new Vec3f((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2);
  }

  private DynamicDraw draw(
      DynamicDraw.Source source,
      int material,
      int lightmap,
      int fog,
      int firstVertex,
      int vertexCount,
      int firstIndex,
      int indexCount,
      int entity,
      int surface,
      boolean depthHack,
      double seconds,
      int rgba,
      float shaderS,
      float shaderT) {
    return new DynamicDraw(
        source,
        material,
        lightmap,
        fog,
        firstVertex,
        vertexCount,
        firstIndex,
        indexCount,
        entity,
        surface,
        depthHack,
        seconds,
        rgba,
        shaderS,
        shaderT,
        vec(0),
        vec(3),
        vec(6),
        bounds);
  }
}
