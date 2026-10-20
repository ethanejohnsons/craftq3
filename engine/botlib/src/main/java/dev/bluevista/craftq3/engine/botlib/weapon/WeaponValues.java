package dev.bluevista.craftq3.engine.botlib.weapon;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Objects;

/** Field checks shared by the weapon and projectile records: the original's 80-byte strings. */
final class WeaponValues {
  private WeaponValues() {}

  static String string(String value) {
    Objects.requireNonNull(value);
    if (value.length() > 79 || value.chars().anyMatch(c -> c == 0 || c > 255))
      throw new IllegalArgumentException(
          "Weapon strings must be non-NUL Latin-1 and at most 79 bytes");
    return value;
  }

  static void finite(float... values) {
    for (float value : values)
      if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite weapon value");
  }

  static Vec3f vector(Vec3f value) {
    Objects.requireNonNull(value);
    finite(value.x(), value.y(), value.z());
    return value;
  }
}
