package dev.bluevista.craftq3.mc.hybrid.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.collision.Contents;
import dev.bluevista.craftq3.engine.formats.entity.BspEntity;
import dev.bluevista.craftq3.mc.hybrid.CoordinateTransform;
import dev.bluevista.craftq3.mc.hybrid.map.SyntheticBsp;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

/**
 * A synthetic map placed with its origin at Minecraft (0, 64, 0), 32 units per block. Quake x is
 * Minecraft x, Quake z is Minecraft y - 64, Quake y is -Minecraft z.
 */
class BuildingQueriesTest {
  static final String SOLID = "textures/base/solid";
  static final CoordinateTransform T = CoordinateTransform.at(0, 64, 0);

  static BuildingQueries map() {
    SyntheticBsp bsp =
        new SyntheticBsp()
            .entity(BspEntity.of("classname", "worldspawn"))
            // Floor: Minecraft y 62..64, x -8..8, z -8..8.
            .worldBox(v(-256, -256, -64), v(256, 256, 0), SOLID, Contents.SOLID, 0)
            // North wall on the floor: Minecraft z -9..-8, y 64..68.
            .worldBox(v(-256, 256, 0), v(256, 288, 128), SOLID, Contents.SOLID, 0)
            // A ledge half a block off the grid: top at Minecraft y 64.5, x 10..13.
            .worldBox(v(320, -64, -64), v(416, 64, 16), SOLID, Contents.SOLID, 0)
            // Two half-block brushes jointly covering cell x=16 (top at y 64).
            .worldBox(v(512, -32, -32), v(528, 0, 0), SOLID, Contents.SOLID, 0)
            .worldBox(v(528, -32, -32), v(544, 0, 0), SOLID, Contents.SOLID, 0)
            // Cell x=19 split by a 2-unit gap through its middle.
            .worldBox(v(608, -32, -32), v(623, 0, 0), SOLID, Contents.SOLID, 0)
            .worldBox(v(625, -32, -32), v(640, 0, 0), SOLID, Contents.SOLID, 0)
            // Cell x=22 half covered (x 704..720).
            .worldBox(v(704, -32, -32), v(720, 0, 0), SOLID, Contents.SOLID, 0)
            // Cell x=27 with a hole (local 12..20 units on both axes) at its centre.
            .worldBox(v(864, -32, -32), v(876, 0, 0), SOLID, Contents.SOLID, 0)
            .worldBox(v(884, -32, -32), v(896, 0, 0), SOLID, Contents.SOLID, 0)
            .worldBox(v(876, -32, -32), v(884, -20, 0), SOLID, Contents.SOLID, 0)
            .worldBox(v(876, -12, -32), v(884, 0, 0), SOLID, Contents.SOLID, 0)
            // Player clip: Minecraft x 25..26, y 64..66; blocks movement, supports nothing.
            .worldBox(
                v(800, -32, -32), v(832, 0, 64), "textures/common/clip", Contents.PLAYERCLIP, 0)
            // A 45 degree ramp rising toward +x from x=30 (Quake 960) to x=34.
            .worldBrush(
                SOLID,
                Contents.SOLID,
                0,
                new float[] {-1, 0, 0, -960},
                new float[] {1, 0, 0, 1088},
                new float[] {0, -1, 0, 64},
                new float[] {0, 1, 0, 64},
                new float[] {0, 0, -1, 64},
                new float[] {-0.70710677f, 0, 0.70710677f, -678.8225f});
    return BuildingQueries.build(bsp.build("building.bsp"), T);
  }

  private static float[] v(float x, float y, float z) {
    return new float[] {x, y, z};
  }

  private final BuildingQueries q = map();

  @Test
  void gridAlignedFloorsSupportTheBlocksAboveThem() {
    // The floor's top is the UP face of cells at y 63.
    for (int x = -8; x < 8; x++)
      for (int z = -7; z < 8; z++)
        for (SupportShape shape : SupportShape.values())
          assertTrue(q.supportsFace(x, 63, z, BlockFace.UP, shape), x + "," + z + " " + shape);
    // Outside the floor, and on the wrong side, nothing.
    assertFalse(q.supportsFace(8, 63, 0, BlockFace.UP, SupportShape.FULL));
    assertFalse(q.supportsFace(0, 64, 0, BlockFace.UP, SupportShape.FULL));
    assertFalse(q.supportsFace(0, 63, 0, BlockFace.DOWN, SupportShape.FULL));
    // The floor's bottom (y 62) supports blocks hanging below it.
    assertTrue(q.supportsFace(0, 62, 0, BlockFace.DOWN, SupportShape.FULL));
    // The wall's south face (Minecraft z = -8) supports cells z = -9 on SOUTH, y 64..67.
    for (int y = 64; y < 68; y++)
      assertTrue(q.supportsFace(0, y, -9, BlockFace.SOUTH, SupportShape.FULL), "wall y " + y);
    assertFalse(q.supportsFace(0, 68, -9, BlockFace.SOUTH, SupportShape.FULL));
    assertTrue(q.statistics().fullySupportedFaces() > 300);
  }

