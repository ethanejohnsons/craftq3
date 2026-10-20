package dev.bluevista.craftq3.engine.render.material;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.AlphaGen;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.AlphaGenType;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.EntityTranslate;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.RgbGen;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.RgbGenType;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Rotate;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Scale;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Scroll;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Stage;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Stretch;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.TcGen;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.TcGenType;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.TcMod;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Transform;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Turbulence;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Wave;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.WaveFunction;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The legacy {@code StageEvaluatorTest} cases, run through the compiler and the reference
 * evaluator: every expected value is the legacy evaluator's output for the same stage and inputs.
 */
class StageEvaluatorTest {
  private static final Vec3f EYE = new Vec3f(0, 0, 128);
  private static final int RGBA = 0x4080c040;

  @Test
  void waveformsRespectPhaseAmplitudeAndNegativeCycles() {
    assertEquals(5, Waveforms.evaluate(new Wave(WaveFunction.SIN, 2, 3, 0.25f, 0), 0), 1e-8);
    for (WaveFunction function : WaveFunction.values()) {
      double value = Waveforms.evaluate(new Wave(function, 0, 1, 0, 1), 0.25);
      assertTrue(value >= -1 && value <= 1);
    }
    for (double[] point :
        new double[][] {{0, 0}, {0.25, 1}, {0.5, 0}, {0.75, -1}, {1, 0}, {-0.25, -1}}) {
      assertEquals(
          point[1],
          Waveforms.evaluate(new Wave(WaveFunction.TRIANGLE, 0, 1, 0, 1), point[0]),
          1e-8);
    }
    assertEquals(-1, Waveforms.evaluate(new Wave(WaveFunction.SQUARE, 0, 1, 0, 1), 0.5));
    assertEquals(0.75, Waveforms.evaluate(new Wave(WaveFunction.SAWTOOTH, 0, 1, 0, 1), -0.25));
    assertEquals(
        0.25, Waveforms.evaluate(new Wave(WaveFunction.INVERSE_SAWTOOTH, 0, 1, 0, 1), -0.25));
  }

  @Test
  void noiseIsBoundedDeterministicAndSmooth() {
    for (int i = 0; i < 1000; i++) {
      double t = i * 0.173;
      double value = Waveforms.noise(i * 0.31, -i * 0.7, i, t);
      assertTrue(value >= -1 && value <= 1);
      assertEquals(value, Waveforms.noise(i * 0.31, -i * 0.7, i, t));
    }
    assertEquals(Waveforms.lattice(3, 4, 5, 6), Waveforms.noise(3, 4, 5, 6), 1e-15);
    assertTrue(
        Math.abs(Waveforms.noise(0.5, 0, 0, 0.5) - Waveforms.noise(0.5001, 0, 0, 0.5)) < 1e-3);
  }

  @Test
  void tcModsApplyInScriptOrderAndTransformUsesQ3MatrixConvention() {
    float[] uv = coordinates(stage(TcGenType.BASE, new Scroll(0.5f, -0.25f), new Scale(2, 3)), 1);
    assertEquals(1.5, uv[0], 1e-6);
    assertEquals(4.5, uv[1], 1e-6);
    float[] matrix = coordinates(stage(TcGenType.BASE, new Transform(2, 3, 4, 5, 6, 7)), 0);
    assertEquals(9.5, matrix[0], 1e-6);
    assertEquals(11.5, matrix[1], 1e-6);
  }

  @Test
  void rotatesAndStretchesAroundTextureCenter() {
    float[] rotated = coordinates(stage(TcGenType.BASE, new Rotate(90)), 1);
    assertEquals(0.75, rotated[0], 1e-6);
    assertEquals(0.75, rotated[1], 1e-6);
    float[] stretched =
        coordinates(stage(TcGenType.BASE, new Stretch(new Wave(WaveFunction.SIN, 2, 0, 0, 0))), 0);
    assertEquals(0.375, stretched[0], 1e-6);
    assertEquals(0.625, stretched[1], 1e-6);
  }

