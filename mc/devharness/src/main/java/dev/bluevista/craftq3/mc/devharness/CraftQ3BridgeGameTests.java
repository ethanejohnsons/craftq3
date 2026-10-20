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
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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
 *   <li>an explosion hurts and pushes the Quake player;
 *   <li>a fire block sets the body alight: the fire and the afterburn are Quake damage, and water
 *       puts it out;
 *   <li>a flaming Punch II arrow, a trident, a blaze's and a ghast's fireball hit the bridged body:
 *       Quake damage, Punch's push, the arrow's and the blaze's burn, the ghast's direct hit and
 *       its blast's push;
 *   <li>a pickup placed with the bridge command is saved in the level and spawned by the next game;
 *   <li>leaving saves the loadout and restores the Minecraft player; entering again restores the
 *       same loadout;
 *   <li>twenty mobs around the player: the bridge tick time is reported.
 * </ol>
 */
public final class CraftQ3BridgeGameTests {
  private static final int X = 4000, Y = 120, Z = 4000;
  private static final int RAILGUN = 7, MACHINEGUN = 2, GAUNTLET = 1;

  @GameTest(maxTicks = 9000)
  public void bridgeScenario(GameTestHelper helper) {
    Optional<Q3Content> mounted = RealContent.mount();
    if (mounted.isEmpty()) {
      helper.succeed();
      return;
    }
    new BridgeScenario(helper, mounted.get()).start();
  }

  /**
   * A player still marked as bridged when the server stopped (crash, kill) gets its Minecraft state
   * back when it joins again, without game data.
   */
  @GameTest
  public void interruptedBridgeRestoredOnJoin(GameTestHelper helper) {
    ServerPlayer p = TestPlayers.join(helper.getLevel(), "bridge-recovered");
    try {
      p.setAttached(
          BridgeData.RETURN_STATE,
          new BridgeData.ReturnState(GameType.CREATIVE.getId(), true, false, false, false));
      p.setGameMode(GameType.ADVENTURE);
      p.setNoGravity(true);
      CraftQ3Mod.instance().services().bridge().joined(p);
      helper.assertTrue(
          p.gameMode.getGameModeForPlayer() == GameType.CREATIVE, "game mode not restored");
      helper.assertTrue(!p.isNoGravity(), "gravity not restored");
      helper.assertTrue(p.getAttached(BridgeData.RETURN_STATE) == null, "marker kept");
    } finally {
      TestPlayers.remove(p);
    }
    helper.succeed();
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
    ServerPlayer rival;
    BridgeSession rivalSession;
    DirectQuakeClient rivalClient;
    int rivalHealthBefore;
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
          .step("an explosion hurts and pushes", 100, this::blast)
          .step("heal before fire", 200, this::heal)
          .step("fire burns the Quake player", 300, this::fire)
          .step("heal before lava", 200, this::heal)
          .step("lava leaves the Quake player burning", 300, this::lava)
          .step("heal before the burning zombie", 200, this::heal)
          .step("a burning zombie's hits set the player alight", 1500, this::burningZombie)
          .step("heal before arrows", 200, this::heal)
          .step("a flaming Punch arrow hurts, pushes and ignites", 120, this::arrow)
          .step("heal before the trident", 200, this::heal)
          .step("a trident hurts and pushes", 120, this::trident)
          .step("heal before the blaze", 200, this::heal)
          .step("a blaze fireball hits the bridged body", 120, this::smallFireball)
          .step("heal before the ghast", 200, this::heal)
          .step("a ghast fireball hits the bridged body", 120, this::largeFireball)
          .step("pickup placement persists", 5, this::pickup)
          .step("leave saves the loadout", 20, this::leave)
          .step("enter again restores it", 400, this::reenter)
          .step("two bridged players fight", 200, this::duel)
          .step("tick time with twenty mobs", 120, this::crowd)
          .cleanup(this::cleanup);
    }

