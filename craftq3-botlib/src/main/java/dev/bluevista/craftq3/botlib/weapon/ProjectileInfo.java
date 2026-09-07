package dev.bluevista.craftq3.botlib.weapon;

import java.nio.ByteBuffer;

/** Immutable projectileinfo_t metadata; the original game remains responsible for applying it. */
public record ProjectileInfo(
    String name,
    String model,
    int flags,
    float gravity,
    int damage,
    float radius,
    int visibleDamage,
    int damageType,
    int healthIncrement,
    float push,
    float detonation,
    float bounce,
    float bounceFriction,
    float bounceStop) {
  public static final int BYTE_SIZE = 208;
  public static final ProjectileInfo EMPTY =
      new ProjectileInfo("", "", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

  public ProjectileInfo {
    name = WeaponAbi.string(name);
    model = WeaponAbi.string(model);
    WeaponAbi.finite(gravity, radius, push, detonation, bounce, bounceFriction, bounceStop);
  }

  public void writeTo(ByteBuffer destination, int offset) {
    put(WeaponAbi.output(destination, offset, BYTE_SIZE));
  }

  void put(ByteBuffer output) {
    WeaponAbi.putString(output, name);
    WeaponAbi.putString(output, model);
    output
        .putInt(flags)
        .putFloat(gravity)
        .putInt(damage)
        .putFloat(radius)
        .putInt(visibleDamage)
        .putInt(damageType)
        .putInt(healthIncrement)
        .putFloat(push)
        .putFloat(detonation)
        .putFloat(bounce)
        .putFloat(bounceFriction)
        .putFloat(bounceStop);
  }
}
