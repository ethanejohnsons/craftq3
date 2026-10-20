package dev.bluevista.craftq3.engine.render.frame;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.bsp.BspFixture;
import dev.bluevista.craftq3.engine.formats.bsp.BspReader;
import dev.bluevista.craftq3.engine.formats.image.Q3Image;
import dev.bluevista.craftq3.engine.formats.md3.Md3Model;
import dev.bluevista.craftq3.engine.formats.shader.ShaderLibrary;
import dev.bluevista.craftq3.engine.render.AreaMask;
import dev.bluevista.craftq3.engine.render.MeshBuffer;
import dev.bluevista.craftq3.engine.render.RenderScene;
import dev.bluevista.craftq3.engine.render.ViewDefinition;
import dev.bluevista.craftq3.engine.render.VisibleSet;
import dev.bluevista.craftq3.engine.render.material.MaterialProgram;
import dev.bluevista.craftq3.engine.render.material.TextureBundle;
import dev.bluevista.craftq3.engine.render.material.VertexArrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Ported from the legacy {@code SubmittedSceneTest} and {@code CinematicImageTest}. */
class FrameBuilderTest {
  private static final Vec3f X = new Vec3f(1, 0, 0), Y = new Vec3f(0, 1, 0), Z = new Vec3f(0, 0, 1);

  private static final ShaderLibrary LIBRARY =
      ShaderLibrary.of(
          List.of(
              new ShaderLibrary.Script(
                  "scripts/frame.shader",
                  """
                  model
                  {
                    cull none
                    {
                      animMap 1 one two
                      blendFunc blend
                      rgbGen entity
                      alphaGen entity
                      tcMod scroll 1 0
                    }
                  }
                  hud
                  {
                    nomipmaps
                    {
                      clampMap hud
                      blendFunc blend
                      rgbGen exactVertex
                      alphaGen vertex
                    }
                    {
                      clampMap hud
                      blendFunc blend
                      rgbGen exactVertex
                      alphaGen vertex
                    }
                  }
                  textures/flare
                  {
                    deformVertexes autosprite
                    {
                      map textures/flare.tga
                      blendFunc add
                    }
                  }
                  """)));

