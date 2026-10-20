package dev.bluevista.craftq3.render.material;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspMap.Uv;
import dev.bluevista.craftq3.assets.bsp.BspMap.Vertex;
import dev.bluevista.craftq3.assets.shader.ShaderDefinition;
import dev.bluevista.craftq3.assets.shader.ShaderDefinition.*;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.render.RenderScene.Camera;
import java.util.List;
import org.junit.jupiter.api.Test;

class StageEvaluatorTest {
  private static final Camera CAMERA = new Camera(new Vec3(0, 0, 128), 0, 0, 90);
  private static final Vertex VERTEX =
      new Vertex(
          new Vec3(0, 0, 0),
          new Uv(0.25f, 0.75f),
          new Uv(0.1f, 0.2f),
          new Vec3(0, 0, 1),
          0x4080c040);

  @Test
  void waveformsRespectPhaseAmplitudeAndNegativeCycles() {
    assertEquals(5, StageEvaluator.wave(new Wave(WaveFunction.SIN, 2, 3, 0.25f, 0), 0), 1e-8);
    for (WaveFunction function : WaveFunction.values()) {
      double value = StageEvaluator.wave(new Wave(function, 0, 1, 0, 1), 0.25);
      assertTrue(value >= -1 && value <= 1);
    }
    for (double[] point :
        new double[][] {{0, 0}, {0.25, 1}, {0.5, 0}, {0.75, -1}, {1, 0}, {-0.25, -1}}) {
      assertEquals(
          point[1],
          StageEvaluator.wave(new Wave(WaveFunction.TRIANGLE, 0, 1, 0, 1), point[0]),
          1e-8);
    }
    assertEquals(-1, StageEvaluator.wave(new Wave(WaveFunction.SQUARE, 0, 1, 0, 1), 0.5));
    assertEquals(0.75, StageEvaluator.wave(new Wave(WaveFunction.SAWTOOTH, 0, 1, 0, 1), -0.25));
    assertEquals(
        0.25, StageEvaluator.wave(new Wave(WaveFunction.INVERSE_SAWTOOTH, 0, 1, 0, 1), -0.25));
  }

  @Test
  void tcModsApplyInScriptOrderAndTransformUsesQ3MatrixConvention() {
    Stage stage =
        stage(TcGen.of(TcGenType.BASE), List.of(new Scroll(0.5f, -0.25f), new Scale(2, 3)));
    Uv coordinates = evaluate(stage, 1).textureUv();
    assertEquals(1.5, coordinates.u(), 1e-6);
    assertEquals(4.5, coordinates.v(), 1e-6);
    Uv matrix =
        evaluate(stage(TcGen.of(TcGenType.BASE), List.of(new Transform(2, 3, 4, 5, 6, 7))), 0)
            .textureUv();
    assertEquals(9.5, matrix.u(), 1e-6);
    assertEquals(11.5, matrix.v(), 1e-6);
  }

  @Test
  void rotatesAndStretchesAroundTextureCenter() {
    Uv rotated = evaluate(stage(TcGen.of(TcGenType.BASE), List.of(new Rotate(90))), 1).textureUv();
    assertEquals(0.75, rotated.u(), 1e-6);
    assertEquals(0.75, rotated.v(), 1e-6);
    Uv stretched =
        evaluate(
                stage(
                    TcGen.of(TcGenType.BASE),
                    List.of(new Stretch(new Wave(WaveFunction.SIN, 2, 0, 0, 0)))),
                0)
            .textureUv();
    assertEquals(0.375, stretched.u(), 1e-6);
    assertEquals(0.625, stretched.v(), 1e-6);
  }

  @Test
  void selectsLightmapVectorAndEnvironmentCoordinates() {
    assertEquals(
        VERTEX.lightmapUv(),
        evaluate(stage(TcGen.of(TcGenType.LIGHTMAP), List.of()), 0).textureUv());
    assertEquals(
        new Uv(0.5f, 0),
        evaluate(stage(TcGen.of(TcGenType.ENVIRONMENT), List.of()), 0).textureUv());
    Vertex source =
        new Vertex(
            new Vec3(3, 4, 5),
            VERTEX.textureUv(),
            VERTEX.lightmapUv(),
            VERTEX.normal(),
            VERTEX.rgba());
    Stage vector =
        stage(new TcGen(TcGenType.VECTOR, new Vec3(2, 0, 0), new Vec3(0, 3, 0)), List.of());
    assertEquals(
        new Uv(6, 12),
        StageEvaluator.evaluate(source, vector, StageEvaluator.Context.defaults(CAMERA, 0))
            .textureUv());
  }

  @Test
  void turbulenceUsesWorldPositionAndIgnoresUnusedBaseValue() {
    Vertex source =
        new Vertex(
            new Vec3(256, 0, 0),
            VERTEX.textureUv(),
            VERTEX.lightmapUv(),
            VERTEX.normal(),
            VERTEX.rgba());
    Stage stage = stage(TcGen.of(TcGenType.BASE), List.of(new Turbulence(100, 0.5f, 0, 0)));
    Uv uv =
        StageEvaluator.evaluate(source, stage, StageEvaluator.Context.defaults(CAMERA, 0))
            .textureUv();
    assertEquals(0.75, uv.u(), 1e-6);
    assertEquals(0.75, uv.v(), 1e-6);
  }

