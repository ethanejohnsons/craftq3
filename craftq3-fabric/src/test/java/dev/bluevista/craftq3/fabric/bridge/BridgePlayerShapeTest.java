package dev.bluevista.craftq3.fabric.bridge;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.core.math.Vec3;
import org.junit.jupiter.api.Test;

class BridgePlayerShapeTest {
  @Test
  void originalStandingAndCrouchingDimensionsFollowFeetAndEyeHeight() {
    var origin = new Vec3(1000, -2000, 3000);
    var min = origin.add(new Vec3(-15, -15, -24));
    var standing =
        BridgePlayerShape.from(
            new BspMap.Bounds(min, origin.add(new Vec3(15, 15, 32))), origin, 26, 32);
    var crouched =
        BridgePlayerShape.from(
            new BspMap.Bounds(min, origin.add(new Vec3(15, 15, 16))), origin, 12, 32);
    assertEquals(new Vec3(-.46875, 0, -.46875), standing.bounds().min());
    assertEquals(new Vec3(.46875, 1.75, .46875), standing.bounds().max());
    assertEquals(.9375, standing.width());
    assertEquals(1.75, standing.height());
    assertEquals(1.5625, standing.eyeHeight());
    assertEquals(.9375, crouched.width());
    assertEquals(1.25, crouched.height());
    assertEquals(1.125, crouched.eyeHeight());
  }

  @Test
  void asymmetricHullsKeepAxisOffsetsAndInvalidSizesFailBeforeNativeUse() {
    var origin = new Vec3(-900, 70, -50);
    var hull =
        new BspMap.Bounds(origin.add(new Vec3(-10, -20, -12)), origin.add(new Vec3(30, 40, 52)));
    var shape = BridgePlayerShape.from(hull, origin, 10, 32);
    assertEquals(new Vec3(-.3125, 0, -1.25), shape.bounds().min());
    assertEquals(new Vec3(.9375, 2, .625), shape.bounds().max());
    assertEquals(.6875, shape.eyeHeight());
    assertThrows(IllegalArgumentException.class, () -> BridgePlayerShape.from(hull, origin, 10, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> BridgePlayerShape.from(hull, origin, Integer.MAX_VALUE, 32));
    assertThrows(
        IllegalArgumentException.class, () -> BridgePlayerShape.from(hull, origin, 10, .01));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BridgePlayerShape(new BspMap.Bounds(new Vec3(0, 0, 0), new Vec3(0, 1, 1)), .5));
  }
}
