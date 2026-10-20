package dev.bluevista.craftq3.mc.fabric.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.bluevista.craftq3.engine.common.time.Clock;
import dev.bluevista.craftq3.mc.fabric.CraftQ3Mod;
import dev.bluevista.craftq3.mc.fabric.client.audio.MinecraftAudio;
import dev.bluevista.craftq3.mc.fabric.client.bridge.BridgeClient;
import dev.bluevista.craftq3.mc.fabric.client.bridge.BridgeScreen;
import dev.bluevista.craftq3.mc.fabric.client.building.BuildingClient;
import dev.bluevista.craftq3.mc.fabric.client.debug.Q3MapViewer;
import dev.bluevista.craftq3.mc.fabric.client.debug.Q3SpikeScreen;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3LevelOverlay;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3RenderContext;
import dev.bluevista.craftq3.mc.fabric.client.screen.Q3Launch;
import dev.bluevista.craftq3.mc.fabric.client.screen.Q3Launcher;
import dev.bluevista.craftq3.mc.fabric.client.screen.Q3Screen;
import dev.bluevista.craftq3.mc.fabric.client.session.Q3SessionFactory;
import dev.bluevista.craftq3.mc.fabric.command.Q3Command;
import dev.bluevista.craftq3.mc.fabric.config.CraftQ3Config;
import dev.bluevista.craftq3.mc.fabric.config.Q3Installation;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Function;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entry point: screens, the Blaze3D backend, audio and input are registered here. The client
 * owns the lifetime of its render context and audio host and closes both when the client stops.
 *
 * <p>The bridge ({@link BridgeClient}) and building mode ({@link BuildingClient}) are driven by the
 * server's payloads; both draw inside Minecraft's level as {@link Q3LevelOverlay}s.
 *
 * <p>Pure Quake III opens from a "CraftQ3" button on the title screen (the original main menu, no
 * Minecraft world) and from {@code /q3 menu}, {@code /q3 map <name>}, {@code /q3 connect
 * <host[:port]>} and {@code /q3 demo <name>} ({@link Q3Launcher}).
 */
public final class CraftQ3ClientMod implements ClientModInitializer {
  /** The title screen button's label. */
  public static final String TITLE_BUTTON = "CraftQ3";

  private Q3RenderContext renderContext;
  private MinecraftAudio audio;
  private Q3Launcher launcher;
  private volatile Q3SessionFactory sessionFactory = Q3SessionFactory.DEFAULT;
  private BridgeClient bridge;
  private BuildingClient building;

  private static final Logger LOGGER = LoggerFactory.getLogger(CraftQ3ClientMod.class);

  /** The initialised client entrypoint, for the render hooks (set once at initialisation). */
  private static volatile CraftQ3ClientMod instance;

