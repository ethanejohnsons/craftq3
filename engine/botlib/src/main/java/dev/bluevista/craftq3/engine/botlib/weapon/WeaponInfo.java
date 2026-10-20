package dev.bluevista.craftq3.engine.botlib.weapon;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Objects;

/** Immutable original weaponinfo_t value, including its linked projectile metadata. */
public record WeaponInfo(
    boolean valid,
    int number,
    String name,
    String model,
    int level,
    int weaponIndex,
    int flags,
    String projectile,
    int projectileCount,
    float horizontalSpread,
    float verticalSpread,
    float speed,
    float acceleration,
    Vec3f recoil,
    Vec3f offset,
    Vec3f angleOffset,
    float extraZVelocity,
    int ammoAmount,
    int ammoIndex,
    float activate,
    float reload,
    float spinUp,
    float spinDown,
    ProjectileInfo projectileInfo) {
  public static final WeaponInfo EMPTY =
      new WeaponInfo(
          false,
          0,
          "",
          "",
          0,
          0,
          0,
          "",
          0,
          0,
          0,
          0,
          0,
          Vec3f.ZERO,
          Vec3f.ZERO,
          Vec3f.ZERO,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          ProjectileInfo.EMPTY);

  public WeaponInfo {
    name = WeaponValues.string(name);
    model = WeaponValues.string(model);
    projectile = WeaponValues.string(projectile);
    WeaponValues.finite(
        horizontalSpread,
        verticalSpread,
        speed,
        acceleration,
        extraZVelocity,
        activate,
        reload,
        spinUp,
        spinDown);
    recoil = WeaponValues.vector(recoil);
    offset = WeaponValues.vector(offset);
    angleOffset = WeaponValues.vector(angleOffset);
    Objects.requireNonNull(projectileInfo);
  }
}
