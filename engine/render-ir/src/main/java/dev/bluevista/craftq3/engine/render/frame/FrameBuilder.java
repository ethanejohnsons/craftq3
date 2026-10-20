package dev.bluevista.craftq3.engine.render.frame;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.md3.Md3Model;
import dev.bluevista.craftq3.engine.render.LightGrid;
import dev.bluevista.craftq3.engine.render.MeshBuffer;
import dev.bluevista.craftq3.engine.render.RenderScene;
import dev.bluevista.craftq3.engine.render.ViewBasis;
import dev.bluevista.craftq3.engine.render.ViewDefinition;
import dev.bluevista.craftq3.engine.render.VisibleSet;
import dev.bluevista.craftq3.engine.render.WorldGeometry;
import dev.bluevista.craftq3.engine.render.material.MaterialProgram;
import dev.bluevista.craftq3.engine.render.material.MaterialRegistry;
import dev.bluevista.craftq3.engine.render.material.ShadingInputs;
import dev.bluevista.craftq3.engine.render.material.VertexDeformer;
import java.util.List;

/**
 * Turns one captured cgame scene into {@link FrameGeometry}: MD3 models, sprites, beams, rail
 * rings, polys, inline-model instances and the visible world surfaces whose material deforms on the
 * CPU. Submitted geometry has its own identity and never enters the static world batches or PVS.
 * Owned by the render thread; reuse one builder and one {@link FrameGeometry} per view so steady
 * state frames allocate only per-draw records.
 */
public final class FrameBuilder {
  private static final float DEFAULT_BEAM_RADIUS = 4;
  private static final float RING_SPACING = 32;
  private static final int MAX_RINGS = 1024;
  private static final float MIN_LIGHT = 0.125f;
  private static final Vec3f NO_GRID_AMBIENT = new Vec3f(0.25f, 0.25f, 0.25f);
  private static final Vec3f NO_GRID_DIRECTED = new Vec3f(0.75f, 0.75f, 0.75f);
  private static final Vec3f UP = new Vec3f(0, 0, 1);

  /**
   * @param expandInlineModels also copy inline BSP models into the frame mesh (CPU fallback);
   *     otherwise they are only listed as {@link FrameGeometry.InlineInstance}s
   */
  public record Options(boolean expandInlineModels) {
    public static final Options DEFAULT = new Options(false);
  }

  private final RenderScene scene;
  private final MaterialRegistry materials;
  private final Options options;
  private final VertexDeformer.Scratch deformScratch = new VertexDeformer.Scratch();
  private final ModelGeometry.Scratch modelScratch = new ModelGeometry.Scratch();
  private final float[] bounds = new float[6];
  private final float[] light = new float[9];

  public FrameBuilder(RenderScene scene, MaterialRegistry materials, Options options) {
    this.scene = scene;
    this.materials = materials;
    this.options = options;
  }

  public FrameBuilder(RenderScene scene, MaterialRegistry materials) {
    this(scene, materials, Options.DEFAULT);
  }

  public MaterialRegistry materials() {
    return materials;
  }

