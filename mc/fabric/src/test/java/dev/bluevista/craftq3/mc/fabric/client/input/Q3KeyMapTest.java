package dev.bluevista.craftq3.mc.fabric.client.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.*;

import dev.bluevista.craftq3.engine.console.KeyNames;
import dev.bluevista.craftq3.mc.fabric.client.input.Q3KeyMap.Platform;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class Q3KeyMapTest {
  private static final Q3KeyMap MAP = Q3KeyMap.forPlatform(Platform.OTHER);
  private static final int NO_SCANCODE = 0;

  private static int key(int glfwKey) {
    return MAP.key(glfwKey, NO_SCANCODE);
  }

  @Test
  void lettersAreLowerCaseAscii() {
    for (int k = GLFW_KEY_A; k <= GLFW_KEY_Z; k++) assertEquals(Character.toLowerCase(k), key(k));
    assertEquals('w', key(GLFW_KEY_W));
  }

  @Test
  void digitsAndPunctuationAreTheirUnshiftedCharacter() {
    for (int k = GLFW_KEY_0; k <= GLFW_KEY_9; k++) assertEquals(k, key(k));
    assertEquals(' ', key(GLFW_KEY_SPACE));
    assertEquals('\'', key(GLFW_KEY_APOSTROPHE));
    assertEquals(',', key(GLFW_KEY_COMMA));
    assertEquals('-', key(GLFW_KEY_MINUS));
    assertEquals('.', key(GLFW_KEY_PERIOD));
    assertEquals('/', key(GLFW_KEY_SLASH));
    assertEquals(';', key(GLFW_KEY_SEMICOLON));
    assertEquals('=', key(GLFW_KEY_EQUAL));
    assertEquals('[', key(GLFW_KEY_LEFT_BRACKET));
    assertEquals('\\', key(GLFW_KEY_BACKSLASH));
    assertEquals(']', key(GLFW_KEY_RIGHT_BRACKET));
    assertEquals('`', key(GLFW_KEY_GRAVE_ACCENT));
  }

  @Test
  void namedKeys() {
    assertEquals(KeyNames.TAB, key(GLFW_KEY_TAB));
    assertEquals(KeyNames.ENTER, key(GLFW_KEY_ENTER));
    assertEquals(KeyNames.ESCAPE, key(GLFW_KEY_ESCAPE));
    assertEquals(KeyNames.SPACE, key(GLFW_KEY_SPACE));
    assertEquals(KeyNames.BACKSPACE, key(GLFW_KEY_BACKSPACE));
    assertEquals(KeyNames.UPARROW, key(GLFW_KEY_UP));
    assertEquals(KeyNames.DOWNARROW, key(GLFW_KEY_DOWN));
    assertEquals(KeyNames.LEFTARROW, key(GLFW_KEY_LEFT));
    assertEquals(KeyNames.RIGHTARROW, key(GLFW_KEY_RIGHT));
    assertEquals(KeyNames.INS, key(GLFW_KEY_INSERT));
    assertEquals(KeyNames.DEL, key(GLFW_KEY_DELETE));
    assertEquals(KeyNames.PGDN, key(GLFW_KEY_PAGE_DOWN));
    assertEquals(KeyNames.PGUP, key(GLFW_KEY_PAGE_UP));
    assertEquals(KeyNames.HOME, key(GLFW_KEY_HOME));
    assertEquals(KeyNames.END, key(GLFW_KEY_END));
    assertEquals(KeyNames.CAPSLOCK, key(GLFW_KEY_CAPS_LOCK));
    assertEquals(KeyNames.PAUSE, key(GLFW_KEY_PAUSE));
    assertEquals(KeyNames.KP_NUMLOCK, key(GLFW_KEY_NUM_LOCK));
  }

  @Test
  void modifiersShareOneKeyPerSide() {
    assertEquals(KeyNames.SHIFT, key(GLFW_KEY_LEFT_SHIFT));
    assertEquals(KeyNames.SHIFT, key(GLFW_KEY_RIGHT_SHIFT));
    assertEquals(KeyNames.CTRL, key(GLFW_KEY_LEFT_CONTROL));
    assertEquals(KeyNames.CTRL, key(GLFW_KEY_RIGHT_CONTROL));
    assertEquals(KeyNames.ALT, key(GLFW_KEY_LEFT_ALT));
    assertEquals(KeyNames.ALT, key(GLFW_KEY_RIGHT_ALT));
    assertEquals(KeyNames.COMMAND, key(GLFW_KEY_LEFT_SUPER));
    assertEquals(KeyNames.COMMAND, key(GLFW_KEY_RIGHT_SUPER));
  }

  @Test
  void functionKeysF1ToF15() {
    for (int i = 0; i < 15; i++) {
      assertEquals(KeyNames.F1 + i, key(GLFW_KEY_F1 + i));
      assertEquals("F" + (i + 1), KeyNames.toName(key(GLFW_KEY_F1 + i)));
    }
    for (int k = GLFW_KEY_F16; k <= GLFW_KEY_F25; k++) assertEquals(Q3KeyMap.UNMAPPED, key(k));
  }

  @Test
  void keypadByPositionIndependentOfNumLock() {
    int[][] pairs = {
      {GLFW_KEY_KP_0, KeyNames.KP_INS},
      {GLFW_KEY_KP_1, KeyNames.KP_END},
      {GLFW_KEY_KP_2, KeyNames.KP_DOWNARROW},
      {GLFW_KEY_KP_3, KeyNames.KP_PGDN},
      {GLFW_KEY_KP_4, KeyNames.KP_LEFTARROW},
      {GLFW_KEY_KP_5, KeyNames.KP_5},
      {GLFW_KEY_KP_6, KeyNames.KP_RIGHTARROW},
      {GLFW_KEY_KP_7, KeyNames.KP_HOME},
      {GLFW_KEY_KP_8, KeyNames.KP_UPARROW},
      {GLFW_KEY_KP_9, KeyNames.KP_PGUP},
      {GLFW_KEY_KP_DECIMAL, KeyNames.KP_DEL},
      {GLFW_KEY_KP_DIVIDE, KeyNames.KP_SLASH},
      {GLFW_KEY_KP_MULTIPLY, KeyNames.KP_STAR},
      {GLFW_KEY_KP_SUBTRACT, KeyNames.KP_MINUS},
      {GLFW_KEY_KP_ADD, KeyNames.KP_PLUS},
      {GLFW_KEY_KP_ENTER, KeyNames.KP_ENTER},
      {GLFW_KEY_KP_EQUAL, KeyNames.KP_EQUALS},
    };
    for (int[] pair : pairs) assertEquals(pair[1], key(pair[0]), "GLFW key " + pair[0]);
    assertEquals("KP_ENTER", KeyNames.toName(key(GLFW_KEY_KP_ENTER)));
  }

  @Test
  void keysQuakeHasNoNumberForAreUnmapped() {
    for (int k :
        new int[] {
          GLFW_KEY_UNKNOWN,
          GLFW_KEY_SCROLL_LOCK,
          GLFW_KEY_PRINT_SCREEN,
          GLFW_KEY_MENU,
          GLFW_KEY_WORLD_1,
          GLFW_KEY_WORLD_2,
          GLFW_KEY_LAST + 1
        }) assertEquals(Q3KeyMap.UNMAPPED, key(k), "GLFW key " + k);
  }

  @Test
  void everyMappedKeyHasAConsoleNameAndIsInRange() {
    Set<Integer> seen = new HashSet<>();
    for (int k = 0; k <= GLFW_KEY_LAST; k++) {
      int q3 = key(k);
      if (q3 == Q3KeyMap.UNMAPPED) continue;
      assertTrue(q3 > 0 && q3 < KeyNames.MAX_KEYS, "GLFW key " + k + " -> " + q3);
      assertEquals(q3, KeyNames.toKey(KeyNames.toName(q3)), "round trip of " + q3);
      seen.add(q3);
    }
    // 26 letters, 10 digits, 12 punctuation keys incl. space, 17 named, 15 F keys, 17 keypad,
    // 4 modifiers.
    assertEquals(26 + 10 + 12 + 17 + 15 + 17 + 4, seen.size());
  }

  @Test
  void mouseButtonsAndWheel() {
    assertEquals(KeyNames.MOUSE1, Q3KeyMap.mouseButton(GLFW_MOUSE_BUTTON_LEFT));
    assertEquals(KeyNames.MOUSE2, Q3KeyMap.mouseButton(GLFW_MOUSE_BUTTON_RIGHT));
    assertEquals(KeyNames.MOUSE3, Q3KeyMap.mouseButton(GLFW_MOUSE_BUTTON_MIDDLE));
    assertEquals(KeyNames.MOUSE1 + 3, Q3KeyMap.mouseButton(GLFW_MOUSE_BUTTON_4));
    assertEquals(KeyNames.MOUSE1 + 4, Q3KeyMap.mouseButton(GLFW_MOUSE_BUTTON_5));
    assertEquals("MOUSE5", KeyNames.toName(Q3KeyMap.mouseButton(GLFW_MOUSE_BUTTON_5)));
    assertEquals(Q3KeyMap.UNMAPPED, Q3KeyMap.mouseButton(GLFW_MOUSE_BUTTON_6));
    assertEquals(Q3KeyMap.UNMAPPED, Q3KeyMap.mouseButton(-1));
    assertEquals(KeyNames.MWHEELUP, Q3KeyMap.wheel(true));
    assertEquals(KeyNames.MWHEELDOWN, Q3KeyMap.wheel(false));
  }

  @ParameterizedTest
  @EnumSource(Platform.class)
  void graveAccentIsTheConsoleKeyEverywhere(Platform platform) {
    Q3KeyMap map = Q3KeyMap.forPlatform(platform);
    assertTrue(map.isConsoleKey(GLFW_KEY_GRAVE_ACCENT, 12345));
    assertEquals(Q3KeyMap.CONSOLE, map.key(GLFW_KEY_GRAVE_ACCENT, 12345));
  }

  @Test
  void consoleScancodeWhenGlfwHasNoKeyToken() {
    // A Japanese keyboard's hankaku/zenkaku key, or any layout GLFW cannot name there.
    assertEquals('`', Q3KeyMap.forPlatform(Platform.WINDOWS).key(GLFW_KEY_UNKNOWN, 0x29));
    assertEquals('`', Q3KeyMap.forPlatform(Platform.LINUX).key(GLFW_KEY_UNKNOWN, 49));
    assertEquals('`', Q3KeyMap.forPlatform(Platform.MACOS).key(GLFW_KEY_UNKNOWN, 0x32));
    // The ISO section key that sits below Escape on European Mac keyboards.
    assertEquals('`', Q3KeyMap.forPlatform(Platform.MACOS).key(GLFW_KEY_WORLD_1, 0x0A));
    assertEquals(Q3KeyMap.UNMAPPED, Q3KeyMap.forPlatform(Platform.OTHER).key(GLFW_KEY_UNKNOWN, 49));
  }

  @Test
  void scancodesOfOtherPlatformsOrNamedKeysAreNotTheConsole() {
    Q3KeyMap linux = Q3KeyMap.forPlatform(Platform.LINUX);
    assertFalse(linux.isConsoleKey(GLFW_KEY_UNKNOWN, 0x29));
    // A named key at a scancode that happens to collide stays itself.
    assertFalse(linux.isConsoleKey(GLFW_KEY_A, 49));
    assertEquals('a', linux.key(GLFW_KEY_A, 49));
    assertFalse(Q3KeyMap.forPlatform(Platform.WINDOWS).isConsoleKey(GLFW_KEY_WORLD_1, 0x56));
  }
}
