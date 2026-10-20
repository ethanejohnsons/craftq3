package dev.bluevista.craftq3.mc.fabric;

import dev.bluevista.craftq3.mc.fabric.command.Q3Command;
import dev.bluevista.craftq3.mc.fabric.config.CraftQ3Config;
import dev.bluevista.craftq3.mc.fabric.config.Q3Installation;
import java.util.concurrent.Executor;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entry point, loaded on both the client and a dedicated server. Server-side simulation,
 * payloads and the building dimension are registered here; nothing here may touch client classes.
 *
 * <p>The configuration is loaded here and never fails initialisation. On a dedicated server {@code
 * /q3} is registered as a server command for permission level 3 (admins); the client registers its
 * own client-side {@code /q3} instead, so an integrated server registers none.
 */
public final class CraftQ3Mod implements ModInitializer {
  public static final String MOD_ID = "craftq3";

  private static final Logger LOGGER = LoggerFactory.getLogger(CraftQ3Mod.class);

  private boolean initialized;
  private Q3Installation installation;

  @Override
  public void onInitialize() {
    CraftQ3Config config = CraftQ3Config.load(FabricLoader.getInstance().getConfigDir());
    installation = new Q3Installation(config);
    LOGGER.info("CraftQ3: {}", config);
    CommandRegistrationCallback.EVENT.register(
        (dispatcher, buildContext, selection) -> {
          if (selection != Commands.CommandSelection.DEDICATED) return;
          dispatcher.register(
              Q3Command.build(
                  installation, SERVER_FEEDBACK, Commands.hasPermission(Commands.LEVEL_ADMINS)));
        });
    initialized = true;
  }

  /** Whether {@link #onInitialize()} has run on this instance (checked by devharness). */
  public boolean initialized() {
    return initialized;
  }

  /** The installation shared by every side of this game instance. */
  public Q3Installation installation() {
    if (installation == null) throw new IllegalStateException("CraftQ3Mod is not initialised");
    return installation;
  }

  /** The loader's instance of this entrypoint (the one that ran). */
  public static CraftQ3Mod instance() {
    return FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class).stream()
        .filter(CraftQ3Mod.class::isInstance)
        .map(CraftQ3Mod.class::cast)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("CraftQ3Mod entrypoint not found"));
  }

  private static final Q3Command.Feedback<CommandSourceStack> SERVER_FEEDBACK =
      new Q3Command.Feedback<>() {
        @Override
        public void success(CommandSourceStack source, Component message) {
          source.sendSuccess(() -> message, false);
        }

        @Override
        public void failure(CommandSourceStack source, Component message) {
          source.sendFailure(message);
        }

        @Override
        public Executor mainThread(CommandSourceStack source) {
          return source.getServer();
        }
      };
}
