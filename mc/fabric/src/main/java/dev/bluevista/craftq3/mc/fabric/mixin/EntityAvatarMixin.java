package dev.bluevista.craftq3.mc.fabric.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.bluevista.craftq3.mc.fabric.bridge.AvatarShape;
import dev.bluevista.craftq3.mc.fabric.bridge.BridgeAvatar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Replaces {@code BridgeAvatarMixin}: a bridged player's bounding box, width, height and eye height
 * report its Quake body ({@link AvatarShape}), set by whichever side owns the entity. The shape is
 * a plain field on the entity, so there is no lookup and no client class here.
 */
@Mixin(Entity.class)
public abstract class EntityAvatarMixin implements BridgeAvatar {
  @Unique private volatile AvatarShape craftq3$avatar;

  @Override
  public AvatarShape craftq3$avatar() {
    return craftq3$avatar;
  }

  @Override
  public void craftq3$setAvatar(AvatarShape shape) {
    craftq3$avatar = shape;
  }

  @ModifyReturnValue(method = "getBoundingBox", at = @At("RETURN"))
  private AABB craftq3$avatarBox(AABB original) {
    AvatarShape shape = craftq3$avatar;
    if (shape == null) return original;
    Entity self = (Entity) (Object) this;
    double x = self.getX(), y = self.getY(), z = self.getZ();
    return new AABB(
        x + shape.minX(),
        y,
        z + shape.minZ(),
        x + shape.maxX(),
        y + shape.height(),
        z + shape.maxZ());
  }

  @ModifyReturnValue(method = "getBbWidth", at = @At("RETURN"))
  private float craftq3$avatarWidth(float original) {
    AvatarShape shape = craftq3$avatar;
    return shape == null ? original : shape.width();
  }

  @ModifyReturnValue(method = "getBbHeight", at = @At("RETURN"))
  private float craftq3$avatarHeight(float original) {
    AvatarShape shape = craftq3$avatar;
    return shape == null ? original : (float) shape.height();
  }

  @ModifyReturnValue(method = "getEyeHeight()F", at = @At("RETURN"))
  private float craftq3$avatarEye(float original) {
    AvatarShape shape = craftq3$avatar;
    return shape == null ? original : (float) shape.eyeHeight();
  }

  @ModifyReturnValue(method = "getEyeY", at = @At("RETURN"))
  private double craftq3$avatarEyeY(double original) {
    AvatarShape shape = craftq3$avatar;
    return shape == null ? original : ((Entity) (Object) this).getY() + shape.eyeHeight();
  }
}
