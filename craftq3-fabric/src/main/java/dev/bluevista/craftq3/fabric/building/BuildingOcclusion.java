package dev.bluevista.craftq3.fabric.building;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Solid BSP occlusion shared by native sight and explosion queries. */
public final class BuildingOcclusion {
  private BuildingOcclusion() {}

  public static boolean blocked(Level level, Vec3 start, Vec3 end) {
    if (!level.dimension().equals(BuildingSession.DIMENSION)) return false;
    int first = (int) Math.clamp(Math.floor((Math.min(start.x, end.x) + 2048) / 4096), 0, 4096);
    int last = Math.min(4095, (int) Math.floor((Math.max(start.x, end.x) + 2048) / 4096));
    var from = new dev.bluevista.craftq3.core.math.Vec3(start.x, start.y, start.z);
    var to = new dev.bluevista.craftq3.core.math.Vec3(end.x, end.y, end.z);
    for (int slot = first; slot <= last; slot++) {
      var environment = BuildingWorlds.at(level, slot * 4096.0);
      if (environment != null && environment.geometry().occludes(from, to)) return true;
    }
    return false;
  }
}
