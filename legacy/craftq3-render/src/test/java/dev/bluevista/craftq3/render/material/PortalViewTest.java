package dev.bluevista.craftq3.render.material;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspFixture;
import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.render.BspSceneBuilder;
import dev.bluevista.craftq3.render.RenderScene;
import dev.bluevista.craftq3.render.RenderScene.Camera;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PortalViewTest {
  @Test
  void mirrorReflectsAcrossSurfaceAndRetainsNegativeHandedness() throws Exception {
    RenderScene scene =
        scene(List.of(Map.of("classname", "misc_portal_surface", "origin", "0 0 16")));
    PortalView mirror = PortalView.find(scene, scene.surfaces().getFirst()).orElseThrow();
    assertTrue(mirror.mirrored());
    close(new Vec3(10, -20, -40), mirror.transformPoint(new Vec3(10, -20, 40)));
    close(new Vec3(1, 2, -3), mirror.transformDirection(new Vec3(1, 2, 3)));
    close(new Vec3(10, 20, 30), mirror.transformPoint(mirror.transformPoint(new Vec3(10, 20, 30))));
    Camera camera = new Camera(new Vec3(10, -20, 40), 30, 45, 90);
    PortalView.Basis basis = mirror.basis(camera);
    assertTrue(ShaderMath.dot(ShaderMath.cross(basis.right(), basis.forward()), basis.up()) < -.99);
    assertEquals(-45, mirror.camera(camera).pitch(), 1e-5);
    assertTrue(mirror.destinationPlane().signedDistance(new Vec3(0, 0, 10)) > 0);
    assertFalse(mirror.animatedCamera());
  }

  @Test
  void linkedPortalMapsLocalFrameToCameraTargetAndClipPlane() throws Exception {
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
    PortalView portal = PortalView.find(scene, scene.surfaces().getFirst()).orElseThrow();
    assertFalse(portal.mirrored());
    close(new Vec3(1000, 2000, 3000), portal.transformPoint(new Vec3(0, 0, 0)));
    close(new Vec3(970, 1990, 3020), portal.transformPoint(new Vec3(10, 20, 30)));
    close(new Vec3(1, 0, 0), portal.transformDirection(new Vec3(0, 0, -1)));
    assertTrue(portal.destinationPlane().signedDistance(new Vec3(1100, 2000, 3000)) > 0);
    assertTrue(portal.destinationPlane().signedDistance(new Vec3(900, 2000, 3000)) < 0);
    Camera camera = new Camera(new Vec3(0, 0, 40), 0, 90, 90);
    PortalView.Basis basis = portal.basis(camera);
    assertTrue(ShaderMath.dot(ShaderMath.cross(basis.right(), basis.forward()), basis.up()) > .99);
    close(new Vec3(960, 2000, 3000), portal.camera(camera).origin());
  }

  @Test
  void cameraAnglesAndRollAreHonoredAndAnimatedFlagsReported() throws Exception {
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
    PortalView a = PortalView.find(upright, upright.surfaces().getFirst()).orElseThrow();
    PortalView b = PortalView.find(inverted, inverted.surfaces().getFirst()).orElseThrow();
    close(new Vec3(0, 1, 0), a.transformDirection(new Vec3(0, 0, -1)));
    close(
        a.transformDirection(new Vec3(1, 0, 0)).scale(-1), b.transformDirection(new Vec3(1, 0, 0)));
    assertTrue(a.animatedCamera());
    assertFalse(b.animatedCamera());
  }

  @Test
  void missingDistantAndMalformedEntitiesDoNotInventMirrors() throws Exception {
    for (Map<String, String> entity :
        List.of(
            Map.of("classname", "misc_portal_surface", "origin", "0 0 65"),
            Map.of("classname", "misc_portal_surface", "origin", "10000 0 16"),
            Map.of("classname", "misc_portal_surface", "origin", "NaN 0 0"),
            Map.of("classname", "misc_portal_surface", "origin", "0 0 16", "target", "missing"))) {
      RenderScene scene = scene(List.of(entity));
      assertTrue(PortalView.find(scene, scene.surfaces().getFirst()).isEmpty());
    }
    RenderScene scene = scene(List.of());
    assertTrue(PortalView.find(scene, scene.surfaces().getFirst()).isEmpty());
  }

  private static RenderScene scene(List<Map<String, String>> entities) throws Exception {
    BspMap old = BspReader.read(BspFixture.map(false));
    BspMap map =
        new BspMap(
            entities,
            old.textures(),
            old.planes(),
            old.nodes(),
            old.leaves(),
            old.leafFaces(),
            old.leafBrushes(),
            old.models(),
            old.brushes(),
            old.brushSides(),
            old.vertices(),
            old.meshVertices(),
            old.effects(),
            old.faces(),
            old.lightmaps(),
            old.lightVolumes(),
            old.visibility());
    return BspSceneBuilder.build("portal_fixture", map, 2);
  }

  private static void close(Vec3 expected, Vec3 actual) {
    assertEquals(expected.x(), actual.x(), 1e-9);
    assertEquals(expected.y(), actual.y(), 1e-9);
    assertEquals(expected.z(), actual.z(), 1e-9);
  }
}
