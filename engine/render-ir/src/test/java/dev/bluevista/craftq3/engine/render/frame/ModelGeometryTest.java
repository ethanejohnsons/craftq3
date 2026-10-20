package dev.bluevista.craftq3.engine.render.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.md3.Md3Model;
import dev.bluevista.craftq3.engine.formats.md3.Md3Reader;
import dev.bluevista.craftq3.engine.formats.md3.SkinParser;
import dev.bluevista.craftq3.engine.render.MeshBuffer;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Ported from the legacy {@code ModelGeometryTest}. */
class ModelGeometryTest {
  private static final float EPSILON = 1e-5f;

  @Test
  void frameBlendingUsesCurrentOriginAndChosenSkin() {
    Md3Model.Tag transform =
        new Md3Model.Tag(
            "tag_torso",
            new Vec3f(10, 20, 30),
            new Vec3f(0, 1, 0),
            new Vec3f(-1, 0, 0),
            new Vec3f(0, 0, 1));
    MeshBuffer mesh = new MeshBuffer(1024, 1024);
    ModelGeometry.Surface surface =
        build(
            mesh,
            0,
            1,
            .25f,
            false,
            transform,
            Optional.of(skin("body,models/red")),
            Optional.empty(),
            0xabcdef12);
    assertEquals("models/red", surface.shader());
    assertEquals(new Vec3f(10, 21.5f, 30), position(mesh, surface.firstVertex()));
    assertEquals(0xabcdef12, mesh.arrays().rgba(surface.firstVertex()));
    float[] bounds = new float[6];
    mesh.bounds(surface.firstVertex(), surface.vertexCount(), bounds, 0);
    assertEquals(9, bounds[0], EPSILON);
    assertEquals(21.5, bounds[1], EPSILON);
    assertEquals(10, bounds[3], EPSILON);
    assertEquals(22.5, bounds[4], EPSILON);
    MeshBuffer second = new MeshBuffer(1024, 1024);
    ModelGeometry.Surface custom =
        build(
            second,
            2,
            3,
            1,
            true,
            ModelGeometry.IDENTITY,
            Optional.empty(),
            Optional.of("special"),
            -1);
    assertEquals("special", custom.shader());
    assertEquals(Vec3f.ZERO, position(second, custom.firstVertex()));
  }

  @Test
  void tagCompositionPositionsAttachedModelsAndMissingSkinIsExplicit() {
    Md3Model.Tag parent =
        new Md3Model.Tag(
            "upper",
            new Vec3f(10, 0, 0),
            new Vec3f(0, 1, 0),
            new Vec3f(-1, 0, 0),
            new Vec3f(0, 0, 1));
    Md3Model.Tag tag =
        new Md3Model.Tag(
            "weapon",
            new Vec3f(3, 0, 5),
            new Vec3f(1, 0, 0),
            new Vec3f(0, 1, 0),
            new Vec3f(0, 0, 1));
    assertEquals(new Vec3f(10, 3, 5), parent.compose(tag).origin());
    MeshBuffer mesh = new MeshBuffer(1024, 1024);
    ModelGeometry.Surface surface =
        build(
            mesh, 0, 0, 0, false, parent.compose(tag), Optional.of(skin("")), Optional.empty(), -1);
    assertEquals(ModelGeometry.MISSING_SHADER, surface.shader());
    assertEquals(new Vec3f(10, 3, 5), position(mesh, surface.firstVertex()));
    MeshBuffer degraded = new MeshBuffer(1024, 1024);
    ModelGeometry.Surface fallback =
        build(degraded, -1, 7, 0, false, parent, Optional.empty(), Optional.empty(), -1);
    assertEquals(new Vec3f(10, 0, 0), position(degraded, fallback.firstVertex()), "frame 0 drawn");
    assertEquals("default", fallback.shader());
  }

