package dev.bluevista.craftq3.fabric.building;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.render.LightGrid;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.Level;

/** Render-only light-grid brightness for native entities; never changes native world light data. */
public final class BuildingLighting {
  private final LightGrid grid;
  private final BspMap.Bounds bounds;

  BuildingLighting(BspMap map) {
    grid = new LightGrid(map);
    bounds = map.models().getFirst().bounds();
  }

  public static int apply(Level level, net.minecraft.world.phys.Vec3 point, int nativeLight) {
    var session = BuildingSession.active();
    if (session == null || !session.matches(level) || BuildingDecorationSmoke.baselineLighting())
      return nativeLight;
    var q = session.transform().toQuake(new Vec3(point.x, point.y, point.z));
    return session.lighting().sample(q, nativeLight);
  }

  int sample(Vec3 point, int nativeLight) {
    if (point.x() < bounds.min().x()
        || point.x() > bounds.max().x()
        || point.y() < bounds.min().y()
        || point.y() > bounds.max().y()
        || point.z() < bounds.min().z()
        || point.z() > bounds.max().z()) return nativeLight;
    return grid.sampleIfPresent(point)
        .map(sample -> merge(sample, nativeLight))
        .orElse(nativeLight);
  }

  static int merge(LightGrid.Sample sample, int nativeLight) {
    // A surface-independent brightness estimate for Minecraft's scalar light map.
    // Native normals still supply face shading; colored/directional Q3 lighting is not encoded.
    var energy = sample.ambient().add(sample.directed().scale(.5));
    double luminance =
        Math.clamp(.2126 * energy.x() + .7152 * energy.y() + .0722 * energy.z(), 0, 1);
    // Invert Minecraft's zero-ambient brightness curve b/(4-3b) before quantization.
    int block = (int) Math.round(15 * (4 * luminance / (1 + 3 * luminance)));
    return block <= LightCoordsUtil.block(nativeLight)
        ? nativeLight
        : LightCoordsUtil.withBlock(nativeLight, block);
  }
}
