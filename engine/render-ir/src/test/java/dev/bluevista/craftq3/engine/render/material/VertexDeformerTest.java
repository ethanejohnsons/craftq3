package dev.bluevista.craftq3.engine.render.material;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.AutoSprite;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.BulgeDeform;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Deform;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.MoveDeform;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.NormalDeform;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.ProjectionShadow;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.TextDeform;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Wave;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.WaveDeform;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.WaveFunction;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The legacy {@code VertexDeformerTest} cases on packed vertex arrays. */
class VertexDeformerTest {
  // A camera at (10, 0, 0) looking down -X (yaw 180): screen right is +Y, up is +Z.
  private static final Vec3f EYE = new Vec3f(10, 0, 0);
  private static final Vec3f RIGHT = new Vec3f(0, 1, 0);
  private static final Vec3f UP = new Vec3f(0, 0, 1);
  private static final float EPSILON = 1e-5f;

  @Test
  void appliesSpatialWaveAndMoveInDeclaredOrder() {
    Mesh mesh = Mesh.of(new float[] {25, 0, 0}, new float[] {0, 0});
    deform(
        mesh,
        shader(
            List.of(
                new WaveDeform(100, new Wave(WaveFunction.SIN, 0, 4, 0, 1)),
                new MoveDeform(new Vec3f(1, 0, 0), new Wave(WaveFunction.SIN, 2, 0, 0, 0))),
            0),
        0);
    assertEquals(27, mesh.positions[0], EPSILON);
    assertEquals(4, mesh.positions[2], EPSILON);
  }

  @Test
  void bulgeUsesTextureSAndTimeInRadiansAndHonorsClampTime() {
    Mesh bulge = Mesh.of(new float[] {0, 0, 0}, new float[] {(float) (Math.PI / 2), 0});
    deform(bulge, shader(List.of(new BulgeDeform(1, 3, 1)), 0), 0);
    assertEquals(3, bulge.positions[2], EPSILON);
    Mesh move = Mesh.of(new float[] {0, 0, 0}, new float[] {0, 0});
    deform(
        move,
        shader(
            List.of(
                new MoveDeform(new Vec3f(1, 0, 0), new Wave(WaveFunction.SAWTOOTH, 0, 1, 0, 1))),
            0.25f),
        100);
    assertEquals(0.25, move.positions[0], EPSILON);
  }

  @Test
  void normalNoiseIsDeterministicNormalizedAndLeavesPosition() {
    MaterialProgram shader = shader(List.of(new NormalDeform(0.5f, 2)), 0);
    Mesh first = Mesh.of(new float[] {20, 30, 40}, new float[] {0, 0});
    Mesh second = Mesh.of(new float[] {20, 30, 40}, new float[] {0, 0});
    Mesh later = Mesh.of(new float[] {20, 30, 40}, new float[] {0, 0});
    deform(first, shader, 0.1);
    deform(second, shader, 0.1);
    deform(later, shader, 0.6);
    assertArrayEquals(first.normals, second.normals);
    assertArrayEquals(new float[] {20, 30, 40}, first.positions);
    float[] n = first.normals;
    assertEquals(1, n[0] * n[0] + n[1] * n[1] + n[2] * n[2], EPSILON);
    assertNotEquals(1, n[2]);
    assertFalse(java.util.Arrays.equals(first.normals, later.normals));
  }

  @Test
  void autospriteFacesCameraAndKeepsQuadCenter() {
    Mesh quad = quad(1, 1);
    deform(quad, shader(List.of(new AutoSprite(false)), 0), 0);
    for (int v = 0; v < 6; v++) {
      assertEquals(0, quad.positions[v * 3], EPSILON);
      assertEquals(1, Math.abs(quad.positions[v * 3 + 1]), EPSILON);
      assertEquals(1, Math.abs(quad.positions[v * 3 + 2]), EPSILON);
    }
    for (int axis = 0; axis < 3; axis++) {
      assertEquals(quad.positions[axis], quad.positions[9 + axis]);
      assertEquals(quad.positions[6 + axis], quad.positions[12 + axis]);
    }
  }

  @Test
  void autospriteUsesFullRolledAndMirroredViewAxes() {
    MaterialProgram shader = shader(List.of(new AutoSprite(false)), 0);
    Vec3f right = new Vec3f(0, 0, 1), up = new Vec3f(0, -1, 0);
    Mesh rolled = quad(1, 1);
    deform(rolled, shader, 0, right, up);
    assertArrayEquals(
        new float[] {0, -1, -1}, java.util.Arrays.copyOf(rolled.positions, 3), EPSILON);
    Mesh mirrored = quad(1, 1);
    deform(mirrored, shader, 0, right.negate(), up);
    assertArrayEquals(
        new float[] {0, -1, 1}, java.util.Arrays.copyOf(mirrored.positions, 3), EPSILON);
    for (int v = 0; v < 6; v++) {
      assertEquals(rolled.positions[v * 3 + 1], mirrored.positions[v * 3 + 1], EPSILON);
      assertEquals(-rolled.positions[v * 3 + 2], mirrored.positions[v * 3 + 2], EPSILON);
    }
    assertArrayEquals(rolled.texCoords, mirrored.texCoords);
  }

