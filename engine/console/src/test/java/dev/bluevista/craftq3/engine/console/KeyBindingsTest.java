package dev.bluevista.craftq3.engine.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeyBindingsTest {
  private final CvarTable cvars = new CvarTable(new ThreadOwner("test bindings"));
  private final KeyBindings keys = new KeyBindings(cvars);

  // Ported from legacy EngineServicesTest.
  @Test
  void keyReleaseUsesBindingAtPressAndClearsOnFocusLoss() {
    keys.bind(KeyNames.MOUSE1, "+attack; echo firing");
    assertEquals(List.of("+attack 178 100", "echo firing"), keys.press(KeyNames.MOUSE1, 100));
    assertEquals(List.of(), keys.press(KeyNames.MOUSE1, 110));
    keys.bind(KeyNames.MOUSE1, "+zoom");
    assertEquals(List.of("-attack 178 120"), keys.releaseAll(120));
    assertEquals(List.of(), keys.releaseAll(121));
    assertEquals(List.of("+zoom 178 130"), keys.press(KeyNames.MOUSE1, 130));
  }

  // Ported from legacy EngineServicesTest.
  @Test
  void quotedButtonCommandReleasesWithArgumentsIntact() {
    keys.bind(1, "  \"+attack\" \"left; right\"; +zoom");
    assertEquals(List.of("\"+attack\" \"left; right\" 1 10", "+zoom 1 10"), keys.press(1, 10));
    assertEquals(List.of("\"-attack\" \"left; right\" 1 20", "-zoom 1 20"), keys.release(1, 20));
  }

  @Test
  void unbindingKeepsHeldReleasesAndMarksArchiveModified() {
    keys.bind('w', "+forward");
    assertTrue(cvars.takeModified(CvarFlags.ARCHIVE));
    keys.press('w', 5);
    keys.unbindAll();
    assertTrue(cvars.takeModified(CvarFlags.ARCHIVE));
    assertFalse(keys.isBound('w'));
    assertEquals("", keys.binding('w'));
    assertEquals(List.of("-forward 119 9"), keys.release('w', 9));
    assertEquals(List.of(), keys.press('w', 10));
    assertThrows(IllegalArgumentException.class, () -> keys.bind(256, "x"));
    assertThrows(IllegalArgumentException.class, () -> keys.binding(-1));
  }

  @Test
  void keyForFindsTheLowestKeyIgnoringCase() {
    keys.bind('x', "+Scores");
    keys.bind(KeyNames.TAB, "+scores");
    assertEquals(KeyNames.TAB, keys.keyFor("+SCORES"));
    assertEquals(-1, keys.keyFor("+zoom"));
  }

  @Test
  void keyNamesRoundTripLikeTheOriginalTable() {
    assertEquals(KeyNames.MOUSE1, KeyNames.toKey("MOUSE1"));
    assertEquals(KeyNames.MOUSE1, KeyNames.toKey("mouse1"));
    assertEquals('w', KeyNames.toKey("W"));
    assertEquals(';', KeyNames.toKey("SEMICOLON"));
    assertEquals(0x95, KeyNames.toKey("0x95"));
    assertEquals(-1, KeyNames.toKey("NOPE"));
    assertEquals(-1, KeyNames.toKey(""));
    assertEquals("TAB", KeyNames.toName(9));
    assertEquals("SPACE", KeyNames.toName(32));
    assertEquals("SEMICOLON", KeyNames.toName(';'));
    assertEquals("0x22", KeyNames.toName('"'));
    assertEquals("\\", KeyNames.toName('\\'));
    assertEquals("F15", KeyNames.toName(KeyNames.F1 + 14));
    assertEquals("KP_EQUALS", KeyNames.toName(KeyNames.KP_EQUALS));
    assertEquals("MWHEELUP", KeyNames.toName(KeyNames.MWHEELUP));
    assertEquals("JOY32", KeyNames.toName(KeyNames.JOY1 + 31));
    assertEquals("AUX16", KeyNames.toName(KeyNames.AUX1 + 15));
    assertEquals("0x82", KeyNames.toName(KeyNames.POWER));
    assertEquals("0x00", KeyNames.toName(0));
    assertEquals("<KEY NOT FOUND>", KeyNames.toName(-1));
    assertEquals("<OUT OF RANGE>", KeyNames.toName(256));
    // Upper-case letter keys are never generated; their names read back as the lower-case key.
    for (int key = 0; key < KeyNames.MAX_KEYS; key++) {
      int expected = key >= 'A' && key <= 'Z' ? key + ('a' - 'A') : key;
      assertEquals(expected, KeyNames.toKey(KeyNames.toName(key)), KeyNames.toName(key));
    }
  }
}
