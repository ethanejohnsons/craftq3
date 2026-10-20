package dev.bluevista.craftq3.mc.hybrid.bridge;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.mc.hybrid.CoordinateTransform;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An original Quake item placed in the Minecraft world: its classname and where it stands. The
 * position is saved in Minecraft coordinates, which stay put while each bridge session anchors
 * Quake coordinates afresh; {@link #quakeOrigin} gives the item's Quake origin for a session.
 * Quantities, pickup rules and respawn stay in qagame.
 *
 * @param id a stable identifier within one world, game and dimension ({@code 1..MAX_PLACEMENTS})
 * @param classname e.g. {@code weapon_rocketlauncher}, {@code item_armor_combat}
 */
public record PickupPlacement(int id, String classname, double x, double y, double z) {
  /** Placements per world, game and dimension. */
  public static final int MAX_PLACEMENTS = 256;

  private static final Pattern CLASSNAME =
      Pattern.compile("(weapon|ammo|item|holdable|team)_[a-z0-9_]{1,48}");

  public PickupPlacement {
    Objects.requireNonNull(classname, "classname");
    if (id < 1 || id > MAX_PLACEMENTS) throw new IllegalArgumentException("Pickup id " + id);
    if (!isItemClassname(classname))
      throw new IllegalArgumentException("Not an item classname: " + classname);
    if (!Double.isFinite(x)
        || !Double.isFinite(y)
        || !Double.isFinite(z)
        || Math.abs(x) > 30_000_000
        || Math.abs(z) > 30_000_000
        || Math.abs(y) > 20_000) throw new IllegalArgumentException("Pickup position out of range");
  }

  /** True for a structurally valid item classname; the running game decides if it knows it. */
  public static boolean isItemClassname(String classname) {
    return CLASSNAME.matcher(classname).matches();
  }

  public Vec3f quakeOrigin(CoordinateTransform transform) {
    return transform.toQuake(x, y, z);
  }

  /** The placements within {@code radius} blocks (horizontally) of a point, nearest first. */
  public static List<PickupPlacement> near(
      List<PickupPlacement> placements, double x, double z, double radius, int max) {
    List<PickupPlacement> out = new ArrayList<>();
    for (PickupPlacement p : placements) {
      double dx = p.x - x;
      double dz = p.z - z;
      if (dx * dx + dz * dz <= radius * radius) out.add(p);
    }
    out.sort(
        (a, b) ->
            Double.compare(
                (a.x - x) * (a.x - x) + (a.z - z) * (a.z - z),
                (b.x - x) * (b.x - x) + (b.z - z) * (b.z - z)));
    return List.copyOf(out.subList(0, Math.min(max, out.size())));
  }

  /** The lowest unused id, or -1 when every id is taken. */
  public static int nextId(List<PickupPlacement> placements) {
    boolean[] used = new boolean[MAX_PLACEMENTS + 1];
    for (PickupPlacement p : placements) used[p.id] = true;
    for (int id = 1; id <= MAX_PLACEMENTS; id++) if (!used[id]) return id;
    return -1;
  }
}
