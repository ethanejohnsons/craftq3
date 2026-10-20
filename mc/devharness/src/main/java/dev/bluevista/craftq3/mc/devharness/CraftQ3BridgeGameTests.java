package dev.bluevista.craftq3.mc.devharness;

import static dev.bluevista.craftq3.mc.devharness.Scenario.check;

import dev.bluevista.craftq3.mc.fabric.CraftQ3Mod;
import dev.bluevista.craftq3.mc.fabric.bridge.AvatarShape;
import dev.bluevista.craftq3.mc.fabric.bridge.BridgeAvatar;
import dev.bluevista.craftq3.mc.fabric.bridge.BridgeData;
import dev.bluevista.craftq3.mc.fabric.bridge.BridgeGame;
import dev.bluevista.craftq3.mc.fabric.bridge.BridgeService;
import dev.bluevista.craftq3.mc.fabric.bridge.BridgeSession;
import dev.bluevista.craftq3.mc.fabric.bridge.BridgedPlayer;
import dev.bluevista.craftq3.mc.fabric.level.CraftQ3Level;
import dev.bluevista.craftq3.mc.fabric.server.Q3Content;
import dev.bluevista.craftq3.mc.hybrid.bridge.Loadout;
import dev.bluevista.craftq3.mc.hybrid.bridge.PickupPlacement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * The bridge on the dedicated GameTest server with the maintainer's installation ({@code
 * CRAFTQ3_Q3ROOT}; without it the test passes as skipped). One scenario, so its steps share the
 * level's single bridge game:
 *
 * <ol>
 *   <li>a player enters on flat stone with a railgun loadout; its Quake client (admitted directly
 *       on the game server under the player's bridge address) spawns standing on the floor and the
 *       loadout is restored;
 *   <li>walking east stops at a stone wall; jumping rises and lands;
 *   <li>a zombie (no AI) in front is railed until Minecraft kills it;
 *   <li>a zombie's melee hits lower the Quake health while Minecraft health is untouched;
 *   <li>a pickup placed with the bridge command is saved in the level and spawned by the next game;
 *   <li>leaving saves the loadout and restores the Minecraft player; entering again restores the
 *       same loadout;
 *   <li>twenty mobs around the player: the bridge tick time is reported.
 * </ol>
 */
public final class CraftQ3BridgeGameTests {
  private static final int X = 4000, Y = 120, Z = 4000;
  private static final int RAILGUN = 7, MACHINEGUN = 2, GAUNTLET = 1;

  @GameTest(maxTicks = 3600)
  public void bridgeScenario(GameTestHelper helper) {
    Optional<Q3Content> mounted = RealContent.mount();
    if (mounted.isEmpty()) {
      helper.succeed();
      return;
    }
    new BridgeScenario(helper, mounted.get()).start();
  }

  private static final class BridgeScenario {
    final GameTestHelper helper;
    final Q3Content content;
    final ServerLevel level;
    final BridgeService bridge = CraftQ3Mod.instance().services().bridge();
    final Scenario scenario;
    final List<Component> messages = new ArrayList<>();
    ServerPlayer player;
    BridgeSession session;
    DirectQuakeClient client;
    Zombie target;
    Zombie attacker;
    final List<Zombie> crowd = new ArrayList<>();
    double startX;
    double highest;
    int healthBefore, armorBefore;
    float minecraftHealthBefore;
    Loadout saved;

    BridgeScenario(GameTestHelper helper, Q3Content content) {
      this.helper = helper;
      this.content = content;
      this.level = helper.getLevel();
      this.scenario = new Scenario(helper, "bridge");
      scenario
          .step("enter on flat terrain", 400, this::enter)
          .step("walk into a wall", 120, this::walk)
          .step("jump", 60, this::jump)
          .step("rail a zombie", 400, this::rail)
          .step("zombie hits the player", 200, this::zombieHits)
          .step("pickup placement persists", 5, this::pickup)
          .step("leave saves the loadout", 20, this::leave)
          .step("enter again restores it", 400, this::reenter)
          .step("tick time with twenty mobs", 120, this::crowd)
          .cleanup(this::cleanup);
    }

    void start() {
      scenario.start();
    }

    private BridgeGame game() {
      return CraftQ3Level.of(level).craftq3$bridge();
    }

    private void arena() {
      for (int cx = (X >> 4) - 2; cx <= (X >> 4) + 2; cx++)
        for (int cz = (Z >> 4) - 2; cz <= (Z >> 4) + 2; cz++) level.setChunkForced(cx, cz, true);
      for (int x = X - 48; x <= X + 16; x++)
        for (int z = Z - 12; z <= Z + 12; z++) {
          level.setBlockAndUpdate(new BlockPos(x, Y - 1, z), Blocks.STONE.defaultBlockState());
          for (int y = Y; y < Y + 8; y++)
            level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
        }
      // A wall five blocks east of the entry point.
      for (int z = Z - 4; z <= Z + 4; z++)
        for (int y = Y; y < Y + 4; y++)
          level.setBlockAndUpdate(new BlockPos(X + 5, y, z), Blocks.STONE.defaultBlockState());
    }

    private static Loadout railLoadout() {
      int[] ammo = new int[Loadout.SLOTS];
      ammo[MACHINEGUN] = 100;
      ammo[RAILGUN] = 10;
      return new Loadout(
          100,
          50,
          (1 << GAUNTLET) | (1 << MACHINEGUN) | (1 << RAILGUN),
          RAILGUN,
          0,
          ammo,
          new int[Loadout.SLOTS]);
    }

    private BridgeSession enterNow(Optional<Loadout> carried, boolean fresh) {
      Optional<BridgeSession> entered =
          bridge.enterWith(player, content, carried, fresh, messages::add);
      check(entered.isPresent(), "bridge entry refused: " + messages);
      return entered.get();
    }

    private boolean enter(int ticks) {
      if (ticks == 0) {
        arena();
        player = TestPlayers.join(level, "bridge-tester");
        player.snapTo(X + 0.5, Y, Z + 0.5, -90, 0);
        level.getChunkSource().move(player);
        session = enterNow(Optional.of(railLoadout()), false);
        check(
            player.gameMode.getGameModeForPlayer() == GameType.ADVENTURE, "not in adventure mode");
        client = new DirectQuakeClient(game().server(), session.addressText(), "bridge-tester");
        return false;
      }
      client.send();
      if (session.slot() != client.slot() || !client.active()) return false;
      if (session.host().restoring()) return false;
      if (ticks < 40) return false; // settle onto the floor
      Loadout now = session.host().captureLoadout().orElseThrow();
      check((now.weapons() & (1 << RAILGUN)) != 0, "railgun not restored: " + now);
      check(now.health() == 100 && now.armor() == 50, "health/armor not restored: " + now);
      check(Math.abs(player.getY() - Y) < 0.1, "not standing on the floor: y=" + player.getY());
      AvatarShape shape = BridgeAvatar.of(player);
      check(shape != null && Math.abs(shape.height() - 56 / 32.0) < 1e-3, "avatar hull: " + shape);
      check(
          Math.abs(player.getBoundingBox().getYsize() - 56 / 32.0) < 1e-3,
          "bounding box not Quake's");
      scenario.note("spawned at " + player.position() + " with " + now);
      startX = player.getX();
      return true;
    }

    private boolean walk(int ticks) {
      client.forward = 127;
      client.send();
      if (ticks < 60) return false;
      client.forward = 0;
      double x = player.getX();
      scenario.note("walked from x=" + startX + " to x=" + x);
      check(x > startX + 3, "did not walk east: " + x);
      check(x < X + 5 - 0.45, "walked into the wall: " + x);
      highest = player.getY();
      return true;
    }

    private boolean jump(int ticks) {
      client.up = ticks < 2 ? 127 : 0;
      client.send();
      highest = Math.max(highest, player.getY());
      if (ticks < 40) return false;
      scenario.note("jump apex " + (highest - Y) + " blocks");
      check(highest > Y + 0.8, "jump too low: " + highest);
      check(Math.abs(player.getY() - Y) < 0.1, "did not land: " + player.getY());
      return true;
    }

    private boolean rail(int ticks) {
      if (ticks == 0) {
        for (int z = Z - 4; z <= Z + 4; z++)
          for (int y = Y; y < Y + 4; y++)
            level.setBlockAndUpdate(new BlockPos(X + 5, y, z), Blocks.AIR.defaultBlockState());
        target = TestPlayers.mob(level, EntityTypes.ZOMBIE, X + 9.5, Y, Z + 0.5, false);
        client.send();
        return false;
      }
      client.buttons = DirectQuakeClient.ATTACK;
      client.send();
      if (target.isAlive()) return false;
      client.buttons = 0;
      scenario.note("zombie killed after " + ticks + " ticks, slugs left " + client.ammo(RAILGUN));
      check(client.ammo(RAILGUN) < 10, "no slug fired");
      return true;
    }

    private boolean zombieHits(int ticks) {
      client.buttons = 0;
      if (ticks == 0) {
        TestPlayers.discard(target);
        attacker =
            TestPlayers.mob(
                level, EntityTypes.ZOMBIE, player.getX() + 1.2, Y, player.getZ(), false);
        healthBefore = client.stat(Loadout.STAT_HEALTH);
        armorBefore = client.stat(Loadout.STAT_ARMOR);
        minecraftHealthBefore = player.getHealth();
      }
      if (ticks % 15 == 1) attacker.doHurtTarget(level, player);
      client.send();
      int health = client.stat(Loadout.STAT_HEALTH), armor = client.stat(Loadout.STAT_ARMOR);
      if (ticks < 50) return false;
      scenario.note(
          "zombie hits: Quake health "
              + healthBefore
              + " -> "
              + health
              + ", armor "
              + armorBefore
              + " -> "
              + armor
              + ", incoming hits "
              + session.incomingHits());
      check(health + armor < healthBefore + armorBefore, "no Quake damage from the zombie");
      check(player.getHealth() == minecraftHealthBefore, "Minecraft health changed");
      TestPlayers.discard(attacker);
      return true;
    }

    private boolean pickup(int ticks) {
      bridge.addPickup(player, "weapon_rocketlauncher", messages::add);
      List<PickupPlacement> placed;
      try {
        placed = BridgeData.pickups(level, content.game());
      } catch (java.io.IOException e) {
        throw new AssertionError(e);
      }
      check(
          placed.stream().anyMatch(p -> p.classname().equals("weapon_rocketlauncher")),
          "pickup not saved: " + messages);
      scenario.note("pickups saved: " + placed);
      return true;
    }

    private boolean leave(int ticks) {
      client.send();
      saved = session.host().captureLoadout().orElseThrow();
      bridge.leave(player, "test");
      check(BridgedPlayer.of(player) == null, "still bridged");
      check(BridgeAvatar.of(player) == null, "avatar shape kept");
      check(player.getAttached(BridgeData.RETURN_STATE) == null, "return marker kept");
      check(player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL, "game mode not restored");
      check(game() == null, "the bridge game did not end with its last player");
      Loadout stored;
      try {
        stored = BridgeData.loadout(player, content.game(), content.qagameSha256()).orElseThrow();
      } catch (java.io.IOException e) {
        throw new AssertionError(e);
      }
      check(stored.equals(saved), "saved " + stored + " but had " + saved);
      scenario.note("loadout saved: " + stored);
      return true;
    }

    private boolean reenter(int ticks) {
      if (ticks == 0) {
        session = enterNow(Optional.empty(), false);
        boolean hasPickup =
            game().server().map().bsp().entities().stream()
                .anyMatch(e -> e.classname().orElse("").equals("weapon_rocketlauncher"));
        check(hasPickup, "the saved pickup was not spawned");
        client = new DirectQuakeClient(game().server(), session.addressText(), "bridge-tester");
        return false;
      }
      client.send();
      if (session.slot() != client.slot() || !client.active() || session.host().restoring())
        return false;
      if (ticks < 20) return false;
      Loadout now = session.host().captureLoadout().orElseThrow();
      check(
          now.weapons() == saved.weapons()
              && now.health() == saved.health()
              && now.armor() == saved.armor()
              && now.ammo(RAILGUN) == saved.ammo(RAILGUN),
          "restored " + now + " instead of " + saved);
      scenario.note("loadout restored: " + now);
      return true;
    }

    private boolean crowd(int ticks) {
      if (ticks == 0) {
        for (int i = 0; i < 20; i++)
          crowd.add(
              TestPlayers.mob(
                  level,
                  EntityTypes.HUSK,
                  player.getX() - 6 + (i % 5) * 2.5,
                  player.getY() + 0.5,
                  player.getZ() - 8 + (i / 5) * 3.5,
                  true));
      }
      client.send();
      if (ticks < 100) return false;
      BridgeGame game = game();
      scenario.note(
          String.format(
              java.util.Locale.ROOT,
              "bridge tick with %d mirrored mobs (%d offered, %d husks alive, player at %s): last"
                  + " %.3f ms, average %.3f ms",
              game.actorHost().actors().size(),
              game.targetCount(),
              crowd.stream().filter(Zombie::isAlive).count(),
              player.position(),
              game.lastTickNanos() / 1e6,
              game.averageTickNanos() / 1e6));
      check(game.targetCount() >= 15, "mobs not offered");
      var snapshot = game.server().snapshot(client.slot());
      int visiblePuppets = 0;
      for (Zombie husk : crowd) {
        int puppet = game.actorHost().actors().client(husk.getId());
        if (puppet >= 0 && snapshot.contains(puppet)) visiblePuppets++;
      }
      scenario.note("puppets in the player's snapshot: " + visiblePuppets);
      check(visiblePuppets == 0, visiblePuppets + " mob puppets are sent to the client");
      check(game.actorHost().actors().size() >= game.targetCount(), "mobs not mirrored");
      return true;
    }

    private void cleanup() {
      BridgeData.savePickups(level, content.game(), List.of());
      crowd.forEach(TestPlayers::discard);
      TestPlayers.discard(target);
      TestPlayers.discard(attacker);
      if (player != null) {
        if (client != null) client.drop();
        bridge.leave(player, "test over");
        TestPlayers.remove(player);
      }
    }
  }
}
