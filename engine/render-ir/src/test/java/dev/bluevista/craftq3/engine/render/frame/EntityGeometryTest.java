package dev.bluevista.craftq3.engine.render.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.md3.Md3Model;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition;
import dev.bluevista.craftq3.engine.formats.shader.ShaderLibrary;
import dev.bluevista.craftq3.engine.render.AreaMask;
import dev.bluevista.craftq3.engine.render.DynamicLight;
import dev.bluevista.craftq3.engine.render.MeshBuffer;
import dev.bluevista.craftq3.engine.render.ViewDefinition;
import dev.bluevista.craftq3.engine.render.material.MaterialProgram;
import dev.bluevista.craftq3.engine.render.material.MaterialRegistry;
import dev.bluevista.craftq3.engine.render.material.StageProgram;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The shapes {@link FrameBuilder} gives non-model entities (rail cores, lightning, rail rings,
 * beams), render flags, projected shadows, and entity lighting in views without a world.
 */
class EntityGeometryTest {
  private static final Vec3f X = new Vec3f(1, 0, 0), Y = new Vec3f(0, 1, 0), Z = new Vec3f(0, 0, 1);
  private static final float EPSILON = 1e-3f;

  private static final ShaderLibrary LIBRARY =
      ShaderLibrary.of(
          List.of(
              new ShaderLibrary.Script(
                  "scripts/gfx.shader",
                  """
                  railCore
                  {
                    sort nearest
                    cull none
                    { map gfx/misc/railcorethin_mono.tga blendFunc GL_ONE GL_ONE rgbGen vertex tcMod scroll -1 0 }
                  }
                  lightningBolt
                  {
                    cull none
                    { map gfx/misc/lightning3.tga blendFunc GL_ONE GL_ONE rgbgen wave sin 1 0.5 0 7.1 }
                  }
                  railDisc
                  {
                    sort nearest
                    cull none
                    { clampmap gfx/misc/raildisc_mono2.tga blendFunc GL_ONE GL_ONE rgbGen vertex }
                  }
                  projectionShadow
                  {
                    polygonOffset
                    deformVertexes projectionShadow
                    { map *white blendFunc GL_ONE GL_ZERO rgbGen wave square 0 0 0 0 }
                  }
                  body
                  {
                    { map models/body.tga rgbGen lightingDiffuse }
                  }
                  """)));

  /** Looking along +y from (256, -200, 0); beams run along x through the origin. */
  private static final Vec3f EYE = new Vec3f(256, -200, 0);

  @Test
  void railCoreTextureRunsAlongTheBeamAndFacesTheCamera() {
    Vec3f impact = Vec3f.ZERO, muzzle = new Vec3f(512, 0, 0);
    FrameGeometry frame =
        build(entity(CgameFrame.EntityType.RAIL_CORE, 0, muzzle, impact, "railCore", 0xff8040ff));
    DynamicDraw draw = only(frame);
    assertEquals(DynamicDraw.Source.BEAM, draw.source());
    assertEquals(4, draw.vertexCount());
    assertEquals(6, draw.indexCount());
    MeshBuffer mesh = frame.mesh();
    Vec3f axis = X;
    Vec3f toEye = EYE.subtract(new Vec3f(256, 0, 0)).normalize();
    for (int v = draw.firstVertex(); v < draw.firstVertex() + 4; v++) {
      Vec3f p = position(mesh, v);
      // s grows with the distance from the impact, one repeat per 256 units; t is the side.
      float along = p.subtract(impact).dot(axis);
      assertEquals(along / 256, s(mesh, v), EPSILON, "s follows the length");
      Vec3f offset = p.subtract(impact.multiplyAdd(along, axis));
      assertEquals(6, offset.length(), EPSILON, "r_railCoreWidth to each side");
      assertEquals(0, offset.dot(toEye), EPSILON, "the quad faces the eye");
    }
    int v0 = draw.firstVertex();
    assertEquals(2, s(mesh, v0 + 2), EPSILON);
    assertEquals(0, t(mesh, v0), EPSILON);
    assertEquals(1, t(mesh, v0 + 1), EPSILON);
    assertEquals(0, t(mesh, v0 + 2), EPSILON);
    assertEquals(
        0,
        position(mesh, v0).add(position(mesh, v0 + 1)).length(),
        EPSILON,
        "t runs across the width");
    // The impact's first corner is dimmed to a quarter of the colour, alpha kept.
    assertEquals(0xff8040ff >>> 24, red(mesh, draw.firstVertex() + 1));
    assertEquals(0xff / 4, red(mesh, draw.firstVertex()));
    assertEquals(0xff, mesh.colors()[draw.firstVertex() * 4 + 3] & 255);
    assertEquals("railcore", material(frame, draw).name(), "the entity shader draws the core");
  }

