package dev.bluevista.craftq3.fabric.building;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.render.LightGrid;
import net.minecraft.util.LightCoordsUtil;
import org.junit.jupiter.api.Test;

class BuildingLightingTest {
  @Test
  void gridBrightnessPreservesNativeSkyEmissionAndDarkness() {
    int nativeLight = LightCoordsUtil.pack(4, 9);
    var dark = new LightGrid.Sample(new Vec3(0, 0, 0), new Vec3(0, 0, 0), new Vec3(0, 0, 1));
    assertEquals(nativeLight, BuildingLighting.merge(dark, nativeLight));
    int previous = 4;
    for (double brightness : new double[] {.05, .25, .5, .75, 1}) {
      var sample =
          new LightGrid.Sample(
              new Vec3(brightness, brightness, brightness), new Vec3(0, 0, 0), new Vec3(0, 0, 1));
      int light = BuildingLighting.merge(sample, nativeLight);
      assertEquals(9, LightCoordsUtil.sky(light));
      assertTrue(LightCoordsUtil.block(light) >= previous);
      assertTrue(LightCoordsUtil.block(light) <= 15);
      assertEquals(
          LightCoordsUtil.pack(15, 3), BuildingLighting.merge(sample, LightCoordsUtil.pack(15, 3)));
      previous = LightCoordsUtil.block(light);
    }
    assertEquals(15, previous);
  }
}