  @Test
  void selectsLightmapVectorAndEnvironmentCoordinates() {
    assertArrayEquals(new float[] {0.1f, 0.2f}, coordinates(stage(TcGenType.LIGHTMAP), 0));
    assertArrayEquals(new float[] {0.5f, 0}, coordinates(stage(TcGenType.ENVIRONMENT), 0));
    StageProgram vector =
        compile(
            stage(
                new TcGen(TcGenType.VECTOR, new Vec3f(2, 0, 0), new Vec3f(0, 3, 0)),
                List.of(),
                RgbGen.of(RgbGenType.IDENTITY),
                AlphaGen.of(AlphaGenType.IDENTITY)));
    StageEvaluator.Vertex v = vertex(3, 4, 5);
    StageEvaluator.evaluate(vector.shading(), ShadingInputs.defaults(EYE, 0), v);
    assertEquals(6, v.outS);
    assertEquals(12, v.outT);
  }

  @Test
  void turbulenceUsesWorldPositionAndIgnoresUnusedBaseValue() {
    StageProgram stage = compile(stage(TcGenType.BASE, new Turbulence(100, 0.5f, 0, 0)));
    StageEvaluator.Vertex v = vertex(256, 0, 0);
    StageEvaluator.evaluate(stage.shading(), ShadingInputs.defaults(EYE, 0), v);
    assertEquals(0.75, v.outS, 1e-6);
    assertEquals(0.75, v.outT, 1e-6);
  }

  @Test
  void colorGeneratorsPreserveIndependentVertexAlphaAndEntityState() {
    assertEquals(
        RGBA, rgba(colors(RgbGen.of(RgbGenType.EXACT_VERTEX), AlphaGen.of(AlphaGenType.VERTEX))));
    assertEquals(
        0xbf7f3fbf,
        rgba(
            colors(
                RgbGen.of(RgbGenType.ONE_MINUS_VERTEX),
                AlphaGen.of(AlphaGenType.ONE_MINUS_VERTEX))));
    ShadingInputs entity =
        new ShadingInputs(
            EYE,
            0,
            new Vec3f(0.2f, 0.4f, 0.6f),
            0.8f,
            new Vec3f(0, 0, 1),
            new Vec3f(0.1f, 0.1f, 0.1f),
            new Vec3f(0.2f, 0.3f, 0.4f),
            new Vec3f(0.5f, 0.5f, 0.5f),
            new Vec3f(0.25f, 0.5f, 0),
            1);
    StageEvaluator.Vertex v = vertex(0, 0, 0);
    StageEvaluator.evaluate(
        compile(colors(RgbGen.of(RgbGenType.ENTITY), AlphaGen.of(AlphaGenType.ENTITY))).shading(),
        entity,
        v);
    assertEquals(0x336699cc, v.outRgba);
    StageEvaluator.evaluate(
        compile(stage(TcGenType.BASE, new EntityTranslate())).shading(), entity, v);
    assertEquals(0.5f, v.outS);
    assertEquals(1.25f, v.outT);
  }

  @Test
  void lightingAndPortalGeneratorsRespondToNormalAndCamera() {
    assertEquals(
        0xffffffff,
        rgba(
            colors(
                RgbGen.of(RgbGenType.LIGHTING_DIFFUSE),
                AlphaGen.of(AlphaGenType.LIGHTING_SPECULAR))));
    assertEquals(
        128, rgba(colors(RgbGen.of(RgbGenType.IDENTITY), AlphaGen.of(AlphaGenType.PORTAL))) & 255);
    assertEquals(
        0x80808080,
        rgba(
            colors(
                new RgbGen(
                    RgbGenType.WAVE, new Wave(WaveFunction.SIN, 0.5f, 0, 0, 0), new Vec3f(1, 1, 1)),
                new AlphaGen(AlphaGenType.CONSTANT, Wave.ONE, 0.5f, 256))));
  }

  @Test
  void dynamicClassificationAllowsStaticStageCaching() {
    assertFalse(StageEvaluator.isDynamic(compile(stage(TcGenType.BASE, new Scale(2, 2)))));
    assertTrue(StageEvaluator.isDynamic(compile(stage(TcGenType.BASE, new Scroll(1, 0)))));
    assertTrue(StageEvaluator.isDynamic(compile(stage(TcGenType.ENVIRONMENT))));
    assertFalse(StageEvaluator.isDynamic(compile(stage(TcGenType.BASE, new Stretch(Wave.ONE)))));
  }

