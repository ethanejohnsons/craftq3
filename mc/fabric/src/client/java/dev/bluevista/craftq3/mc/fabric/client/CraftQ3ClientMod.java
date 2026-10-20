package dev.bluevista.craftq3.mc.fabric.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import dev.bluevista.craftq3.engine.common.time.Clock;
import dev.bluevista.craftq3.mc.fabric.client.debug.Q3SpikeScreen;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3RenderContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;

/** Client entry point: screens, the Blaze3D backend, audio and input are registered here. */
public final class CraftQ3ClientMod implements ClientModInitializer {
  private Q3RenderContext renderContext;

  @Override
  public void onInitializeClient() {
    // Pipelines must be registered before the first resource reload compiles them.
    renderContext = new Q3RenderContext();
    ClientCommandRegistrationCallback.EVENT.register(
        (dispatcher, buildContext) ->
            dispatcher.register(
                literal("q3debug")
                    .requires(FabricClientCommandSource::attended)
                    .then(
                        literal("spike")
                            .executes(
                                c -> {
                                  Minecraft client = c.getSource().getClient();
                                  // The chat screen closes after the command returns.
                                  client.execute(() -> openSpike(client));
                                  return 1;
                                }))));
    ClientLifecycleEvents.CLIENT_STOPPING.register(client -> renderContext.close());
  }

  /** The client's shared render context. */
  public Q3RenderContext renderContext() {
    return renderContext;
  }

  /** Opens the Blaze3D spike over the current screen, animated by the host clock. */
  public void openSpike(Minecraft client) {
    client.gui.setScreen(new Q3SpikeScreen(renderContext, Clock.system(), client.gui.screen()));
  }
}
