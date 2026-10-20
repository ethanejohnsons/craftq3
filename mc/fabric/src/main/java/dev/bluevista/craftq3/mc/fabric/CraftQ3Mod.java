package dev.bluevista.craftq3.mc.fabric;

import dev.bluevista.craftq3.mc.fabric.bridge.BridgeData;
import dev.bluevista.craftq3.mc.fabric.bridge.BridgeService;
import dev.bluevista.craftq3.mc.fabric.bridge.BridgeSession;
import dev.bluevista.craftq3.mc.fabric.bridge.BridgedPlayer;
import dev.bluevista.craftq3.mc.fabric.building.BuildingService;
import dev.bluevista.craftq3.mc.fabric.command.Q3Command;
import dev.bluevista.craftq3.mc.fabric.command.Q3ServerCommands;
import dev.bluevista.craftq3.mc.fabric.config.CraftQ3Config;
import dev.bluevista.craftq3.mc.fabric.config.Q3Installation;
import dev.bluevista.craftq3.mc.fabric.net.BridgeRequestPayload;
import dev.bluevista.craftq3.mc.fabric.net.BridgeStatePayload;
import dev.bluevista.craftq3.mc.fabric.net.BuildingRegionsPayload;
import dev.bluevista.craftq3.mc.fabric.net.Q3DatagramPayload;
import dev.bluevista.craftq3.mc.fabric.server.Q3ServerContent;
import java.util.concurrent.Executor;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entry point, loaded on both the client and a dedicated server. Server-side simulation,
 * payloads and the building dimension are registered here; nothing here may touch client classes.
 *
 * <ul>
 *   <li><b>Config</b>: loaded here; never fails initialisation.
 *   <li><b>Commands</b>: every server (integrated or dedicated) registers {@code /q3 bridge
 *       [fresh]}, {@code /q3 leave}, {@code /q3 pickup ...} and {@code /q3 build <map>}; a
 *       dedicated server also registers {@code /q3 status|path} for admins (the client registers
 *       those as client commands, and Fabric forwards {@code /q3} subcommands it does not know to
 *       the server).
 *   <li><b>Payloads</b>: protocol-68 datagrams both ways, the bridge state and the building regions
 *       to the client, bridge requests to the server.
 *   <li><b>Per server</b>: one {@link Q3ServerContent} (the mounted installation), {@link
 *       BridgeService} and {@link BuildingService}, from server start to stop; the bridge ticks
 *       after each level's tick.
 * </ul>
 */
public final class CraftQ3Mod implements ModInitializer {
  public static final String MOD_ID = "craftq3";

  private static final Logger LOGGER = LoggerFactory.getLogger(CraftQ3Mod.class);

  private boolean initialized;
  private Q3Installation installation;

  /** The running server's services (one server at a time per game instance). */
  private volatile ServerServices services;

  /** What a running server holds. */
  public record ServerServices(
      MinecraftServer server,
      Q3ServerContent content,
      BridgeService bridge,
      BuildingService building) {}

  @Override
  public void onInitialize() {
    CraftQ3Config config = CraftQ3Config.load(FabricLoader.getInstance().getConfigDir());
    installation = new Q3Installation(config);
    LOGGER.info("CraftQ3: {}", config);
    BridgeData.register();
    BuildingService.register();
    registerPayloads();
    CommandRegistrationCallback.EVENT.register(
        (dispatcher, buildContext, selection) -> {
          boolean dedicated = selection == Commands.CommandSelection.DEDICATED;
          var root =
              dedicated
                  ? Q3Command.build(
                      installation,
                      SERVER_FEEDBACK,
                      source -> true,
                      Commands.hasPermission(Commands.LEVEL_ADMINS))
                  : Commands.literal(Q3Command.NAME);
          dispatcher.register(
              Q3ServerCommands.addTo(root, () -> services().bridge(), () -> services().building()));
        });
    registerServerEvents();
    initialized = true;
  }

  private static void registerPayloads() {
    PayloadTypeRegistry.serverboundPlay().register(Q3DatagramPayload.TYPE, Q3DatagramPayload.CODEC);
    PayloadTypeRegistry.clientboundPlay().register(Q3DatagramPayload.TYPE, Q3DatagramPayload.CODEC);
    PayloadTypeRegistry.serverboundPlay()
        .register(BridgeRequestPayload.TYPE, BridgeRequestPayload.CODEC);
    PayloadTypeRegistry.clientboundPlay()
        .register(BridgeStatePayload.TYPE, BridgeStatePayload.CODEC);
    PayloadTypeRegistry.clientboundPlay()
        .register(BuildingRegionsPayload.TYPE, BuildingRegionsPayload.CODEC);
  }

  private void registerServerEvents() {
    ServerLifecycleEvents.SERVER_STARTING.register(
        server -> {
          Q3ServerContent content = new Q3ServerContent(installation);
          services =
              new ServerServices(
                  server,
                  content,
                  new BridgeService(server, content),
                  new BuildingService(server, content));
        });
    ServerLevelEvents.LOAD.register(
        (server, level) -> {
          ServerServices s = services;
          if (s != null && s.server() == server) s.building().levelLoaded(level);
        });
    ServerLifecycleEvents.SERVER_STARTED.register(
        server -> {
          ServerServices s = services;
          if (s != null && s.server() == server) s.building().awaitLoad();
        });
    ServerLifecycleEvents.SERVER_STOPPING.register(
        server -> {
          ServerServices s = services;
          if (s == null || s.server() != server) return;
          try {
            s.bridge().close();
            s.building().close();
          } finally {
            s.content().close();
            services = null;
          }
        });
    ServerTickEvents.END_LEVEL_TICK.register(
        level -> {
          ServerServices s = services;
          if (s != null) s.bridge().tick(level);
        });
    ServerPlayNetworking.registerGlobalReceiver(
        Q3DatagramPayload.TYPE,
        (payload, context) -> {
          ServerServices s = services;
          if (s != null) s.bridge().datagram(context.player(), payload.data());
        });
    ServerPlayNetworking.registerGlobalReceiver(
        BridgeRequestPayload.TYPE,
        (payload, context) -> {
          ServerServices s = services;
          if (s == null) return;
          String reason =
              payload.kind() == BridgeRequestPayload.Kind.FAILED
                  ? "Your Quake session could not start: " + payload.detail()
                  : "Left the bridge";
          s.bridge().leave(context.player(), reason);
        });
    ServerPlayConnectionEvents.JOIN.register(
        (listener, sender, server) -> {
          ServerServices s = services;
          if (s == null) return;
          s.bridge().joined(listener.player);
          s.building().sync(listener.player);
        });
    ServerPlayConnectionEvents.DISCONNECT.register(
        (listener, server) -> {
          ServerServices s = services;
          if (s != null) s.bridge().leave(listener.player, "Disconnected");
        });
    ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register(
        (player, origin, destination) -> {
          ServerServices s = services;
          if (s == null) return;
          s.bridge().leave(player, "Changed dimension");
          s.building().sync(player);
        });
    ServerLivingEntityEvents.ALLOW_DAMAGE.register(
        (entity, source, amount) -> {
          // A dead Quake player cannot be hurt by Minecraft until it respawns.
          BridgeSession session = BridgedPlayer.of(entity);
          return session == null || session.alive();
        });
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

  /** The running server's services. */
  public ServerServices services() {
    ServerServices s = services;
    if (s == null) throw new IllegalStateException("No server is running");
    return s;
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
