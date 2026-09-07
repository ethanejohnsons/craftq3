package dev.bluevista.craftq3.botlib.weapon;

import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.ByteBuffer;
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
    Vec3 recoil,
    Vec3 offset,
    Vec3 angleOffset,
    float extraZVelocity,
    int ammoAmount,
    int ammoIndex,
    float activate,
    float reload,
    float spinUp,
    float spinDown,
    ProjectileInfo projectileInfo) {
  public static final int BYTE_SIZE = 552, PROJECTILE_OFFSET = 344;
  private static final Vec3 ZERO = new Vec3(0, 0, 0);
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
          ZERO,
          ZERO,
          ZERO,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          ProjectileInfo.EMPTY);

  public WeaponInfo {
    name = WeaponAbi.string(name);
    model = WeaponAbi.string(model);
    projectile = WeaponAbi.string(projectile);
    WeaponAbi.finite(
        horizontalSpread,
        verticalSpread,
        speed,
        acceleration,
        extraZVelocity,
        activate,
        reload,
        spinUp,
        spinDown);
    recoil = WeaponAbi.vector(recoil);
    offset = WeaponAbi.vector(offset);
    angleOffset = WeaponAbi.vector(angleOffset);
    Objects.requireNonNull(projectileInfo);
  }

  /** Exactly 552 little-endian ABI bytes; destination position and byte order remain unchanged. */
  public void writeTo(ByteBuffer destination, int destinationOffset) {
    ByteBuffer output = WeaponAbi.output(destination, destinationOffset, BYTE_SIZE);
    output.putInt(valid ? 1 : 0).putInt(number);
    WeaponAbi.putString(output, name);
    WeaponAbi.putString(output, model);
    output.putInt(level).putInt(weaponIndex).putInt(flags);
    WeaponAbi.putString(output, projectile);
    output
        .putInt(projectileCount)
        .putFloat(horizontalSpread)
        .putFloat(verticalSpread)
        .putFloat(speed)
        .putFloat(acceleration);
    WeaponAbi.putVector(output, recoil);
    WeaponAbi.putVector(output, offset);
    WeaponAbi.putVector(output, angleOffset);
    output
        .putFloat(extraZVelocity)
        .putInt(ammoAmount)
        .putInt(ammoIndex)
        .putFloat(activate)
        .putFloat(reload)
        .putFloat(spinUp)
        .putFloat(spinDown);
    projectileInfo.put(output);
  }
}
