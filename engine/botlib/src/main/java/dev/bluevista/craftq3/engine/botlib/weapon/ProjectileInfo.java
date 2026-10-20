package dev.bluevista.craftq3.engine.botlib.weapon;

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
  public static final ProjectileInfo EMPTY =
      new ProjectileInfo("", "", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

  public ProjectileInfo {
    name = WeaponValues.string(name);
    model = WeaponValues.string(model);
    WeaponValues.finite(gravity, radius, push, detonation, bounce, bounceFriction, bounceStop);
  }
}