  @Test
  void reflectedModelsRetainQuakeWindingAndScaledNormalsStayPerpendicular() {
    Md3Model.Tag transform =
        new Md3Model.Tag(
            "", Vec3f.ZERO, new Vec3f(-2, 0, 0), new Vec3f(0, 3, 0), new Vec3f(0, 0, 4));
    MeshBuffer mesh = new MeshBuffer(1024, 1024);
    build(mesh, 0, 0, 0, false, transform, Optional.empty(), Optional.empty(), -1);
    int[] indices = mesh.indices();
    Vec3f a = position(mesh, indices[0]),
        b = position(mesh, indices[1]),
        c = position(mesh, indices[2]);
    double signedArea = (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
    assertTrue(signedArea < 0, "Q3 source winding stays clockwise after reflection");
    int packed = 32 << 8 | 64; // azimuth 45 degrees, polar 90 degrees: (1, 1, 0) / sqrt 2
    Vec3f source = Md3Reader.decodeNormal(packed);
    MeshBuffer scaled = new MeshBuffer(1024, 1024);
    ModelGeometry.build(
        TestMd3.triangle("default", packed),
        0,
        0,
        0,
        false,
        transform,
        Optional.empty(),
        Optional.empty(),
        0,
        -1,
        scaled,
        new ModelGeometry.Scratch());
    Vec3f normal = normal(scaled, 0);
    Vec3f tangent = new Vec3f(-2 * source.y(), 3 * -source.x(), 0);
    assertEquals(0, normal.dot(tangent), 1e-5, "normal stays perpendicular to a mapped tangent");
    assertEquals(1, normal.length(), 1e-5);
  }

  @Test
  void zeroScaleSubmittedByOriginalCgameProducesFiniteCollapsedGeometry() {
    Vec3f origin = new Vec3f(294.5761413574219f, 664.5830078125f, 350.9553527832031f);
    Md3Model.Tag transform =
        new Md3Model.Tag("", origin, new Vec3f(-0f, 0f, -0f), new Vec3f(-0f, -0f, 0f), Vec3f.ZERO);
    MeshBuffer mesh = new MeshBuffer(1024, 1024);
    ModelGeometry.Surface surface =
        build(mesh, 0, 0, 0, false, transform, Optional.empty(), Optional.empty(), 0);
    assertEquals(3, surface.vertexCount());
    for (int v = 0; v < 3; v++) {
      assertEquals(origin, position(mesh, v));
      assertEquals(0, normal(mesh, v).length());
    }
  }

  @Test
  void singularAxesCanPreserveAVisiblePlanarSurface() {
    Md3Model.Tag transform =
        new Md3Model.Tag(
            "", new Vec3f(10, 20, 30), new Vec3f(2, 0, 0), new Vec3f(0, 3, 0), Vec3f.ZERO);
    MeshBuffer mesh = new MeshBuffer(1024, 1024);
    build(mesh, 0, 0, 0, false, transform, Optional.empty(), Optional.empty(), -1);
    float[] bounds = new float[6];
    mesh.bounds(0, 3, bounds, 0);
    assertEquals(
        List.of(10f, 20f, 30f, 12f, 23f, 30f),
        List.of(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]));
    for (int v = 0; v < 3; v++) assertEquals(new Vec3f(0, 0, 1), normal(mesh, v));
  }

  @Test
  void smallNonzeroScaleKeepsUnitNormalsWithoutADeterminantCutoff() {
    Md3Model.Tag transform =
        new Md3Model.Tag(
            "", Vec3f.ZERO, new Vec3f(1e-6f, 0, 0), new Vec3f(0, 1e-6f, 0), new Vec3f(0, 0, 1e-6f));
    MeshBuffer mesh = new MeshBuffer(1024, 1024);
    build(mesh, 0, 0, 0, false, transform, Optional.empty(), Optional.empty(), -1);
    float[] bounds = new float[6];
    mesh.bounds(0, 3, bounds, 0);
    assertEquals(1e-6f, bounds[3], 1e-12);
    assertEquals(1e-6f, bounds[4], 1e-12);
    for (int v = 0; v < 3; v++) assertEquals(new Vec3f(0, 0, 1), normal(mesh, v));
  }

  private static ModelGeometry.Surface build(
      MeshBuffer mesh,
      int oldFrame,
      int frame,
      float backlerp,
      boolean wrap,
      Md3Model.Tag transform,
      Optional<SkinParser.Skin> skin,
      Optional<String> custom,
      int rgba) {
    return ModelGeometry.build(
            TestMd3.triangle("default", TestMd3.UP),
            oldFrame,
            frame,
            backlerp,
            wrap,
            transform,
            skin,
            custom,
            0,
            rgba,
            mesh,
            new ModelGeometry.Scratch())
        .getFirst();
  }

  private static SkinParser.Skin skin(String text) {
    return SkinParser.parse(text);
  }

  static Vec3f position(MeshBuffer mesh, int v) {
    float[] p = mesh.positions();
    return new Vec3f(p[v * 3], p[v * 3 + 1], p[v * 3 + 2]);
  }

  static Vec3f normal(MeshBuffer mesh, int v) {
    float[] n = mesh.normals();
    return new Vec3f(n[v * 3], n[v * 3 + 1], n[v * 3 + 2]);
  }
}
