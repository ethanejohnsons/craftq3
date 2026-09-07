package dev.bluevista.craftq3.fabric.building;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspFixture;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.collision.BspTraceWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.platform.CoordinateTransform;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class BuildingSupportTest {
  @BeforeAll
  static void minecraft() {
    net.minecraft.SharedConstants.tryDetectVersion();
    net.minecraft.server.Bootstrap.bootStrap();
  }

  private BuildingSupport support(double originY) throws Exception {
    return new BuildingSupport(
        new BspTraceWorld(BspReader.read(BspFixture.map(false))),
        new CoordinateTransform(32, new Vec3(0, originY, 0)));
  }

  @Test
  void projectedRequirementsAgreeWithNativeSupportTypesOnAllFacesOfRealBlockShapes()
      throws Exception {
    var support = support(0);
    var pos = new BlockPos(20, 20, 20); // None of these faces coincide with the fixture BSP floor.
    var states =
        List.of(
            Blocks.AIR.defaultBlockState(),
            Blocks.STONE.defaultBlockState(),
            Blocks.STONE_SLAB.defaultBlockState(),
            Blocks.STONE_SLAB
                .defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP),
            Blocks.STONE_SLAB
                .defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE),
            Blocks.OAK_FENCE.defaultBlockState(),
            Blocks.COBBLESTONE_WALL.defaultBlockState(),
            Blocks.GLASS_PANE.defaultBlockState(),
            Blocks.HOPPER.defaultBlockState(),
            Blocks.CAULDRON.defaultBlockState());
    for (var state : states)
      for (var face : Direction.values())
        for (var type : SupportType.values())
          assertEquals(
              type.isSupporting(state, null, pos, face),
              support.supports(state, null, pos, face, type),
              state + " " + face + " " + type);
  }

  @Test
  void bspFloorSuppliesAllNativeSupportTypesWithoutInventingGridGapSupport() throws Exception {
    for (var type : SupportType.values()) {
      assertTrue(
          support(0)
              .supports(
                  Blocks.AIR.defaultBlockState(),
                  null,
                  new BlockPos(0, -1, 0),
                  Direction.UP,
                  type));
      assertFalse(
          support(.5)
              .supports(
                  Blocks.AIR.defaultBlockState(), null, new BlockPos(0, 0, 0), Direction.UP, type));
    }
  }
}