  @Test
  void colorGeneratorsPreserveIndependentVertexAlphaAndEntityState() {
    Stage vertex = colors(RgbGen.of(RgbGenType.EXACT_VERTEX), AlphaGen.of(AlphaGenType.VERTEX));
    assertEquals(VERTEX.rgba(), evaluate(vertex, 0).rgba());
    Stage inverse =
        colors(RgbGen.of(RgbGenType.ONE_MINUS_VERTEX), AlphaGen.of(AlphaGenType.ONE_MINUS_VERTEX));
    assertEquals(0xbf7f3fbf, evaluate(inverse, 0).rgba());
    var context =
        new StageEvaluator.Context(
            CAMERA,
            0,
            new Vec3(0.2, 0.4, 0.6),
            0.8f,
            new Vec3(0, 0, 1),
            new Vec3(0.1, 0.1, 0.1),
            new Vec3(0.2, 0.3, 0.4),
            new Vec3(0.5, 0.5, 0.5),
            new Vec3(0.25, 0.5, 0),
            1);
    assertEquals(
        0x336699cc,
        StageEvaluator.evaluate(
                VERTEX,
                colors(RgbGen.of(RgbGenType.ENTITY), AlphaGen.of(AlphaGenType.ENTITY)),
                context)
            .rgba());
    assertEquals(
        new Uv(0.5f, 1.25f),
        StageEvaluator.evaluate(
                VERTEX, stage(TcGen.of(TcGenType.BASE), List.of(new EntityTranslate())), context)
            .textureUv());
  }

  @Test
  void lightingAndPortalGeneratorsRespondToNormalAndCamera() {
    assertEquals(
        0xffffffff,
        evaluate(
                colors(
                    RgbGen.of(RgbGenType.LIGHTING_DIFFUSE),
                    AlphaGen.of(AlphaGenType.LIGHTING_SPECULAR)),
                0)
            .rgba());
    assertEquals(
        128,
        evaluate(colors(RgbGen.of(RgbGenType.IDENTITY), AlphaGen.of(AlphaGenType.PORTAL)), 0).rgba()
            & 255);
    assertEquals(
        0x80808080,
        evaluate(
                colors(
                    new RgbGen(
                        RgbGenType.WAVE,
                        new Wave(WaveFunction.SIN, 0.5f, 0, 0, 0),
                        new Vec3(1, 1, 1)),
                    new AlphaGen(AlphaGenType.CONSTANT, Wave.ONE, 0.5f, 256)),
                0)
            .rgba());
  }

  @Test
  void dynamicClassificationAllowsStaticStageCaching() {
    assertFalse(
        StageEvaluator.isDynamic(stage(TcGen.of(TcGenType.BASE), List.of(new Scale(2, 2)))));
    assertTrue(
        StageEvaluator.isDynamic(stage(TcGen.of(TcGenType.BASE), List.of(new Scroll(1, 0)))));
    assertTrue(StageEvaluator.isDynamic(stage(TcGen.of(TcGenType.ENVIRONMENT), List.of())));
    assertFalse(
        StageEvaluator.isDynamic(stage(TcGen.of(TcGenType.BASE), List.of(new Stretch(Wave.ONE)))));
  }

  @Test
  void bspOverbrightRestorationPreservesHueAndAlpha() {
    assertEquals(0xc8642840, StageEvaluator.restoreMapLightColor(0x32190a40, 2));
    int saturated = StageEvaluator.restoreMapLightColor(0x64321940, 2);
    assertEquals(255, saturated >>> 24);
    assertEquals(128, saturated >>> 16 & 255);
    assertEquals(64, saturated & 255);
  }

  private static Vertex evaluate(Stage stage, double time) {
    return StageEvaluator.evaluate(VERTEX, stage, StageEvaluator.Context.defaults(CAMERA, time));
  }

  private static Stage stage(TcGen tcGen, List<TcMod> mods) {
    Stage base = ShaderDefinition.implicit("textures/test", false).stages().getFirst();
    return new Stage(
        base.texture(),
        base.blend(),
        base.alphaFunc(),
        base.rgbGen(),
        base.alphaGen(),
        tcGen,
        mods,
        base.depthFunc(),
        base.depthWrite(),
        false);
  }

  private static Stage colors(RgbGen rgb, AlphaGen alpha) {
    Stage base = stage(TcGen.of(TcGenType.BASE), List.of());
    return new Stage(
        base.texture(),
        base.blend(),
        base.alphaFunc(),
        rgb,
        alpha,
        base.tcGen(),
        List.of(),
        base.depthFunc(),
        base.depthWrite(),
        false);
  }
}