  @Test
  void lightningIsFourQuadsTurned45DegreesAboutTheBolt() {
    Vec3f muzzle = Vec3f.ZERO, end = new Vec3f(512, 0, 0);
    FrameGeometry frame =
        build(entity(CgameFrame.EntityType.LIGHTNING, 0, muzzle, end, "lightningBolt", -1));
    DynamicDraw draw = only(frame);
    assertEquals(16, draw.vertexCount());
    assertEquals(24, draw.indexCount());
    MeshBuffer mesh = frame.mesh();
    Vec3f previous = null;
    for (int q = 0; q < 4; q++) {
      int v = draw.firstVertex() + q * 4;
      Vec3f side = position(mesh, v).subtract(muzzle);
      assertEquals(8, side.length(), EPSILON);
      assertEquals(0, side.dot(X), EPSILON);
      assertEquals(0, s(mesh, v), EPSILON);
      assertEquals(2, s(mesh, v + 2), EPSILON, "s runs from the muzzle to the far end");
      if (previous == null)
        assertEquals(0, side.dot(EYE.subtract(new Vec3f(256, 0, 0)).normalize()), EPSILON);
      else
        assertEquals(
            Math.cos(Math.toRadians(45)), side.normalize().dot(previous.normalize()), EPSILON);
      previous = side;
    }
  }

  @Test
  void railRingsAreDiscsAcrossTheBeamEverySegment() {
    Vec3f start = Vec3f.ZERO, end = new Vec3f(320, 0, 0);
    FrameGeometry frame =
        build(entity(CgameFrame.EntityType.RAIL_RINGS, 0, end, start, "railDisc", -1));
    DynamicDraw draw = only(frame);
    // 320 / 32 = 10 segments; long shots drop one and start a segment in.
    assertEquals(9 * 4, draw.vertexCount());
    MeshBuffer mesh = frame.mesh();
    for (int disc = 0; disc < 9; disc++) {
      Vec3f center = Vec3f.ZERO;
      for (int j = 0; j < 4; j++)
        center = center.add(position(mesh, draw.firstVertex() + disc * 4 + j));
      center = center.scale(0.25f);
      assertEquals(32 * (disc + 1), center.x(), EPSILON);
      for (int j = 0; j < 4; j++) {
        Vec3f corner = position(mesh, draw.firstVertex() + disc * 4 + j).subtract(center);
        assertEquals(4, corner.length(), EPSILON, "a quarter of r_railWidth from the axis");
        assertEquals(0, corner.dot(X), EPSILON, "discs lie across the beam");
      }
    }
    FrameGeometry shortShot =
        build(
            entity(
                CgameFrame.EntityType.RAIL_RINGS, 0, new Vec3f(20, 0, 0), start, "railDisc", -1));
    assertEquals(4, only(shortShot).vertexCount(), "one disc at the start");
  }

  @Test
  void beamIsARedAdditiveTubeWhateverTheShader() {
    FrameGeometry frame =
        build(
            entity(
                CgameFrame.EntityType.BEAM, 0, Vec3f.ZERO, new Vec3f(0, 0, 100), "railCore", -1));
    DynamicDraw draw = only(frame);
    assertEquals(14, draw.vertexCount());
    assertEquals(36, draw.indexCount());
    MeshBuffer mesh = frame.mesh();
    for (int v = draw.firstVertex(); v < draw.firstVertex() + draw.vertexCount(); v++) {
      Vec3f p = position(mesh, v);
      assertEquals(4, Math.hypot(p.x(), p.y()), EPSILON);
      assertEquals(255, red(mesh, v));
      assertEquals(0, mesh.colors()[v * 4 + 1]);
    }
    MaterialProgram material = material(frame, draw);
    StageProgram stage = material.stages().getFirst();
    assertEquals(ShaderDefinition.BlendFactor.ONE, stage.state().sourceBlend());
    assertEquals(ShaderDefinition.BlendFactor.ONE, stage.state().destinationBlend());
    assertEquals(MaterialProgram.CullMode.TWO_SIDED, material.cull());
    assertEquals(16, material.sort(), "ordered by the entity shader's sort");
  }

