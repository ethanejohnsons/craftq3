package dev.bluevista.craftq3.engine.render.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.BlendFactor;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.WaveFunction;
import dev.bluevista.craftq3.engine.formats.shader.ShaderLibrary;
import dev.bluevista.craftq3.engine.render.material.StageProgram.AlphaOp;
import dev.bluevista.craftq3.engine.render.material.StageProgram.AlphaTest;
import dev.bluevista.craftq3.engine.render.material.StageProgram.DepthTest;
import dev.bluevista.craftq3.engine.render.material.StageProgram.FogMode;
import dev.bluevista.craftq3.engine.render.material.StageProgram.RgbOp;
import dev.bluevista.craftq3.engine.render.material.StageProgram.TexGenOp;
import dev.bluevista.craftq3.engine.render.material.StageProgram.TexModOp;
import java.util.List;
import org.junit.jupiter.api.Test;

class MaterialCompilerTest {
  private static final String SCRIPT =
      """
      textures/test/everything
      {
        cull none
        polygonOffset
        nopicmip
        deformVertexes wave 64 sin 0 2 0.5 1
        deformVertexes normal 0.5 2
        deformVertexes bulge 1 2 3
        deformVertexes move 0 0 1 triangle 0 4 0 0.5
        {
          clampMap textures/test/base.tga
          rgbGen identityLighting
        }
        {
          animMap 5 textures/a.tga textures/b.tga textures/c.tga
          blendFunc add
          rgbGen wave inversesawtooth 0 1 0.25 2
          alphaGen const 0.5
          tcGen environment
          tcMod turb 0 0.1 0.2 0.3
          tcMod scale 2 3
          tcMod scroll 0.5 -0.5
          tcMod stretch square 1 0.5 0 1
          tcMod transform 1 2 3 4 5 6
          tcMod rotate 45
          tcMod entityTranslate
        }
        {
          map $lightmap
          blendFunc filter
          depthFunc equal
        }
        {
          map $whiteimage
          blendFunc blend
          alphaFunc GE128
          rgbGen const ( 0.25 0.5 0.75 )
          alphaGen portal 300
          tcGen vector ( 1 0 0 ) ( 0 1 0 )
          depthWrite
        }
        {
          map *white
          blendFunc GL_ONE GL_ONE_MINUS_SRC_ALPHA
          rgbGen lightingDiffuse
          alphaGen lightingSpecular
        }
      }
      models/sprites/flare
      {
        deformVertexes autosprite2
        deformVertexes text3
        {
          map models/sprites/flare.tga
          blendFunc GL_DST_COLOR GL_ZERO
          rgbGen entity
          alphaGen oneMinusEntity
        }
      }
      textures/sky/space
      {
        surfaceparm sky
        surfaceparm noimpact
        skyparms env/space 256 -
        {
          map textures/sky/clouds.tga
          tcMod scroll 0.01 0
        }
      }
      fog/water
      {
        surfaceparm fog
        fogparms ( 0.1 0.2 0.3 ) 512
      }
      textures/test/many
      {
        {
          map textures/test/many.tga
          tcMod scale 1 1
          tcMod scale 1 1
          tcMod scale 1 1
          tcMod scale 1 1
          tcMod scale 1 1
          tcMod scale 1 1
          tcMod scale 1 1
          tcMod scale 1 1
          tcMod scale 1 1
        }
      }
      """;

  private static final ShaderLibrary LIBRARY =
      ShaderLibrary.of(List.of(new ShaderLibrary.Script("scripts/test.shader", SCRIPT)));

  private static MaterialProgram program(String name) {
    return MaterialCompiler.compile(LIBRARY.find(name).orElseThrow());
  }

  @Test
  void compilesMaterialStateAndGpuDeforms() {
    MaterialProgram m = program("textures/test/everything");
    assertEquals(MaterialProgram.CullMode.TWO_SIDED, m.cull());
    assertTrue(m.polygonOffset());
    assertEquals(5, m.stages().size());
    assertTrue(m.unexpressed().isEmpty());
    assertEquals(4, m.deforms().size());
    assertEquals(DeformProgram.Op.WAVE, m.deforms().get(0).op());
    assertEquals(64, m.deforms().get(0).p0());
    assertEquals(WaveFunction.SIN, m.deforms().get(0).wave().function());
    assertEquals(DeformProgram.Op.NORMAL, m.deforms().get(1).op());
    assertEquals(0.5f, m.deforms().get(1).p0());
    assertEquals(2, m.deforms().get(1).p1());
    assertEquals(DeformProgram.Op.BULGE, m.deforms().get(2).op());
    assertEquals(3, m.deforms().get(2).p2());
    assertEquals(DeformProgram.Op.MOVE, m.deforms().get(3).op());
    assertEquals(1, m.deforms().get(3).p2());
    assertFalse(m.needsCpuDeform());
    assertTrue(m.deformsGeometry());
    assertTrue(m.opaque());
    assertTrue(m.fogPass());
    assertTrue(m.dynamicLightPass());
    assertTrue(m.dynamic());
    assertTrue(m.entityDependent());
  }

