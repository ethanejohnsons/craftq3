package dev.bluevista.craftq3.engine.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.shader.ShaderLibrary;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ported from the legacy {@code PortalViewTest}, plus cgame portal entities and view data. */
class PortalViewTest {
  private static final double EPSILON = 1e-3;

  @Test
  void mirrorReflectsAcrossSurfaceAndRetainsNegativeHandedness() {
    RenderScene scene =
        scene(List.of(Map.of("classname", "misc_portal_surface", "origin", "0 0 16")));
    PortalView mirror = PortalView.find(scene.bsp(), scene.world(), 0).orElseThrow();
    assertTrue(mirror.mirrored());
    close(new Vec3f(10, -20, -40), mirror.transformPoint(new Vec3f(10, -20, 40)));
    close(new Vec3f(1, 2, -3), mirror.transformDirection(new Vec3f(1, 2, 3)));
    close(
        new Vec3f(10, 20, 30), mirror.transformPoint(mirror.transformPoint(new Vec3f(10, 20, 30))));
    Camera camera = new Camera(new Vec3f(10, -20, 40), 30, 45, 90);
    ViewBasis basis = mirror.basis(camera);
    assertTrue(basis.right().cross(basis.forward()).dot(basis.up()) < -.99);
    assertTrue(basis.mirrored());
    assertEquals(-45, mirror.camera(camera).pitch(), 1e-3);
    assertTrue(mirror.destinationPlane().signedDistance(new Vec3f(0, 0, 10)) > 0);
    assertFalse(mirror.animatedCamera());
  }

  @Test
  void linkedPortalMapsLocalFrameToCameraTargetAndClipPlane() {
    RenderScene scene =
        scene(
            List.of(
                Map.of("classname", "misc_portal_surface", "origin", "0 0 16", "target", "camera"),
                Map.of(
                    "classname",
                    "misc_portal_camera",
                    "targetname",
                    "camera",
                    "origin",
                    "1000 2000 3000",
                    "target",
                    "aim",
                    "roll",
                    "180"),
                Map.of(
                    "classname",
                    "target_position",
                    "targetname",
                    "aim",
                    "origin",
                    "1100 2000 3000")));
    PortalView portal = PortalView.find(scene.bsp(), scene.world(), 0).orElseThrow();
    assertFalse(portal.mirrored());
    close(new Vec3f(1000, 2000, 3000), portal.transformPoint(Vec3f.ZERO));
    close(new Vec3f(970, 1990, 3020), portal.transformPoint(new Vec3f(10, 20, 30)));
    close(new Vec3f(1, 0, 0), portal.transformDirection(new Vec3f(0, 0, -1)));
    assertTrue(portal.destinationPlane().signedDistance(new Vec3f(1100, 2000, 3000)) > 0);
    assertTrue(portal.destinationPlane().signedDistance(new Vec3f(900, 2000, 3000)) < 0);
    Camera camera = new Camera(new Vec3f(0, 0, 40), 0, 90, 90);
    ViewBasis basis = portal.basis(camera);
    assertTrue(basis.right().cross(basis.forward()).dot(basis.up()) > .99);
    close(new Vec3f(960, 2000, 3000), portal.camera(camera).origin());
  }

  @Test
  void cameraAnglesAndRollAreHonoredAndAnimatedFlagsReported() {
    Map<String, String> surface =
        Map.of("classname", "misc_portal_surface", "origin", "0 0 16", "target", "cam");
    RenderScene upright =
        scene(
            List.of(
                surface,
                Map.of(
                    "classname",
                    "misc_portal_camera",
                    "targetname",
                    "cam",
                    "angles",
                    "0 90 0",
                    "roll",
                    "180",
                    "spawnflags",
                    "1")));
    RenderScene inverted =
        scene(
            List.of(
                surface,
                Map.of(
                    "classname",
                    "misc_portal_camera",
                    "targetname",
                    "cam",
                    "angles",
                    "0 90 0",
                    "roll",
                    "0")));
    PortalView a = PortalView.find(upright.bsp(), upright.world(), 0).orElseThrow();
    PortalView b = PortalView.find(inverted.bsp(), inverted.world(), 0).orElseThrow();
    close(new Vec3f(0, 1, 0), a.transformDirection(new Vec3f(0, 0, -1)));
    close(
        a.transformDirection(new Vec3f(1, 0, 0)).negate(),
        b.transformDirection(new Vec3f(1, 0, 0)));
    assertTrue(a.animatedCamera());
    assertFalse(b.animatedCamera());
  }

