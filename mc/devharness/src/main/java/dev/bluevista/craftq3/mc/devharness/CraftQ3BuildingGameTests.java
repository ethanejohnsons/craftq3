package dev.bluevista.craftq3.mc.devharness;

import static dev.bluevista.craftq3.mc.devharness.Scenario.check;

import dev.bluevista.craftq3.mc.fabric.CraftQ3Mod;
import dev.bluevista.craftq3.mc.fabric.building.BuildingDimension;
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
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
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

    private void cleanup() {
      zombies.forEach(TestPlayers::discard);
      if (waterCell != null && level != null)
        for (int dx = -6; dx <= 6; dx++)
          for (int dz = -6; dz <= 6; dz++)
            for (int dy = -3; dy <= 0; dy++) {
              BlockPos p = waterCell.offset(dx, dy, dz);
              if (!level.getFluidState(p).isEmpty())
                level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
            }
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