  @Test
  void renderFlagsFollowTheOriginalPerViewRules() {
    Vec3f a = new Vec3f(100, 0, 0), b = new Vec3f(200, 0, 0);
    CgameFrame.RefEntity thirdSprite =
        entity(CgameFrame.EntityType.SPRITE, CgameFrame.RF_THIRD_PERSON, a, a, "railDisc", -1);
    CgameFrame.RefEntity firstCore =
        entity(
            CgameFrame.EntityType.RAIL_CORE,
            CgameFrame.RF_FIRST_PERSON | CgameFrame.RF_DEPTHHACK,
            a,
            b,
            "railCore",
            -1);
    CgameFrame.View view =
        new CgameFrame.View(refdef(0), List.of(thirdSprite, firstCore), List.of(), List.of());
    FrameBuilder builder = FrameBuilder.withoutWorld(registry());
    FrameGeometry main = builder.build(view, view.refdef().view(), null, new FrameGeometry());
    assertEquals(1, main.draws().size());
    assertEquals(1, main.draws().getFirst().entity());
    assertTrue(main.draws().getFirst().depthHack(), "RF_DEPTHHACK reaches the backend");
    FrameGeometry portal = builder.build(view, portal(view), null, new FrameGeometry());
    assertEquals(1, portal.draws().size());
    assertEquals(0, portal.draws().getFirst().entity());
  }

  @Test
  void shadowPlaneFlattensOpaqueSurfacesEvenForThirdPersonModels() {
    Md3Model model = TestMd3.triangle("body", TestMd3.UP);
    CgameFrame.RefEntity player =
        modelEntity(model, CgameFrame.RF_SHADOW_PLANE | CgameFrame.RF_THIRD_PERSON, -24);
    FrameGeometry frame = build(List.of(player), 0, List.of(), FrameBuilder.Options.DEFAULT);
    DynamicDraw shadow = only(frame);
    assertEquals(DynamicDraw.Source.SHADOW, shadow.source());
    assertEquals("projectionshadow", material(frame, shadow).name());
    for (int v = shadow.firstVertex(); v < shadow.firstVertex() + shadow.vertexCount(); v++)
      assertEquals(-24, position(frame.mesh(), v).z(), EPSILON, "on the shadow plane");

    CgameFrame.RefEntity visible = modelEntity(model, CgameFrame.RF_SHADOW_PLANE, -24);
    FrameGeometry both = build(List.of(visible), 0, List.of(), FrameBuilder.Options.DEFAULT);
    assertEquals(2, both.draws().size());
    assertEquals(DynamicDraw.Source.MODEL, both.draws().get(1).source());
    FrameBuilder.Options off = new FrameBuilder.Options(false, 16, 6, 32, false);
    assertEquals(1, build(List.of(visible), 0, List.of(), off).draws().size());
  }

  @Test
  void entitiesWithoutAWorldGetQ3DefaultLightingAndDynamicLights() {
    Md3Model model = TestMd3.triangle("body", TestMd3.UP);
    CgameFrame.RefEntity player = modelEntity(model, 0, 0);
    int noWorld = ViewDefinition.RDF_NOWORLDMODEL;
    DynamicDraw plain =
        only(build(List.of(player), noWorld, List.of(), FrameBuilder.Options.DEFAULT));
    assertEquals((150 + 32) / 255f, plain.ambient().x(), EPSILON);
    assertEquals(150 / 255f, plain.directed().x(), EPSILON);
    Vec3f sun = new Vec3f(0.45f, 0.3f, 0.9f).normalize();
    assertEquals(1, plain.lightDirection().dot(sun), EPSILON);

    // A light 100 units above adds 16 * r^2 / d^2 bytes of directed light (doubled to display).
    DynamicLight above = new DynamicLight(new Vec3f(0, 0, 100), 200, new Vec3f(1, 0, 0));
    DynamicDraw lit =
        only(build(List.of(player), noWorld, List.of(above), FrameBuilder.Options.DEFAULT));
    float added = 16f * 200 * 200 / (100 * 100) * 2 / 255;
    assertEquals(150 / 255f + added, lit.directed().x(), EPSILON);
    assertEquals(150 / 255f, lit.directed().y(), EPSILON);
    assertTrue(lit.lightDirection().z() > plain.lightDirection().z(), "pulled toward the light");
    assertEquals(plain.ambient(), lit.ambient());
    assertFalse(lit.source().projectsDynamicLights());
  }