    private Boolean spawnedMonsters;

    void start() {
      // The GameTest world keeps its time between runs; at night monsters would spawn around the
      // arena and join the fight (each one a puppet in the game).
      spawnedMonsters = level.getGameRules().get(GameRules.SPAWN_MONSTERS);
      level.getGameRules().set(GameRules.SPAWN_MONSTERS, false, level.getServer());
      purgeStrays();
      helper.onEachTick(this::purgeStrays);
      scenario.start();
    }

    /** Removes mobs the test did not place from the bridge's reach around the arena. */
    private void purgeStrays() {
      AABB around = new AABB(X - 192, Y - 80, Z - 96, X + 160, Y + 80, Z + 96);
      for (Mob mob : level.getEntitiesOfClass(Mob.class, around, Mob::isAlive)) {
        boolean ours =
            mob == target
                || mob == attacker
                || mob == shooter
                || mob == burning
                || crowd.contains(mob);
        if (!ours) mob.discard();
      }
    }

    private BridgeGame game() {
      return CraftQ3Level.of(level).craftq3$bridge();
    }

    private void arena() {
      for (int cx = (X >> 4) - 9; cx <= (X >> 4) + 7; cx++)
        for (int cz = (Z >> 4) - 2; cz <= (Z >> 4) + 2; cz++) level.setChunkForced(cx, cz, true);
      for (int x = X - 128; x <= X + 96; x++)
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

    private net.minecraft.world.phys.Vec3 blastStart;
    private int blastHealth;

    /** TNT-free blast two blocks west of the player: Quake damage and a push to the east. */
    private boolean blast(int ticks) {
      client.send();
      // Let the zombie's knockback settle first.
      if (ticks < 40) return false;
      if (ticks == 40) {
        blastStart = player.position();
        blastHealth = client.stat(Loadout.STAT_HEALTH) + client.stat(Loadout.STAT_ARMOR);
        level.explode(
            null,
            player.getX() - 2,
            player.getY() + 0.5,
            player.getZ(),
            2f,
            net.minecraft.world.level.Level.ExplosionInteraction.NONE);
        return false;
      }
      if (ticks < 70) return false;
      int now = client.stat(Loadout.STAT_HEALTH) + client.stat(Loadout.STAT_ARMOR);
      double moved = player.getX() - blastStart.x;
      scenario.note(
          "blast: Quake health+armor "
              + blastHealth
              + " -> "
              + now
              + ", pushed "
              + moved
              + " blocks east");
      check(now < blastHealth, "the explosion did no Quake damage");
      check(moved > 0.2, "the explosion did not push the Quake player away");
      return true;
    }

    // ---- burning and projectiles ----------------------------------------------------------------

    /**
     * The player's own tick ({@code ServerPlayer.doTick}: fire ticks, invulnerability frames),
     * which a real connection runs every tick ({@code ServerGamePacketListenerImpl.tickPlayer});
     * the test players' in-memory connections are not ticked by the server.
     */
    private void playerTick() {
      player.doTick();
    }

    private int quakeHealth() {
      return client.stat(Loadout.STAT_HEALTH) + client.stat(Loadout.STAT_ARMOR);
    }

    /** Full health and armor again (the game's own restoration), standing still and not burning. */
    private boolean heal(int ticks) {
      playerTick();
      client.buttons = 0;
      client.forward = 0;
      client.send();
      if (ticks == 0) {
        player.clearFire();
        session.host().restoreLoadout(railLoadout());
        return false;
      }
      if (session.host().restoring() || ticks < 25) return false;
      // Standing still on the floor, so the next push is measured from rest.
      if (!session.grounded() || session.velocity().horizontalDistance() > 1e-3) return false;
      check(client.stat(Loadout.STAT_HEALTH) == 100, "not healed: " + client.stat(0));
      return true;
    }

    private BlockPos fireCell;
    private int fireHealth, fireStage, fireMark;
    private long burnsMark;

    /**
     * A fire block at the player's feet sets the Quake body alight; burning goes on after the fire
     * is removed (on-fire ticks), each hit Quake damage; water puts it out. Minecraft health stays.
     */
    private boolean fire(int ticks) {
      playerTick();
      client.send();
      if (ticks == 0) {
        minecraftHealthBefore = player.getHealth();
        fireHealth = quakeHealth();
        fireCell = player.blockPosition();
        burnsMark = session.incomingBurns();
        level.setBlockAndUpdate(fireCell, Blocks.FIRE.defaultBlockState());
        fireStage = 0;
        return false;
      }
      switch (fireStage) {
        case 0 -> {
          // Standing in fire: in_fire hits and ignition.
          if (!player.isOnFire() || session.incomingBurns() < burnsMark + 1) return false;
          level.setBlockAndUpdate(fireCell, Blocks.AIR.defaultBlockState());
          scenario.note(
              "fire: alight after "
                  + ticks
                  + " ticks, "
                  + (session.incomingBurns() - burnsMark)
                  + " burn hits, Quake health+armor "
                  + fireHealth
                  + " -> "
                  + quakeHealth());
          burnsMark = session.incomingBurns();
          fireMark = quakeHealth();
          fireStage = 1;
          return false;
        }
        case 1 -> {
          // Out of the fire, still burning: on_fire ticks.
          if (session.incomingBurns() < burnsMark + 2) return false;
          check(player.isOnFire(), "the burn stopped with the fire block");
          int now = quakeHealth();
          scenario.note("afterburn: Quake health+armor " + fireMark + " -> " + now);
          check(now < fireMark, "burning did no Quake damage");
          fireCell = player.blockPosition();
          level.setBlockAndUpdate(fireCell, Blocks.WATER.defaultBlockState());
          fireStage = 2;
          fireMark = ticks;
          return false;
        }
        default -> {
          if (player.isOnFire()) {
            check(ticks - fireMark < 20, "water did not put the fire out");
            return false;
          }
          level.setBlockAndUpdate(fireCell, Blocks.AIR.defaultBlockState());
          check(player.getHealth() == minecraftHealthBefore, "Minecraft health changed");
          check(quakeHealth() < fireHealth, "no Quake damage from fire");
          scenario.note("water put the fire out after " + (ticks - fireMark) + " ticks");
          return true;
        }
      }
    }

    /**
     * Lava at the feet: Quake's own lava hurts the player and Minecraft sets the body alight; out
     * of the lava, the burn goes on as Quake damage until water puts it out.
     */
    private boolean lava(int ticks) {
      playerTick();
      client.send();
      if (ticks == 0) {
        fireHealth = quakeHealth();
        fireCell = player.blockPosition();
        burnsMark = session.incomingBurns();
        level.setBlockAndUpdate(fireCell, Blocks.LAVA.defaultBlockState());
        fireStage = 0;
        return false;
      }
      switch (fireStage) {
        case 0 -> {
          if (!player.isOnFire()) return false;
          clearFluid(fireCell);
          scenario.note(
              "lava: alight after "
                  + ticks
                  + " ticks, Quake health+armor "
                  + fireHealth
                  + " -> "
                  + quakeHealth()
                  + " (Quake lava)");
          check(session.incomingBurns() == burnsMark, "lava's own damage reached Quake twice");
          fireMark = quakeHealth();
          fireStage = 1;
          return false;
        }
        case 1 -> {
          if (session.incomingBurns() < burnsMark + 2) return false;
          int now = quakeHealth();
          scenario.note("lava afterburn: Quake health+armor " + fireMark + " -> " + now);
          check(now < fireMark, "the lava burn did no Quake damage");
          player.clearFire();
          return true;
        }
        default -> {
          return true;
        }
      }
    }

    /** Removes the fluid of {@code cell} and the flow it started around it. */
    private void clearFluid(BlockPos cell) {
      for (int dx = -3; dx <= 3; dx++)
        for (int dz = -3; dz <= 3; dz++)
          for (int dy = 0; dy <= 1; dy++) {
            BlockPos p = cell.offset(dx, dy, dz);
            if (!level.getFluidState(p).isEmpty())
              level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
          }
    }

    private Zombie burning;

    /** A burning zombie hits until a hit sets the body alight (Minecraft's own chance). */
    private boolean burningZombie(int ticks) {
      playerTick();
      client.send();
      if (ticks == 0) {
        burning =
            TestPlayers.mob(
                level, EntityTypes.ZOMBIE, player.getX() + 1.2, Y, player.getZ(), false);
        burnsMark = session.incomingBurns();
        return false;
      }
      burning.setRemainingFireTicks(400);
      burning.setHealth(burning.getMaxHealth());
      if (!player.isOnFire()) {
        // Minecraft's chance can take many hits: keep the Quake player alive meanwhile.
        if (client.stat(Loadout.STAT_HEALTH) < 50 && !session.host().restoring())
          session.host().restoreLoadout(railLoadout());
        if (ticks % 11 == 1) {
          // Hits from alternating sides, so their knockback keeps the player on the arena.
          double side = (ticks / 11) % 2 == 0 ? 1.2 : -1.2;
          burning.snapTo(player.getX() + side, Y, player.getZ(), 0, 0);
          burning.doHurtTarget(level, player);
        }
        return false;
      }
      if (session.incomingBurns() < burnsMark + 2) return false;
      scenario.note(
          "burning zombie: alight after "
              + ticks
              + " ticks, "
              + (session.incomingBurns() - burnsMark)
              + " burn hits reached Quake");
      TestPlayers.discard(burning);
      burning = null;
      player.clearFire();
      return true;
    }

    private Mob shooter;
    private Entity projectile;
    private Vec3 shotStart;
    private int shotHealth;
    private long shotHits;
    private int shotSettle;

    /**
     * A shooter mob {@code blocks} east (west when negative) of the player, without AI. Shots
     * alternate sides so the pushes keep the player on the arena.
     */
    private <T extends Mob> T shooter(EntityType<T> type, double blocks, double up) {
      T mob =
          TestPlayers.mob(
              level, type, player.getX() + blocks, player.getY() + up, player.getZ(), false);
      mob.setNoGravity(true);
      shooter = mob;
      return mob;
    }

    /** Fires {@code shot} from where it is at the middle of the player's Quake body. */
    private void launch(Projectile shot, float speed) {
      Vec3 aim =
          new Vec3(
                  player.getX() - shot.getX(),
                  player.getY(0.5) - shot.getY(),
                  player.getZ() - shot.getZ())
              .normalize();
      shot.shoot(aim.x, aim.y, aim.z, speed, 0);
      level.addFreshEntity(shot);
      projectile = shot;
      shotStart = player.position();
      shotHealth = quakeHealth();
      shotHits = session.incomingHits();
      shotSettle = Integer.MAX_VALUE;
    }

    /**
     * After the hit, {@code settle} more ticks and landing: the Quake health+armor lost, else -1.
     */
    private int shotLanded(int ticks, int settle) {
      if (session.incomingHits() == shotHits) {
        check(
            ticks < 100,
            "the projectile never hit the bridged body: "
                + projectile
                + " removed "
                + projectile.isRemoved()
                + " motion "
                + projectile.getDeltaMovement()
                + ", player at "
                + player.position()
                + " box "
                + player.getBoundingBox());
        return -1;
      }
      if (shotSettle == Integer.MAX_VALUE) shotSettle = ticks + settle;
      // Pushed players land before the next step (pickups placed where they stand must be on the
      // floor).
      if (ticks < shotSettle || !session.grounded()) return -1;
      return shotHealth - quakeHealth();
    }

    private void endShot() {
      TestPlayers.discard(projectile);
      TestPlayers.discard(shooter);
      projectile = null;
      shooter = null;
      player.clearFire();
    }

    private boolean arrow(int ticks) {
      playerTick();
      client.send();
      if (ticks == 0) {
        Mob skeleton = shooter(EntityTypes.SKELETON, 7, 0);
        ItemStack bow = new ItemStack(Items.BOW);
        bow.enchant(
            level
                .registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.PUNCH),
            2);
        Arrow arrow = new Arrow(level, skeleton, new ItemStack(Items.ARROW), bow);
        arrow.setPos(skeleton.getX() - 1, player.getY(0.5), player.getZ());
        arrow.igniteForSeconds(100);
        launch(arrow, 2.5f);
        return false;
      }
      int lost = shotLanded(ticks, 15);
      if (lost < 0) return false;
      double pushed = shotStart.x - player.getX();
      scenario.note(
          "Punch II flaming arrow: Quake health+armor lost "
              + lost
              + ", pushed "
              + pushed
              + " blocks west, on fire "
              + player.isOnFire());
      check(lost > 0, "the arrow did no Quake damage");
      // Without the Punch push only the hit's ordinary knockback moves the player (under 6 blocks).
      check(pushed > 10, "the Punch arrow did not push the Quake player: " + pushed);
      check(player.isOnFire(), "the flaming arrow did not ignite the bridged body");
      endShot();
      return true;
    }

