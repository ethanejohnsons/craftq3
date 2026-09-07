package dev.bluevista.craftq3.server;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Map;
import java.util.Objects;

/** Original item spawn request; quantities, pickup rules and respawn remain in qagame. */
public record ExternalPickup(String classname, Vec3 origin) {
  public ExternalPickup {
    Objects.requireNonNull(classname);
    Objects.requireNonNull(origin);
    if (!classname.matches("(weapon|ammo|item|holdable)_[a-z0-9_]{1,48}")
        || Math.abs(origin.x()) > 1_000_000
        || Math.abs(origin.y()) > 1_000_000
        || Math.abs(origin.z()) > 1_000_000)
      throw new IllegalArgumentException("Invalid external pickup spawn");
  }

  Map<String, String> entity() {
    return Map.of(
        "classname", classname, "origin", origin.x() + " " + origin.y() + " " + origin.z());
  }
}
