package dev.bluevista.craftq3.engine.common.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogTest {
  @Test
  void warnOnceDeduplicatesPerLoggerAndKey() {
    List<String> lines = new ArrayList<>();
    Log.Sink previous =
        Log.install((name, level, message, error) -> lines.add(level + " " + message));
    try {
      Log.resetOnce();
      Log log = Log.get("test");
      assertTrue(log.warnOnce("qagame:43", "unsupported trap 43"));
      assertFalse(log.warnOnce("qagame:43", "unsupported trap 43"));
      assertTrue(Log.get("other").warnOnce("qagame:43", "again"));
      assertEquals(List.of("WARN unsupported trap 43", "WARN again"), lines);
    } finally {
      Log.install(previous);
      Log.resetOnce();
    }
  }
}
