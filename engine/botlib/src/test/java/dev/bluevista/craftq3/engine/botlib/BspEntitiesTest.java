package dev.bluevista.craftq3.engine.botlib;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class BspEntitiesTest {
  private static final String LUMP =
      """
      { "classname" "worldspawn" "message" "first" "message" "last" }
      { "classname" "target_position" "Origin" "1 2 3" "origin" "4 5 6" "count" "12abc" }
      """;

  @Test
  void entityNumbersStartAtOneAndTheCursorStopsPastTheEnd() {
    var entities = BspEntities.parse(LUMP);
    assertEquals(2, entities.count());
    assertEquals(1, entities.next(0));
    assertEquals(2, entities.next(1));
    assertEquals(0, entities.next(2));
    assertEquals(0, entities.next(-5));
    assertEquals(Optional.empty(), entities.value(0, "classname"));
    assertEquals(Optional.empty(), entities.value(3, "classname"));
    assertEquals(Optional.of("worldspawn"), entities.value(1, "classname"));
  }

  @Test
  void repeatedKeysKeepTheLastValueAndKeysAreCaseSensitive() {
    var entities = BspEntities.parse(LUMP);
    assertEquals(Optional.of("last"), entities.value(1, "message"));
    assertEquals(Optional.of(new Vec3f(1, 2, 3)), entities.vectorValue(2, "Origin"));
    assertEquals(Optional.of(new Vec3f(4, 5, 6)), entities.vectorValue(2, "origin"));
    assertEquals(Optional.empty(), entities.value(2, "ORIGIN"));
  }

  @Test
  void numbersParseLikeTheCLibrary() {
    assertEquals(Optional.of(12), BspEntities.parse(LUMP).intValue(2, "count"));
    assertEquals(-42, BspEntities.atoi("  -42.9"));
    assertEquals(0, BspEntities.atoi("x1"));
    assertEquals(Integer.MAX_VALUE, BspEntities.atoi("99999999999"));
    assertEquals(Integer.MIN_VALUE, BspEntities.atoi("-99999999999"));
    assertEquals(1.5f, BspEntities.atof(" 1.5e0junk"));
    assertEquals(.25f, BspEntities.atof(".25"));
    assertEquals(0, BspEntities.atof("junk"));
    assertEquals(new Vec3f(1, -2, 0), BspEntities.vector("1 -2 junk 4"));
    assertEquals(Vec3f.ZERO, BspEntities.vector(""));
  }

  @Test
  void malformedLumpsYieldNoEntities() {
    assertEquals(0, BspEntities.parse("{ \"classname\" ").count());
    assertEquals(0, BspEntities.parse("").count());
  }
}