    private boolean trident(int ticks) {
      playerTick();
      client.send();
      if (ticks == 0) {
        Mob drowned = shooter(EntityTypes.DROWNED, -7, 0);
        ThrownTrident trident = new ThrownTrident(level, drowned, new ItemStack(Items.TRIDENT));
        trident.setPos(drowned.getX() + 1, player.getY(0.5), player.getZ());
        launch(trident, 2.5f);
        return false;
      }
      int lost = shotLanded(ticks, 15);
      if (lost < 0) return false;
      double pushed = player.getX() - shotStart.x;
      scenario.note(
          "trident: Quake health+armor lost " + lost + ", pushed " + pushed + " blocks east");
      check(lost > 0, "the trident did no Quake damage");
      check(pushed > 0.05, "the trident did not push the Quake player");
      endShot();
      return true;
    }

    private boolean smallFireball(int ticks) {
      playerTick();
      client.send();
      if (ticks == 0) {
        Mob blaze = shooter(EntityTypes.BLAZE, -8, 0);
        SmallFireball ball = new SmallFireball(level, blaze, Vec3.ZERO);
        ball.setPos(player.getX() - 4, player.getY(0.5), player.getZ());
        launch(ball, 1.0f);
        return false;
      }
      int lost = shotLanded(ticks, 5);
      if (lost < 0) return false;
      scenario.note(
          "blaze fireball: Quake health+armor lost "
              + lost
              + ", on fire "
              + player.isOnFire()
              + ", fireball removed "
              + projectile.isRemoved());
      check(lost > 0, "the blaze fireball did no Quake damage");
      check(player.isOnFire(), "the blaze fireball did not ignite the bridged body");
      endShot();
      return true;
    }

