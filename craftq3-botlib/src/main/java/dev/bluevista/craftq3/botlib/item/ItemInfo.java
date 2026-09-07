package dev.bluevista.craftq3.botlib.item;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Objects;

/** User-configured item metadata. Inventory indexes, model indexes and types remain game data. */
public record ItemInfo(
    String classname,
    String name,
    String model,
    int modelIndex,
    int type,
    int inventoryIndex,
    float respawnTime,
    Vec3 mins,
    Vec3 maxs,
    int number) {
  public ItemInfo {
    Objects.requireNonNull(classname);
    Objects.requireNonNull(name);
    Objects.requireNonNull(model);
    Objects.requireNonNull(mins);
    Objects.requireNonNull(maxs);
    if (classname.length() > 31
        || name.length() > 79
        || model.length() > 79
        || number < 0
        || !Float.isFinite(respawnTime))
      throw new IllegalArgumentException("Invalid bot item metadata");
    mins = new Vec3((float) mins.x(), (float) mins.y(), (float) mins.z());
    maxs = new Vec3((float) maxs.x(), (float) maxs.y(), (float) maxs.z());
    if (mins.x() > maxs.x() || mins.y() > maxs.y() || mins.z() > maxs.z())
      throw new IllegalArgumentException("Inverted bot item bounds");
  }
}
