package dev.bluevista.craftq3.engine.formats.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class EntityParserTest {
  @Test
  void keepsBackslashesCommentsAndStopsAtTheTerminator() {
    List<BspEntity> entities =
        EntityParser.parse("/* hi */ { \"path\" \"a\\b\" } // end\n\0{ garbage after NUL");
    assertEquals(1, entities.size());
    assertEquals("a\\b", entities.getFirst().get("path").orElseThrow());
  }

  @Test
  void keepsDuplicateKeysInOrderAndLooksUpTheFirstIgnoringCase() {
    BspEntity entity =
        EntityParser.parse("{ \"classname\" \"light\" \"Light\" \"300\" \"light\" \"200\" }")
            .getFirst();
    assertEquals(3, entity.size());
    assertEquals(List.of("classname", "Light", "light"), entity.keys());
    assertEquals("300", entity.get("LIGHT").orElseThrow());
    assertEquals("light", entity.classname().orElseThrow());
    assertTrue(entity.get("missing").isEmpty());
  }

  @Test
  void acceptsUnquotedTokensAndUnterminatedTrailingComments() {
    BspEntity entity = EntityParser.parse("{\nclassname worldspawn\n}\n/* trailing").getFirst();
    assertEquals("worldspawn", entity.get("classname").orElseThrow());
    assertTrue(EntityParser.parse("").isEmpty());
    assertTrue(EntityParser.parse("\0{").isEmpty());
  }

  @Test
  void rejectsStructuralDamage() {
    for (String invalid : new String[] {"{", "{ \"x\" }", "{ \"x\" \"unterminated", "x {"})
      assertThrows(EntityFormatException.class, () -> EntityParser.parse(invalid), invalid);
    assertThrows(
        EntityFormatException.class,
        () -> EntityParser.parse(" ".repeat(EntityParser.MAX_TEXT_CHARS + 1)));
  }
}