  @Test
  void missingDistantAndMalformedEntitiesDoNotInventMirrors() {
    for (Map<String, String> entity :
        List.of(
            Map.of("classname", "misc_portal_surface", "origin", "0 0 65"),
            Map.of("classname", "misc_portal_surface", "origin", "10000 0 16"),
            Map.of("classname", "misc_portal_surface", "origin", "NaN 0 0"),
            Map.of("classname", "misc_portal_surface", "origin", "0 0 16", "target", "missing"))) {
      RenderScene scene = scene(List.of(entity));
      assertTrue(PortalView.find(scene.bsp(), scene.world(), 0).isEmpty(), entity.toString());
    }
    RenderScene scene = scene(List.of());
    assertTrue(PortalView.find(scene.bsp(), scene.world(), 0).isEmpty());
  }

  @Test
  void cgamePortalEntitiesLinkMirrorsAndCamerasWithQuakeSurfaceAxes() {
    Plane wall = new Plane(new Vec3f(1, 0, 0), 0);
    PortalView mirror =
        PortalView.fromEntity(
                wall,
                new Vec3f(4, 0, 0),
                new Vec3f(4, 0, 0),
                new Vec3f(1, 0, 0),
                new Vec3f(0, 1, 0),
                new Vec3f(0, 0, 1),
                0,
                0,
                0,
                0)
            .orElseThrow();
    assertTrue(mirror.mirrored());
    close(new Vec3f(-5, 1, 2), mirror.transformPoint(new Vec3f(5, 1, 2)));
    PortalView camera =
        PortalView.fromEntity(
                wall,
                new Vec3f(4, 0, 0),
                new Vec3f(500, 0, 0),
                new Vec3f(0, 1, 0),
                new Vec3f(-1, 0, 0),
                new Vec3f(0, 0, 1),
                0,
                0,
                0,
                0)
            .orElseThrow();
    assertFalse(camera.mirrored());
    close(new Vec3f(0, 1, 0), camera.transformDirection(new Vec3f(-1, 0, 0)));
    close(new Vec3f(0, 0, 1), camera.transformDirection(new Vec3f(0, 0, 1)));
    close(new Vec3f(500, 0, 0), camera.transformPoint(Vec3f.ZERO));
    PortalView rotating =
        PortalView.fromEntity(
                wall,
                new Vec3f(4, 0, 0),
                new Vec3f(500, 0, 0),
                new Vec3f(0, 1, 0),
                new Vec3f(-1, 0, 0),
                new Vec3f(0, 0, 1),
                0,
                1,
                90,
                0)
            .orElseThrow();
    assertTrue(rotating.animatedCamera());
    close(new Vec3f(0, 1, 0), rotating.transformDirection(new Vec3f(-1, 0, 0)));
    assertEquals(0, rotating.transformDirection(new Vec3f(0, 0, 1)).z(), EPSILON);
    assertTrue(
        PortalView.fromEntity(
                wall,
                new Vec3f(100, 0, 0),
                new Vec3f(500, 0, 0),
                new Vec3f(0, 1, 0),
                new Vec3f(-1, 0, 0),
                new Vec3f(0, 0, 1),
                0,
                0,
                0,
                0)
            .isEmpty(),
        "entities beyond 64 units do not link");
  }