  @Test
  void offGridFacesNeverSupport() {
    // The ledge top is at y 64.5: neither cell 63 nor 64 is supported by it.
    for (int x = 10; x < 13; x++) {
      assertFalse(q.supportsFace(x, 64, 0, BlockFace.UP, SupportShape.FULL));
      assertFalse(q.touchesFace(x, 64, 0, BlockFace.UP));
    }
  }

  @Test
  void adjoiningBrushesSupportJointlyAndHolesStayHoles() {
    assertTrue(q.supportsFace(16, 63, 0, BlockFace.UP, SupportShape.FULL));
    // Cell 19: a 2-unit (1/16 block) slot through the middle.
    assertFalse(q.supportsFace(19, 63, 0, BlockFace.UP, SupportShape.FULL));
    assertFalse(q.supportsFace(19, 63, 0, BlockFace.UP, SupportShape.CENTER));
    // The slot crosses the frame's north and south bars as well.
    assertFalse(q.supportsFace(19, 63, 0, BlockFace.UP, SupportShape.RIGID));
    // Cell 27: a hole at the centre, framed by four brushes.
    assertFalse(q.supportsFace(27, 63, 0, BlockFace.UP, SupportShape.FULL));
    assertFalse(q.supportsFace(27, 63, 0, BlockFace.UP, SupportShape.CENTER));
    assertTrue(q.supportsFace(27, 63, 0, BlockFace.UP, SupportShape.RIGID));
    assertTrue(q.touchesFace(19, 63, 0, BlockFace.UP));
    // Cell 22: half covered.
    assertFalse(q.supportsFace(22, 63, 0, BlockFace.UP, SupportShape.FULL));
    assertTrue(q.touchesFace(22, 63, 0, BlockFace.UP));
  }

  @Test
  void hostFaceShapesCombineWithBspFaces() {
    // Cell 22's BSP face covers u (x) 0..0.5; a host shape covering 0.5..1 completes it.
    double[] otherHalf = {0.5, 0, 1, 1};
    assertTrue(q.supportsFace(22, 63, 0, BlockFace.UP, SupportShape.FULL, otherHalf, 1));
    double[] tooLittle = {0.6, 0, 1, 1};
    assertFalse(q.supportsFace(22, 63, 0, BlockFace.UP, SupportShape.FULL, tooLittle, 1));
    // The slot in cell 19 can be filled by the block's own shape.
    double[] slot = {0.4, 0, 0.6, 1};
    assertTrue(q.supportsFace(19, 63, 0, BlockFace.UP, SupportShape.FULL, slot, 1));
    // A host shape alone covers where the BSP is absent.
    double[] whole = {0, 0, 1, 1};
    assertTrue(q.supportsFace(40, 63, 0, BlockFace.UP, SupportShape.CENTER, whole, 1));
    assertFalse(q.supportsFace(40, 63, 0, BlockFace.UP, SupportShape.CENTER, whole, 0));
  }

  @Test
  void playerClipBlocksMovementButNotBlocks() {
    assertFalse(q.boxClear(25.1, 64.1, 0.1, 25.9, 65.9, 0.9, BuildingQueries.MOVEMENT_MASK));
    assertTrue(q.boxClear(25.1, 64.1, 0.1, 25.9, 65.9, 0.9, BuildingQueries.SOLID_MASK));
    assertTrue(q.cellClear(25, 64, 0));
    assertFalse(q.supportsFace(25, 65, 0, BlockFace.UP, SupportShape.FULL));
    assertEquals(Contents.PLAYERCLIP, q.pointContents(25.5, 65, 0.5));
  }

  @Test
  void occupancyOfCells() {
    assertFalse(q.cellClear(0, 63, 0));
    assertTrue(q.cellClear(0, 64, 0));
    assertFalse(q.cellClear(0, 65, -9));
    // The cell under the off-grid ledge top is partly solid.
    assertFalse(q.cellClear(11, 64, 0));
    assertTrue(q.cellClear(11, 65, 0));
    assertEquals(Contents.SOLID, q.pointContents(0.5, 63.5, 0.5));
  }

