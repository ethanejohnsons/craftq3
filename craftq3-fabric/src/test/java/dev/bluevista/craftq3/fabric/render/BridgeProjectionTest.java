package dev.bluevista.craftq3.fabric.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.platform.CoordinateTransform;
import dev.bluevista.craftq3.render.*;
import java.util.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

class BridgeProjectionTest {
  private static CgameFrame.Refdef ref(int flags) {
    double roll = .4;
    return new CgameFrame.Refdef(
        0,
        0,
        800,
        600,
        90,
        74,
        new Vec3(32, 64, 96),
        new Vec3(1, 0, 0),
        new Vec3(0, Math.cos(roll), Math.sin(roll)),
        new Vec3(0, -Math.sin(roll), Math.cos(roll)),
        1000,
        flags,
        new BspMap.Bytes(new byte[0]),
        List.of());
  }

  @Test
  void cgameRollAndBasisMatchMinecraftViewWithoutChangingScale() {
    var transform = new CoordinateTransform(32, new Vec3(0, 0, 0));
    var ref = ref(0);
    var rotation = BridgeProjection.rotation(ref, transform);
    var axes = List.of(ref.basis().right(), ref.basis().up(), ref.basis().forward());
    var expected = List.of(new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, -1));
    for (int i = 0; i < axes.size(); i++) {
      var mc = transform.directionToMinecraft(axes.get(i)).scale(32);
      var actual =
          rotation.transformDirection(new Vector3f((float) mc.x(), (float) mc.y(), (float) mc.z()));
      assertTrue(actual.distance(expected.get(i)) < 1e-6);
    }
  }

  @Test
  void quakeAndNativePositionsProduceSameClipCoordinatesAtLargeAnchors() {
    var transform = new CoordinateTransform(32, new Vec3(1000000, 80, -1000000));
    var eye = transform.toMinecraft(ref(0).origin());
    var camera = new net.minecraft.world.phys.Vec3(eye.x(), eye.y(), eye.z());
    var projection = new Matrix4f().perspective(1.2f, 4f / 3, 1024, .05f, true);
    var rotation = BridgeProjection.rotation(ref(0), transform);
    var combined = BridgeProjection.world(projection, rotation, camera, transform);
    var nativeMatrix = new Matrix4f(projection).mul(rotation);
    var random = new Random(142);
    for (int i = 0; i < 100; i++) {
      var quake = new Vec3(random.nextInt(10000), random.nextInt(10000), random.nextInt(10000));
      var mc = transform.toMinecraft(quake).add(eye.scale(-1));
      var a =
          combined.transform(
              new Vector4f((float) quake.x(), (float) quake.y(), (float) quake.z(), 1));
      var b =
          nativeMatrix.transform(new Vector4f((float) mc.x(), (float) mc.y(), (float) mc.z(), 1));
      assertTrue(a.distance(b) < .0001, a + " != " + b);
    }
  }

  @Test
  void reversedViewmodelDepthStaysNearWithoutChangingScreenCoordinates() {
    for (boolean zero : new boolean[] {true, false})
      for (float depth : new float[] {0, .2f, .9f, 1}) {
        float clip = zero ? depth : depth * 2 - 1;
        var point = new Vector4f(.2f, -.3f, clip, 1);
        var result = SubmittedProjection.depthHack(new Matrix4f(), zero, true).transform(point);
        float actual = zero ? result.z() : result.z() * .5f + .5f;
        assertEquals(.7f + .3f * depth, actual, 1e-6);
        assertEquals(.2f, result.x());
        assertEquals(-.3f, result.y());
      }
  }

  @Test
  void worldAndHudViewsRetainOrderAndResourcesWithinTheirPasses() {
    var world = new CgameFrame.View(ref(0), List.of(), List.of(), List.of());
    var hud =
        new CgameFrame.View(ref(CgameFrame.RDF_NOWORLDMODEL), List.of(), List.of(), List.of());
    var frame = new CgameFrame(List.of(world, hud, hud), SceneAssets.EMPTY, 1000);
    assertEquals(List.of(world), BridgeProjection.partition(frame, true).commands());
    assertEquals(List.of(hud, hud), BridgeProjection.partition(frame, false).commands());
    assertSame(frame.assets(), BridgeProjection.partition(frame, true).assets());
  }
}