  @Test
  void submissionsOwnTheirCommandsAndRetainFullRolledViewBasis() {
    List<CgameFrame.Command> commands = new ArrayList<>();
    CgameFrame.Quad quad = new CgameFrame.Quad(10, 20, 30, 40, 0, 0, 1, 1, "hud", 0x12345678);
    commands.add(quad);
    CgameFrame frame = new CgameFrame(commands, SceneAssets.EMPTY);
    commands.clear();
    assertEquals(List.of(quad), frame.commands());
    assertThrows(UnsupportedOperationException.class, () -> frame.commands().clear());
    byte[] bytes = {4};
    CgameFrame.Refdef view =
        new CgameFrame.Refdef(
            0, 0, 640, 480, 90, 74, X, X, Z, Y.negate(), 2000, 0, AreaMask.of(bytes), List.of());
    bytes[0] = 0;
    assertEquals(4, view.areaMask().unsigned(0));
    assertTrue(view.areaMask().hidden(2));
    assertEquals(Z.negate(), view.basis().right());
    assertFalse(view.mirrored());
    assertEquals(0, view.camera().yaw());
    assertEquals(ViewDefinition.Kind.MAIN, view.view().kind());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CgameFrame.Refdef(
                0, 0, 0, 480, 90, 74, Vec3f.ZERO, X, Y, Z, 0, 0, AreaMask.NONE, List.of()));
  }

  @Test
  void modelFramesEntityColorAndShaderTimeReachTheStageEvaluator() {
    RenderScene scene = scene();
    FrameBuilder builder = new FrameBuilder(scene, scene.newMaterialRegistry());
    CgameFrame.RefEntity entity =
        entity(CgameFrame.EntityType.MODEL, 0, TestMd3.triangle("model", TestMd3.UP), 0x80402080);
    CgameFrame.View view = new CgameFrame.View(ref(), List.of(entity), List.of(), List.of());
    FrameGeometry frame = builder.build(view, view.refdef().view(), null, new FrameGeometry());
    assertEquals(1, frame.draws().size());
    DynamicDraw draw = frame.draws().getFirst();
    MaterialProgram material = builder.materials().program(draw.material());
    double seconds = material.shaderTime(draw.shaderSeconds());
    assertEquals(1.5, seconds, 1e-9);
    assertEquals("two", material.stages().getFirst().texture().imageAt(seconds));
    float[] uv = new float[draw.vertexCount() * 2];
    byte[] rgba = new byte[draw.vertexCount() * 4];
    FallbackShading.shade(
        frame,
        draw,
        material,
        material.stages().getFirst(),
        Vec3f.ZERO,
        List.of(),
        scene.lightGrid(),
        scene.fog(),
        uv,
        rgba);
    assertEquals(0x80402080, new VertexArrays(new float[0], new float[0], uv, uv, rgba).rgba(0));
    assertEquals(.5, uv[0], 1e-6);
    assertEquals(
        new Vec3f(11.5f, 0, 0), ModelGeometryTest.position(frame.mesh(), draw.firstVertex()));
    assertEquals(-1, draw.lightmap());
    assertEquals(DynamicDraw.Source.MODEL, draw.source());
  }

  @Test
  void firstAndThirdPersonModelsSwitchInPortalsAndSpriteWindingSurvivesRoll() {
    RenderScene scene = scene();
    Md3Model model = TestMd3.triangle("model", TestMd3.UP);
    CgameFrame.RefEntity first =
        entity(CgameFrame.EntityType.MODEL, CgameFrame.RF_FIRST_PERSON, model, -1);
    CgameFrame.RefEntity third =
        entity(CgameFrame.EntityType.MODEL, CgameFrame.RF_THIRD_PERSON, model, -1);
    CgameFrame.RefEntity sprite = entity(CgameFrame.EntityType.SPRITE, 0, null, -1);
    CgameFrame.Refdef rolled =
        new CgameFrame.Refdef(
            0, 0, 640, 480, 90, 74, Vec3f.ZERO, X, Z, Y.negate(), 0, 0, AreaMask.NONE, List.of());
    CgameFrame.View view =
        new CgameFrame.View(rolled, List.of(first, third, sprite), List.of(), List.of());
    FrameBuilder builder = new FrameBuilder(scene, scene.newMaterialRegistry());
    ViewDefinition main = rolled.view();
    ViewDefinition portal =
        new ViewDefinition(
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
    FrameGeometry direct = builder.build(view, main, null, new FrameGeometry());
    FrameGeometry remote = builder.build(view, portal, null, new FrameGeometry());
    assertEquals(2, direct.draws().size());
    assertEquals(2, remote.draws().size());
    assertEquals(0, direct.draws().getFirst().entity());
    assertEquals(1, remote.draws().getFirst().entity());
    DynamicDraw quad = direct.draws().get(1);
    assertEquals(DynamicDraw.Source.SPRITE, quad.source());
    MeshBuffer mesh = direct.mesh();
    int[] i = mesh.indices();
    Vec3f a = ModelGeometryTest.position(mesh, i[quad.firstIndex()]);
    Vec3f b = ModelGeometryTest.position(mesh, i[quad.firstIndex() + 1]);
    Vec3f c = ModelGeometryTest.position(mesh, i[quad.firstIndex() + 2]);
    Vec3f normal = ModelGeometryTest.normal(mesh, i[quad.firstIndex()]);
    assertTrue(b.subtract(a).cross(c.subtract(a)).dot(normal) < 0);
    assertEquals(10, quad.bound(0), 1e-5);
    assertEquals(-4, quad.bound(1), 1e-5);
    assertEquals(-4, quad.bound(2), 1e-5);
    assertEquals(4, quad.bound(4), 1e-5);
    assertEquals(4, quad.bound(5), 1e-5);
  }

  @Test
  void hudPreservesStageOrderUvColorAndConvexPolyAttributes() {
    RenderScene scene = scene();
    FrameBuilder builder = new FrameBuilder(scene, scene.newMaterialRegistry());
    FrameGeometry overlay = new FrameGeometry();
    builder.overlay(
        new CgameFrame.Quad(10, 20, 30, 40, .1f, .2f, .8f, .9f, "hud", 0x10203040), 0, overlay);
    DynamicDraw hud = overlay.draws().getFirst();
    MaterialProgram material = builder.materials().program(hud.material());
    assertEquals(2, material.stages().size());
    assertFalse(material.stages().getFirst().texture().mipmaps());
    MeshBuffer mesh = overlay.mesh();
    assertEquals(new Vec3f(40, 60, 0), ModelGeometryTest.position(mesh, hud.firstVertex() + 2));
    assertEquals(.8f, mesh.texCoords()[(hud.firstVertex() + 2) * 2]);
    assertEquals(.9f, mesh.texCoords()[(hud.firstVertex() + 2) * 2 + 1]);
    assertEquals(0x10203040, mesh.arrays().rgba(hud.firstVertex()));
    CgameFrame.Poly poly =
        new CgameFrame.Poly(
            "hud",
            new float[] {0, 0, 0, 0, 1, 0, 1, 0, 0, 1, -1, 0},
            new float[] {0, 0, 1, 0, 1, 1, 0, 1},
            new int[] {-1, 0x10203040, -1, -1});
    CgameFrame.View view = new CgameFrame.View(ref(), List.of(), List.of(poly), List.of());
    FrameGeometry frame = builder.build(view, view.refdef().view(), null, new FrameGeometry());
    DynamicDraw draw = frame.draws().getFirst();
    assertEquals(6, draw.indexCount());
    assertEquals(4, draw.vertexCount());
    assertEquals(0x10203040, frame.mesh().arrays().rgba(draw.firstVertex() + 1));
    assertEquals(-1, draw.entity());
    assertEquals(DynamicDraw.Source.POLY, draw.source());
  }

  @Test
  void cinematicFramesBecomeOpaqueOverlaysOfTheirStream() {
    RenderScene scene = scene();
    FrameBuilder builder = new FrameBuilder(scene, scene.newMaterialRegistry());
    CgameFrame.Image movie = new CgameFrame.Image(42, 13, 17, 512, 256, pixels(2, 2));
    CgameFrame.Quad overlayQuad = new CgameFrame.Quad(0, 0, 5, 5, 0, 0, 1, 1, "$whiteimage", -1);
    CgameFrame frame =
        new CgameFrame(List.of(overlayQuad, movie, overlayQuad), SceneAssets.EMPTY, 100);
    assertEquals(movie, frame.commands().get(1));
    FrameGeometry out = new FrameGeometry();
    builder.overlay(movie, out);
    DynamicDraw draw = out.draws().getFirst();
    MaterialProgram material = builder.materials().program(draw.material());
    assertEquals(TextureBundle.Kind.BUILTIN, material.stages().getFirst().texture().kind());
    assertEquals("$cinematic/42", material.stages().getFirst().texture().imageAt(0));
    assertTrue(material.stages().getFirst().state().opaque());
    assertEquals(4, draw.vertexCount());
    assertEquals(13, draw.bound(0));
    assertEquals(17, draw.bound(1));
    assertEquals(525, draw.bound(3));
    assertEquals(273, draw.bound(4));
    float[] st = out.mesh().texCoords();
    assertEquals(0, st[draw.firstVertex() * 2]);
    assertEquals(1, st[(draw.firstVertex() + 2) * 2 + 1]);
    for (int v = 0; v < 4; v++) assertEquals(-1, out.mesh().arrays().rgba(draw.firstVertex() + v));
  }

  @Test
  void rejectsInvalidCinematicExtentsAndConflictingSnapshots() {
    Q3Image image = pixels(1, 1);
    assertThrows(IllegalArgumentException.class, () -> new CgameFrame.Image(-1, 0, 0, 1, 1, image));
    assertThrows(
        IllegalArgumentException.class, () -> new CgameFrame.Image(0, Float.NaN, 0, 1, 1, image));
    assertThrows(IllegalArgumentException.class, () -> new CgameFrame.Image(0, 0, 0, 0, 1, image));
    CgameFrame.Image a = new CgameFrame.Image(1, 0, 0, 1, 1, image);
    CgameFrame.Image b = new CgameFrame.Image(1, 2, 2, 1, 1, image);
    assertDoesNotThrow(() -> new CgameFrame(List.of(a, b), SceneAssets.EMPTY));
    CgameFrame.Image changed = new CgameFrame.Image(1, 0, 0, 1, 1, pixels(1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CgameFrame(List.of(a, changed), SceneAssets.EMPTY));
  }

  @Test
  void boundsDistinctCinematicImagesWhileAllowingRepeatedDraws() {
    List<CgameFrame.Command> commands = new ArrayList<>();
    Q3Image tiny = pixels(1, 1);
    for (int i = 0; i < 33; i++) commands.add(new CgameFrame.Image(i, 0, 0, 1, 1, tiny));
    assertThrows(IllegalArgumentException.class, () -> new CgameFrame(commands, SceneAssets.EMPTY));
    commands.clear();
    Q3Image large = pixels(2048, 2048);
    for (int i = 0; i < 4; i++) commands.add(new CgameFrame.Image(i, 0, 0, 1, 1, large));
    assertDoesNotThrow(() -> new CgameFrame(commands, SceneAssets.EMPTY));
    commands.add(new CgameFrame.Image(0, 0, 0, 1, 1, large));
    assertDoesNotThrow(() -> new CgameFrame(commands, SceneAssets.EMPTY));
    commands.add(new CgameFrame.Image(4, 0, 0, 1, 1, large));
    assertThrows(IllegalArgumentException.class, () -> new CgameFrame(commands, SceneAssets.EMPTY));
  }

  @Test
  void cpuDeformedWorldSurfacesAndInlineModelsComeFromTheStaticBuffers() {
    RenderScene scene =
        RenderScene.build(
            "flare",
            BspReader.read(BspFixture.map(false)),
            (name, lightmapped) -> LIBRARY.resolve("textures/flare", lightmapped));
    VisibleSet visible = new VisibleSet();
    CgameFrame.Refdef refdef =
        new CgameFrame.Refdef(
            0,
            0,
            640,
            480,
            90,
            74,
            new Vec3f(0, 0, 500),
            Z.negate(),
            Y,
            X,
            0,
            0,
            AreaMask.NONE,
            List.of());
    scene.visibility().select(refdef.view(), true, true, visible);
    assertEquals(1, visible.cpuSurfaceCount());
    assertEquals(0, visible.batchCount());
    CgameFrame.RefEntity door = entity(CgameFrame.EntityType.MODEL, 0, null, -1);
    door =
        new CgameFrame.RefEntity(
            door.type(),
            0,
            null,
            0,
            door.transform(),
            door.oldOrigin(),
            0,
            0,
            0,
            Vec3f.ZERO,
            0,
            0,
            Optional.empty(),
            Optional.empty(),
            -1,
            0,
            0,
            0,
            0,
            0);
    CgameFrame.View view = new CgameFrame.View(refdef, List.of(door), List.of(), List.of());
    FrameBuilder builder =
        new FrameBuilder(scene, scene.newMaterialRegistry(), new FrameBuilder.Options(true));
    FrameGeometry frame = builder.build(view, refdef.view(), visible, new FrameGeometry());
    DynamicDraw world = frame.draws().getFirst();
    assertEquals(DynamicDraw.Source.WORLD, world.source());
    assertEquals(0, world.surface());
    assertEquals(scene.world().surfaceMaterial(0), world.material());
    assertEquals(1, frame.inlineModels().size());
    FrameGeometry.InlineInstance instance = frame.inlineModels().getFirst();
    assertEquals(0, instance.model());
    assertFalse(instance.mirrored());
    DynamicDraw inline = frame.draws().get(1);
    assertEquals(DynamicDraw.Source.INLINE, inline.source());
    // The door is the world model moved by its transform origin (10, 0, 0).
    float[] world0 = scene.world().vertexArrays().positions();
    assertEquals(world0[0] + 10, frame.mesh().positions()[inline.firstVertex() * 3], 1e-5);
  }

  private static RenderScene scene() {
    return RenderScene.build("test", BspReader.read(BspFixture.map(false)), LIBRARY::resolve);
  }

  private static CgameFrame.Refdef ref() {
    return new CgameFrame.Refdef(
        0, 0, 640, 480, 90, 74, Vec3f.ZERO, X, Y, Z, 2000, 0, AreaMask.NONE, List.of());
  }

  private static CgameFrame.RefEntity entity(
      CgameFrame.EntityType type, int flags, Md3Model model, int rgba) {
    return new CgameFrame.RefEntity(
        type,
        flags,
        model,
        -1,
        new Md3Model.Tag("", new Vec3f(10, 0, 0), X, Y, Z),
        new Vec3f(20, 0, 0),
        1,
        0,
        .25f,
        Vec3f.ZERO,
        0,
        0,
        Optional.empty(),
        Optional.of("model"),
        rgba,
        0,
        0,
        .5f,
        4,
        0);
  }

  private static Q3Image pixels(int width, int height) {
    return new Q3Image(width, height, new byte[width * height * 4]);
  }
}