  @Test
  void hyperspaceGreyRampsWithTheLowTimeBits() {
    assertTrue(refdef(ViewDefinition.RDF_HYPERSPACE).view().hyperspace());
    assertFalse(refdef(0).view().hyperspace());
    assertEquals(0, view(256).hyperspaceGrey(), EPSILON);
    assertEquals(128 / 255f, view(128 + 1024).hyperspaceGrey(), EPSILON);
    assertEquals(1, view(255).hyperspaceGrey(), EPSILON);
  }

  private static ViewDefinition view(int time) {
    ViewDefinition v = refdef(ViewDefinition.RDF_HYPERSPACE).view();
    return new ViewDefinition(
        v.kind(),
        v.origin(),
        v.basis(),
        v.fovX(),
        v.fovY(),
        v.x(),
        v.y(),
        v.width(),
        v.height(),
        time,
        v.rdflags(),
        v.areaMask(),
        v.clipPlane(),
        v.pvsOrigin(),
        v.portalSurface(),
        v.depth());
  }

  /** The registry each frame built by {@link #build} resolved its materials in. */
  private static final java.util.Map<FrameGeometry, MaterialRegistry> BUILT =
      new java.util.IdentityHashMap<>();

  private static FrameGeometry build(CgameFrame.RefEntity entity) {
    return build(List.of(entity), 0, List.of(), FrameBuilder.Options.DEFAULT);
  }

  private static FrameGeometry build(
      List<CgameFrame.RefEntity> entities,
      int rdflags,
      List<DynamicLight> lights,
      FrameBuilder.Options options) {
    CgameFrame.View view = new CgameFrame.View(refdef(rdflags), entities, List.of(), lights);
    MaterialRegistry registry = registry();
    FrameGeometry frame =
        new FrameBuilder(null, registry, options)
            .build(view, view.refdef().view(), null, new FrameGeometry());
    BUILT.put(frame, registry);
    return frame;
  }

  private static MaterialRegistry registry() {
    return new MaterialRegistry(LIBRARY::resolve);
  }

  private static DynamicDraw only(FrameGeometry frame) {
    assertEquals(1, frame.draws().size(), frame.draws().toString());
    return frame.draws().getFirst();
  }

  private static MaterialProgram material(FrameGeometry frame, DynamicDraw draw) {
    return BUILT.get(frame).program(draw.material());
  }

  private static CgameFrame.Refdef refdef(int rdflags) {
    return new CgameFrame.Refdef(
        0, 0, 640, 480, 90, 74, EYE, Y, X.negate(), Z, 1000, rdflags, AreaMask.NONE, List.of());
  }

  private static ViewDefinition portal(CgameFrame.View view) {
    ViewDefinition main = view.refdef().view();
    return new ViewDefinition(
        ViewDefinition.Kind.PORTAL,
        main.origin(),
        main.basis(),
        main.fovX(),
        main.fovY(),
        0,
        0,
        640,
        480,
        0,
        0,
        AreaMask.NONE,
        Optional.empty(),
        main.origin(),
        0,
        1);
  }

  private static CgameFrame.RefEntity entity(
      CgameFrame.EntityType type,
      int flags,
      Vec3f origin,
      Vec3f oldOrigin,
      String shader,
      int rgba) {
    return new CgameFrame.RefEntity(
        type,
        flags,
        null,
        -1,
        new Md3Model.Tag("", origin, X, Y, Z),
        oldOrigin,
        0,
        0,
        0,
        origin,
        0,
        0,
        Optional.empty(),
        Optional.of(shader),
        rgba,
        0,
        0,
        0,
        16,
        0);
  }

  private static CgameFrame.RefEntity modelEntity(Md3Model model, int flags, float shadowPlane) {
    Vec3f origin = new Vec3f(0, 0, 0);
    return new CgameFrame.RefEntity(
        CgameFrame.EntityType.MODEL,
        flags,
        model,
        -1,
        new Md3Model.Tag("", origin, X, Y, Z),
        origin,
        0,
        0,
        0,
        origin,
        shadowPlane,
        0,
        Optional.empty(),
        Optional.empty(),
        -1,
        0,
        0,
        0,
        0,
        0);
  }

  private static Vec3f position(MeshBuffer mesh, int v) {
    float[] p = mesh.positions();
    return new Vec3f(p[v * 3], p[v * 3 + 1], p[v * 3 + 2]);
  }

  private static float s(MeshBuffer mesh, int v) {
    return mesh.texCoords()[v * 2];
  }

  private static float t(MeshBuffer mesh, int v) {
    return mesh.texCoords()[v * 2 + 1];
  }

  private static int red(MeshBuffer mesh, int v) {
    return mesh.colors()[v * 4] & 255;
  }
}
