package dev.bluevista.craftq3.engine.formats.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.AlphaFunc;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.AlphaGenType;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.AutoSprite;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Blend;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Cull;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.DepthFunc;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.RgbGenType;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Scroll;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Sky;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Stage;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.TcGenType;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Transform;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.WaveFunction;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShaderParserTest {
  @Test
  void parsesLayeredCutoutWithExplicitDepthAndLightmap() {
    var result =
        ShaderParser.parse(
            "synthetic.shader",
            """
        /* unrelated { braces } */ TEXTURES\\SYNTHETIC\\GRATE.TGA {
          cull disable
          surfaceparm metalsteps
          {
            map "textures/synthetic/grate.tga" // preserve alpha
            alphaFunc GE128
            rgbGen identity
            depthWrite
          }
          {
            map $LIGHTMAP
            blendFunc filter
            depthFunc equal
          }
        }
        """);
    assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
    var shader = result.definitions().getFirst();
    assertEquals("textures/synthetic/grate", shader.name());
    assertEquals(Cull.NONE, shader.cull());
    assertEquals(3, shader.sort());
    assertTrue(shader.surfaceParms().contains("metalsteps"));
    assertEquals(AlphaFunc.GE128, shader.stages().getFirst().alphaFunc());
    assertTrue(shader.stages().getFirst().depthWrite());
    var lightmap = shader.stages().get(1);
    assertEquals(TcGenType.LIGHTMAP, lightmap.tcGen().type());
    assertEquals(RgbGenType.IDENTITY, lightmap.rgbGen().type());
    assertEquals(Blend.FILTER, lightmap.blend());
    assertEquals(DepthFunc.EQUAL, lightmap.depthFunc());
    assertFalse(lightmap.depthWrite());
  }

  @Test
  void preservesOrderedAnimationGeneratorsAndTextureModifiers() {
    var result =
        ShaderParser.parse(
            "effects.shader",
            """
        textures/synthetic/effect {
          polygonOffset
          nomipmaps
          clampTime 10
          {
            animMap 2 textures/a.tga textures/b.tga textures/c.tga
            blendFunc GL_SRC_ALPHA GL_ONE_MINUS_SRC_ALPHA
            rgbGen const ( .25 .5 1 )
            alphaGen wave triangle .5 .5 .25 2
            tcGen vector ( 1 0 0 ) ( 0 .5 0 )
            tcMod scroll .1 -.2
            tcMod scale 2 3
            tcMod rotate 45
            tcMod stretch sin 1 .5 0 2
            tcMod transform 1 2 3 4 5 6
            tcMod turb 0 .1 .2 .3
            tcMod entityTranslate
            detail
          }
          { clampmap textures/glow.jpg blendFunc add rgbGen wave noise .5 .1 0 1 }
        }
        """);
    assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
    var material = result.definitions().getFirst();
    assertTrue(material.noMipmaps());
    assertTrue(material.noPicmip());
    assertTrue(material.polygonOffset());
    assertEquals(4, material.sort());
    assertEquals(10, material.clampTime());
    Stage stage = material.stages().getFirst();
    assertEquals("textures/a.tga", stage.texture().atTime(0));
    assertEquals("textures/b.tga", stage.texture().atTime(.5));
    assertEquals("textures/a.tga", stage.texture().atTime(1.5));
    assertEquals(Blend.ALPHA, stage.blend());
    assertFalse(stage.depthWrite());
    assertEquals(new Vec3f(.25f, .5f, 1), stage.rgbGen().constant());
    assertEquals(WaveFunction.TRIANGLE, stage.alphaGen().wave().function());
    assertEquals(new Vec3f(0, .5f, 0), stage.tcGen().t());
    assertEquals(7, stage.tcMods().size());
    assertInstanceOf(Scroll.class, stage.tcMods().getFirst());
    assertEquals(new Transform(1, 2, 3, 4, 5, 6), stage.tcMods().get(4));
    assertTrue(stage.detail());
    assertTrue(material.stages().get(1).texture().clamp());
  }

  @Test
  void parsesSkyFogPortalAndAllVertexDeformations() {
    var result =
        ShaderParser.parse(
            "geometry.shader",
            """
        textures/synthetic/sky {
          surfaceparm sky
          skyParms env/test 256 -
          q3map_sun 1 1 1 100 45 60
          { map textures/clouds.tga }
        }
        textures/synthetic/moving {
          surfaceparm fog
          fogParms ( .2 .4 .6 ) 512
          portal
          deformVertexes wave 100 sin 0 4 0 1
          deformVertexes normal .2 .5
          deformVertexes bulge 1 2 3
          deformVertexes move 0 0 8 square 0 1 0 2
          deformVertexes autosprite
          deformVertexes autosprite2
          deformVertexes projectionShadow
          deformVertexes text3
          { map textures/portal.tga alphaGen portal 1024 }
        }
        """);
    assertTrue(result.diagnostics().isEmpty(), result.diagnostics().toString());
    assertEquals(2, result.definitions().getFirst().sort());
    assertEquals(
        new Sky("env/test", 256, "-"), result.definitions().getFirst().sky().orElseThrow());
    var moving = result.definitions().get(1);
    assertEquals(1, moving.sort());
    assertTrue(moving.portal());
    assertEquals(512, moving.fog().orElseThrow().depthForOpaque());
    assertEquals(8, moving.deforms().size());
    assertEquals(new AutoSprite(true), moving.deforms().get(5));
    assertEquals(1024, moving.stages().getFirst().alphaGen().portalRange());
  }

  @Test
  void unknownOrBadDirectiveCannotConsumeNextStageOrShader() {
    var result =
        ShaderParser.parse(
            "recover.shader",
            """
        textures/synthetic/a {
          futureGlobal 1 2 3
          {
            map textures/a.tga
            tcMod scale NaN 2
            unsupported value
            tcMod scroll 1 2
            rgbGen vertex
          }
          { map $lightmap blendFunc filter }
        }
        textures/synthetic/b {
          surfaceparm nodraw
        }
        """);
    assertEquals(3, result.diagnostics().size());
    assertTrue(result.diagnostics().stream().allMatch(d -> d.line() > 0));
    var first = result.definitions().getFirst();
    assertEquals(2, first.stages().size());
    assertEquals(List.of(new Scroll(1, 2)), first.stages().getFirst().tcMods());
    assertEquals(AlphaGenType.VERTEX, first.stages().getFirst().alphaGen().type());
    assertEquals(0, result.definitions().get(1).stages().size());
    assertTrue(result.definitions().get(1).noDraw());
  }

  @Test
  void skipsOnlyTheDamagedShaderAndRejectsOnlyResourceExhaustion() {
    for (String damaged :
        List.of(
            "textures/a {",
            "textures/a { { map x }",
            "textures/a { { { } } }",
            "textures/a { { map \"unterminated } }")) {
      var result =
          ShaderParser.parse("damaged", damaged + "\ntextures/next { surfaceparm nodraw }");
      assertFalse(result.diagnostics().isEmpty(), damaged);
      assertTrue(
          result.definitions().stream().noneMatch(d -> d.name().equals("textures/a")), damaged);
    }
    var recovered =
        ShaderParser.parse(
            "recover", "textures/a { { { } } }\ntextures/b { surfaceparm nodraw }\n} stray");
    assertEquals(
        List.of("textures/b"),
        recovered.definitions().stream().map(ShaderDefinition::name).toList());
    var comment = ShaderParser.parse("open", "textures/a { surfaceparm nodraw } /* open");
    assertEquals(1, comment.definitions().size());
    assertEquals(1, comment.diagnostics().size());
    var stages = ShaderParser.parse("stages", "textures/a {" + "{ map x }".repeat(65) + "}");
    assertTrue(stages.definitions().isEmpty());
    var mods =
        ShaderParser.parse(
            "mods", "textures/a { { map x\n" + "tcMod rotate 1\n".repeat(33) + "} }");
    assertTrue(mods.definitions().isEmpty());
    assertThrows(
        ShaderFormatException.class,
        () -> ShaderParser.parse("huge", " ".repeat(ShaderParser.MAX_SCRIPT_CHARS + 1)));
  }

  @Test
  void untrustedPathsStayWithinVfsAndDefaultStagesHaveCorrectState() {
    var result =
        ShaderParser.parse(
            "paths",
            """
        textures/a {
          { map ../outside }
          { map textures/a.tga blendFunc add }
        }
        """);
    assertEquals(
        "$whiteimage",
        result.definitions().getFirst().stages().getFirst().texture().frames().getFirst());
    assertEquals(2, result.diagnostics().size());
    ShaderDefinition implicit = ShaderDefinition.implicit("Textures/WALL.TGA", true);
    assertEquals(2, implicit.stages().size());
    assertEquals("textures/wall", implicit.name());
    assertEquals(Blend.OPAQUE, implicit.stages().getFirst().blend());
    assertEquals(TcGenType.LIGHTMAP, implicit.stages().get(1).tcGen().type());
    assertEquals(DepthFunc.EQUAL, implicit.stages().get(1).depthFunc());
    assertThrows(UnsupportedOperationException.class, () -> implicit.stages().clear());
  }
}
