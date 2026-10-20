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

class VertexDeformerTest {
  private static final Camera CAMERA = new Camera(new Vec3(10, 0, 0), 180, 0, 90);

  @Test
  void appliesSpatialWaveAndMoveInDeclaredOrder() {
    Vertex vertex = vertex(25, 0, 0, 0, 0);
    ShaderDefinition shader =
        shader(
            List.of(
                new WaveDeform(100, new Wave(WaveFunction.SIN, 0, 4, 0, 1)),
                new MoveDeform(new Vec3(1, 0, 0), new Wave(WaveFunction.SIN, 2, 0, 0, 0))),
            0);
    Vec3 position = VertexDeformer.deform(List.of(vertex), shader, 0, CAMERA).getFirst().position();
    assertEquals(27, position.x(), 1e-8);
    assertEquals(4, position.z(), 1e-8);
    assertEquals(new Vec3(25, 0, 0), vertex.position());
  }

  @Test
  void bulgeUsesTextureSAndTimeInRadiansAndHonorsClampTime() {
    Vertex vertex = vertex(0, 0, 0, (float) (Math.PI / 2), 0);
    ShaderDefinition bulge = shader(List.of(new BulgeDeform(1, 3, 1)), 0);
    assertEquals(
        3,
        VertexDeformer.deform(List.of(vertex), bulge, 0, CAMERA).getFirst().position().z(),
        1e-6);
    ShaderDefinition move =
        shader(
            List.of(new MoveDeform(new Vec3(1, 0, 0), new Wave(WaveFunction.SAWTOOTH, 0, 1, 0, 1))),
            0.25f);
    assertEquals(
        0.25,
        VertexDeformer.deform(List.of(vertex), move, 100, CAMERA).getFirst().position().x(),
        1e-6);
  }

  @Test
  void normalNoiseIsDeterministicNormalizedAndLeavesPosition() {
    Vertex vertex = vertex(20, 30, 40, 0, 0);
    ShaderDefinition shader = shader(List.of(new NormalDeform(0.5f, 2)), 0);
    Vertex first = VertexDeformer.deform(List.of(vertex), shader, 0.1, CAMERA).getFirst();
    Vertex second = VertexDeformer.deform(List.of(vertex), shader, 0.1, CAMERA).getFirst();
    assertEquals(first, second);
    assertEquals(vertex.position(), first.position());
    assertEquals(1, ShaderMath.dot(first.normal(), first.normal()), 1e-8);
    assertNotEquals(vertex.normal(), first.normal());
    assertNotEquals(
        first.normal(),
        VertexDeformer.deform(List.of(vertex), shader, 0.6, CAMERA).getFirst().normal());
  }

  @Test
  void autospriteFacesCameraAndKeepsQuadCenter() {
    List<Vertex> output =
        VertexDeformer.deform(quad(1, 1), shader(List.of(new AutoSprite(false)), 0), 0, CAMERA);
    for (Vertex vertex : output) {
      assertEquals(0, vertex.position().x(), 1e-8);
      assertEquals(1, Math.abs(vertex.position().y()), 1e-8);
      assertEquals(1, Math.abs(vertex.position().z()), 1e-8);
    }
    assertEquals(output.get(0), output.get(3));
    assertEquals(output.get(2), output.get(4));
  }

  @Test
  void autospriteUsesFullRolledAndMirroredPortalAxes() {
    var shader = shader(List.of(new AutoSprite(false)), 0);
    var basis = new PortalView.Basis(new Vec3(-1, 0, 0), new Vec3(0, 0, 1), new Vec3(0, -1, 0));
    var rolled = VertexDeformer.deform(quad(1, 1), shader, 0, CAMERA, basis);
    assertEquals(new Vec3(0, -1, -1), rolled.getFirst().position());
    var reflectedBasis = new PortalView.Basis(basis.forward(), basis.right().scale(-1), basis.up());
    var mirrored = VertexDeformer.deform(quad(1, 1), shader, 0, CAMERA, reflectedBasis);
    assertEquals(new Vec3(0, -1, 1), mirrored.getFirst().position());
    for (int i = 0; i < rolled.size(); i++) {
      assertEquals(rolled.get(i).textureUv(), mirrored.get(i).textureUv());
      assertEquals(rolled.get(i).position().y(), mirrored.get(i).position().y(), 1e-8);
      assertEquals(-rolled.get(i).position().z(), mirrored.get(i).position().z(), 1e-8);
    }
  }

  @Test
  void axialSpriteRetainsLongAxisAndEdgeWidths() {
    List<Vertex> output =
        VertexDeformer.deform(quad(1, 5), shader(List.of(new AutoSprite(true)), 0), 0, CAMERA);
    for (Vertex vertex : output) {
      assertEquals(0, vertex.position().x(), 1e-8);
      assertEquals(5, Math.abs(vertex.position().y()), 1e-8);
      assertEquals(1, Math.abs(vertex.position().z()), 1e-8);
    }
  }

  @Test
  void staticAndUnsupportedDeformsHaveExplicitPredictableBehavior() {
    List<Vertex> original = List.of(vertex(0, 0, 0, 0, 0));
    ShaderDefinition empty = shader(List.of(), 0);
    assertSame(original, VertexDeformer.deform(original, empty, 0, CAMERA));
    assertFalse(
        VertexDeformer.isDynamic(shader(List.of(new MoveDeform(new Vec3(1, 0, 0), Wave.ONE)), 0)));
    assertTrue(VertexDeformer.isDynamic(shader(List.of(new AutoSprite(false)), 0)));
    assertEquals(
        original,
        VertexDeformer.deform(
            original, shader(List.of(new TextDeform(0), new ProjectionShadow()), 0), 0, CAMERA));
    assertEquals(
        original,
        VertexDeformer.deform(original, shader(List.of(new AutoSprite(false)), 0), 0, CAMERA));
  }

  private static Vertex vertex(double x, double y, double z, float s, float t) {
    return new Vertex(new Vec3(x, y, z), new Uv(s, t), new Uv(0, 0), new Vec3(0, 0, 1), 0xffffffff);
  }

  private static List<Vertex> quad(float halfWidth, float halfHeight) {
    Vertex a = vertex(-halfWidth, -halfHeight, 0, 0, 0);
    Vertex b = vertex(halfWidth, -halfHeight, 0, 1, 0);
    Vertex c = vertex(halfWidth, halfHeight, 0, 1, 1);
    Vertex d = vertex(-halfWidth, halfHeight, 0, 0, 1);
    return List.of(a, b, c, a, c, d);
  }

  private static ShaderDefinition shader(List<Deform> deforms, float clampTime) {
    ShaderDefinition base = ShaderDefinition.implicit("textures/test", false);
    return new ShaderDefinition(
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
        clampTime);
  }
}