  @Test
  void compilesTextureBundlesBlendDepthAndAlphaState() {
    List<StageProgram> s = program("textures/test/everything").stages();
    TextureBundle base = s.get(0).texture();
    assertEquals(TextureBundle.Kind.IMAGE, base.kind());
    assertTrue(base.clamp());
    assertFalse(base.picmip());
    assertTrue(base.mipmaps());
    assertEquals(RgbOp.IDENTITY_LIGHTING, s.get(0).shading().rgbGen().op());
    assertTrue(s.get(0).state().opaque());
    assertTrue(s.get(0).state().depthWrite());

    TextureBundle anim = s.get(1).texture();
    assertEquals(TextureBundle.Kind.ANIMATED, anim.kind());
    assertEquals(5, anim.frequency());
    assertEquals("textures/a.tga", anim.imageAt(0.1));
    assertEquals("textures/b.tga", anim.imageAt(0.2));
    assertEquals("textures/a.tga", anim.imageAt(0.6));
    assertEquals(BlendFactor.ONE, s.get(1).state().sourceBlend());
    assertEquals(BlendFactor.ONE, s.get(1).state().destinationBlend());
    assertEquals(FogMode.NONE, s.get(1).state().fogMode(), "opaque materials fog in a pass");
    assertEquals(RgbOp.WAVE, s.get(1).shading().rgbGen().op());
    assertEquals(WaveFunction.INVERSE_SAWTOOTH, s.get(1).shading().rgbGen().wave().function());
    assertEquals(AlphaOp.CONSTANT, s.get(1).shading().alphaGen().op());
    assertEquals(TexGenOp.ENVIRONMENT, s.get(1).shading().texGen().op());
    List<TexModOp> ops =
        s.get(1).shading().texMods().stream().map(StageProgram.TexMod::op).toList();
    assertEquals(
        List.of(
            TexModOp.TURB,
            TexModOp.SCALE,
            TexModOp.SCROLL,
            TexModOp.STRETCH,
            TexModOp.TRANSFORM,
            TexModOp.ROTATE,
            TexModOp.ENTITY_TRANSLATE),
        ops);
    StageProgram.TexMod turb = s.get(1).shading().texMods().getFirst();
    assertEquals(0.1f, turb.wave().amplitude());
    assertEquals(0.2f, turb.wave().phase());
    assertEquals(0.3f, turb.wave().frequency());
    StageProgram.TexMod transform = s.get(1).shading().texMods().get(4);
    assertEquals(6, transform.p5());

    assertEquals(TextureBundle.Kind.LIGHTMAP, s.get(2).texture().kind());
    assertEquals(TexGenOp.LIGHTMAP, s.get(2).shading().texGen().op());
    assertEquals(DepthTest.EQUAL, s.get(2).state().depthTest());
    assertFalse(s.get(2).state().depthWrite());

    assertEquals(TextureBundle.Kind.WHITE, s.get(3).texture().kind());
    assertEquals(AlphaTest.GE128, s.get(3).state().alphaTest());
    assertTrue(s.get(3).state().depthWrite());
    assertEquals(new Vec3f(0.25f, 0.5f, 0.75f), s.get(3).shading().rgbGen().constant());
    assertEquals(AlphaOp.PORTAL, s.get(3).shading().alphaGen().op());
    assertEquals(300, s.get(3).shading().alphaGen().portalRange());
    assertEquals(TexGenOp.VECTOR, s.get(3).shading().texGen().op());

    assertEquals(TextureBundle.Kind.WHITE, s.get(4).texture().kind());
    assertEquals(TextureBundle.WHITE, s.get(4).texture().imageAt(0));
    assertEquals(RgbOp.LIGHTING_DIFFUSE, s.get(4).shading().rgbGen().op());
    assertEquals(AlphaOp.LIGHTING_SPECULAR, s.get(4).shading().alphaGen().op());
  }