  @Test
  void portalViewsAreDerivedOnlyForVisibleFacingPortalsAndOneLevelDeep() {
    RenderScene scene =
        scene(List.of(Map.of("classname", "misc_portal_surface", "origin", "0 0 16")));
    assertEquals(1, scene.portalSurfaces().length);
    VisibleSet visible = new VisibleSet();
    ViewDefinition above = ViewDefinition.of(new Camera(new Vec3f(0, 0, 100), 0, 60, 90), 1);
    scene.visibility().select(above, false, false, visible);
    List<PortalViews.Derived> views = PortalViews.collect(scene, above, visible, List.of(), 1);
    assertEquals(1, views.size());
    ViewDefinition mirror = views.getFirst().view();
    assertEquals(ViewDefinition.Kind.MIRROR, mirror.kind());
    assertTrue(mirror.mirrored());
    assertEquals(1, mirror.depth());
    assertEquals(0, mirror.portalSurface());
    close(new Vec3f(0, 0, -100), mirror.origin());
    assertTrue(mirror.clipPlane().orElseThrow().signedDistance(new Vec3f(0, 0, 5)) > 0);
    assertTrue(mirror.pvsOrigin().z() > 0, "visibility is computed in front of the mirror");
    assertTrue(PortalViews.collect(scene, mirror, visible, List.of(), 1).isEmpty());
    ViewDefinition below = ViewDefinition.of(new Camera(new Vec3f(0, 0, -100), 0, -60, 90), 1);
    assertTrue(PortalViews.collect(scene, below, visible, List.of(), 1).isEmpty());
    PortalViews.PortalEntity entity =
        new PortalViews.PortalEntity(
            new Vec3f(0, 0, 8),
            new Vec3f(0, 0, 8),
            new Vec3f(1, 0, 0),
            new Vec3f(0, 1, 0),
            new Vec3f(0, 0, 1),
            0,
            0,
            0);
    assertEquals(
        ViewDefinition.Kind.MIRROR,
        PortalViews.collect(scene, above, visible, List.of(entity), 1).getFirst().view().kind());
  }

  /** The upward fixture triangle at z = 0 with a portal material and the given entities. */
  private static RenderScene scene(List<Map<String, String>> entities) {
    StringBuilder text = new StringBuilder("{\n\"classname\" \"worldspawn\"\n}\n");
    for (Map<String, String> entity : entities) {
      text.append("{\n");
      entity.forEach((k, v) -> text.append('"').append(k).append("\" \"").append(v).append("\"\n"));
      text.append("}\n");
    }
    TestBsp bsp = new TestBsp().entities(text.toString());
    int texture = bsp.texture("textures/portal", 0);
    bsp.vertex(-128, -128, 0, 0, 0, 0, 0, 0, 0, 1, -1);
    bsp.vertex(128, -128, 0, 1, 0, 0, 0, 0, 0, 1, -1);
    bsp.vertex(-128, 128, 0, 0, 1, 0, 0, 0, 0, 1, -1);
    bsp.planar(texture, -1, -1, 0, 3, new float[] {0, 0, 1}, 0, 2, 1);
    bsp.model(new float[] {-256, -256, -256}, new float[] {256, 256, 256}, 0, 1, 0, 0);
    ShaderLibrary library =
        ShaderLibrary.of(
            List.of(
                new ShaderLibrary.Script(
                    "scripts/p.shader",
                    """
                    textures/portal
                    {
                      portal
                      {
                        map textures/portal.tga
                        blendFunc blend
                        alphaGen portal 256
                      }
                    }
                    """)));
    return RenderScene.build("portal", bsp.read(), library::resolve);
  }

  private static void close(Vec3f expected, Vec3f actual) {
    assertEquals(expected.x(), actual.x(), EPSILON, () -> expected + " vs " + actual);
    assertEquals(expected.y(), actual.y(), EPSILON, () -> expected + " vs " + actual);
    assertEquals(expected.z(), actual.z(), EPSILON, () -> expected + " vs " + actual);
  }
}
