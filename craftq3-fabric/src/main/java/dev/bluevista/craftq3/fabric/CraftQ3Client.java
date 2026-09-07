package dev.bluevista.craftq3.fabric;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.bluevista.craftq3.fabric.render.BspScreen;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CraftQ3Client implements ClientModInitializer {
  public static final Logger LOGGER = LoggerFactory.getLogger("CraftQ3");
  private final ExecutorService io =
      Executors.newSingleThreadExecutor(
          Thread.ofPlatform().daemon().name("CraftQ3-assets").factory());
  private final AtomicBoolean busy = new AtomicBoolean();
  private final AtomicBoolean stopping = new AtomicBoolean();
  private CraftQ3Runtime runtime;

  @FunctionalInterface
  private interface Operation {
    List<String> run() throws Exception;
  }

  @Override
  public void onInitializeClient() {
    dev.bluevista.craftq3.fabric.render.Blaze3dRenderBackend.registerPipelines();
    var loader = FabricLoader.getInstance();
    runtime =
        new CraftQ3Runtime(
            loader.getConfigDir().resolve("craftq3/craftq3.properties"),
            loader.getGameDir().resolve("craftq3/games"),
            loader.isDevelopmentEnvironment()
                    && System.getProperty("craftq3.developmentInstallation") != null
                ? java.nio.file.Path.of(System.getProperty("craftq3.developmentInstallation"))
                : null);
    io.execute(
        () -> {
          try {
            runtime.reload();
          } catch (Exception e) {
            LOGGER.error("CraftQ3 startup: {}", e.getMessage(), e);
          }
        });
    ClientCommandRegistrationCallback.EVENT.register(
        (dispatcher, context) -> {
          var root =
              literal("q3")
                  .requires(FabricClientCommandSource::attended)
                  .executes(c -> run(c.getSource(), runtime::status));
          root.then(
              literal("reload")
                  .executes(
                      c ->
                          run(
                              c.getSource(),
                              () -> {
                                runtime.reload();
                                return runtime.status();
                              })));
          root.then(
              literal("path")
                  .then(
                      argument("installation", StringArgumentType.greedyString())
                          .executes(
                              c ->
                                  run(
                                      c.getSource(),
                                      () -> {
                                        runtime.installation(
                                            Path.of(
                                                StringArgumentType.getString(c, "installation")));
                                        return runtime.status();
                                      }))));
          root.then(
              literal("game")
                  .then(
                      argument("directory", StringArgumentType.word())
                          .executes(
                              c ->
                                  run(
                                      c.getSource(),
                                      () -> {
                                        runtime.game(StringArgumentType.getString(c, "directory"));
                                        return runtime.status();
                                      }))));
          var fs = literal("fs");
          fs.then(literal("status").executes(c -> run(c.getSource(), runtime::status)));
          fs.then(
              literal("list")
                  .executes(c -> run(c.getSource(), () -> runtime.list("")))
                  .then(
                      argument("path", StringArgumentType.greedyString())
                          .executes(
                              c ->
                                  run(
                                      c.getSource(),
                                      () ->
                                          runtime.list(StringArgumentType.getString(c, "path"))))));
          fs.then(
              literal("which")
                  .then(
                      argument("path", StringArgumentType.greedyString())
                          .executes(
                              c ->
                                  run(
                                      c.getSource(),
                                      () ->
                                          List.of(
                                              runtime.which(
                                                  StringArgumentType.getString(c, "path")))))));
          root.then(fs);
          root.then(
              literal("debug")
                  .then(
                      literal("bsp")
                          .executes(c -> run(c.getSource(), () -> List.of(runtime.bspStatus())))));
          for (String command : List.of("map", "play"))
            root.then(
                literal(command)
                    .then(
                        argument("name", StringArgumentType.word())
                            .executes(
                                c -> {
                                  var source = c.getSource();
                                  return play(
                                      source.getClient(),
                                      StringArgumentType.getString(c, "name"),
                                      message -> source.sendFeedback(Component.literal(message)));
                                })));
          root.then(
              literal("view")
                  .then(
                      argument("name", StringArgumentType.word())
                          .executes(
                              c ->
                                  run(
                                      c.getSource(),
                                      () -> {
                                        var scene =
                                            runtime.loadWorld(
                                                StringArgumentType.getString(c, "name"));
                                        var client = c.getSource().getClient();
                                        var player = c.getSource().getPlayer();
                                        client.execute(
                                            () -> {
                                              if (!stopping.get() && client.player == player)
                                                client.gui.setScreen(
                                                    new BspScreen(
                                                        scene.scene(), scene.materials()));
                                            });
                                        return List.of(
                                            "BSP viewer: WASD move; Space/C rise/fall; arrows look;"
                                                + " F8 diagnostics; F6 debug mode; F7 PVS; Esc"
                                                + " returns.");
                                      }))));
          root.then(
              literal("menu")
                  .executes(
                      c ->
                          play(
                              c.getSource().getClient(),
                              null,
                              message -> c.getSource().sendFeedback(Component.literal(message)))));
          root.then(
              literal("pickup")
                  .then(literal("list").executes(c -> pickup(c.getSource(), "list", "", 0)))
                  .then(
                      literal("add")
                          .then(
                              argument("item", StringArgumentType.word())
                                  .suggests(
                                      (suggestContext, builder) -> {
                                        for (String name :
                                            List.of(
                                                "weapon_rocketlauncher",
                                                "weapon_railgun",
                                                "weapon_shotgun",
                                                "weapon_plasmagun",
                                                "weapon_lightning",
                                                "weapon_grenadelauncher",
                                                "weapon_bfg",
                                                "ammo_rockets",
                                                "ammo_bullets",
                                                "ammo_cells",
                                                "ammo_shells",
                                                "ammo_slugs",
                                                "ammo_lightning",
                                                "ammo_grenades",
                                                "ammo_bfg",
                                                "item_health",
                                                "item_health_large",
                                                "item_health_mega",
                                                "item_health_small",
                                                "item_armor_combat",
                                                "item_armor_body",
                                                "item_armor_shard",
                                                "item_quad",
                                                "item_haste",
                                                "item_regen",
                                                "item_enviro",
                                                "item_invis",
                                                "item_flight",
                                                "holdable_medkit",
                                                "holdable_teleporter"))
                                          if (name.startsWith(builder.getRemaining()))
                                            builder.suggest(name);
                                        return builder.buildFuture();
                                      })
                                  .executes(
                                      c ->
                                          pickup(
                                              c.getSource(),
                                              "add",
                                              StringArgumentType.getString(c, "item"),
                                              0))))
                  .then(
                      literal("remove")
                          .then(
                              argument(
                                      "id",
                                      com.mojang.brigadier.arguments.IntegerArgumentType.integer(
                                          1, 256))
                                  .executes(
                                      c ->
                                          pickup(
                                              c.getSource(),
                                              "remove",
                                              "",
                                              com.mojang.brigadier.arguments.IntegerArgumentType
                                                  .getInteger(c, "id"))))));
          root.then(
              literal("bridge")
                  .executes(
                      c ->
                          queueBridge(
                              c.getSource().getClient(),
                              text -> c.getSource().sendFeedback(Component.literal(text)),
                              false))
                  .then(
                      literal("fresh")
                          .executes(
                              c ->
                                  queueBridge(
                                      c.getSource().getClient(),
                                      text -> c.getSource().sendFeedback(Component.literal(text)),
                                      true))));
          root.then(
              literal("build")
                  .then(
                      argument("map", StringArgumentType.word())
                          .executes(
                              c ->
                                  build(
                                      c.getSource().getClient(),
                                      StringArgumentType.getString(c, "map"),
                                      text ->
                                          c.getSource().sendFeedback(Component.literal(text))))));
          root.then(
              literal("leave")
                  .executes(
                      c -> {
                        var session =
                            dev.bluevista.craftq3.fabric.building.BuildingSession.active();
                        if (session == null) {
                          c.getSource()
                              .sendFeedback(Component.literal("No Quake build map is active."));
                          return 0;
                        }
                        session.leave();
                        return 1;
                      }));
          dispatcher.register(root);
        });
    net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register(
        (client, screen, width, height) -> {
          if (screen instanceof dev.bluevista.craftq3.fabric.render.QuakeView)
            dev.bluevista.craftq3.fabric.audio.QuakeHostAudio.enter(client);
          if (screen instanceof net.minecraft.client.gui.screens.TitleScreen)
            net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen)
                .add(
                    net.minecraft.client.gui.components.Button.builder(
                            Component.literal("CraftQ3"),
                            button -> {
                              var launch =
                                  new dev.bluevista.craftq3.fabric.render.Q3LaunchScreen(screen);
                              client.gui.setScreen(launch);
                              play(client, null, launch::status);
                            })
                        .bounds(8, 8, 100, 20)
                        .build());
        });
    if (loader.isDevelopmentEnvironment() && System.getProperty("craftq3.debugMap") != null) {
      AtomicBoolean started = new AtomicBoolean();
      net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(
          client -> {
            if (client.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen
                && client.gui.overlay() == null
                && started.compareAndSet(false, true)) {
              io.execute(
                  () -> {
                    try {
                      var scene = runtime.loadWorld(System.getProperty("craftq3.debugMap"));
                      client.execute(
                          () -> {
                            if (!stopping.get())
                              client.gui.setScreen(new BspScreen(scene.scene(), scene.materials()));
                          });
                    } catch (Exception e) {
                      LOGGER.error("Development map launch failed", e);
                    }
                  });
            }
          });
    }
    if (loader.isDevelopmentEnvironment() && System.getProperty("craftq3.playMap") != null) {
      AtomicBoolean started = new AtomicBoolean();
      net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(
          client -> {
            if (client.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen
                && client.gui.overlay() == null
                && started.compareAndSet(false, true))
              play(client, System.getProperty("craftq3.playMap"), LOGGER::info);
          });
    }
    if (loader.isDevelopmentEnvironment() && Boolean.getBoolean("craftq3.uiPreview")) {
      AtomicBoolean started = new AtomicBoolean();
      net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(
          client -> {
            if (client.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen
                && client.gui.overlay() == null
                && started.compareAndSet(false, true)) previewUi(client);
          });
    }
    if (loader.isDevelopmentEnvironment() && Boolean.getBoolean("craftq3.lifecycleCapture")) {
      AtomicBoolean started = new AtomicBoolean();
      net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(
          client -> {
            if (client.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen
                && client.gui.overlay() == null
                && started.compareAndSet(false, true)) play(client, null, LOGGER::info);
          });
    }
    if (loader.isDevelopmentEnvironment()
        && System.getProperty("craftq3.bridgeSmokeWorld") != null) {
      int[] stage = {0}, ticks = {0};
      boolean[] prepared = {false};
      net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(
          client -> {
            if (dev.bluevista.craftq3.fabric.building.BuildingSmoke.enabled())
              dev.bluevista.craftq3.fabric.building.BuildingSmoke.tick(client);
            dev.bluevista.craftq3.fabric.building.BuildingWorldReloadSmoke.tick(client);
            if ((dev.bluevista.craftq3.fabric.bridge.BridgeRecoverySmoke.verifying()
                    || dev.bluevista.craftq3.fabric.building.BuildingRecoverySmoke.verifying()
                    || dev.bluevista.craftq3.fabric.building.BuildingWorldReloadSmoke.verifying())
                && stage[0] != 0) return;
            if (stage[0] == 1 && client.player != null && client.level != null) {
              // An unattended QA window can lose focus during world loading. Resume its
              // native pause screen so setup reaches the same command path as normal play.
              if (client.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen)
                client.gui.setScreen(null);
              if (client.player.isDeadOrDying()) {
                client.player.respawn();
                prepared[0] = false;
                ticks[0] = 0;
                return;
              }
              if (!prepared[0]) {
                prepared[0] = true;
                dev.bluevista.craftq3.fabric.bridge.BridgeCombatSmoke.prepare(client);
              }
            }
            if (stage[0] == 0
                && client.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen
                && client.gui.overlay() == null) {
              stage[0] = 1;
              client
                  .createWorldOpenFlows()
                  .openWorld(System.getProperty("craftq3.bridgeSmokeWorld"), () -> client.stop());
            } else if (stage[0] == 1
                && client.level != null
                && client.player != null
                && client.gui.screen() == null
                && ++ticks[0] >= 60) {
              stage[0] = 2;
              if (dev.bluevista.craftq3.fabric.building.BuildingSmoke.enabled()) {
                stage[0] = 4;
                if (build(client, "q3dm17", LOGGER::info) == 0) client.stop();
              } else if (dev.bluevista.craftq3.fabric.bridge.BridgeCombatSmoke.enabled()) {
                dev.bluevista.craftq3.fabric.bridge.BridgeCombatSmoke.setup(client)
                    .whenComplete(
                        (unused, failure) ->
                            client.execute(
                                () -> {
                                  if (failure != null) {
                                    LOGGER.error("Combat fixture failed", failure);
                                    client.stop();
                                  } else {
                                    ticks[0] = 0;
                                    stage[0] = 3;
                                  }
                                }));
              } else if (bridge(client, LOGGER::info) == 0) client.stop();
            } else if (stage[0] == 3 && ++ticks[0] >= 20) {
              stage[0] = 4;
              if (dev.bluevista.craftq3.fabric.bridge.BridgeTransferSmoke.enabled()) {
                if (play(client, null, LOGGER::info) == 0) client.stop();
              } else if (dev.bluevista.craftq3.fabric.render.BridgeConsoleSmoke.enabled()) {
                dev.bluevista.craftq3.fabric.render.BridgeConsoleSmoke.launchFromChat(client);
              } else if (bridge(client, LOGGER::info) == 0) client.stop();
            }
          });
    }
    net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register(
        (handler, sender, server) -> {
          try {
            dev.bluevista.craftq3.fabric.bridge.BridgeRecoverySmoke.beforeJoin(handler.player);
            dev.bluevista.craftq3.fabric.bridge.BridgeReturnState.recover(handler.player);
            dev.bluevista.craftq3.fabric.bridge.BridgeRecoverySmoke.afterJoin(handler.player);
            dev.bluevista.craftq3.fabric.building.BuildingRecoverySmoke.beforeJoin(handler.player);
            dev.bluevista.craftq3.fabric.building.BuildReturnState.recover(handler.player);
            dev.bluevista.craftq3.fabric.building.BuildingRecoverySmoke.afterJoin(handler.player);
          } catch (java.io.IOException failure) {
            LOGGER.error("Could not recover the Minecraft return state", failure);
            if (dev.bluevista.craftq3.fabric.building.BuildingRecoverySmoke.enabled()
                || dev.bluevista.craftq3.fabric.bridge.BridgeRecoverySmoke.enabled())
              net.minecraft.client.Minecraft.getInstance()
                  .execute(() -> net.minecraft.client.Minecraft.getInstance().stop());
            handler.disconnect(
                net.minecraft.network.chat.Component.literal(
                    "CraftQ3 could not restore your Minecraft return state. Recovery data was left"
                        + " unchanged; see the game log."));
          }
        });
    net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
        (handler, client) -> {
          var session = dev.bluevista.craftq3.fabric.building.BuildingSession.active();
          if (session != null) session.leave();
        });
    net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(
        server -> {
          try {
            dev.bluevista.craftq3.fabric.building.BuildingWorldLoader.prepare(
                server, runtime::buildingFiles);
          } catch (java.io.IOException e) {
            throw new IllegalStateException(
                "CraftQ3 cannot restore this save's BSP building regions. Check the configured PK3"
                    + " installation and restore the matching original maps.",
                e);
          }
        });
    net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents.LOAD.register(
        (server, level) -> {
          try {
            dev.bluevista.craftq3.fabric.building.BuildingWorldLoader.restore(level);
          } catch (java.io.IOException e) {
            throw new IllegalStateException(
                "CraftQ3 cannot restore this save's BSP building regions. Check the configured PK3"
                    + " installation and restore the matching original maps.",
                e);
          }
        });
    net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(
        server -> {
          dev.bluevista.craftq3.fabric.bridge.BridgePlayerController.clear(server);
          dev.bluevista.craftq3.fabric.building.BuildingWorlds.clear(server);
          dev.bluevista.craftq3.fabric.building.BuildingWorldLoader.clear(server);
        });
    ClientLifecycleEvents.CLIENT_STOPPING.register(
        client -> {
          stopping.set(true);
          io.shutdownNow();
          try {
            runtime.close();
          } catch (Exception e) {
            LOGGER.warn("CraftQ3 shutdown", e);
          }
        });
    LOGGER.info("CraftQ3 initialized: Minecraft 26.2, Java 25, Blaze3D graphics backend");
  }

  private int run(FabricClientCommandSource source, Operation operation) {
    if (!busy.compareAndSet(false, true)) {
      source.sendError(Component.literal("CraftQ3 is loading; please wait."));
      return 0;
    }
    source.sendFeedback(Component.literal("CraftQ3: working…"));
    var client = source.getClient();
    var player = source.getPlayer();
    io.execute(
        () -> {
          try {
            List<String> result = operation.run();
            client.execute(
                () -> {
                  if (client.player == player)
                    result.forEach(line -> source.sendFeedback(Component.literal(line)));
                });
          } catch (Exception e) {
            LOGGER.warn("CraftQ3 command failed: {}", e.getMessage(), e);
            client.execute(
                () -> {
                  if (client.player == player)
                    source.sendError(Component.literal("CraftQ3: " + e.getMessage()));
                });
          } finally {
            busy.set(false);
          }
        });
    return 1;
  }

  private void previewUi(net.minecraft.client.Minecraft client) {
    if (stopping.get() || !busy.compareAndSet(false, true)) return;
    dev.bluevista.craftq3.fabric.audio.MinecraftAudioBackend audio;
    try {
      audio = new dev.bluevista.craftq3.fabric.audio.MinecraftAudioBackend(client);
    } catch (RuntimeException e) {
      busy.set(false);
      LOGGER.error("Could not open UI audio", e);
      client.stop();
      return;
    }
    int width = client.getWindow().getWidth(), height = client.getWindow().getHeight();
    io.execute(
        () -> {
          try {
            var session = runtime.loadUiPreview(audio, width, height);
            if (stopping.get()) {
              session.close();
              return;
            }
            client.execute(
                () -> {
                  if (stopping.get()) {
                    try {
                      session.close();
                    } catch (java.io.IOException e) {
                      LOGGER.warn("UI preview cleanup", e);
                    }
                    return;
                  }
                  client.gui.setScreen(
                      new dev.bluevista.craftq3.fabric.render.UiPreviewScreen(session));
                });
          } catch (Exception e) {
            audio.close();
            LOGGER.error("Original UI preview launch failed", e);
            if (Boolean.getBoolean("craftq3.uiCapture")) client.execute(client::stop);
          } finally {
            busy.set(false);
          }
        });
  }

  private int build(
      net.minecraft.client.Minecraft client,
      String map,
      java.util.function.Consumer<String> output) {
    if (client.level == null || client.player == null || client.getSingleplayerServer() == null) {
      output.accept("Open a local Minecraft world first.");
      return 0;
    }
    if (dev.bluevista.craftq3.fabric.building.BuildingSession.active() != null
        || !busy.compareAndSet(false, true)) {
      output.accept("Leave the current session or wait for loading to finish.");
      return 0;
    }
    dev.bluevista.craftq3.assets.fs.Pk3FileSystem files = null;
    try {
      files = runtime.bridgeFiles();
      var session = new dev.bluevista.craftq3.fabric.building.BuildingSession(client, files, map);
      var owned = files;
      session
          .enter()
          .whenComplete(
              (unused, failure) ->
                  client.execute(
                      () -> {
                        busy.set(false);
                        if (failure != null) {
                          try {
                            owned.close();
                          } catch (java.io.IOException close) {
                            failure.addSuppressed(close);
                          }
                          LOGGER.error("Build map entry failed", failure);
                          output.accept("Build: " + failure.getMessage());
                        } else
                          output.accept(
                              "Minecraft building in "
                                  + map
                                  + ". /q3 leave returns to your world.");
                      }));
      return 1;
    } catch (Exception failure) {
      busy.set(false);
      if (files != null)
        try {
          files.close();
        } catch (java.io.IOException close) {
          failure.addSuppressed(close);
        }
      LOGGER.error("Build map load failed", failure);
      output.accept("Build: " + failure.getMessage());
      return 0;
    }
  }

  private int pickup(FabricClientCommandSource source, String action, String item, int id) {
    try {
      return dev.bluevista.craftq3.fabric.bridge.PickupCommands.execute(
          source.getClient(),
          runtime.gameName(),
          action,
          item,
          id,
          text -> source.sendFeedback(Component.literal(text)));
    } catch (Exception failure) {
      source.sendError(Component.literal("Pickups: " + failure.getMessage()));
      return 0;
    }
  }

  /** ChatScreen closes itself after dispatch; launch only after that callback has returned. */
  private int queueBridge(
      net.minecraft.client.Minecraft client,
      java.util.function.Consumer<String> output,
      boolean fresh) {
    var level = client.level;
    var player = client.player;
    var screen = client.gui.screen();
    client.schedule(
        () -> {
          if (client.level != level
              || client.player != player
              || client.gui.screen() != null && client.gui.screen() != screen) {
            output.accept("Bridge launch cancelled because the world or screen changed.");
            return;
          }
          bridge(client, output, fresh);
        });
    return 1;
  }

  private int bridge(
      net.minecraft.client.Minecraft client, java.util.function.Consumer<String> output) {
    return bridge(
        client, output, dev.bluevista.craftq3.fabric.bridge.BridgeLoadoutSmoke.startFresh());
  }

  private int bridge(
      net.minecraft.client.Minecraft client,
      java.util.function.Consumer<String> output,
      boolean fresh) {
    return bridge(client, output, fresh, null);
  }

  private int bridge(
      net.minecraft.client.Minecraft client,
      java.util.function.Consumer<String> output,
      boolean fresh,
      dev.bluevista.craftq3.fabric.game.QuakeSession source) {
    if (client.level == null || client.player == null || client.getSingleplayerServer() == null) {
      output.accept("Open a local Minecraft world, then run /q3 bridge.");
      return 0;
    }
    if (!busy.compareAndSet(false, true)) {
      output.accept("CraftQ3 is loading; please wait.");
      return 0;
    }
    dev.bluevista.craftq3.assets.fs.Pk3FileSystem files = null;
    dev.bluevista.craftq3.platform.audio.AudioBackend audio = null;
    dev.bluevista.craftq3.core.fs.GameFileStore settings = null;
    dev.bluevista.craftq3.fabric.bridge.BridgeGame game = null;
    try {
      files = runtime.bridgeFiles();
      var arriving =
          source == null
              ? null
              : source.bridgeLoadout(
                  files.read(new dev.bluevista.craftq3.core.fs.VirtualPath("vm/qagame.qvm")));
      dev.bluevista.craftq3.fabric.bridge.BridgeTransferSmoke.captured(source, arriving);
      var terrain =
          new dev.bluevista.craftq3.fabric.bridge.MinecraftTerrain(
              client.level, client.player.blockPosition(), 32);
      var pos = client.player.position();
      var spawn =
          terrain
              .transform()
              .toQuake(new dev.bluevista.craftq3.core.math.Vec3(pos.x, pos.y, pos.z))
              .add(new dev.bluevista.craftq3.core.math.Vec3(0, 0, 24));
      var bridgeAudio =
          source == null
              ? new dev.bluevista.craftq3.fabric.audio.MinecraftAudioBackend(
                  client, terrain.transform())
              : dev.bluevista.craftq3.fabric.audio.MinecraftAudioBackend.prepare(
                  client, terrain.transform());
      audio = bridgeAudio;
      settings = runtime.bridgeSettings();
      game =
          new dev.bluevista.craftq3.fabric.bridge.BridgeGame(
              files,
              settings,
              terrain,
              spawn,
              -client.player.getYRot() - 90,
              client.getUser().getName(),
              client.player.getUUID(),
              fresh,
              arriving,
              dev.bluevista.craftq3.fabric.bridge.PickupCommands.load(
                  client, runtime.gameName(), terrain.transform()),
              audio,
              client.getWindow().getWidth(),
              client.getWindow().getHeight(),
              text -> LOGGER.info("Q3 bridge: {}", text));
      game.combat(
          new dev.bluevista.craftq3.fabric.bridge.MinecraftCombat(client, terrain.transform()));
      var audioReady = new java.util.concurrent.CompletableFuture<Void>();
      var screen =
          new dev.bluevista.craftq3.fabric.render.BridgeScreen(client, game, terrain, audioReady);
      screen.quakeAction((sourceGame, map) -> returnToQuake(client, sourceGame, map));
      client.gui.setScreen(screen);
      bridgeAudio
          .activateAfterRelease()
          .whenComplete(
              (unused, failure) -> {
                if (failure == null) audioReady.complete(null);
                else audioReady.completeExceptionally(failure);
              });
      output.accept("Quake bridge enabled. Esc returns to Minecraft.");
      return 1;
    } catch (Exception failure) {
      try {
        if (game != null) game.close();
        else {
          if (settings != null) settings.close();
          if (audio != null) audio.close();
          if (files != null) files.close();
        }
      } catch (Exception close) {
        failure.addSuppressed(close);
      }
      LOGGER.error("Could not launch Minecraft bridge", failure);
      output.accept("Bridge: " + failure.getMessage());
      return 0;
    } finally {
      busy.set(false);
    }
  }

  private void returnToQuake(
      net.minecraft.client.Minecraft client,
      dev.bluevista.craftq3.fabric.bridge.BridgeGame source,
      String map) {
    if (!(client.gui.screen() instanceof dev.bluevista.craftq3.fabric.render.BridgeScreen previous)
        || !busy.compareAndSet(false, true)) {
      source.transferMessage("CraftQ3 is loading; please wait.");
      return;
    }
    dev.bluevista.craftq3.fabric.audio.MinecraftAudioBackend audio = null;
    dev.bluevista.craftq3.fabric.game.QuakeSession next = null;
    dev.bluevista.craftq3.server.PlayerLoadout loadout = null;
    try {
      if (source.health() <= 0)
        throw new IllegalStateException("Respawn before transferring into Quake.");
      loadout = source.loadout();
      var module = source.gameModule();
      audio =
          dev.bluevista.craftq3.fabric.audio.MinecraftAudioBackend.prepare(
              client,
              new dev.bluevista.craftq3.platform.CoordinateTransform(
                  32, new dev.bluevista.craftq3.core.math.Vec3(0, 0, 0)));
      next =
          runtime.loadGame(
              map,
              audio,
              client.getUser().getName(),
              client.getWindow().getWidth(),
              client.getWindow().getHeight(),
              loadout,
              module);
      dev.bluevista.craftq3.fabric.bridge.BridgeTransferSmoke.returnPrepared(source, loadout);
      var audioReady = new java.util.concurrent.CompletableFuture<Void>();
      client.gui.setScreen(
          new dev.bluevista.craftq3.fabric.render.Q3Screen(
              next,
              session -> bridge(client, session::bridgeMessage, false, session),
              audioReady.thenCombine(previous.returnComplete(), (sound, player) -> null)));
      audio
          .activateAfterRelease()
          .whenComplete(
              (unused, failure) -> {
                if (failure == null) audioReady.complete(null);
                else audioReady.completeExceptionally(failure);
              });
    } catch (Exception failure) {
      try {
        if (next != null) next.close();
        else if (audio != null) audio.close();
      } catch (Exception cleanup) {
        failure.addSuppressed(cleanup);
      }
      LOGGER.error("Could not transfer bridge loadout into Quake", failure);
      source.transferMessage("Quake transfer: " + failure.getMessage());
      dev.bluevista.craftq3.fabric.bridge.BridgeTransferSmoke.returnFailed(source, loadout);
    } finally {
      busy.set(false);
    }
  }

  private int play(
      net.minecraft.client.Minecraft client,
      String map,
      java.util.function.Consumer<String> output) {
    if (stopping.get()) return 0;
    if (!busy.compareAndSet(false, true)) {
      output.accept("CraftQ3 is loading; please wait.");
      return 0;
    }
    output.accept("CraftQ3: loading Quake III…");
    var startingPlayer = client.player;
    var startingScreen = client.gui.screen();
    final dev.bluevista.craftq3.fabric.audio.MinecraftAudioBackend audio;
    try {
      audio = new dev.bluevista.craftq3.fabric.audio.MinecraftAudioBackend(client);
    } catch (RuntimeException e) {
      busy.set(false);
      LOGGER.error("Could not create Quake audio session", e);
      output.accept("CraftQ3: " + e.getMessage());
      return 0;
    }
    int width = client.getWindow().getWidth(), height = client.getWindow().getHeight();
    String name = client.getUser().getName();
    io.execute(
        () -> {
          try {
            var session = runtime.loadGame(map, audio, name, width, height);
            if (stopping.get()) {
              session.close();
              return;
            }
            client.execute(
                () -> {
                  try {
                    if (stopping.get()
                        || client.player != startingPlayer
                        || (startingScreen
                                instanceof dev.bluevista.craftq3.fabric.render.Q3LaunchScreen
                            && client.gui.screen() != startingScreen)) {
                      session.close();
                      return;
                    }
                    client.gui.setScreen(
                        new dev.bluevista.craftq3.fabric.render.Q3Screen(
                            session,
                            source -> bridge(client, source::bridgeMessage, false, source)));
                  } catch (Exception e) {
                    try {
                      session.close();
                    } catch (Exception closeError) {
                      e.addSuppressed(closeError);
                    }
                    LOGGER.error("Could not open Quake session", e);
                    output.accept("CraftQ3: " + e.getMessage());
                  }
                });
          } catch (Exception e) {
            audio.close();
            LOGGER.error("CraftQ3 gameplay launch failed", e);
            client.execute(
                () -> {
                  output.accept("CraftQ3: " + e.getMessage());
                  if (net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()
                      && (Boolean.getBoolean("craftq3.playCapture")
                          || Boolean.getBoolean("craftq3.lifecycleCapture"))) client.stop();
                });
          } finally {
            busy.set(false);
          }
        });
    return 1;
  }
}
