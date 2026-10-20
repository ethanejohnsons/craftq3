package dev.bluevista.craftq3.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.md3.Md3Model;
import dev.bluevista.craftq3.assets.md3.SkinParser;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ModelGeometryTest {
  @Test
  void frameBlendingUsesCurrentOriginAndChosenSkin() {
    var transform =
        new Md3Model.Tag(
            "tag_torso",
            new Vec3(10, 20, 30),
            new Vec3(0, 1, 0),
            new Vec3(-1, 0, 0),
            new Vec3(0, 0, 1));
    var surface =
        ModelGeometry.build(
                model(),
                0,
                1,
                .25f,
                false,
                transform,
                Optional.of(new SkinParser.Skin(Map.of("body", "models/red"))),
                Optional.empty(),
                0,
                0xabcdef12)
            .getFirst();
    assertEquals("models/red", surface.shader());
    assertEquals(new Vec3(10, 21.5, 30), surface.vertices().getFirst().position());
    assertEquals(0xabcdef12, surface.vertices().getFirst().rgba());
    assertEquals(new Vec3(9, 21.5, 30), surface.bounds().min());
    assertEquals(new Vec3(10, 22.5, 30), surface.bounds().max());
    var custom =
        ModelGeometry.build(
                model(),
                2,
                3,
                1,
                true,
                ModelGeometry.IDENTITY,
                Optional.empty(),
                Optional.of("special"),
                0,
                -1)
            .getFirst();
    assertEquals("special", custom.shader());
    assertEquals(new Vec3(0, 0, 0), custom.vertices().getFirst().position());
  }

  @Test
  void tagCompositionPositionsAttachedModelsAndMissingSkinIsExplicit() {
    var parent =
        new Md3Model.Tag(
            "upper", new Vec3(10, 0, 0), new Vec3(0, 1, 0), new Vec3(-1, 0, 0), new Vec3(0, 0, 1));
    var tag =
        new Md3Model.Tag(
            "weapon", new Vec3(3, 0, 5), new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1));
    assertEquals(new Vec3(10, 3, 5), parent.compose(tag).origin());
    var surface =
        ModelGeometry.build(
                model(),
                0,
                0,
                0,
                false,
                parent.compose(tag),
                Optional.of(new SkinParser.Skin(Map.of())),
                Optional.empty(),
                0,
                -1)
            .getFirst();
    assertEquals("$missing", surface.shader());
    assertEquals(new Vec3(10, 3, 5), surface.vertices().getFirst().position());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ModelGeometry.build(
                model(), -1, 0, 0, false, parent, Optional.empty(), Optional.empty(), 0, -1));
  }

  private static Md3Model model() {
    return model(new Vec3(0, 0, 1));
  }

  @Test
  void reflectedModelsRetainQuakeWindingAndScaledNormalsStayPerpendicular() {
    var transform =
        new Md3Model.Tag(
            "", new Vec3(0, 0, 0), new Vec3(-2, 0, 0), new Vec3(0, 3, 0), new Vec3(0, 0, 4));
    var vertices =
        ModelGeometry.build(
                model(), 0, 0, 0, false, transform, Optional.empty(), Optional.empty(), 0, -1)
            .getFirst()
            .vertices();
    var a = vertices.get(0).position();
    var b = vertices.get(1).position();
    var c = vertices.get(2).position();
    double signedArea = (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
    assertTrue(signedArea < 0, "Q3 source winding stays clockwise after reflection");
    var normal =
        ModelGeometry.build(
                model(new Vec3(1, 1, 0)),
                0,
                0,
                0,
                false,
                transform,
                Optional.empty(),
                Optional.empty(),
                0,
                -1)
            .getFirst()
            .vertices()
            .getFirst()
            .normal();
    assertEquals(
        0,
        -2 * normal.x() - 3 * normal.y(),
        1e-12,
        "Transformed normal stays perpendicular to a transformed tangent");
    assertEquals(1, Math.hypot(normal.x(), normal.y()), 1e-12);
  }

  private static Md3Model model(Vec3 normal) {
    Vec3 zero = new Vec3(0, 0, 0);
    var first =
        List.of(
            new Md3Model.Vertex(zero, normal),
            new Md3Model.Vertex(new Vec3(1, 0, 0), normal),
            new Md3Model.Vertex(new Vec3(0, 1, 0), normal));
    var second =
        first.stream()
            .map(v -> new Md3Model.Vertex(v.position().add(new Vec3(2, 0, 0)), v.normal()))
            .toList();
    var frame = new Md3Model.Frame(zero, new Vec3(3, 1, 0), zero, 4, "frame");
    var surface =
        new Md3Model.Surface(
            "body",
            0,
            List.of(new Md3Model.Shader("default", 0)),
            List.of(new Md3Model.Triangle(0, 2, 1)),
            List.of(
                new Md3Model.TexCoord(0, 0),
                new Md3Model.TexCoord(1, 0),
                new Md3Model.TexCoord(0, 1)),
            List.of(first, second));
    return new Md3Model(
        "test", 0, List.of(frame, frame), List.of(List.of(), List.of()), List.of(surface));
  }

  @Test
  void zeroScaleSubmittedByOriginalCgameProducesFiniteCollapsedGeometry() {
    var origin = new Vec3(294.5761413574219, 664.5830078125, 350.9553527832031);
    var zero = new Vec3(0, 0, 0);
    var transform =
        new Md3Model.Tag("", origin, new Vec3(-0.0, 0.0, -0.0), new Vec3(-0.0, -0.0, 0.0), zero);
    var surface =
        ModelGeometry.build(
                model(), 0, 0, 0, false, transform, Optional.empty(), Optional.empty(), 0, 0)
            .getFirst();
    assertEquals(3, surface.vertices().size());
    for (var vertex : surface.vertices()) {
      assertEquals(origin, vertex.position());
      assertEquals(zero, vertex.normal());
    }
    assertEquals(origin, surface.bounds().min());
    assertEquals(origin, surface.bounds().max());
  }

  @Test
  void singularAxesCanPreserveAVisiblePlanarSurface() {
    var transform =
        new Md3Model.Tag(
            "", new Vec3(10, 20, 30), new Vec3(2, 0, 0), new Vec3(0, 3, 0), new Vec3(0, 0, 0));
    var surface =
        ModelGeometry.build(
                model(), 0, 0, 0, false, transform, Optional.empty(), Optional.empty(), 0, -1)
            .getFirst();
    assertEquals(new Vec3(10, 20, 30), surface.bounds().min());
    assertEquals(new Vec3(12, 23, 30), surface.bounds().max());
    for (var vertex : surface.vertices()) assertEquals(new Vec3(0, 0, 1), vertex.normal());
  }

  @Test
  void smallNonzeroScaleKeepsUnitNormalsWithoutADeterminantCutoff() {
    var transform =
        new Md3Model.Tag(
            "",
            new Vec3(0, 0, 0),
            new Vec3(1e-6, 0, 0),
            new Vec3(0, 1e-6, 0),
            new Vec3(0, 0, 1e-6));
    var surface =
        ModelGeometry.build(
                model(), 0, 0, 0, false, transform, Optional.empty(), Optional.empty(), 0, -1)
            .getFirst();
    assertEquals(new Vec3(1e-6, 1e-6, 0), surface.bounds().max());
    for (var vertex : surface.vertices()) assertEquals(new Vec3(0, 0, 1), vertex.normal());
  }
}
