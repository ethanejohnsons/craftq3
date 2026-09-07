package dev.bluevista.craftq3.fabric.mixin;

import dev.bluevista.craftq3.fabric.building.BuildingLighting;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public abstract class BuildingEntityLightMixin {
  @Inject(method = "getPackedLightCoords", at = @At("RETURN"), cancellable = true)
  private void craftq3$light(Entity entity, float partial, CallbackInfoReturnable<Integer> ci) {
    ci.setReturnValue(
        BuildingLighting.apply(
            entity.level(), entity.getLightProbePosition(partial), ci.getReturnValueI()));
  }
}