    private boolean largeFireball(int ticks) {
      playerTick();
      client.send();
      if (ticks == 0) {
        Mob ghast = shooter(EntityTypes.GHAST, -14, 3);
        LargeFireball ball = new LargeFireball(level, ghast, Vec3.ZERO, 1);
        ball.setPos(player.getX() - 4, player.getY(0.5), player.getZ());
        launch(ball, 1.0f);
        return false;
      }
      int lost = shotLanded(ticks, 15);
      if (lost < 0) return false;
      double pushed = player.getX() - shotStart.x;
      scenario.note(
          "ghast fireball: Quake health+armor lost "
              + lost
              + ", hits "
              + (session.incomingHits() - shotHits)
              + ", pushed "
              + pushed
              + " blocks east");
      // The direct hit (6 points, 30 Quake) lands first; the blast in the same tick falls inside
      // Minecraft's invulnerability frames, so only its push arrives.
      check(lost >= 30, "the ghast fireball did not hit the bridged body directly: " + lost);
      check(pushed > 0.5, "the ghast fireball's blast did not push the Quake player");
      endShot();
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

    /** A second player enters the same bridge four blocks ahead and is railed by the first. */
    private boolean duel(int ticks) {
      if (ticks == 0) {
        rival = TestPlayers.join(level, "bridge-rival");
        rival.snapTo(player.getX() + 4, player.getY(), player.getZ(), 90, 0);
        level.getChunkSource().move(rival);
        Optional<BridgeSession> entered =
            bridge.enterWith(rival, content, Optional.empty(), true, messages::add);
        check(entered.isPresent(), "the second player could not enter: " + messages);
        rivalSession = entered.get();
        check(rivalSession.game() == game(), "the second player got another game");
        rivalClient =
            new DirectQuakeClient(game().server(), rivalSession.addressText(), "bridge-rival");
        return false;
      }
      boolean ready =
          rivalSession.slot() == rivalClient.slot() && rivalClient.active() && ticks >= 40;
      // One command per client and tick: a second one with the same time would be ignored.
      client.buttons = ready ? DirectQuakeClient.ATTACK : 0;
      client.send();
      rivalClient.send();
      if (!ready) {
        rivalHealthBefore = rivalClient.active() ? rivalClient.stat(Loadout.STAT_HEALTH) : 0;
        return false;
      }
      int health = rivalClient.stat(Loadout.STAT_HEALTH);
      // A rail hit (100), not the spawn bonus counting down.
      if (health > rivalHealthBefore - 50) return false;
      client.buttons = 0;
      scenario.note(
          "rival Quake health "
              + rivalHealthBefore
              + " -> "
              + health
              + " (rival at "
              + rival.position()
              + ", shooter at "
              + player.position()
              + ")");
      bridge.leave(rival, "test");
      check(BridgedPlayer.of(rival) == null, "the rival is still bridged");
      check(
          game() != null && game().sessions().size() == 1, "the shared game did not keep running");
      TestPlayers.remove(rival);
      rival = null;
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
              game.bridgeHost().actors().size(),
              game.targetCount(),
              crowd.stream().filter(Zombie::isAlive).count(),
              player.position(),
              game.lastTickNanos() / 1e6,
              game.averageTickNanos() / 1e6));
      check(game.targetCount() >= 15, "mobs not offered");
      var snapshot = game.server().snapshot(client.slot());
      int visiblePuppets = 0;
      for (Zombie husk : crowd) {
        int puppet = game.bridgeHost().actors().client(husk.getId());
        if (puppet >= 0 && snapshot.contains(puppet)) visiblePuppets++;
      }
      scenario.note("puppets in the player's snapshot: " + visiblePuppets);
      check(visiblePuppets == 0, visiblePuppets + " mob puppets are sent to the client");
      check(game.bridgeHost().actors().size() >= game.targetCount(), "mobs not mirrored");
      return true;
    }

    private void cleanup() {
      if (spawnedMonsters != null)
        level.getGameRules().set(GameRules.SPAWN_MONSTERS, spawnedMonsters, level.getServer());
      BridgeData.savePickups(level, content.game(), List.of());
      TestPlayers.discard(projectile);
      TestPlayers.discard(shooter);
      TestPlayers.discard(burning);
      crowd.forEach(TestPlayers::discard);
      TestPlayers.discard(target);
      TestPlayers.discard(attacker);
      if (rival != null) {
        bridge.leave(rival, "test over");
        TestPlayers.remove(rival);
      }
      if (player != null) {
        if (client != null) client.drop();
        bridge.leave(player, "test over");
        TestPlayers.remove(player);
      }
    }
  }
}