  @Test
  void axialSpriteRetainsLongAxisAndEdgeWidths() {
    Mesh quad = quad(1, 5);
    deform(quad, shader(List.of(new AutoSprite(true)), 0), 0);
    for (int v = 0; v < 6; v++) {
      assertEquals(0, quad.positions[v * 3], EPSILON);
      assertEquals(5, Math.abs(quad.positions[v * 3 + 1]), EPSILON);
      assertEquals(1, Math.abs(quad.positions[v * 3 + 2]), EPSILON);
    }
  }

  @Test
  void spritesWorkOnSharedQuadVertices() {
    Mesh shared =
        Mesh.of(
            new float[] {-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0},
            new float[] {0, 0, 1, 0, 1, 1, 0, 1});
    shared.indices = new int[] {0, 1, 2, 0, 2, 3};
    deform(shared, shader(List.of(new AutoSprite(false)), 0), 0);
    for (int v = 0; v < 4; v++) {
      assertEquals(0, shared.positions[v * 3], EPSILON);
      assertEquals(1, shared.normals[v * 3], EPSILON);
    }
  }

  @Test
  void staticAndUnsupportedDeformsHaveExplicitPredictableBehavior() {
    assertFalse(
        VertexDeformer.isDynamic(shader(List.of(new MoveDeform(new Vec3f(1, 0, 0), Wave.ONE)), 0)));
    assertTrue(VertexDeformer.isDynamic(shader(List.of(new AutoSprite(false)), 0)));
    Mesh untouched = Mesh.of(new float[] {0, 0, 0}, new float[] {0, 0});
    deform(untouched, shader(List.of(new TextDeform(0), new ProjectionShadow()), 0), 0);
    assertArrayEquals(new float[] {0, 0, 0}, untouched.positions);
    Mesh single = Mesh.of(new float[] {0, 0, 0}, new float[] {0, 0});
    deform(single, shader(List.of(new AutoSprite(false)), 0), 0);
    assertArrayEquals(new float[] {0, 0, 0}, single.positions, "not a quad");
  }

  /** Raw per-vertex arrays with +Z normals and white colours. */
  private static final class Mesh {
    float[] positions, normals, texCoords;
    int[] indices;

    static Mesh of(float[] positions, float[] texCoords) {
      Mesh mesh = new Mesh();
      int count = positions.length / 3;
      mesh.positions = positions;
      mesh.texCoords = texCoords;
      mesh.normals = new float[count * 3];
      for (int v = 0; v < count; v++) mesh.normals[v * 3 + 2] = 1;
      mesh.indices = new int[count];
      for (int i = 0; i < count; i++) mesh.indices[i] = i;
      return mesh;
    }

    VertexArrays arrays() {
      int count = positions.length / 3;
      return new VertexArrays(
          positions, normals, texCoords, new float[count * 2], new byte[count * 4]);
    }
  }

  /** Two unrolled triangles (six vertices, duplicated corners) spanning the XY plane. */
  private static Mesh quad(float halfWidth, float halfHeight) {
    float w = halfWidth, h = halfHeight;
    return Mesh.of(
        new float[] {-w, -h, 0, w, -h, 0, w, h, 0, -w, -h, 0, w, h, 0, -w, h, 0},
        new float[] {0, 0, 1, 0, 1, 1, 0, 0, 1, 1, 0, 1});
  }

  private static void deform(Mesh mesh, MaterialProgram shader, double seconds) {
    deform(mesh, shader, seconds, RIGHT, UP);
  }

  private static void deform(
      Mesh mesh, MaterialProgram shader, double seconds, Vec3f right, Vec3f up) {
    VertexDeformer.deform(
        shader,
        seconds,
        EYE,
        right,
        up,
        mesh.arrays(),
        0,
        mesh.positions.length / 3,
        mesh.indices,
        0,
        mesh.indices.length,
        new VertexDeformer.Scratch());
  }

  private static MaterialProgram shader(List<Deform> deforms, float clampTime) {
    ShaderDefinition base = ShaderDefinition.implicit("textures/test", false);
    return MaterialCompiler.compile(
        new ShaderDefinition(
            base.name(),
            base.stages(),
            base.cull(),
            base.sort(),
            false,
            false,
            false,
            base.surfaceParms(),
            base.sky(),
            base.fog(),
            deforms,
            false,
            clampTime));
  }
}
