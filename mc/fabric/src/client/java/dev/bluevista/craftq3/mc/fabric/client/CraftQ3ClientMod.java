package dev.bluevista.craftq3.mc.fabric.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import dev.bluevista.craftq3.engine.common.time.Clock;
import dev.bluevista.craftq3.mc.fabric.CraftQ3Mod;
import dev.bluevista.craftq3.mc.fabric.client.audio.MinecraftAudio;
import dev.bluevista.craftq3.mc.fabric.client.debug.Q3SpikeScreen;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3RenderContext;
import dev.bluevista.craftq3.mc.fabric.command.Q3Command;
import java.util.concurrent.Executor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Client entry point: screens, the Blaze3D backend, audio and input are registered here. The client
 * owns the lifetime of its render context and audio host and closes both when the client stops.
 */
public final class CraftQ3ClientMod implements ClientModInitializer {
  private Q3RenderContext renderContext;
  private MinecraftAudio audio;

  @Override
  public void onInitializeClient() {
    // Pipelines must be registered before the first resource reload compiles them.
    renderContext = new Q3RenderContext();
    var installation = CraftQ3Mod.instance().installation();
    ClientCommandRegistrationCallback.EVENT.register(
        (dispatcher, buildContext) -> {
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
                              })));
          dispatcher.register(
              Q3Command.build(installation, CLIENT_FEEDBACK, FabricClientCommandSource::attended));
        });
    ClientLifecycleEvents.CLIENT_STOPPING.register(client -> close());
  }

  /** The client's shared render context. */
  public Q3RenderContext renderContext() {
    return renderContext;
  }

  /** The client's Quake III audio host, created on first use (render thread only). */
  public MinecraftAudio audio() {
    if (audio == null) audio = new MinecraftAudio(Minecraft.getInstance());
    return audio;
  }

  /** Opens the Blaze3D spike over the current screen, animated by the host clock. */
  public void openSpike(Minecraft client) {
    client.gui.setScreen(new Q3SpikeScreen(renderContext, Clock.system(), client.gui.screen()));
  }

  private void close() {
    if (audio != null) audio.close();
    renderContext.close();
  }

  private static final Q3Command.Feedback<FabricClientCommandSource> CLIENT_FEEDBACK =
      new Q3Command.Feedback<>() {
        @Override
        public void success(FabricClientCommandSource source, Component message) {
          source.sendFeedback(message);
        }

        @Override
        public void failure(FabricClientCommandSource source, Component message) {
          source.sendError(message);
        }

        @Override
        public Executor mainThread(FabricClientCommandSource source) {
          return source.getClient();
        }
      };
}