  /**
   * Builds the dynamic geometry of {@code view} as seen in {@code definition} (the refdef's own
   * view, or a portal view derived from it). {@code visible} supplies the CPU-deformed world
   * surfaces and may be null. Entities flagged first-person are skipped in portal views and
   * third-person ones in the main view.
   */
  public FrameGeometry build(
      CgameFrame.View view, ViewDefinition definition, VisibleSet visible, FrameGeometry out) {
    out.clear();
    boolean world = definition.worldModel();
    boolean portalView = definition.depth() > 0;
    double seconds = view.refdef().timeMillis() / 1000.0;
    if (world && visible != null) worldSurfaces(definition, visible, seconds, out);
    List<CgameFrame.RefEntity> entities = view.entities();
    for (int i = 0; i < entities.size(); i++) {
      CgameFrame.RefEntity entity = entities.get(i);
      if (!entity.visible(portalView)) continue;
      double entitySeconds = seconds - entity.shaderTime();
      switch (entity.type()) {
        case MODEL -> model(entity, i, entitySeconds, world, out);
        case SPRITE -> sprite(entity, i, entitySeconds, definition, world, out);
        case BEAM, RAIL_CORE, LIGHTNING -> ribbon(entity, i, entitySeconds, definition, world, out);
        case RAIL_RINGS -> rings(entity, i, entitySeconds, definition, world, out);
        case POLY, PORTALSURFACE -> {
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
      sampleLight(center(bounds), true, false);
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
      CgameFrame.RefEntity entity, int index, double seconds, boolean world, FrameGeometry out) {
    if (entity.model() != null) {
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
      sampleEntityLight(entity, world);
      for (int s = 0; s < surfaces.size(); s++) {
        ModelGeometry.Surface surface = surfaces.get(s);
        int material = materials.index(surface.shader(), false);
        if (skipped(material)) continue;
        mesh.bounds(surface.firstVertex(), surface.vertexCount(), bounds, 0);
        out.add(
            draw(
                DynamicDraw.Source.MODEL,
                material,
                -1,
                fogFor(world),
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
                entity.shaderT()));
      }
      return;
    }
    BspMap map = scene.bsp();
    int model = entity.inlineModel();
    if (model < 0 || model >= map.models().size()) return;
    Md3Model.Tag t = entity.transform();
    boolean mirrored = t.axisX().dot(t.axisY().cross(t.axisZ())) < 0;
    sampleEntityLight(entity, world);
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
      FrameGeometry out) {
    double angle = Math.toRadians(entity.rotation());
    float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
    ViewBasis basis = view.basis();
    Vec3f r = basis.right().scale(c).add(basis.up().scale(s)).scale(entity.radius());
    Vec3f u = basis.up().scale(c).add(basis.right().scale(-s)).scale(entity.radius());
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
    finish(DynamicDraw.Source.SPRITE, entity, index, seconds, world, first, firstIndex, out);
  }

  private void ribbon(
      CgameFrame.RefEntity entity,
      int index,
      double seconds,
      ViewDefinition view,
      boolean world,
      FrameGeometry out) {
    Vec3f a = entity.transform().origin(), b = entity.oldOrigin();
    Vec3f eye = view.origin().subtract(a.add(b).scale(0.5f));
    Vec3f side = b.subtract(a).cross(eye).normalize();
    if (side.lengthSquared() < 0.5f) side = view.basis().right();
    side = side.scale(entity.radius() > 0 ? entity.radius() : DEFAULT_BEAM_RADIUS);
    int first = out.mesh().vertexCount(), firstIndex = out.mesh().indexCount();
    quad(
        out.mesh(),
        a.subtract(side),
        a.add(side),
        b.add(side),
        b.subtract(side),
        eye.normalize(),
        entity.rgba());
    finish(DynamicDraw.Source.BEAM, entity, index, seconds, world, first, firstIndex, out);
  }

  private void rings(
      CgameFrame.RefEntity entity,
      int index,
      double seconds,
      ViewDefinition view,
      boolean world,
      FrameGeometry out) {
    Vec3f a = entity.transform().origin(), delta = entity.oldOrigin().subtract(a);
    float distance = delta.length();
    int count = Math.min(MAX_RINGS, Math.max(1, (int) (distance / RING_SPACING)));
    Vec3f direction = delta.normalize();
    Vec3f side = direction.cross(view.basis().up()).normalize();
    if (side.lengthSquared() < 0.5f) side = view.basis().right();
    Vec3f up = direction.cross(side).normalize();
    float radius = entity.radius() > 0 ? entity.radius() : DEFAULT_BEAM_RADIUS;
    side = side.scale(radius);
    up = up.scale(radius);
    int first = out.mesh().vertexCount(), firstIndex = out.mesh().indexCount();
    for (int i = 0; i < count; i++) {
      Vec3f center = a.add(delta.scale((i + 0.5f) / count));
      quad(
          out.mesh(),
          center.subtract(side).add(up),
          center.add(side).add(up),
          center.add(side).subtract(up),
          center.subtract(side).subtract(up),
          direction.negate(),
          entity.rgba());
    }
    finish(DynamicDraw.Source.RAIL_RINGS, entity, index, seconds, world, first, firstIndex, out);
  }

  private void finish(
      DynamicDraw.Source source,
      CgameFrame.RefEntity entity,
      int index,
      double seconds,
      boolean world,
      int first,
      int firstIndex,
      FrameGeometry out) {
    int material =
        materials.index(entity.customShader().orElse(ModelGeometry.MISSING_SHADER), false);
    if (skipped(material)) return;
    MeshBuffer mesh = out.mesh();
    mesh.bounds(first, mesh.vertexCount() - first, bounds, 0);
    sampleEntityLight(entity, world);
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
    sampleLight(center(bounds), world, false);
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

  private boolean skipped(int material) {
    MaterialProgram program = materials.program(material);
    return program.noDraw() || program.isSky();
  }

  private int fogFor(boolean world) {
    return world ? scene.fog().volumeContaining(bounds, 0) : -1;
  }

  private void sampleEntityLight(CgameFrame.RefEntity entity, boolean world) {
    sampleLight(entity.lightOrigin(), world, (entity.renderFx() & CgameFrame.RF_MINLIGHT) != 0);
  }

  /**
   * Light-grid lighting at {@code point}: the grid's full-bright fallback where it has no sample,
   * neutral defaults for views without a world.
   */
  private void sampleLight(Vec3f point, boolean world, boolean minLight) {
    LightGrid grid = scene.lightGrid();
    if (!world) {
      NO_GRID_AMBIENT.copyTo(light);
      light[3] = NO_GRID_DIRECTED.x();
      light[4] = NO_GRID_DIRECTED.y();
      light[5] = NO_GRID_DIRECTED.z();
      light[6] = 0;
      light[7] = 0;
      light[8] = 1;
    } else if (!grid.sample(point.x(), point.y(), point.z(), light)) {
      LightGrid.FALLBACK.ambient().copyTo(light);
      light[3] = light[4] = light[5] = 0;
      light[6] = 0;
      light[7] = 0;
      light[8] = 1;
    }
    if (minLight) for (int i = 0; i < 3; i++) light[i] = Math.max(light[i], MIN_LIGHT);
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