  @Test
  void bspOverbrightRestorationPreservesHueAndAlpha() {
    assertEquals(0xc8642840, StageEvaluator.restoreMapLightColor(0x32190a40, 2));
    int saturated = StageEvaluator.restoreMapLightColor(0x64321940, 2);
    assertEquals(255, saturated >>> 24);
    assertEquals(128, saturated >>> 16 & 255);
    assertEquals(64, saturated & 255);
  }

  @Test
  void bulkEvaluationMatchesSingleVertexEvaluationAndPerVertexLighting() {
    StageProgram stage =
        compile(
            stage(
                TcGen.of(TcGenType.ENVIRONMENT),
                List.of(new Scroll(0.25f, 0.5f)),
                RgbGen.of(RgbGenType.LIGHTING_DIFFUSE),
                AlphaGen.of(AlphaGenType.VERTEX)));
    float[] positions = {0, 0, 0, 10, 20, 30, -5, 4, 2};
    float[] normals = {0, 0, 1, 1, 0, 0, 0, 1, 0};
    float[] uv = {0.25f, 0.75f, 0, 1, 2, 3};
    float[] lm = new float[6];
    byte[] colors = {1, 2, 3, 4, 50, 60, 70, 80, -1, -1, -1, -1};
    VertexArrays arrays = new VertexArrays(positions, normals, uv, lm, colors);
    ShadingInputs inputs = ShadingInputs.defaults(EYE, 1.5);
    float[] uvOut = new float[6];
    byte[] colorOut = new byte[12];
    StageEvaluator.evaluate(stage.shading(), inputs, arrays, 0, 3, null, null, uvOut, colorOut, 0);
    StageEvaluator.Vertex v = new StageEvaluator.Vertex();
    for (int i = 0; i < 3; i++) {
      StageEvaluator.evaluate(stage.shading(), inputs, v.load(arrays, i));
      assertEquals(v.outS, uvOut[i * 2]);
      assertEquals(v.outT, uvOut[i * 2 + 1]);
      assertEquals(v.outRgba, new VertexArrays(positions, normals, uvOut, lm, colorOut).rgba(i));
    }
    VertexLighting dark = (px, py, pz, nx, ny, nz, out) -> java.util.Arrays.fill(out, 0, 6, 0);
    StageEvaluator.evaluate(stage.shading(), inputs, arrays, 0, 3, dark, null, uvOut, colorOut, 0);
    assertEquals(0, colorOut[0]);
    assertEquals(4, colorOut[3]);
  }

  private static StageEvaluator.Vertex vertex(float x, float y, float z) {
    return new StageEvaluator.Vertex().set(x, y, z, 0, 0, 1, 0.25f, 0.75f, 0.1f, 0.2f, RGBA);
  }

  private static float[] coordinates(Stage stage, double time) {
    StageEvaluator.Vertex v = vertex(0, 0, 0);
    StageEvaluator.evaluate(compile(stage).shading(), ShadingInputs.defaults(EYE, time), v);
    return new float[] {v.outS, v.outT};
  }

  private static int rgba(Stage stage) {
    StageEvaluator.Vertex v = vertex(0, 0, 0);
    StageEvaluator.evaluate(compile(stage).shading(), ShadingInputs.defaults(EYE, 0), v);
    return v.outRgba;
  }

  static StageProgram compile(Stage stage) {
    ShaderDefinition base = ShaderDefinition.implicit("textures/test", false);
    return MaterialCompiler.compile(
            new ShaderDefinition(
                base.name(),
                List.of(stage),
                base.cull(),
                base.sort(),
                false,
                false,
                false,
                Set.of(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                false,
                0))
        .stages()
        .getFirst();
  }

  private static Stage stage(TcGenType type, TcMod... mods) {
    return stage(
        TcGen.of(type),
        List.of(mods),
        RgbGen.of(RgbGenType.VERTEX),
        AlphaGen.of(AlphaGenType.IDENTITY));
  }

  private static Stage colors(RgbGen rgb, AlphaGen alpha) {
    return stage(TcGen.of(TcGenType.BASE), List.of(), rgb, alpha);
  }

  static Stage stage(TcGen tcGen, List<TcMod> mods, RgbGen rgb, AlphaGen alpha) {
    Stage base = ShaderDefinition.implicit("textures/test", false).stages().getFirst();
    return new Stage(
        base.texture(),
        base.blend(),
        base.alphaFunc(),
        rgb,
        alpha,
        tcGen,
        mods,
        base.depthFunc(),
        base.depthWrite(),
        false);
  }
}
