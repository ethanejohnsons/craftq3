package dev.bluevista.craftq3.engine.common.info;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InfoStringTest {
  @Test
  void roundTripsInOrder() {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("name", "Sarge");
    values.put("model", "sarge/default");
    String encoded = InfoString.encode(values, 1024);
    assertEquals("\\name\\Sarge\\model\\sarge/default", encoded);
    assertEquals(
        List.copyOf(values.keySet()), List.copyOf(InfoString.parse(encoded, 1024).keySet()));
  }

  @Test
  void dropsEmptyValuesWhenEncoding() {
    assertEquals("\\a\\1", InfoString.encode(new LinkedHashMap<>(Map.of("a", "1", "b", "")), 64));
  }

  @Test
  void rejectsSeparatorsQuotesAndOverlongText() {
    assertThrows(IllegalArgumentException.class, () -> InfoString.check("a;b"));
    assertThrows(IllegalArgumentException.class, () -> InfoString.check("a\"b"));
    assertThrows(IllegalArgumentException.class, () -> InfoString.parse("\\a\\b", 4));
    assertThrows(IllegalArgumentException.class, () -> InfoString.parse("\\a", 64));
  }
}
