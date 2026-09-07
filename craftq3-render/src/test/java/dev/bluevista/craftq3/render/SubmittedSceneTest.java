package dev.bluevista.craftq3.render;

import static dev.bluevista.craftq3.assets.shader.ShaderDefinition.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.assets.md3.Md3Model;
import dev.bluevista.craftq3.assets.shader.ShaderDefinition;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SubmittedSceneTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0),
      X = new Vec3(1, 0, 0),
      Y = new Vec3(0, 1, 0),
      Z = new Vec3(0, 0, 1);

  @Test
  void submissionsOwnTheirCommandsAndRetainFullRolledViewBasis() {
    var commands = new ArrayList<CgameFrame.Command>();
    var quad = new CgameFrame.Quad(10, 20, 30, 40, 0, 0, 1, 1, "hud", 0x12345678);
    commands.add(quad);
    var frame = new CgameFrame(commands, SceneAssets.EMPTY);
    commands.clear();
    assertEquals(List.of(quad), frame.commands());
    assertThrows(UnsupportedOperationException.class, () -> frame.commands().clear());
    byte[] bytes = {4};
    var view =
        new CgameFrame.Refdef(
            0,
            0,
            640,
            480,
            90,
            74,
            X,
            X,
            Z,
            Y.scale(-1),
            2000,
            0,
            new BspMap.Bytes(bytes),
            List.of());
    bytes[0] = 0;
    assertEquals(4, view.areaMask().unsigned(0));
    assertEquals(Z.scale(-1), view.basis().right());
    assertFalse(view.mirrored());
    assertEquals(0, view.camera().yaw());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CgameFrame.Refdef(
                0,
                0,
                0,
                480,
                90,
                74,
                ZERO,
                X,
                Y,
                Z,
                0,
                0,
                new BspMap.Bytes(new byte[0]),
                List.of()));
  }

  @Test
  void modelFramesEntityColorAndShaderTimeReachTheOriginalStageEvaluator() {
    var entity = entity(CgameFrame.EntityType.MODEL, 0, model(), 0x80402080);
    var stage =
        new Stage(
            new TextureMap(List.of("one", "two"), 1, false),
            Blend.ALPHA,
            AlphaFunc.NONE,
            RgbGen.of(RgbGenType.ENTITY),
            AlphaGen.of(AlphaGenType.ENTITY),
            TcGen.of(TcGenType.BASE),
            List.of(new Scroll(1, 0)),
            DepthFunc.LEQUAL,
            false,
            false);
    var shader =
        new ShaderDefinition(
            "model",
            List.of(stage),
            Cull.NONE,
            5,
            false,
            false,
            false,
            Set.of(),
            Optional.empty(),
            Optional.empty(),
            List.of(),
            false,
            0);
    var result =
        new SubmittedMaterials()
            .build(
                world(),
                new CgameFrame.View(ref(), List.of(entity), List.of(), List.of()),
                new SceneAssets(Map.of("model", shader), Map.of()),
                null,
                null,
                false);
    assertEquals(1, result.size());
    var batch = result.getFirst();
    assertEquals("two", batch.texture());
    assertEquals(0x80402080, batch.vertices().getFirst().rgba());
    assertEquals(.5, batch.vertices().getFirst().textureUv().u(), 1e-7);
    assertEquals(new Vec3(11.5, 0, 0), batch.vertices().getFirst().position());
    assertEquals(-1, batch.lightmap());
  }

  @Test
  void firstAndThirdPersonModelsSwitchInPortalsAndSpriteWindingSurvivesRoll() {
    var first = entity(CgameFrame.EntityType.MODEL, CgameFrame.RF_FIRST_PERSON, model(), -1);
    var third = entity(CgameFrame.EntityType.MODEL, CgameFrame.RF_THIRD_PERSON, model(), -1);
    var sprites = entity(CgameFrame.EntityType.SPRITE, 0, null, -1);
    var rolled =
        new CgameFrame.Refdef(
            0,
            0,
            640,
            480,
            90,
            74,
            ZERO,
            X,
            Z,
            Y.scale(-1),
            0,
            0,
            new BspMap.Bytes(new byte[0]),
            List.of());
    var view = new CgameFrame.View(rolled, List.of(first, third, sprites), List.of(), List.of());
    var builder = new SubmittedGeometry();
    var direct = builder.build(world(), view, false);
    var remote = builder.build(world(), view, true);
    assertEquals(2, direct.size());
    assertEquals(2, remote.size());
    assertSame(first, direct.getFirst().entity());
    assertSame(third, remote.getFirst().entity());
    var sprite = direct.get(1);
    var v = sprite.vertices();
    Vec3 cross =
        SubmittedGeometry.cross(
            SubmittedGeometry.sub(v.get(1).position(), v.get(0).position()),
            SubmittedGeometry.sub(v.get(2).position(), v.get(0).position()));
    assertTrue(SubmittedGeometry.dot(cross, v.getFirst().normal()) < 0);
    assertEquals(new Vec3(10, -4, -4), sprite.bounds().min());
    assertEquals(new Vec3(10, 4, 4), sprite.bounds().max());
  }

  @Test
  void hudPreservesStageOrderUvColorAndConvexPolyAttributes() {
    var stage =
        new Stage(
            new TextureMap(List.of("hud"), 0, true),
            Blend.ALPHA,
            AlphaFunc.NONE,
            RgbGen.of(RgbGenType.EXACT_VERTEX),
            AlphaGen.of(AlphaGenType.VERTEX),
            TcGen.of(TcGenType.BASE),
            List.of(),
            DepthFunc.LEQUAL,
            false,
            false);
    var shader =
        new ShaderDefinition(
            "hud",
            List.of(stage, stage),
            Cull.FRONT,
            3,
            false,
            true,
            false,
            Set.of(),
            Optional.empty(),
            Optional.empty(),
            List.of(),
            false,
            0);
    var batches =
        SubmittedMaterials.quad(
            new CgameFrame.Quad(10, 20, 30, 40, .1f, .2f, .8f, .9f, "hud", 0x10203040),
            new SceneAssets(Map.of("hud", shader), Map.of()),
            0);
    assertEquals(2, batches.size());
    assertTrue(batches.getFirst().overlay());
    assertEquals(new Vec3(40, 60, 0), batches.getFirst().vertices().get(2).position());
    assertEquals(new BspMap.Uv(.8f, .9f), batches.getFirst().vertices().get(2).textureUv());
    assertEquals(0x10203040, batches.getFirst().vertices().getFirst().rgba());
    var poly =
        new CgameFrame.Poly(
            "hud",
            List.of(
                new CgameFrame.PolyVertex(ZERO, new BspMap.Uv(0, 0), -1),
                new CgameFrame.PolyVertex(Y, new BspMap.Uv(1, 0), 0x10203040),
                new CgameFrame.PolyVertex(X, new BspMap.Uv(1, 1), -1),
                new CgameFrame.PolyVertex(X.add(Y.scale(-1)), new BspMap.Uv(0, 1), -1)));
    var geometry =
        new SubmittedGeometry()
            .build(world(), new CgameFrame.View(ref(), List.of(), List.of(poly), List.of()), false)
            .getFirst();
    assertEquals(6, geometry.vertices().size());
    assertEquals(0x10203040, geometry.vertices().get(1).rgba());
    assertNull(geometry.entity());
  }

  private static RenderScene world() {
    return new RenderScene("test", List.of(), ref().camera());
  }

  private static CgameFrame.Refdef ref() {
    return new CgameFrame.Refdef(
        0, 0, 640, 480, 90, 74, ZERO, X, Y, Z, 2000, 0, new BspMap.Bytes(new byte[0]), List.of());
  }

  private static CgameFrame.RefEntity entity(
      CgameFrame.EntityType type, int flags, Md3Model model, int rgba) {
    return new CgameFrame.RefEntity(
        type,
        flags,
        model,
        -1,
        new Md3Model.Tag("", new Vec3(10, 0, 0), X, Y, Z),
        new Vec3(20, 0, 0),
        1,
        0,
        .25f,
        ZERO,
        0,
        0,
        Optional.empty(),
        Optional.of("model"),
        rgba,
        new BspMap.Uv(0, 0),
        .5f,
        4,
        0);
  }

  private static Md3Model model() {
    var first =
        List.of(new Md3Model.Vertex(ZERO, Z), new Md3Model.Vertex(Y, Z), new Md3Model.Vertex(X, Z));
    var second =
        first.stream().map(v -> new Md3Model.Vertex(v.position().add(X.scale(2)), Z)).toList();
    var frame = new Md3Model.Frame(ZERO, new Vec3(3, 1, 0), ZERO, 4, "frame");
    var surface =
        new Md3Model.Surface(
            "body",
            0,
            List.of(new Md3Model.Shader("model", 0)),
            List.of(new Md3Model.Triangle(0, 1, 2)),
            List.of(
                new Md3Model.TexCoord(0, 0),
                new Md3Model.TexCoord(1, 0),
                new Md3Model.TexCoord(0, 1)),
            List.of(first, second));
    return new Md3Model(
        "original-fixture",
        0,
        List.of(frame, frame),
        List.of(List.of(), List.of()),
        List.of(surface));
  }
}