  @Test
  void cpuDeformsSkyFogAndTranslucentFogModes() {
    MaterialProgram sprite = program("models/sprites/flare");
    assertTrue(sprite.needsCpuDeform());
    assertEquals(DeformProgram.Op.AUTOSPRITE2, sprite.deforms().get(0).op());
    assertEquals(DeformProgram.Op.TEXT, sprite.deforms().get(1).op());
    assertEquals(3, sprite.deforms().get(1).p0());
    assertFalse(sprite.opaque());
    assertFalse(sprite.fogPass());
    assertEquals(FogMode.BLEND_TO_WHITE, sprite.stages().getFirst().state().fogMode());
    assertEquals(RgbOp.ENTITY, sprite.stages().getFirst().shading().rgbGen().op());
    assertEquals(AlphaOp.ONE_MINUS_ENTITY, sprite.stages().getFirst().shading().alphaGen().op());

    MaterialProgram sky = program("textures/sky/space");
    assertTrue(sky.isSky());
    assertEquals("env/space", sky.sky().orElseThrow().farBox());
    assertEquals(256, sky.sky().orElseThrow().cloudHeight());
    assertEquals(2, sky.sort());
    assertFalse(sky.fogPass());
    assertFalse(sky.dynamicLightPass());

    MaterialProgram fog = program("fog/water");
    assertEquals(512, fog.fog().orElseThrow().depthForOpaque());
    assertEquals(new Vec3f(0.1f, 0.2f, 0.3f), fog.fog().orElseThrow().color());
    assertTrue(fog.stages().isEmpty());

    assertEquals(FogMode.MODULATE_ALPHA, MaterialCompiler.fogMode(ShaderDefinition.Blend.ALPHA));
    assertEquals(
        FogMode.MODULATE_RGBA,
        MaterialCompiler.fogMode(
            new ShaderDefinition.Blend(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA)));
    assertEquals(FogMode.MODULATE_RGB, MaterialCompiler.fogMode(ShaderDefinition.Blend.ADD));
  }

  @Test
  void featuresBeyondTheEncodingAreCounted() {
    MaterialProgram many = program("textures/test/many");
    assertEquals(ProgramLayout.MAX_TEX_MODS, many.stages().getFirst().shading().texMods().size());
    assertEquals(1, many.unexpressed().size());
  }

  @Test
  void implicitMaterialsCompileToLightmappedOrVertexLitPrograms() {
    MaterialProgram lit = MaterialCompiler.compile(ShaderDefinition.implicit("textures/x", true));
    assertEquals(2, lit.stages().size());
    assertEquals(TextureBundle.Kind.IMAGE, lit.stages().get(0).texture().kind());
    assertEquals("textures/x", lit.stages().get(0).texture().imageAt(0));
    assertEquals(TextureBundle.Kind.LIGHTMAP, lit.stages().get(1).texture().kind());
    MaterialProgram vertex =
        MaterialCompiler.compile(ShaderDefinition.implicit("textures/x", false));
    assertEquals(RgbOp.VERTEX, vertex.stages().getFirst().shading().rgbGen().op());
    assertEquals(MaterialProgram.CullMode.FRONT_SIDED, vertex.cull());
    assertEquals(MaterialCompiler.compile(ShaderDefinition.implicit("textures/x", true)), lit);
  }

  @Test
  void packedLayoutRoundTripsEveryStageAndDeform() {
    for (String name : LIBRARY.definitions().keySet()) {
      MaterialProgram m = program(name);
      float[] block = new float[ProgramLayout.STAGE_FLOATS];
      for (StageProgram stage : m.stages()) {
        ProgramLayout.packStage(stage, block, 0);
        assertEquals(stage.shading(), ProgramLayout.unpackShading(block, 0), name);
        assertEquals(stage.state().alphaTest(), ProgramLayout.unpackAlphaTest(block, 0));
        assertEquals(stage.state().fogMode(), ProgramLayout.unpackFogMode(block, 0));
      }
      float[] deforms = new float[ProgramLayout.DEFORM_FLOATS + 3];
      ProgramLayout.packDeforms(m.deforms(), deforms, 3);
      assertEquals(m.deforms(), ProgramLayout.unpackDeforms(deforms, 3), name);
    }
    assertEquals(0, ProgramLayout.STAGE_FLOATS % 4);
    assertEquals(0, ProgramLayout.DEFORM_FLOATS % 4);
  }
}
