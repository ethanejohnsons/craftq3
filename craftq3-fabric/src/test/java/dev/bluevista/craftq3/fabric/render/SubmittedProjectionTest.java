package dev.bluevista.craftq3.fabric.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.render.CgameFrame;
import java.util.List;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

class SubmittedProjectionTest {
  @Test
  void independentFovsAndSubViewportProjectToExpectedFramebufferCoordinates() {
    var ref =
        new CgameFrame.Refdef(
            100,
            50,
            200,
            100,
            90,
            60,
            new Vec3(0, 0, 0),
            new Vec3(1, 0, 0),
            new Vec3(0, 1, 0),
            new Vec3(0, 0, 1),
            0,
            0,
            new BspMap.Bytes(new byte[0]),
            List.of());
    var matrix = SubmittedProjection.matrix(ref, 800, 600, true);
    var center = matrix.transform(new Vector4f(10, 0, 0, 1));
    center.div(center.w());
    assertEquals(-.5, center.x(), 1e-6);
    assertEquals(2.0 / 3, center.y(), 1e-6);
    var right = matrix.transform(new Vector4f(10, -10, 0, 1));
    right.div(right.w());
    assertEquals(-.25, right.x(), 1e-6);
    var top = matrix.transform(new Vector4f(10, 0, (float) (10 * Math.tan(Math.toRadians(30))), 1));
    top.div(top.w());
    assertEquals(5.0 / 6, top.y(), 1e-6);
    assertFalse(
        SubmittedProjection.intersects(
            ref, new BspMap.Bounds(new Vec3(-20, -1, -1), new Vec3(-10, 1, 1))));
  }

  @Test
  void depthHackCompressesDepthForBothBackendDepthConventions() {
    var ref =
        new CgameFrame.Refdef(
            0,
            0,
            640,
            480,
            90,
            74,
            new Vec3(0, 0, 0),
            new Vec3(1, 0, 0),
            new Vec3(0, 1, 0),
            new Vec3(0, 0, 1),
            0,
            0,
            new BspMap.Bytes(new byte[0]),
            List.of());
    for (boolean zero : new boolean[] {true, false}) {
      var matrix = SubmittedProjection.matrix(ref, 640, 480, zero);
      var base = matrix.transform(new Vector4f(100, 0, 0, 1));
      var hacked =
          SubmittedProjection.depthHack(matrix, zero).transform(new Vector4f(100, 0, 0, 1));
      double depth = base.z() / base.w(), near = hacked.z() / hacked.w();
      if (!zero) {
        depth = depth * .5 + .5;
        near = near * .5 + .5;
      }
      assertEquals(depth * .3, near, 1e-6);
    }
  }
}
