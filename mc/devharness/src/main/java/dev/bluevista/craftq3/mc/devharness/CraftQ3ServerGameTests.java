package dev.bluevista.craftq3.mc.devharness;

import dev.bluevista.craftq3.mc.fabric.CraftQ3Mod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.gametest.framework.GameTestHelper;
import org.spongepowered.asm.mixin.MixinEnvironment;

/**
 * Server GameTests, run on the dedicated GameTest server by {@code runGameTest}. They must never
 * reference client classes: the dedicated server has none.
 */
public final class CraftQ3ServerGameTests {
  /** The common entrypoint ran on a dedicated server, and only the common mixin config applies. */
  @GameTest
  public void commonEntrypointLoadsOnDedicatedServer(GameTestHelper helper) {
    FabricLoader loader = FabricLoader.getInstance();
    helper.assertTrue(
        loader.getEnvironmentType() == EnvType.SERVER,
        "expected a dedicated server, got " + loader.getEnvironmentType());
    boolean initialized =
        loader.getEntrypointContainers("main", ModInitializer.class).stream()
            .filter(c -> c.getProvider().getMetadata().getId().equals(CraftQ3Mod.MOD_ID))
            .map(EntrypointContainer::getEntrypoint)
            .anyMatch(entrypoint -> entrypoint instanceof CraftQ3Mod mod && mod.initialized());
    helper.assertTrue(initialized, "CraftQ3Mod.onInitialize did not run");
    helper.assertTrue(
        MixinEnvironment.getCurrentEnvironment().getSide() == MixinEnvironment.Side.SERVER,
        "Mixin is not running in the server environment, so client mixins could apply");
    helper.succeed();
  }
}
