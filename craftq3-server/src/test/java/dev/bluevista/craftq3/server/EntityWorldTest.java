package dev.bluevista.craftq3.server;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspFixture;
import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class EntityWorldTest {
  private static final int BASE = 1024, STRIDE = 1024;
  private static final Vec3 ZERO = new Vec3(0, 0, 0);

  @Test
  void linksBothGuestLayoutsAndSkipsOnlyActualSharedOwners() throws Exception {
    var map = BspReader.read(BspFixture.map(false));
    for (GameAbi abi : GameAbi.values()) {
      var memory = GameAbiTest.memory();
      var world = new EntityWorld(map, memory, abi);
      world.locate(BASE, 4, STRIDE, 8192, 1024, 4);
      int touched = BASE + STRIDE;
      VmAbi.vector(memory, touched + abi.mins(), new Vec3(-1, -1, -1));
      VmAbi.vector(memory, touched + abi.maxs(), new Vec3(1, 1, 1));
      VmAbi.vector(memory, touched + abi.origin(), new Vec3(100, 0, 100));
      memory.writeInt(touched + abi.contents(), 1);
      memory.writeInt(touched + abi.owner(), 2);
      memory.writeInt(BASE + abi.owner(), 2);
      world.link(touched);
      assertEquals(1, memory.readInt(touched + abi.linked()));
      assertEquals(1, memory.readInt(touched + abi.linkCount()));
      assertEquals(new Vec3(98, -2, 98), VmAbi.vector(memory, touched + abi.absmin()));
      var ray = TraceRequest.ray(new Vec3(0, 0, 100), new Vec3(200, 0, 100), 1);
      assertTrue(world.trace(ray).fraction() < 1);
      assertEquals(1, world.trace(ray.ignoring(0)).fraction());
      // ENTITYNUM_NONE does not make unrelated unowned objects share an owner.
      memory.writeInt(touched + abi.owner(), 1023);
      memory.writeInt(BASE + abi.owner(), 1023);
      world.link(touched);
      assertTrue(world.trace(ray.ignoring(0)).fraction() < 1);
      assertEquals(1, world.trace(ray.ignoring(1)).fraction());
      world.unlink(touched);
      assertEquals(0, memory.readInt(touched + abi.linked()));
      assertEquals(1, world.trace(ray).fraction());
    }
  }

  @Test
  void individualBotTraceUsesCurrentShapeWithoutWorldOrOwnerFiltering() throws Exception {
    var map = BspReader.read(BspFixture.map(false));
    for (GameAbi abi : GameAbi.values()) {
      var memory = GameAbiTest.memory();
      var world = new EntityWorld(map, memory, abi);
      world.locate(BASE, 2, STRIDE, 8192, 1024, 2);
      int pointer = BASE + STRIDE;
      VmAbi.vector(memory, pointer + abi.mins(), new Vec3(-1, -1, -1));
      VmAbi.vector(memory, pointer + abi.maxs(), new Vec3(1, 1, 1));
      VmAbi.vector(memory, pointer + abi.origin(), new Vec3(100, 0, 100));
      memory.writeInt(pointer + abi.contents(), 1);
      memory.writeInt(pointer + abi.owner(), 0);
      var ray = TraceRequest.ray(new Vec3(0, 0, 100), new Vec3(200, 0, 100), 1).ignoring(0);
      assertTrue(world.traceEntity(1, ray).fraction() < 1);
      assertTrue(world.traceEntity(1, ray.ignoring(1)).fraction() < 1);
      memory.writeInt(pointer + abi.contents(), 0);
      assertEquals(1, world.traceEntity(1, ray).fraction());
      // A ray inside static world brushes remains clear when this individual entity is disabled.
      assertEquals(
          1,
          world
              .traceEntity(1, TraceRequest.ray(new Vec3(0, 0, -100), new Vec3(10, 0, -100), 1))
              .fraction());
      world.locate(BASE, 1, STRIDE, 8192, 1024, 2);
      assertThrows(IllegalArgumentException.class, () -> world.pointer(1));
      memory.writeInt(pointer + abi.contents(), 1);
      assertTrue(world.traceEntity(1, ray).fraction() < 1);
      // Contents filtering precedes model lookup even in an inactive retained slot.
      memory.writeInt(pointer + abi.bmodel(), 1);
      memory.writeInt(pointer + 160, Integer.MAX_VALUE);
      assertEquals(1, world.traceEntity(1, TraceRequest.ray(ray.start(), ray.end(), 2)).fraction());
      memory.writeInt(pointer + abi.contents(), 0);
      assertEquals(1, world.traceEntity(1, ray).fraction());
      assertThrows(IllegalArgumentException.class, () -> world.traceEntity(-1, ray));
      assertThrows(IllegalArgumentException.class, () -> world.traceEntity(1024, ray));
      assertThrows(IllegalArgumentException.class, () -> world.traceEntity(1023, ray));
    }
  }

  @Test
  void portalAssociationUsesAreaSentinelsIndependentlyOfVisibilityClusters() throws Exception {
    var source = BspReader.read(BspFixture.map(false));
    var touching = new BspMap.Bounds(new Vec3(-5, -5, -5), new Vec3(5, 5, 5));
    var map =
        withLeaves(
            source,
            List.of(
                new BspMap.Leaf(311, -1, touching, 0, 0, 0, 0),
                new BspMap.Leaf(-1, 0, touching, 0, 0, 0, 0),
                new BspMap.Leaf(313, 1, touching, 0, 0, 0, 0),
                new BspMap.Leaf(314, 1, touching, 0, 0, 0, 0)));
    // Native q3tourney4 retains 1/0 despite leaf631's cluster311/area-1; an authored
    // native metadata fixture also associates valid area0 on solid cluster-1 leaf615.
    for (GameAbi abi : GameAbi.values()) {
      var memory = GameAbiTest.memory();
      var world = new EntityWorld(map, memory, abi);
      world.locate(BASE, 1, STRIDE, 8192, 1024, 1);
      VmAbi.vector(memory, BASE + abi.mins(), new Vec3(-1, -1, -1));
      VmAbi.vector(memory, BASE + abi.maxs(), new Vec3(1, 1, 1));
      memory.writeInt(BASE + abi.owner(), 1023);
      world.link(BASE);
      assertEquals(List.of(0, 1), world.areas(BASE));
      var absent =
          new EntityWorld(
              withLeaves(source, List.of(new BspMap.Leaf(311, -1, touching, 0, 0, 0, 0))),
              memory,
              abi);
      absent.locate(BASE, 1, STRIDE, 8192, 1024, 1);
      absent.link(BASE);
      assertEquals(List.of(), absent.areas(BASE));
    }
  }

  private static BspMap withLeaves(BspMap source, List<BspMap.Leaf> leaves) {
    var planes = new ArrayList<>(source.planes());
    int plane = planes.size();
    planes.add(new BspMap.Plane(new Vec3(1, 0, 0), 0));
    var nodes = new ArrayList<BspMap.Node>();
    for (int i = 0; i < leaves.size() - 1; i++)
      nodes.add(
          new BspMap.Node(
              plane,
              ~i,
              i + 2 == leaves.size() ? ~(i + 1) : i + 1,
              source.models().getFirst().bounds()));
    return new BspMap(
        source.entities(),
        source.textures(),
        planes,
        nodes,
        leaves,
        source.leafFaces(),
        source.leafBrushes(),
        source.models(),
        source.brushes(),
        source.brushSides(),
        source.vertices(),
        source.meshVertices(),
        source.effects(),
        source.faces(),
        source.lightmaps(),
        source.lightVolumes(),
        source.visibility());
  }

  @Test
  void areaAssociationUsesOrderedTopologyAndFirstThenLastDifferentAreaWith128LeafCap()
      throws Exception {
    var source = BspReader.read(BspFixture.map(false));
    // Deliberately remote leaf AABBs: membership is established by BSP planes, not these boxes.
    var bounds = new BspMap.Bounds(new Vec3(100, 100, 100), new Vec3(110, 110, 110));
    for (int[] sequence :
        List.of(
            new int[] {1, 2, 3},
            new int[] {1, 2, 1},
            new int[] {1, 2, 3, 2},
            new int[] {0, 1, 0})) {
      var leaves = new ArrayList<BspMap.Leaf>();
      for (int area : sequence) leaves.add(new BspMap.Leaf(-1, area, bounds, 0, 0, 0, 0));
      var memory = GameAbiTest.memory();
      var world = new EntityWorld(withLeaves(source, leaves), memory, GameAbi.Q3_132);
      world.locate(BASE, 1, STRIDE, 8192, 1024, 1);
      world.link(BASE);
      int lastDifferent =
          sequence[sequence.length - 1] == sequence[0]
              ? sequence[sequence.length - 2]
              : sequence[sequence.length - 1];
      assertEquals(List.of(sequence[0], lastDifferent), world.areas(BASE));
    }
    var leaves = new ArrayList<BspMap.Leaf>();
    for (int i = 0; i < 129; i++)
      leaves.add(
          new BspMap.Leaf(i, i == 0 ? 1 : i == 127 ? 2 : i == 128 ? 3 : -1, bounds, 0, 0, 0, 0));
    var memory = GameAbiTest.memory();
    var world = new EntityWorld(withLeaves(source, leaves), memory, GameAbi.Q3_132);
    world.locate(BASE, 1, STRIDE, 8192, 1024, 1);
    world.link(BASE);
    assertEquals(List.of(1, 2), world.areas(BASE));
  }

  @Test
  void cachedAreaPairSurvivesGuestBoundsAndUnlinkButRefreshesOnLinkAndWorldLifecycle()
      throws Exception {
    var source = BspReader.read(BspFixture.map(false));
    var bounds = new BspMap.Bounds(new Vec3(-10, -10, -10), new Vec3(10, 10, 10));
    var map =
        withLeaves(
            source,
            List.of(
                new BspMap.Leaf(0, 0, bounds, 0, 0, 0, 0),
                new BspMap.Leaf(1, 1, bounds, 0, 0, 0, 0)));
    for (GameAbi abi : GameAbi.values()) {
      var memory = GameAbiTest.memory();
      var world = new EntityWorld(map, memory, abi);
      world.locate(BASE, 2, STRIDE, 8192, 1024, 1);
      world.link(BASE);
      world.link(BASE + STRIDE);
      assertEquals(List.of(0, 1), world.areas(BASE));
      VmAbi.vector(memory, BASE + abi.absmin(), new Vec3(100, 100, 100));
      VmAbi.vector(memory, BASE + abi.absmax(), new Vec3(102, 102, 102));
      assertEquals(List.of(0, 1), world.areas(BASE));
      world.unlink(BASE);
      assertEquals(List.of(0, 1), world.areas(BASE));
      VmAbi.vector(memory, BASE + abi.origin(), new Vec3(100, 0, 0));
      world.link(BASE);
      assertEquals(List.of(0), world.areas(BASE));
      world.locate(BASE, 1, STRIDE, 8192, 1024, 1);
      world.locate(BASE, 2, STRIDE, 8192, 1024, 1);
      assertTrue(world.areas(BASE + STRIDE).isEmpty());
      world.unlink(BASE);
      world.locate(BASE + 2 * STRIDE, 1, STRIDE, 8192, 1024, 1);
      assertTrue(world.areas(BASE + 2 * STRIDE).isEmpty());
      world.link(BASE + 2 * STRIDE);
      world.reset();
      world.locate(BASE + 2 * STRIDE, 1, STRIDE, 8192, 1024, 1);
      assertTrue(world.areas(BASE + 2 * STRIDE).isEmpty());
    }
  }

  @Test
  void currentSharedContentsCanDisableALinkedInlineBrush() throws Exception {
    var source = BspReader.read(BspFixture.map(false));
    var map =
        new BspMap(
            source.entities(),
            source.textures(),
            source.planes(),
            source.nodes(),
            source.leaves(),
            source.leafFaces(),
            source.leafBrushes(),
            List.of(source.models().getFirst(), source.models().getFirst()),
            source.brushes(),
            source.brushSides(),
            source.vertices(),
            source.meshVertices(),
            source.effects(),
            source.faces(),
            source.lightmaps(),
            source.lightVolumes(),
            source.visibility());
    var memory = GameAbiTest.memory();
    var abi = GameAbi.RETAIL_1999;
    var world = new EntityWorld(map, memory, abi);
    world.locate(BASE, 1, STRIDE, 8192, 1024, 1);
    world.brushModel(BASE, "*1");
    VmAbi.vector(memory, BASE + abi.origin(), new Vec3(0, 0, 200));
    memory.writeInt(BASE + abi.owner(), 1023);
    world.link(BASE);
    var ray = TraceRequest.ray(new Vec3(0, 0, 100), new Vec3(10, 0, 100), 1);
    assertTrue(world.trace(ray).startSolid());
    memory.writeInt(BASE + abi.contents(), 0);
    assertFalse(world.trace(ray).startSolid());
    assertEquals(1, world.trace(ray).fraction());
  }
}
