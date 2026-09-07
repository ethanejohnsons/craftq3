package dev.bluevista.craftq3.collision;

import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.file.Path;

/**
 * Optional local-asset audit. Original PK3 data and generated geometry never become test fixtures.
 */
public final class RealMapCollisionAudit {
  private RealMapCollisionAudit() {}

  public static void main(String[] arguments) throws Exception {
    if (arguments.length < 2)
      throw new IllegalArgumentException("Usage: install-directory map-name...");
    try (var fs = Pk3FileSystem.mount(Path.of(arguments[0]), "baseq3")) {
      for (int i = 1; i < arguments.length; i++) {
        String name = arguments[i];
        var map = BspReader.read(fs.read(new VirtualPath("maps/" + name + ".bsp")));
        long before = System.nanoTime();
        var world = new BspTraceWorld(map);
        double loadMs = (System.nanoTime() - before) / 1e6;
        int spawns = 0, hits = 0, solid = 0;
        for (var entity : map.entities()) {
          if (!entity.getOrDefault("classname", "").equals("info_player_deathmatch")) continue;
          String[] xyz = entity.getOrDefault("origin", "").trim().split("\\s+");
          if (xyz.length != 3) continue;
          Vec3 start =
              new Vec3(
                  Double.parseDouble(xyz[0]),
                  Double.parseDouble(xyz[1]),
                  Double.parseDouble(xyz[2]) + 9);
          var trace =
              world.trace(
                  TraceRequest.box(
                      start,
                      start.add(new Vec3(0, 0, -4096)),
                      new Vec3(-15, -15, -24),
                      new Vec3(15, 15, 32),
                      Contents.MASK_PLAYERSOLID));
          if (trace.blocked()) hits++;
          if (trace.startSolid()) solid++;
          spawns++;
          if (spawns == 1) System.out.println(name + " first floor " + trace);
        }
        System.out.println(
            name
                + " "
                + world.statistics()
                + " buildMs="
                + loadMs
                + " spawns="
                + spawns
                + " floorHits="
                + hits
                + " startSolid="
                + solid);
        if (spawns == 0 || hits != spawns || solid != 0)
          throw new IllegalStateException(
              "Not every original spawn has an unobstructed floor trace: " + name);
      }
    }
  }
}
