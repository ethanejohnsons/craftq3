package dev.bluevista.craftq3.engine.botlib.item;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
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
    Vec3f mins,
    Vec3f maxs,
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
    mins = new Vec3f(mins.x(), mins.y(), mins.z());
    maxs = new Vec3f(maxs.x(), maxs.y(), maxs.z());
    if (mins.x() > maxs.x() || mins.y() > maxs.y() || mins.z() > maxs.z())
      throw new IllegalArgumentException("Inverted bot item bounds");
  }
}