  @Test
  void occlusionForSightExplosionsAndFluids() {
    // Through the wall (z -9..-8) at head height.
    assertTrue(q.occludes(0.5, 65.5, -7.5, 0.5, 65.5, -10.5));
    // Over it.
    assertFalse(q.occludes(0.5, 69.5, -7.5, 0.5, 69.5, -10.5));
    // From inside the floor.
    assertTrue(q.occludes(0.5, 63.5, 0.5, 0.5, 70, 0.5));
    // Fluids cannot enter the wall or pass it.
    assertFalse(q.fluidCanFlow(0, 64, -8, 0, 64, -9));
    assertTrue(q.fluidCanFlow(0, 64, 0, 1, 64, 0));
    assertFalse(q.fluidCanFlow(0, 64, 0, 0, 63, 0));
    assertEquals(0.5, q.rayFraction(0.5, 66, 0.5, 0.5, 62, 0.5), 1e-3);
  }

  @Test
  void floorsAndSteps() {
    // Standing height on the floor, a surface epsilon above y 64.
    assertEquals(64, q.floorBelow(0.5, 65, 0.5, 3), 0.01);
    assertTrue(Double.isNaN(q.floorBelow(0.5, 70, 0.5, 3)));
    // The 45 degree ramp counts as a floor (normal z 0.707).
    double ramp = q.floorBelow(32.5, 70, 0.5, 10);
    assertEquals(64 + 2.5, ramp, 0.01);
    // Stepping onto the half-block ledge from the floor beside it.
    double step = q.stepSurface(9.7, 64.004, -0.3, 10.3, 0.3, 0.6);
    assertEquals(64.5, step, 0.01);
    assertTrue(Double.isNaN(q.stepSurface(9.7, 64.004, -0.3, 10.3, 0.3, 0.4)));
  }

  @Test
  void entityMovementStopsAtSurfaces() {
    // A 0.6 x 1.8 body walking north into the wall at z = -8.
    double moved =
        q.clipAxis(-0.3, 64.01, -7.3, 0.3, 65.81, -6.7, 2, -2, BuildingQueries.MOVEMENT_MASK);
    assertEquals(-0.7, moved, 0.01);
    // Falling onto the floor.
    double fall = q.clipAxis(-0.3, 66, -0.3, 0.3, 67.8, 0.3, 1, -5, BuildingQueries.MOVEMENT_MASK);
    assertEquals(-2, fall, 0.01);
    assertTrue(q.pathClear(-0.3, 64.01, -0.3, 0.3, 65.81, 0.3, 3, 0, 0));
    assertFalse(q.pathClear(-0.3, 64.01, -6.3, 0.3, 65.81, -5.7, 0, 0, -3));
    // Into the player clip.
    assertFalse(q.pathClear(23.3, 64.01, 0.1, 23.9, 65.81, 0.7, 3, 0, 0));
  }

  @Test
  void pickingFindsTheFreeCellOutsideTheSurface() {
    BuildingQueries.Placement floor =
        q.pick(0.5, 70, 0.5, 0.5, 60, 0.5, BuildingQueries.MOVEMENT_MASK);
    assertNotNull(floor);
    assertEquals(BlockFace.UP, floor.face());
    assertEquals(64, floor.cellY());
    assertEquals(64, floor.y(), 0.01);
    BuildingQueries.Placement ledge =
        q.pick(11.5, 70, 0.5, 11.5, 60, 0.5, BuildingQueries.MOVEMENT_MASK);
    assertNotNull(ledge);
    assertEquals(65, ledge.cellY());
    BuildingQueries.Placement wall =
        q.pick(0.5, 65.5, -2, 0.5, 65.5, -12, BuildingQueries.MOVEMENT_MASK);
    assertNotNull(wall);
    assertEquals(BlockFace.SOUTH, wall.face());
    assertEquals(-8, wall.cellZ());
    assertNull(q.pick(0.5, 70, 0.5, 0.5, 80, 0.5, BuildingQueries.MOVEMENT_MASK));
  }

  @Test
  void queriesDoNotAllocate() {
    Runnable batch =
        () -> {
          for (int i = 0; i < 200; i++) {
            q.cellClear(i % 20 - 10, 63 + i % 3, i % 7 - 3);
            q.occludes(0.5, 65.5, -7.5 + i * 0.001, 0.5, 65.5, -10.5);
            q.supportsFace(i % 16 - 8, 63, 0, BlockFace.UP, SupportShape.CENTER);
            q.floorBelow(0.5, 65, 0.5, 3);
            q.clipAxis(-0.3, 64.01, -7.3, 0.3, 65.81, -6.7, 2, -2, BuildingQueries.MOVEMENT_MASK);
          }
        };
    for (int i = 0; i < 50; i++) batch.run();
    var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    long thread = Thread.currentThread().threadId();
    long before = threads.getThreadAllocatedBytes(thread);
    for (int i = 0; i < 20; i++) batch.run();
    long allocated = threads.getThreadAllocatedBytes(thread) - before;
    assertTrue(allocated < 4096, "allocated " + allocated);
  }
}
