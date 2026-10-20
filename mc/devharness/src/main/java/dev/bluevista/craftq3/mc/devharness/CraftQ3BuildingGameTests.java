package dev.bluevista.craftq3.mc.devharness;

import static dev.bluevista.craftq3.mc.devharness.Scenario.check;

import dev.bluevista.craftq3.mc.fabric.CraftQ3Mod;
import dev.bluevista.craftq3.mc.fabric.building.BuildingDimension;
import dev.bluevista.craftq3.mc.fabric.building.BuildingRules;
import dev.bluevista.craftq3.mc.fabric.building.BuildingService;
import dev.bluevista.craftq3.mc.fabric.level.CraftQ3Level;
import dev.bluevista.craftq3.mc.fabric.server.Q3Content;
import dev.bluevista.craftq3.mc.hybrid.building.BlockFace;
import dev.bluevista.craftq3.mc.hybrid.building.BuildingQueries;
import dev.bluevista.craftq3.mc.hybrid.building.BuildingRegion;
import dev.bluevista.craftq3.mc.hybrid.building.BuildingRegions;
import dev.bluevista.craftq3.mc.hybrid.building.SupportShape;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Building mode on the dedicated GameTest server with the maintainer's installation ({@code
 * CRAFTQ3_Q3ROOT}; skipped without it): {@code q3dm17} is placed in the building dimension, then
 *
 * <ol>
 *   <li>a torch can stand on a BSP floor cell (BSP face support) but not in mid-air, and sand
 *       dropped above the floor lands on it (entity collision);
 *   <li>an explosion does not hurt a zombie behind a BSP wall while one in the open is hurt;
 *   <li>a zombie's path never crosses BSP solid, and it can path over BSP floors;
 *   <li>water placed next to a BSP wall does not flow into it or through the floor;
 *   <li>a water bucket aimed at a BSP floor fills the cell above it and an empty bucket collects it
 *       again; a lava bucket aimed at a BSP wall fills the cell in front of it; emptying into BSP
 *       solid is refused;
 *   <li>an item frame and a painting placed on a BSP wall (through the crosshair's BSP pick) and an
 *       item frame on a BSP floor hang there, nothing hangs in mid-air;
 *   <li>a zombie sent above a BSP floor that is off the block grid plans to that floor's cell and
 *       walks onto it;
 *   <li>a saved region whose BSP is missing loads disabled with a reason while the real one loads
 *       enabled.
 * </ol>
 */
public final class CraftQ3BuildingGameTests {
  @GameTest(maxTicks = 1600)
  public void buildingScenario(GameTestHelper helper) {
    Optional<Q3Content> mounted = RealContent.mount();
    if (mounted.isEmpty()) {
      helper.succeed();
      return;
    }
    new BuildingScenario(helper, mounted.get()).start();
  }

  private static final class BuildingScenario {
    final Scenario scenario;
    final Q3Content content;
    final BuildingService service = CraftQ3Mod.instance().services().building();
    ServerLevel level;
    BuildingRegions.Enabled region;
    BuildingQueries queries;
    BlockPos spawnCell;
    BlockPos floorCell;
    BlockPos sandCell;
    final List<Zombie> zombies = new ArrayList<>();
    Zombie sheltered, exposed;
    BlockPos waterCell;
    List<BuildingRegion> savedRegions;

    final ServerLevel testLevel;

    BuildingScenario(GameTestHelper helper, Q3Content content) {
      this.content = content;
      this.testLevel = helper.getLevel();
      this.scenario = new Scenario(helper, "building");
      scenario
          .step("place q3dm17", 5, this::place)
          .step("blocks stand on BSP floors", 100, this::support)
          .step("explosion occluded by a BSP wall", 20, this::explosion)
          .step("mob paths respect the BSP", 60, this::pathing)
          .step("fluid stops at BSP surfaces", 120, this::fluid)
          .step("buckets against BSP floors and walls", 5, this::buckets)
          .step("item frames and paintings hang on BSP surfaces", 40, this::hanging)
          .step("mob destinations snap onto BSP floors off the grid", 400, this::destinations)
          .step("a save without the PK3 loads disabled", 400, this::missingPk3)
          .cleanup(this::cleanup);
    }

    void start() {
      scenario.start();
    }

    private boolean place(int ticks) throws Exception {
      level = helperServer().getLevel(BuildingDimension.KEY);
      if (level == null) {
        // The GameTest server bakes its world without data pack dimensions; the building rules
        // are per level, so the test level holds the region instead.
        level = testLevel;
        scenario.note("no craftq3:building on the GameTest server; using the test level");
      }
      byte[] bsp = content.read("maps/q3dm17.bsp").orElseThrow();
      BuildingRegions.Status status =
          service.placeNow(level, content.game(), "maps/q3dm17.bsp", bsp);
      check(status instanceof BuildingRegions.Enabled, "q3dm17 not enabled: " + status);
      region = (BuildingRegions.Enabled) status;
      queries = region.queries();
      Vec3 spawn = BuildingService.spawnPoint(region);
      spawnCell = BlockPos.containing(spawn);
      for (int cx = (spawnCell.getX() >> 4) - 3; cx <= (spawnCell.getX() >> 4) + 3; cx++)
        for (int cz = (spawnCell.getZ() >> 4) - 3; cz <= (spawnCell.getZ() >> 4) + 3; cz++)
          level.setChunkForced(cx, cz, true);
      scenario.note("q3dm17 in region " + region.region().slot() + ", spawn " + spawn);
      return true;
    }

    private net.minecraft.server.MinecraftServer helperServer() {
      return CraftQ3Mod.instance().services().server();
    }

    /** A clear cell with a supporting BSP floor face below, near the spawn. */
    private BlockPos findFloorCell() {
      for (int r = 0; r < 64; r++)
        for (int dx = -r; dx <= r; dx++)
          for (int dz = -r; dz <= r; dz++)
            for (int dy = -12; dy <= 12; dy++) {
              if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
              int x = spawnCell.getX() + dx, y = spawnCell.getY() + dy, z = spawnCell.getZ() + dz;
              if (queries.cellClear(x, y, z)
                  && queries.cellClear(x, y + 1, z)
                  && queries.cellClear(x, y + 2, z)
                  && queries.supportsFace(x, y - 1, z, BlockFace.UP, SupportShape.CENTER))
                return new BlockPos(x, y, z);
            }
      throw new AssertionError("no supported floor cell near the spawn");
    }

    private boolean support(int ticks) {
      if (ticks == 0) {
        floorCell = findFloorCell();
        check(
            Blocks.TORCH.defaultBlockState().canSurvive(level, floorCell),
            "a torch cannot stand on the BSP floor at " + floorCell);
        BlockPos air = floorCell.above(2);
        check(
            !Blocks.TORCH.defaultBlockState().canSurvive(level, air),
            "a torch can stand in mid-air at " + air);
        sandCell = floorCell;
        FallingBlockEntity.fall(level, floorCell.above(2), Blocks.SAND.defaultBlockState());
        level.setBlockAndUpdate(floorCell.above(2), Blocks.AIR.defaultBlockState());
        scenario.note("BSP floor cell " + floorCell);
        return false;
      }
      if (!level.getBlockState(sandCell).is(Blocks.SAND)) return false;
      level.setBlockAndUpdate(sandCell, Blocks.AIR.defaultBlockState());
      return true;
    }

    /** Two clear two-cell spaces three cells apart along an axis, with or without BSP between. */
    private BlockPos[] findWall(boolean blocked) {
      for (int r = 0; r < 64; r++)
        for (int dx = -r; dx <= r; dx++)
          for (int dz = -r; dz <= r; dz++)
            for (int dy = -12; dy <= 12; dy++) {
              if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
              BlockPos a = spawnCell.offset(dx, dy, dz);
              if (!clear2(a)) continue;
              for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos b = a.relative(d, 3);
                if (!clear2(b)) continue;
                if (blocked ? sheltered(a, b) : open(a, b)) return new BlockPos[] {a, b};
              }
            }
      throw new AssertionError("no " + (blocked ? "walled" : "open") + " cell pair found");
    }

    /** Every sample of a mob-sized body in {@code b} is hidden from the centre of {@code a}. */
    private boolean sheltered(BlockPos a, BlockPos b) {
      Vec3 c = Vec3.atCenterOf(a);
      for (double fx : new double[] {0.15, 0.85})
        for (double fy : new double[] {0.02, 1.0, 1.98})
          for (double fz : new double[] {0.15, 0.85})
            if (!queries.occludes(c.x, c.y, c.z, b.getX() + fx, b.getY() + fy, b.getZ() + fz))
              return false;
      return true;
    }

    /** Nothing hides a mob-sized body in {@code b} from the centre of {@code a}. */
    private boolean open(BlockPos a, BlockPos b) {
      Vec3 c = Vec3.atCenterOf(a);
      for (double fx : new double[] {0.15, 0.85})
        for (double fy : new double[] {0.02, 1.0, 1.98})
          for (double fz : new double[] {0.15, 0.85})
            if (queries.occludes(c.x, c.y, c.z, b.getX() + fx, b.getY() + fy, b.getZ() + fz))
              return false;
      return true;
    }

    private boolean clear2(BlockPos p) {
      return queries.cellClear(p.getX(), p.getY(), p.getZ())
          && queries.cellClear(p.getX(), p.getY() + 1, p.getZ());
    }

    private boolean floored(BlockPos p) {
      return queries.cellClear(p.getX(), p.getY(), p.getZ())
          && queries.cellClear(p.getX(), p.getY() + 1, p.getZ())
          && !Double.isNaN(
              queries.floorBelow(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 0.75));
    }

    private boolean explosion(int ticks) {
      if (ticks == 0) {
        BlockPos[] walled = findWall(true);
        BlockPos[] open = findWall(false);
        sheltered = zombieAt(walled[1]);
        exposed = zombieAt(open[1]);
        Vec3 c1 = Vec3.atCenterOf(walled[0]);
        level.explode(null, c1.x, c1.y, c1.z, 3f, Level.ExplosionInteraction.NONE);
        Vec3 c2 = Vec3.atCenterOf(open[0]);
        level.explode(null, c2.x, c2.y, c2.z, 3f, Level.ExplosionInteraction.NONE);
        scenario.note(
            "walled pair "
                + walled[0]
                + "/"
                + walled[1]
                + ", open pair "
                + open[0]
                + "/"
                + open[1]);
        return false;
      }
      if (ticks < 3) return false;
      scenario.note(
          "sheltered zombie health " + sheltered.getHealth() + ", exposed " + exposed.getHealth());
      // Minecraft deals one point to anything in range even behind full cover (zero exposure).
      check(
          sheltered.getMaxHealth() - sheltered.getHealth() <= 1.01f,
          "the BSP wall did not shelter");
      check(
          exposed.getHealth() < exposed.getMaxHealth() || !exposed.isAlive(),
          "the open blast missed");
      return true;
    }

    private Zombie zombieAt(BlockPos cell) {
      Zombie z =
          TestPlayers.mob(
              level,
              EntityTypes.HUSK,
              cell.getX() + 0.5,
              cell.getY() + 0.01,
              cell.getZ() + 0.5,
              false);
      z.setNoGravity(true);
      zombies.add(z);
      return z;
    }

    private Zombie walker;

    private boolean pathing(int ticks) {
      if (ticks == 0) {
        walker =
            TestPlayers.mob(
                level,
                EntityTypes.HUSK,
                floorCell.getX() + 0.5,
                floorCell.getY() + 0.2,
                floorCell.getZ() + 0.5,
                true);
        zombies.add(walker);
        return false;
      }
      // Ground navigation only plans from the ground: let it land on the BSP floor first.
      if (!walker.onGround()) return false;
      int paths = 0, longest = 0, crossings = 0;
      for (int dx = -6; dx <= 6; dx++)
        for (int dz = -6; dz <= 6; dz++)
          for (int dy = -2; dy <= 2; dy++) {
            BlockPos target = floorCell.offset(dx, dy, dz);
            if ((dx == 0 && dz == 0) || !floored(target)) continue;
            Path path = walker.getNavigation().createPath(target, 0);
            if (path == null) continue;
            paths++;
            longest = Math.max(longest, path.getNodeCount());
            for (int i = 0; i + 1 < path.getNodeCount(); i++) {
              Node a = path.getNode(i), b = path.getNode(i + 1);
              if (queries.occludes(
                  a.x + 0.5, a.y + 0.6, a.z + 0.5, b.x + 0.5, b.y + 0.6, b.z + 0.5)) crossings++;
            }
          }
      scenario.note(
          "paths over q3dm17 from "
              + floorCell
              + ": "
              + paths
              + " found, longest "
              + longest
              + " nodes, BSP crossings "
              + crossings);
      check(paths > 0 && longest >= 3, "no path over BSP floors");
      check(crossings == 0, "a path crosses BSP solid");
      return true;
    }

    private boolean fluid(int ticks) {
      if (ticks == 0) {
        // A floored cell beside a BSP wall cell.
        for (int r = 0; r < 64 && waterCell == null; r++)
          for (int dx = -r; dx <= r && waterCell == null; dx++)
            for (int dz = -r; dz <= r && waterCell == null; dz++)
              for (int dy = -12; dy <= 12 && waterCell == null; dy++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                BlockPos p = spawnCell.offset(dx, dy, dz);
                if (!floored(p)) continue;
                for (Direction d : Direction.Plane.HORIZONTAL)
                  if (!queries.cellClear(p.relative(d).getX(), p.getY(), p.relative(d).getZ())) {
                    waterCell = p;
                    break;
                  }
              }
        check(waterCell != null, "no cell beside a BSP wall");
        level.setBlockAndUpdate(waterCell, Blocks.WATER.defaultBlockState());
        return false;
      }
      if (ticks < 100) return false;
      int wet = 0, intruded = 0;
      for (int dx = -6; dx <= 6; dx++)
        for (int dz = -6; dz <= 6; dz++)
          for (int dy = -3; dy <= 0; dy++) {
            BlockPos p = waterCell.offset(dx, dy, dz);
            if (level.getFluidState(p).isEmpty()) continue;
            wet++;
            if (!queries.cellClear(p.getX(), p.getY(), p.getZ())) intruded++;
          }
      BlockPos below = waterCell.below();
      scenario.note("water spread to " + wet + " cells, " + intruded + " inside BSP solid");
      check(intruded == 0, "water flowed into BSP solid");
      check(level.getFluidState(below).isEmpty(), "water fell through the BSP floor");
      clearWater();
      return true;
    }

    // ---- buckets, hanging entities, destinations ---------------------------------------------

    private ServerPlayer builder;

    /** A survival player in the region whose eye is at {@code eye}, looking at {@code target}. */
    private ServerPlayer builderAt(Vec3 eye, Vec3 target, ItemStack held) {
      if (builder == null) {
        builder = TestPlayers.join(level, "building-tester");
        builder.setNoGravity(true);
      }
      Vec3 d = target.subtract(eye);
      float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
      float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
      builder.snapTo(eye.x, eye.y - builder.getEyeHeight(), eye.z, yaw, pitch);
      builder.setItemInHand(InteractionHand.MAIN_HAND, held);
      return builder;
    }

    /** A clear cell beside a BSP wall with a free cell two further out to look from, or null. */
    private BlockPos[] findWallFacing(SupportShape shape) {
      for (int r = 0; r < 48; r++)
        for (int dx = -r; dx <= r; dx++)
          for (int dz = -r; dz <= r; dz++)
            for (int dy = -12; dy <= 12; dy++) {
              if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
              BlockPos c = spawnCell.offset(dx, dy, dz);
              if (!queries.cellClear(c.getX(), c.getY(), c.getZ())) continue;
              for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos wall = c.relative(d);
                BlockPos eye = c.relative(d.getOpposite(), 2);
                BlockPos between = c.relative(d.getOpposite());
                if (queries.cellClear(wall.getX(), wall.getY(), wall.getZ())) continue;
                if (!queries.supportsFace(
                    wall.getX(),
                    wall.getY(),
                    wall.getZ(),
                    BlockFace.ofOrdinal(d.getOpposite().ordinal()),
                    shape)) continue;
                if (!queries.cellClear(eye.getX(), eye.getY(), eye.getZ())
                    || !queries.cellClear(between.getX(), between.getY(), between.getZ())) continue;
                if (!level.getBlockState(c).isAir()) continue;
                return new BlockPos[] {c, wall, eye};
              }
            }
      return null;
    }

    private boolean buckets(int ticks) {
      // 1. A water bucket aimed straight down at the BSP floor fills the cell above it.
      BlockPos f = floorCell;
      Vec3 eye = new Vec3(f.getX() + 0.5, f.getY() + 2.6, f.getZ() + 0.5);
      Vec3 floorPoint = new Vec3(f.getX() + 0.5, f.getY() - 1, f.getZ() + 0.5);
      BuildingQueries.Placement expect =
          queries.pick(eye.x, eye.y, eye.z, eye.x, eye.y - 5, eye.z, BuildingQueries.SOLID_MASK);
      check(expect != null, "no BSP floor under " + f);
      BlockPos into = new BlockPos(expect.cellX(), expect.cellY(), expect.cellZ());
      check(level.getFluidState(into).isEmpty(), "fluid already in " + into);
      ServerPlayer p = builderAt(eye, floorPoint, new ItemStack(Items.WATER_BUCKET));
      InteractionResult placed = p.getMainHandItem().use(level, p, InteractionHand.MAIN_HAND);
      check(placed instanceof InteractionResult.Success, "the water bucket did nothing: " + placed);
      check(
          level.getFluidState(into).is(FluidTags.WATER) && level.getFluidState(into).isSource(),
          "a water bucket on the BSP floor did not fill " + into + " (" + placed + ")");
      scenario.note("water bucket on the BSP floor filled " + into + " (" + placed + ")");
      // 2. An empty bucket aimed the same way collects it again.
      p = builderAt(eye, floorPoint, new ItemStack(Items.BUCKET));
      InteractionResult collected = p.getMainHandItem().use(level, p, InteractionHand.MAIN_HAND);
      check(level.getFluidState(into).isEmpty(), "the empty bucket did not collect the water");
      check(
          collected instanceof InteractionResult.Success success
              && success.heldItemTransformedTo() != null
              && success.heldItemTransformedTo().is(Items.WATER_BUCKET),
          "the bucket was not filled: " + collected);
      // 3. Aimed at a BSP wall, lava goes into the free cell in front of it, never into the wall.
      BlockPos[] wall = findWallFacing(SupportShape.CENTER);
      check(wall != null, "no BSP wall near the spawn");
      Vec3 wallEye = Vec3.atCenterOf(wall[2]);
      p = builderAt(wallEye, Vec3.atCenterOf(wall[1]), new ItemStack(Items.LAVA_BUCKET));
      InteractionResult lava = p.getMainHandItem().use(level, p, InteractionHand.MAIN_HAND);
      check(
          level.getFluidState(wall[0]).is(FluidTags.LAVA),
          "a lava bucket aimed at the BSP wall did not fill " + wall[0] + " (" + lava + ")");
      check(level.getFluidState(wall[1]).isEmpty(), "lava went into the BSP wall");
      level.setBlockAndUpdate(wall[0], Blocks.AIR.defaultBlockState());
      // 4. Emptying straight into BSP solid (as a dispenser would) is refused.
      boolean intoSolid = ((BucketItem) Items.WATER_BUCKET).emptyContents(p, level, wall[1], null);
      check(!intoSolid && level.getFluidState(wall[1]).isEmpty(), "water was put into BSP solid");
      scenario.note(
          "lava bucket at the BSP wall "
              + wall[1]
              + " filled "
              + wall[0]
              + "; emptying into the wall refused");
      return true;
    }

    private ItemFrame frame;
    private Painting painting;
    private ItemFrame floorFrame;

    /**
     * {@code BuildingPickMixin}'s hit (the client's crosshair) from {@code eye} toward {@code at}.
     */
    private BlockHitResult pick(Vec3 eye, Vec3 at) {
      Vec3 to = eye.add(at.subtract(eye).normalize().scale(5));
      return BuildingRules.clip(
          level,
          eye,
          to,
          BlockHitResult.miss(
              to, Direction.getApproximateNearest(eye.subtract(to)), BlockPos.containing(to)));
    }

    private boolean hanging(int ticks) {
      if (ticks == 0) {
        BlockPos[] wall = findWallFacing(SupportShape.FULL);
        check(wall != null, "no fully supported BSP wall face near the spawn");
        Vec3 eye = Vec3.atCenterOf(wall[2]);
        BlockHitResult hit = pick(eye, Vec3.atCenterOf(wall[1]));
        check(
            hit.getBlockPos().equals(wall[0]),
            "the pick named " + hit.getBlockPos() + " instead of " + wall[0]);
        // An item frame on the wall.
        ServerPlayer p = builderAt(eye, Vec3.atCenterOf(wall[1]), new ItemStack(Items.ITEM_FRAME));
        InteractionResult framed =
            p.getMainHandItem().useOn(new UseOnContext(p, InteractionHand.MAIN_HAND, hit));
        List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class, new AABB(wall[0]));
        check(frames.size() == 1, "no item frame on the BSP wall (" + framed + ")");
        frame = frames.getFirst();
        check(frame.getDirection() == hit.getDirection(), "frame faces " + frame.getDirection());
        frame.discard();
        // A painting in the same spot.
        p = builderAt(eye, Vec3.atCenterOf(wall[1]), new ItemStack(Items.PAINTING));
        InteractionResult painted =
            p.getMainHandItem().useOn(new UseOnContext(p, InteractionHand.MAIN_HAND, hit));
        List<Painting> paintings =
            level.getEntitiesOfClass(Painting.class, new AABB(wall[0]).inflate(2));
        check(paintings.size() == 1, "no painting on the BSP wall (" + painted + ")");
        painting = paintings.getFirst();
        // An item frame on the BSP floor, facing up.
        Vec3 above =
            new Vec3(floorCell.getX() + 0.5, floorCell.getY() + 1.6, floorCell.getZ() + 0.5);
        Vec3 down = new Vec3(floorCell.getX() + 0.5, floorCell.getY() - 1, floorCell.getZ() + 0.5);
        BlockHitResult floorHit = pick(above, down);
        p = builderAt(above, down, new ItemStack(Items.ITEM_FRAME));
        p.getMainHandItem().useOn(new UseOnContext(p, InteractionHand.MAIN_HAND, floorHit));
        List<ItemFrame> onFloor = level.getEntitiesOfClass(ItemFrame.class, new AABB(floorCell));
        check(onFloor.size() == 1, "no item frame on the BSP floor at " + floorCell);
        floorFrame = onFloor.getFirst();
        check(
            floorFrame.getDirection() == Direction.UP,
            "floor frame faces " + floorFrame.getDirection());
        // Nothing to hang on in mid-air.
        BlockPos air = floorCell.above(2);
        check(
            !new ItemFrame(level, air, Direction.NORTH).survives(),
            "an item frame survives in mid-air at " + air);
        scenario.note(
            "item frame and "
                + painting.getVariant().getRegisteredName()
                + " painting on the BSP wall "
                + wall[1]
                + " (hit "
                + hit.getDirection()
                + "), item frame on the BSP floor "
                + floorCell);
        return false;
      }
      // They stay up (survives is checked as Minecraft does every so often).
      if (ticks < 30) return false;
      check(painting.isAlive() && painting.survives(), "the painting fell off the BSP wall");
      check(floorFrame.isAlive() && floorFrame.survives(), "the frame fell off the BSP floor");
      painting.discard();
      floorFrame.discard();
      return true;
    }

    private BlockPos offGrid;
    private double offGridFloor;

    /**
     * A cell whose level BSP floor is off the block grid near the spawn, and a start three to five
     * cells away on a level floor from which the strider can reach it: the strider is put there
     * (standing on its floor).
     */
    private boolean findOffGrid() {
      int attempts = 0;
      for (int r = 0; r < 40; r++)
        for (int dx = -r; dx <= r; dx++)
          for (int dz = -r; dz <= r; dz++)
            for (int dy = -16; dy <= 16; dy++) {
              if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
              BlockPos c = spawnCell.offset(dx, dy, dz);
              double floor = BuildingRules.floorIn(level, c);
              if (Double.isNaN(floor)) continue;
              double frac = floor - Math.floor(floor);
              if (frac < 0.1 || frac > 0.9 || !flat(c, floor) || !open(c, 4)) continue;
              for (Direction d : Direction.Plane.HORIZONTAL)
                for (int far = 3; far <= 5; far++) {
                  BlockPos start = c.relative(d, far);
                  double startFloor = BuildingRules.floorIn(level, start);
                  // A start on a level floor a few cells away, at most a step from this one.
                  if (Double.isNaN(startFloor)
                      || Math.abs(startFloor - floor) > 0.55
                      || !flat(start, startFloor)
                      || !open(start, 2)) continue;
                  if (++attempts > 400) return false;
                  strider.snapTo(start.getX() + 0.5, startFloor, start.getZ() + 0.5, 0, 0);
                  strider.setOnGround(true);
                  Path path = strider.getNavigation().createPath(c, 0);
                  if (path == null || !path.canReach()) continue;
                  offGrid = c;
                  offGridFloor = floor;
                  return true;
                }
            }
      return false;
    }

    /**
     * The floor in {@code cell} is level under a mob's whole footprint (not a slope or an edge).
     */
    private boolean flat(BlockPos cell, double floor) {
      for (double fx : new double[] {0.15, 0.85})
        for (double fz : new double[] {0.15, 0.85}) {
          double corner =
              queries.floorBelow(cell.getX() + fx, cell.getY() + 0.5, cell.getZ() + fz, 1.0);
          if (Double.isNaN(corner) || Math.abs(corner - floor) > 0.01) return false;
        }
      return true;
    }

    /** {@code cell} and {@code height - 1} cells above it are empty and free of BSP solid. */
    private boolean open(BlockPos cell, int height) {
      for (int up = 0; up < height; up++) {
        BlockPos p = cell.above(up);
        if (!level.getBlockState(p).isAir()
            || (up > 0 && !queries.cellClear(p.getX(), p.getY(), p.getZ()))) return false;
      }
      return true;
    }

    private Zombie strider;
    private int destinationStart = -1;

    private boolean destinations(int ticks) {
      if (ticks == 0) {
        if (builder != null) {
          TestPlayers.remove(builder);
          builder = null;
        }
        // A walker that only goes where it is sent (no goals of its own).
        strider =
            TestPlayers.mob(
                level,
                EntityTypes.HUSK,
                floorCell.getX() + 0.5,
                floorCell.getY() + 0.2,
                floorCell.getZ() + 0.5,
                true);
        strider.removeFreeWill();
        zombies.add(strider);
        walker = strider;
        return false;
      }
      if (destinationStart < 0) {
        if (!strider.onGround()) return false;
        destinationStart = ticks;
        check(findOffGrid(), "no reachable BSP floor off the block grid near the spawn");
        // Sent to a spot three blocks above that floor, Minecraft would look for a block below.
        BlockPos above = offGrid.above(3);
        Path path = walker.getNavigation().createPath(above, 0);
        check(path != null, "no path to the spot above the off-grid floor");
        check(
            path.getTarget().equals(offGrid),
            "destination " + path.getTarget() + " instead of the floor cell " + offGrid);
        check(
            walker.getNavigation().isStableDestination(offGrid),
            "a cell on a BSP floor is not a stable destination");
        strider.getNavigation().moveTo(above.getX() + 0.5, above.getY(), above.getZ() + 0.5, 1.0);
        scenario.note(
            "off-grid BSP floor at y "
                + offGridFloor
                + " in "
                + offGrid
                + ": destination snapped from "
                + above
                + ", path of "
                + path.getNodeCount()
                + " nodes");
        return false;
      }
      double dx = walker.getX() - (offGrid.getX() + 0.5),
          dz = walker.getZ() - (offGrid.getZ() + 0.5);
      // moveTo plans to within one block of its target (Minecraft's reach for it).
      if (Math.hypot(dx, dz) > 1.5 || Math.abs(walker.getY() - offGridFloor) > 0.05) {
        if (ticks % 100 == 0)
          scenario.note(
              "strider at "
                  + walker.position()
                  + " onGround "
                  + walker.onGround()
                  + " navigation done "
                  + walker.getNavigation().isDone()
                  + " path "
                  + (walker.getNavigation().getPath() == null
                      ? "none"
                      : walker.getNavigation().getPath().getNextNodeIndex()
                          + "/"
                          + walker.getNavigation().getPath().getNodeCount()
                          + " to "
                          + walker.getNavigation().getPath().getTarget()));
        if (ticks % 40 == 0 && walker.getNavigation().isDone())
          walker
              .getNavigation()
              .moveTo(offGrid.getX() + 0.5, offGrid.getY() + 3, offGrid.getZ() + 0.5, 1.0);
        return false;
      }
      scenario.note(
          "the walker stands on the off-grid floor at y "
              + walker.getY()
              + " after "
              + ticks
              + " ticks");
      return true;
    }

    private boolean missingPk3(int ticks) {
      if (ticks == 0) {
        savedRegions = BuildingService.saved(level);
        List<BuildingRegion> withMissing = new ArrayList<>(savedRegions);
        withMissing.add(
            new BuildingRegion(
                4000,
                content.game(),
                "maps/craftq3_missing.bsp",
                "0".repeat(64),
                4000 * 4096,
                0,
                0,
                32));
        BuildingService.save(level, withMissing);
        service.loadRegions(level);
        return false;
      }
      List<BuildingRegions.Status> statuses = CraftQ3Level.of(level).craftq3$building().statuses();
      boolean loading =
          statuses.stream()
              .anyMatch(
                  s -> s instanceof BuildingRegions.Disabled d && d.reason().startsWith("loading"));
      if (loading) return false;
      BuildingRegions.Status missing =
          statuses.stream().filter(s -> s.region().slot() == 4000).findFirst().orElseThrow();
      check(missing instanceof BuildingRegions.Disabled, "the missing map's region is enabled");
      scenario.note("missing region: " + ((BuildingRegions.Disabled) missing).reason());
      check(
          statuses.stream()
              .anyMatch(
                  s ->
                      s instanceof BuildingRegions.Enabled e && e.region().equals(region.region())),
          "the real region did not load");
      return true;
    }

    /** Removes the water of the fluid step. */
    private void clearWater() {
      if (waterCell == null || level == null) return;
      for (int dx = -6; dx <= 6; dx++)
        for (int dz = -6; dz <= 6; dz++)
          for (int dy = -3; dy <= 0; dy++) {
            BlockPos p = waterCell.offset(dx, dy, dz);
            if (!level.getFluidState(p).isEmpty())
              level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
          }
    }

    private void cleanup() {
      zombies.forEach(TestPlayers::discard);
      TestPlayers.discard(frame);
      TestPlayers.discard(painting);
      TestPlayers.discard(floorFrame);
      if (builder != null) TestPlayers.remove(builder);
      clearWater();
      if (level == testLevel && level != null) {
        level.removeAttached(BuildingService.REGIONS);
        dev.bluevista.craftq3.mc.fabric.level.CraftQ3Level.of(level)
            .craftq3$setBuilding(dev.bluevista.craftq3.mc.fabric.building.LevelBuilding.NONE);
      } else if (savedRegions != null && level != null) {
        BuildingService.save(level, savedRegions);
        service.loadRegions(level);
      }
    }
  }
}
