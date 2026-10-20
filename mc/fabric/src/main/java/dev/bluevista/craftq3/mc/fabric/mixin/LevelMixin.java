package dev.bluevista.craftq3.mc.fabric.mixin;

import dev.bluevista.craftq3.mc.fabric.bridge.BridgeGame;
import dev.bluevista.craftq3.mc.fabric.building.LevelBuilding;
import dev.bluevista.craftq3.mc.fabric.level.CraftQ3Level;
import java.util.Objects;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * The duck behind {@link CraftQ3Level}: per-level bridge and building state as fields of the level
 * itself (replacing the old global synchronized lookup).
 */
@Mixin(Level.class)
public abstract class LevelMixin implements CraftQ3Level {
  @Unique private BridgeGame craftq3$bridge;
  @Unique private volatile LevelBuilding craftq3$building = LevelBuilding.NONE;

  @Override
  public BridgeGame craftq3$bridge() {
    return craftq3$bridge;
  }

  @Override
  public void craftq3$setBridge(BridgeGame game) {
    craftq3$bridge = game;
  }

  @Override
  public LevelBuilding craftq3$building() {
    return craftq3$building;
  }

  @Override
  public void craftq3$setBuilding(LevelBuilding building) {
    craftq3$building = Objects.requireNonNull(building);
  }
}
