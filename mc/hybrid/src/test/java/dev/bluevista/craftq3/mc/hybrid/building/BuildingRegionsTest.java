package dev.bluevista.craftq3.mc.hybrid.building;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.collision.Contents;
import dev.bluevista.craftq3.engine.formats.entity.BspEntity;
import dev.bluevista.craftq3.mc.hybrid.map.SyntheticBsp;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BuildingRegionsTest {
  static byte[] floorMap(float size) {
    return new SyntheticBsp()
        .entity(BspEntity.of("classname", "worldspawn"))
        .worldBox(
            new float[] {-size, -size, -64},
            new float[] {size, size, 0},
            "textures/base/floor",
            Contents.SOLID,
            0)
        .write();
  }

  private static final String SHA = "ab".repeat(32);

  @Test
  void codecRoundTripsAndIsVersioned() throws IOException {
    List<BuildingRegion> regions =
        List.of(
            new BuildingRegion(0, "baseq3", "maps/q3dm17.bsp", SHA, 0, 66, 0, 32),
            new BuildingRegion(3, "cpma", "maps/cpm 22.bsp", SHA, 12288, -10, 0, 32));
    byte[] bytes = BuildingRegionCodec.encode(regions);
    assertEquals(regions, BuildingRegionCodec.decode(bytes));
    String text = new String(bytes, StandardCharsets.UTF_8);
    assertTrue(text.contains("craftq3.building=1"));
    assertTrue(text.contains("region.3.origin=12288 -10 0"));
    assertEquals(List.of(), BuildingRegionCodec.decode(new byte[0]));
    // Unknown keys from a later minor revision are ignored.
    String extended = text + "region.0.note=hello\nfuture.key=1\n";
    assertEquals(regions, BuildingRegionCodec.decode(extended.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void codecRejectsBadFilesWhole() {
    String good =
        new String(
            BuildingRegionCodec.encode(
                List.of(new BuildingRegion(0, "baseq3", "maps/q3dm17.bsp", SHA, 0, 66, 0, 32))),
            StandardCharsets.UTF_8);
    for (String bad :
        List.of(
            good.replace("craftq3.building=1", "craftq3.building=2"),
            good.replace("craftq3.building=1", ""),
            good.replace("sha256=" + SHA, "sha256=xyz"),
            good.replace("origin=0 66 0", "origin=0 66"),
            good.replace("map=maps/q3dm17.bsp", "map=../../etc/passwd"),
            good.replace("scale=32", ""),
            good + "region.x.game=baseq3\n"))
      assertThrows(
          IOException.class,
          () -> BuildingRegionCodec.decode(bad.getBytes(StandardCharsets.UTF_8)),
          bad);
    assertThrows(
        IOException.class,
        () -> BuildingRegionCodec.decode(new byte[BuildingRegionCodec.MAX_BYTES + 1]));
  }

  @Test
  void placingReusesTheSameBspAndAllocatesNewSlots() {
    byte[] small = floorMap(512);
    BuildingRegion first =
        BuildingRegions.place(
            List.of(), "baseq3", "maps/a.bsp", small, BuildingRegions.Layout.DEFAULT);
    assertEquals(0, first.slot());
    assertEquals(0, first.originX());
    // The floor's bottom (Quake z -64, two blocks) lands two blocks above the dimension floor.
    assertEquals(-64 + 2 + 2, first.originY());
    assertSame(
        first,
        BuildingRegions.place(
            List.of(first), "baseq3", "maps/a.bsp", small, BuildingRegions.Layout.DEFAULT));
    byte[] changed = floorMap(640);
    BuildingRegion second =
        BuildingRegions.place(
            List.of(first), "baseq3", "maps/a.bsp", changed, BuildingRegions.Layout.DEFAULT);
    assertEquals(1, second.slot());
    assertEquals(4096, second.originX());
    assertEquals(1, BuildingRegions.Layout.DEFAULT.slotAt(4096 + 1500));
    assertEquals(0, BuildingRegions.Layout.DEFAULT.slotAt(-2000));
    assertEquals(-1, BuildingRegions.Layout.DEFAULT.slotAt(-3000));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BuildingRegions.place(
                List.of(),
                "baseq3",
                "maps/huge.bsp",
                floorMap(66000),
                BuildingRegions.Layout.DEFAULT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BuildingRegions.place(
                List.of(), "baseq3", "maps/x.bsp", new byte[10], BuildingRegions.Layout.DEFAULT));
  }

  @Test
  void missingOrChangedMapsDisableRegionsWithoutThrowing() {
    byte[] bsp = floorMap(512);
    BuildingRegion region =
        BuildingRegions.place(
            List.of(), "baseq3", "maps/a.bsp", bsp, BuildingRegions.Layout.DEFAULT);
    Map<String, byte[]> files = Map.of("baseq3/maps/a.bsp", bsp);
    BuildingRegions.MapFiles present =
        (game, map) -> Optional.ofNullable(files.get(game + "/" + map));
    BuildingRegions.Status ok = BuildingRegions.load(region, present);
    BuildingRegions.Enabled enabled = assertInstanceOf(BuildingRegions.Enabled.class, ok);
    // The region's floor top is at Quake z 0, Minecraft y originY.
    assertTrue(
        enabled
            .queries()
            .supportsFace(0, region.originY() - 1, 0, BlockFace.UP, SupportShape.FULL));

    BuildingRegions.Disabled missing =
        assertInstanceOf(
            BuildingRegions.Disabled.class,
            BuildingRegions.load(region, (game, map) -> Optional.empty()));
    assertTrue(missing.reason().contains("missing PK3"), missing.reason());

    BuildingRegions.Disabled changed =
        assertInstanceOf(
            BuildingRegions.Disabled.class,
            BuildingRegions.load(region, (game, map) -> Optional.of(floorMap(256))));
    assertTrue(changed.reason().contains("differs"), changed.reason());

    BuildingRegions.Disabled unreadable =
        assertInstanceOf(
            BuildingRegions.Disabled.class,
            BuildingRegions.load(
                region,
                (game, map) -> {
                  throw new IOException("disk on fire");
                }));
    assertTrue(unreadable.reason().contains("disk on fire"));

    byte[] garbage = "not a bsp".getBytes(StandardCharsets.US_ASCII);
    BuildingRegion garbageRegion =
        new BuildingRegion(
            1, "baseq3", "maps/g.bsp", BuildingRegions.sha256(garbage), 4096, 0, 0, 32);
    BuildingRegions.Disabled malformed =
        assertInstanceOf(
            BuildingRegions.Disabled.class,
            BuildingRegions.load(garbageRegion, (game, map) -> Optional.of(garbage)));
    assertTrue(malformed.reason().startsWith("Cannot load"), malformed.reason());

    List<BuildingRegions.Status> all =
        BuildingRegions.loadAll(List.of(region, garbageRegion), present);
    assertInstanceOf(BuildingRegions.Enabled.class, all.get(0));
    assertInstanceOf(BuildingRegions.Disabled.class, all.get(1));
  }
}
