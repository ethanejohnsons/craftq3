package dev.bluevista.craftq3.mc.hybrid.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.collision.CollisionModel;
import dev.bluevista.craftq3.engine.collision.Contents;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.bsp.BspFixture;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.bsp.BspReader;
import dev.bluevista.craftq3.engine.formats.entity.BspEntity;
import dev.bluevista.craftq3.engine.game.map.GameMap;
import dev.bluevista.craftq3.engine.game.map.MapSource;
import dev.bluevista.craftq3.mc.hybrid.CoordinateTransform;
import dev.bluevista.craftq3.mc.hybrid.terrain.GridCollisionSource;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MapSourcesTest {
  private static GridCollisionSource flat() {
    return new GridCollisionSource(
        (x, y, z, sink) -> {
          if (y < 64) sink.full();
        },
        CoordinateTransform.at(0, 64, 0));
  }

  @Test
  void syntheticBridgeMapHasSpawnTriggersItemsAndTerrain() throws IOException {
    SyntheticMapSource source = new SyntheticMapSource();
    assertFalse(source.exists(SyntheticMapSource.MAP_NAME));
    assertThrows(NoSuchFileException.class, () -> source.load(SyntheticMapSource.MAP_NAME));
    GridCollisionSource terrain = flat();
    source.prepare(
        new SyntheticMapSource.World(
            terrain,
            new Vec3f(10.4f, -20.6f, 30),
            90,
            List.of(
                new SyntheticMapSource.ItemSpawn("weapon_railgun", new Vec3f(64, 0, 40)),
                new SyntheticMapSource.ItemSpawn("item_armor_combat", new Vec3f(-64, 0, 40))),
            "hello \"world\""));
    assertTrue(source.exists("maps/craftq3_bridge.bsp"));
    assertFalse(source.exists("q3dm17"));
    GameMap map = source.load(SyntheticMapSource.MAP_NAME);
    assertEquals(SyntheticMapSource.MAP_NAME, map.name());
    assertSame(terrain, map.sources().getFirst());
    assertTrue(map.aas().isEmpty());
    assertEquals(map.bsp().checksum(), map.checksum());
    List<BspEntity> e = map.bsp().entities();
    assertEquals("worldspawn", e.get(0).classname().orElseThrow());
    assertEquals("hello  world ", e.get(0).get("message").orElseThrow());
    assertEquals("info_player_deathmatch", e.get(1).classname().orElseThrow());
    assertEquals("10 -21 30", e.get(1).get("origin").orElseThrow());
    assertEquals("90", e.get(1).get("angle").orElseThrow());
    assertEquals(2 + HostTriggers.MODELS + 2 + 2, e.size());
    assertEquals("weapon_railgun", e.get(e.size() - 2).classname().orElseThrow());
    assertEquals(SyntheticMapSource.FIRST_HOST_MODEL, HostTriggerMapSource.firstHostModel(map));
    assertEquals(1 + HostTriggers.MODELS, map.bsp().models().size());
    CollisionModel cm = CollisionModel.build(map.bsp());
    assertEquals(Contents.TRIGGER, cm.pointContents(new float[] {0, 0, 0}, 1));
    // No geometry of its own: the world is open until the terrain joins traces.
    assertEquals(0, cm.pointContents(new float[] {0, 0, -100}, 0));
    assertEquals(Contents.SOLID, terrain.pointContents(0, 0, -100));
  }

  @Test
  void worldsRejectBadSpawns() {
    GridCollisionSource terrain = flat();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SyntheticMapSource.World(terrain, new Vec3f(Float.NaN, 0, 0), 0, List.of(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SyntheticMapSource.World(terrain, new Vec3f(40000, 0, 0), 0, List.of(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SyntheticMapSource.ItemSpawn("info_player_start", Vec3f.ZERO));
  }

  @Test
  void hostTriggersJoinRealMapsOnTheServerOnly() throws IOException {
    BspMap fixture = BspReader.read(BspFixture.map(false), "maps/fixture.bsp");
    GameMap plain = GameMap.of("fixture", fixture, Optional.empty());
    MapSource base =
        new MapSource() {
          @Override
          public boolean exists(String name) {
            return name.equals("fixture");
          }

          @Override
          public GameMap load(String name) {
            return plain;
          }
        };
    HostTriggerMapSource source = new HostTriggerMapSource(base);
    assertTrue(source.exists("fixture"));
    assertEquals(-1, HostTriggerMapSource.firstHostModel(plain));
    GameMap map = source.load("fixture");
    int first = HostTriggerMapSource.firstHostModel(map);
    assertEquals(fixture.models().size(), first);
    assertEquals(fixture.models().size() + HostTriggers.MODELS, map.bsp().models().size());
    // Clients keep loading the original file: the published checksum is the original's.
    assertEquals(fixture.checksum(), map.checksum());
    assertTrue(map.entityText().startsWith(fixture.entityText()));
    assertEquals(map.entityText(), map.bsp().entityText());
    // The original world collision is untouched.
    CollisionModel a = CollisionModel.build(fixture);
    CollisionModel b = CollisionModel.build(map.bsp());
    for (float z = -300; z <= 300; z += 25) {
      float[] p = {10, 20, z};
      assertEquals(a.pointContents(p, 0), b.pointContents(p, 0));
    }
    // Loading an already augmented map changes nothing.
    MapSource again =
        new MapSource() {
          @Override
          public boolean exists(String name) {
            return true;
          }

          @Override
          public GameMap load(String name) {
            return map;
          }
        };
    assertSame(map, new HostTriggerMapSource(again).load("fixture"));
  }
}