  @Override
  public void onInitializeClient() {
    // Pipelines must be registered before the first resource reload compiles them.
    renderContext = new Q3RenderContext();
    var installation = CraftQ3Mod.instance().installation();
    launcher =
        new Q3Launcher(
            installation,
            renderContext,
            this::audio,
            () -> sessionFactory,
            FabricLoader.getInstance().getGameDir());
    bridge =
        new BridgeClient(
            installation, renderContext, this::audio, FabricLoader.getInstance().getGameDir());
    bridge.register();
    building = new BuildingClient(installation, renderContext);
    building.register();
    instance = this;
    ScreenEvents.AFTER_INIT.register(
        (client, screen, width, height) -> {
          if (screen instanceof TitleScreen title)
            Screens.getWidgets(screen)
                .add(
                    Button.builder(
                            Component.literal(TITLE_BUTTON),
                            button -> launcher.open(client, title, Q3Launch.menu()))
                        .bounds(8, 8, 100, 20)
                        .build());
        });
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
                                // Queued: the chat screen closes after the command returns (execute
                                // would run inline).
                                client.schedule(() -> openSpike(client));
                                return 1;
                              }))
                  .then(
                      literal("map")
                          .then(
                              argument("name", StringArgumentType.word())
                                  .executes(
                                      c ->
                                          openMap(
                                              c.getSource(),
                                              installation,
                                              StringArgumentType.getString(c, "name"))))));
          dispatcher.register(
              Q3Command.build(installation, CLIENT_FEEDBACK, FabricClientCommandSource::attended)
                  .then(literal("menu").executes(c -> launch(c.getSource(), Q3Launch.menu())))
                  .then(launchArgument("map", "name", Q3Launch::map))
                  .then(launchArgument("connect", "address", Q3Launch::connect))
                  .then(launchArgument("demo", "name", Q3Launch::demo))
                  // The server's subcommands: this client tree would otherwise shadow them.
                  .then(
                      literal("bridge")
                          .executes(CraftQ3ClientMod::forward)
                          .then(literal("fresh").executes(CraftQ3ClientMod::forward)))
                  .then(literal("leave").executes(CraftQ3ClientMod::forward))
                  .then(
                      literal("build")
                          .then(
                              argument("map", StringArgumentType.word())
                                  .executes(CraftQ3ClientMod::forward)))
                  .then(
                      literal("pickup")
                          .then(
                              literal("add")
                                  .then(
                                      argument("classname", StringArgumentType.word())
                                          .suggests(
                                              (c, b) ->
                                                  net.minecraft.commands.SharedSuggestionProvider
                                                      .suggest(
                                                          dev.bluevista.craftq3.mc.fabric.command
                                                              .Q3ServerCommands.BASE_ITEMS,
                                                          b))
                                          .executes(CraftQ3ClientMod::forward)))
                          .then(literal("list").executes(CraftQ3ClientMod::forward))
                          .then(
                              literal("remove")
                                  .then(
                                      argument(
                                              "id",
                                              com.mojang.brigadier.arguments.IntegerArgumentType
                                                  .integer(1, 256))
                                          .executes(CraftQ3ClientMod::forward)))));
        });
    ClientLifecycleEvents.CLIENT_STOPPING.register(this::close);
  }

  private LiteralArgumentBuilder<FabricClientCommandSource> launchArgument(
      String name, String argument, Function<String, Q3Launch> request) {
    return literal(name)
        .then(
            argument(argument, StringArgumentType.greedyString())
                .executes(
                    c -> {
                      Q3Launch launch;
                      try {
                        launch = request.apply(StringArgumentType.getString(c, argument));
                      } catch (IllegalArgumentException e) {
                        c.getSource().sendError(Component.literal("CraftQ3: " + e.getMessage()));
                        return 0;
                      }
                      return launch(c.getSource(), launch);
                    }));
  }

  /** Sends a {@code /q3} subcommand the server implements (bridge, building, pickups) to it. */
  private static int forward(
      com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> context) {
    var connection = context.getSource().getClient().getConnection();
    if (connection == null) {
      context.getSource().sendError(Component.literal("CraftQ3: not connected to a world"));
      return 0;
    }
    // Straight to the server: sendCommand would run it through the client tree again. No argument
    // is a signable message, so the unsigned packet is what vanilla sends too.
    String command = context.getInput();
    if (command.startsWith("/")) command = command.substring(1);
    connection.send(new net.minecraft.network.protocol.game.ServerboundChatCommandPacket(command));
    return 1;
  }

  /** Opens Quake III once the chat screen that ran the command has closed. */
  private int launch(FabricClientCommandSource source, Q3Launch request) {
    Minecraft client = source.getClient();
    client.schedule(() -> launcher.open(client, null, request));
    return 1;
  }

  /** Opens Quake III over {@code parent} (null: back to the game when it closes). */
  public Screen openQuake(Minecraft client, Screen parent, Q3Launch request) {
    return launcher.open(client, parent, request);
  }

  /** The factory new Quake screens start their sessions with. */
  public Q3SessionFactory sessionFactory() {
    return sessionFactory;
  }

  /** Replaces the session factory (devharness). */
  public void sessionFactory(Q3SessionFactory factory) {
    sessionFactory = Objects.requireNonNull(factory, "factory");
  }

  /** The client's building-mode state. */
  public BuildingClient building() {
    return building;
  }

  /** The initialised client entrypoint. */
  public static CraftQ3ClientMod current() {
    CraftQ3ClientMod mod = instance;
    if (mod == null) throw new IllegalStateException("CraftQ3 client is not initialised");
    return mod;
  }

  /** Start of a frame ({@code GameRenderer.update}): the overlays' simulation and camera pose. */
  public static void beforeCamera(Minecraft minecraft) {
    CraftQ3ClientMod mod = instance;
    if (mod == null) return;
    BridgeScreen bridgeScreen = BridgeScreen.current(minecraft);
    if (bridgeScreen != null) bridgeScreen.beforeCamera(minecraft);
    guarded(minecraft, () -> mod.building.beforeCamera(minecraft));
  }

  /** The level pass ended: overlays draw with Minecraft's camera and depth. */
  public static void renderInLevel(
      Minecraft minecraft, Matrix4fc projection, Matrix4fc viewRotation, Vec3 camera) {
    CraftQ3ClientMod mod = instance;
    if (mod == null) return;
    guarded(
        minecraft, () -> mod.building.renderInLevel(minecraft, projection, viewRotation, camera));
    BridgeScreen bridgeScreen = BridgeScreen.current(minecraft);
    if (bridgeScreen != null)
      bridgeScreen.renderInLevel(minecraft, projection, viewRotation, camera);
  }

  /** Whether the local player's own movement is held (bridged, or waiting for regions). */
  public static boolean holdsLocalPlayer(Minecraft minecraft) {
    CraftQ3ClientMod mod = instance;
    return mod != null
        && (BridgeScreen.current(minecraft) != null || mod.building.holdsPlayer(minecraft));
  }

  private static void guarded(Minecraft minecraft, Runnable action) {
    try {
      action.run();
    } catch (RuntimeException e) {
      LOGGER.error("CraftQ3: a level overlay failed", e);
    }
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

  /** {@code /q3debug map <name>}: views a map of the configured installation from its spawn. */
  private int openMap(FabricClientCommandSource source, Q3Installation installation, String map) {
    CraftQ3Config config = installation.config();
    var root = config.installationPath();
    if (root.isEmpty()) {
      source.sendError(Component.literal("No Quake III installation is configured (/q3 path)"));
      return 0;
    }
    source.sendFeedback(Component.literal("Loading " + map + "..."));
    Q3MapViewer.open(source.getClient(), renderContext, root.get(), config.game(), map)
        .whenComplete(
            (name, failure) ->
                source
                    .getClient()
                    .execute(
                        () -> {
                          if (failure != null)
                            source.sendError(
                                Component.literal(
                                    "Cannot view " + map + ": " + failure.getMessage()));
                        }));
    return 1;
  }

  private void close(Minecraft client) {
    // An open Quake screen closes its session first (q3config.cfg, threads, GPU objects).
    if (Q3Screen.current(client) != null) client.gui.setScreen(null);
    if (BridgeScreen.current(client) != null) client.gui.setScreen(null);
    building.close();
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
