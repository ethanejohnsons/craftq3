package dev.bluevista.craftq3.fabric.building;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Server-owned immutable geometry survives view/session closure while native chunks still tick. */
public final class BuildingWorlds {
  public record Environment(BuildingGeometry geometry, BuildingSupport support) {}

  private static final Map<ServerLevel, Map<Integer, Environment>> WORLDS = new WeakHashMap<>();

  private BuildingWorlds() {}

  public static synchronized void register(ServerLevel level, int slot, Environment environment) {
    if (!level.dimension().equals(BuildingSession.DIMENSION) || slot < 0 || slot >= 4096)
      throw new IllegalArgumentException("Invalid building region");
    WORLDS.computeIfAbsent(level, ignored -> new HashMap<>()).put(slot, environment);
  }

  public static Environment at(Level level, double x) {
    if (!level.dimension().equals(BuildingSession.DIMENSION)) return null;
    int region = BuildSpaceIndex.region(x);
    if (region < 0) return null;
    if (level instanceof ServerLevel serverLevel) {
      synchronized (BuildingWorlds.class) {
        var regions = WORLDS.get(serverLevel);
        return regions == null ? null : regions.get(region);
      }
    }
    var session = BuildingSession.active();
    return session != null && session.matches(level) && session.region() == region
        ? session.environment()
        : null;
  }

  public static synchronized void clear(MinecraftServer server) {
    WORLDS.keySet().removeIf(level -> level.getServer() == server);
  }
}
