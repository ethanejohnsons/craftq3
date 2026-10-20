package dev.bluevista.craftq3.mc.fabric.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.bluevista.craftq3.mc.fabric.bridge.BridgeService;
import dev.bluevista.craftq3.mc.fabric.bridge.BridgedPlayer;
import dev.bluevista.craftq3.mc.fabric.building.BuildingService;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * The server-side {@code /q3} subcommands, registered on every server (integrated and dedicated):
 * {@code bridge [fresh]}, {@code leave}, {@code pickup add|list|remove} and {@code build <map>}.
 * Everyone may enter and leave the bridge and building; placing pickups needs game-master
 * permission, or being the owner of a singleplayer world.
 */
public final class Q3ServerCommands {
  /** The base game's item classnames, for completion; any valid item classname is accepted. */
  public static final List<String> BASE_ITEMS =
      List.of(
          "item_armor_shard",
          "item_armor_combat",
          "item_armor_body",
          "item_health_small",
          "item_health",
          "item_health_large",
          "item_health_mega",
          "weapon_gauntlet",
          "weapon_shotgun",
          "weapon_machinegun",
          "weapon_grenadelauncher",
          "weapon_rocketlauncher",
          "weapon_lightning",
          "weapon_railgun",
          "weapon_plasmagun",
          "weapon_bfg",
          "ammo_shells",
          "ammo_bullets",
          "ammo_grenades",
          "ammo_cells",
          "ammo_lightning",
          "ammo_rockets",
          "ammo_slugs",
          "ammo_bfg",
          "holdable_teleporter",
          "holdable_medkit",
          "item_quad",
          "item_enviro",
          "item_haste",
          "item_invis",
          "item_regen",
          "item_flight");

  private Q3ServerCommands() {}

  /** Adds the subcommands to the server's {@code /q3} root. */
  public static LiteralArgumentBuilder<CommandSourceStack> addTo(
      LiteralArgumentBuilder<CommandSourceStack> root,
      Supplier<BridgeService> bridge,
      Supplier<BuildingService> building) {
    return root.then(
            Commands.literal("bridge")
                .executes(c -> enterBridge(c, bridge, false))
                .then(Commands.literal("fresh").executes(c -> enterBridge(c, bridge, true))))
        .then(Commands.literal("leave").executes(c -> leave(c, bridge, building)))
        .then(
            Commands.literal("pickup")
                .requires(Q3ServerCommands::mayPlacePickups)
                .then(
                    Commands.literal("add")
                        .then(
                            Commands.argument("classname", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(BASE_ITEMS, b))
                                .executes(
                                    c -> {
                                      ServerPlayer player = c.getSource().getPlayerOrException();
                                      bridge
                                          .get()
                                          .addPickup(
                                              player,
                                              StringArgumentType.getString(c, "classname"),
                                              feedback(c.getSource()));
                                      return 1;
                                    })))
                .then(
                    Commands.literal("list")
                        .executes(
                            c -> {
                              bridge
                                  .get()
                                  .listPickups(
                                      c.getSource().getPlayerOrException(),
                                      feedback(c.getSource()));
                              return 1;
                            }))
                .then(
                    Commands.literal("remove")
                        .then(
                            Commands.argument("id", IntegerArgumentType.integer(1, 256))
                                .executes(
                                    c -> {
                                      bridge
                                          .get()
                                          .removePickup(
                                              c.getSource().getPlayerOrException(),
                                              IntegerArgumentType.getInteger(c, "id"),
                                              feedback(c.getSource()));
                                      return 1;
                                    }))))
        .then(
            Commands.literal("build")
                .then(
                    Commands.argument("map", StringArgumentType.word())
                        .executes(
                            c -> {
                              building
                                  .get()
                                  .build(
                                      c.getSource().getPlayerOrException(),
                                      StringArgumentType.getString(c, "map"),
                                      feedback(c.getSource()));
                              return 1;
                            })));
  }

  private static boolean mayPlacePickups(CommandSourceStack source) {
    if (Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source)) return true;
    ServerPlayer player = source.getPlayer();
    return player != null && source.getServer().isSingleplayerOwner(player.nameAndId());
  }

  private static int enterBridge(
      CommandContext<CommandSourceStack> c, Supplier<BridgeService> bridge, boolean fresh)
      throws CommandSyntaxException {
    ServerPlayer player = c.getSource().getPlayerOrException();
    bridge.get().enter(player, fresh, feedback(c.getSource()));
    return 1;
  }

  private static int leave(
      CommandContext<CommandSourceStack> c,
      Supplier<BridgeService> bridge,
      Supplier<BuildingService> building)
      throws CommandSyntaxException {
    ServerPlayer player = c.getSource().getPlayerOrException();
    if (BridgedPlayer.of(player) != null) {
      bridge.get().leave(player, "Left the bridge");
      return 1;
    }
    if (building.get().leave(player, feedback(c.getSource()))) return 1;
    c.getSource().sendFailure(Component.literal("CraftQ3: you are not in the bridge or building"));
    return 0;
  }

  /** Messages to the command's player (also later, from asynchronous completions). */
  private static Consumer<Component> feedback(CommandSourceStack source) {
    ServerPlayer player = source.getPlayer();
    if (player == null) return message -> source.sendSystemMessage(message);
    return message -> {
      if (!player.hasDisconnected()) player.sendSystemMessage(message);
    };
  }
}
