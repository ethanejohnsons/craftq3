package dev.bluevista.craftq3.server;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.cvar.InfoString;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExternalActorNameTest {
  @Test
  void ordinaryAndLatinOneLabelsRemainReadable() {
    assertEquals("Iron Golem", ExternalActorName.clean("Iron Golem"));
    assertEquals("René's guardian", ExternalActorName.clean("René's guardian"));
    assertEquals("Tall mob", ExternalActorName.clean("  Tall\t\r\nmob  "));
  }

  @Test
  void labelsCannotInjectInfoFieldsColorsOrCommands() {
    var name = ExternalActorName.clean("A\\team\\red;\"^1\nB");
    assertEquals("A?team?red???1 B", name);
    assertEquals(
        Map.of("name", name),
        InfoString.parse(InfoString.encode(Map.of("name", name), 1024), 1024));
  }

  @Test
  void unsupportedUnicodeIsReplacedByCodePointAndLabelsAreBounded() {
    assertEquals("Mob ? ?", ExternalActorName.clean("Mob 🐉 龍"));
    assertEquals("a".repeat(34), ExternalActorName.clean("a".repeat(100000)));
    assertEquals("Minecraft mob", ExternalActorName.clean("\u0000\n\t"));
    assertEquals("Minecraft mob", ExternalActorName.clean(" ".repeat(100000) + "ignored"));
  }
}
