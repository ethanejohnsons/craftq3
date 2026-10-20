package dev.bluevista.craftq3.mc.fabric.client.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.*;

import dev.bluevista.craftq3.engine.common.time.ManualClock;
import dev.bluevista.craftq3.engine.console.KeyNames;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class Q3InputCaptureTest {
  /**
   * Records sink calls as compact strings: {@code k<key><+|->@<time>}, {@code c<cp>}, {@code
   * m<dx>,<dy>@<time>}.
   */
  static final class Recorder implements Q3InputSink {
    final List<String> events = new ArrayList<>();

    @Override
    public void key(int q3Key, boolean down, long time) {
      events.add("k" + q3Key + (down ? "+" : "-") + "@" + time);
    }

    @Override
    public void character(int codepoint) {
      events.add("c" + codepoint);
    }

    @Override
    public void mouseMove(double dx, double dy, long time) {
      events.add("m" + dx + "," + dy + "@" + time);
    }

    List<String> take() {
      List<String> copy = List.copyOf(events);
      events.clear();
      return copy;
    }
  }

  static final class FakeHost implements InputHost {
    boolean focused = true;
    boolean grabbed;
    int grabs;
    int releases;

    @Override
    public boolean inputFocused() {
      return focused;
    }

    @Override
    public void grabCursor() {
      grabbed = true;
      grabs++;
    }

    @Override
    public void releaseCursor() {
      grabbed = false;
      releases++;
    }
  }

  private final Recorder sink = new Recorder();
  private final FakeHost host = new FakeHost();
  private final ManualClock clock = new ManualClock(1000);
  private int hatches;
  private Q3InputCapture capture;

  @BeforeEach
  void setUp() {
    capture =
        new Q3InputCapture(
            sink, Q3KeyMap.forPlatform(Q3KeyMap.Platform.LINUX), clock, host, () -> hatches++);
    capture.open();
  }

  @Test
  void keysCarryTimestampsFromTheInjectedClock() {
    capture.keyPressed(GLFW_KEY_W, 25, 0);
    clock.advance(16);
    capture.keyReleased(GLFW_KEY_W, 25, 0);
    assertEquals(List.of("k119+@1000", "k119-@1016"), sink.take());
  }

  @Test
  void autoRepeatSendsFurtherDownEvents() {
    capture.keyPressed(GLFW_KEY_UP, 111, 0);
    clock.advance(500);
    capture.keyPressed(GLFW_KEY_UP, 111, 0);
    capture.keyReleased(GLFW_KEY_UP, 111, 0);
    int up = KeyNames.UPARROW;
    assertEquals(
        List.of("k" + up + "+@1000", "k" + up + "+@1500", "k" + up + "-@1500"), sink.take());
  }

  @Test
  void leftAndRightShiftShareOneQuakeKey() {
    int shift = KeyNames.SHIFT;
    capture.keyPressed(GLFW_KEY_LEFT_SHIFT, 50, GLFW_MOD_SHIFT);
    capture.keyPressed(GLFW_KEY_RIGHT_SHIFT, 62, GLFW_MOD_SHIFT);
    capture.keyReleased(GLFW_KEY_LEFT_SHIFT, 50, GLFW_MOD_SHIFT);
    assertTrue(capture.isDown(shift));
    capture.keyReleased(GLFW_KEY_RIGHT_SHIFT, 62, 0);
    assertFalse(capture.isDown(shift));
    assertEquals(List.of("k" + shift + "+@1000", "k" + shift + "-@1000"), sink.take());
  }

  @Test
  void releaseOfAKeyPressedBeforeTheScreenOpenedIsIgnored() {
    capture.keyReleased(GLFW_KEY_E, 26, 0);
    capture.mouseButton(GLFW_MOUSE_BUTTON_LEFT, false);
    assertEquals(List.of(), sink.take());
  }

  @Test
  void unmappedKeysAreDropped() {
    capture.keyPressed(GLFW_KEY_PRINT_SCREEN, 107, 0);
    capture.keyReleased(GLFW_KEY_PRINT_SCREEN, 107, 0);
    assertEquals(List.of(), sink.take());
  }

  @Test
  void textReachesTheSink() {
    capture.charTyped('h');
    capture.charTyped('é');
    capture.charTyped(0x1F600);
    capture.charTyped(0x7f);
    assertEquals(List.of("c104", "c233", "c128512"), sink.take());
  }

  @Test
  void backspaceAndCtrlLettersSynthesiseControlCharacters() {
    capture.keyPressed(GLFW_KEY_BACKSPACE, 22, 0);
    capture.keyReleased(GLFW_KEY_BACKSPACE, 22, 0);
    capture.keyPressed(GLFW_KEY_V, 55, GLFW_MOD_CONTROL);
    int bs = KeyNames.BACKSPACE;
    assertEquals(
        List.of("k" + bs + "+@1000", "c8", "k" + bs + "-@1000", "k118+@1000", "c22"), sink.take());
  }

  @Test
  void consoleKeyIsBacktickAndItsCharacterIsSwallowed() {
    // German layout: the key below Escape types '^'; GLFW still reports GRAVE_ACCENT.
    capture.keyPressed(GLFW_KEY_GRAVE_ACCENT, 49, 0);
    capture.charTyped('^');
    capture.keyReleased(GLFW_KEY_GRAVE_ACCENT, 49, 0);
    capture.charTyped('x');
    assertEquals(List.of("k96+@1000", "k96-@1000", "c120"), sink.take());
  }

  @Test
  void consoleKeyByScancodeWhenGlfwCannotNameIt() {
    capture.keyPressed(GLFW_KEY_UNKNOWN, 49, 0);
    capture.charTyped(0x534A); // the character an IME layout might type there
    capture.keyReleased(GLFW_KEY_UNKNOWN, 49, 0);
    assertEquals(List.of("k96+@1000", "k96-@1000"), sink.take());
  }

  @Test
  void mouseButtonsAndWheel() {
    capture.mouseButton(GLFW_MOUSE_BUTTON_LEFT, true);
    capture.mouseButton(GLFW_MOUSE_BUTTON_5, true);
    capture.mouseButton(GLFW_MOUSE_BUTTON_8, true);
    capture.mouseButton(GLFW_MOUSE_BUTTON_LEFT, false);
    capture.mouseButton(GLFW_MOUSE_BUTTON_5, false);
    capture.mouseScrolled(2);
    capture.mouseScrolled(-1);
    int m1 = KeyNames.MOUSE1, m5 = KeyNames.MOUSE1 + 4;
    int up = KeyNames.MWHEELUP, down = KeyNames.MWHEELDOWN;
    assertEquals(
        List.of(
            "k" + m1 + "+@1000",
            "k" + m5 + "+@1000",
            "k" + m1 + "-@1000",
            "k" + m5 + "-@1000",
            "k" + up + "+@1000",
            "k" + up + "-@1000",
            "k" + up + "+@1000",
            "k" + up + "-@1000",
            "k" + down + "+@1000",
            "k" + down + "-@1000"),
        sink.take());
  }

  @Test
  void smoothScrollFractionsAccumulateIntoNotches() {
    capture.mouseScrolled(0.4);
    capture.mouseScrolled(0.4);
    assertEquals(List.of(), sink.take());
    capture.mouseScrolled(0.4);
    assertEquals(2, sink.take().size());
    // A direction change discards the leftover fraction.
    capture.mouseScrolled(-0.5);
    capture.mouseScrolled(-0.5);
    assertEquals(2, sink.take().size());
    capture.mouseScrolled(1000);
    assertEquals(2 * Q3InputCapture.MAX_WHEEL_STEPS, sink.take().size());
  }

  @Test
  void cursorIsGrabbedOnFirstPollAndMotionFlowsOnlyWhileGrabbed() {
    assertFalse(host.grabbed);
    capture.poll();
    assertTrue(host.grabbed);
    assertTrue(capture.cursorGrabbed());
    capture.mouseMoved(3, -2);
    capture.mouseMoved(0, 0);
    assertEquals(List.of("m3.0,-2.0@1000"), sink.take());

    capture.setMouseCaptured(false);
    assertFalse(host.grabbed);
    capture.mouseMoved(5, 5);
    assertEquals(List.of(), sink.take());
    capture.setMouseCaptured(true);
    assertTrue(host.grabbed);
    assertEquals(2, host.grabs);
  }

  @Test
  void focusLossReleasesEveryKeyAndTheCursorThenRegrabs() {
    capture.keyPressed(GLFW_KEY_W, 25, 0);
    capture.keyPressed(GLFW_KEY_LEFT_CONTROL, 37, GLFW_MOD_CONTROL);
    capture.mouseButton(GLFW_MOUSE_BUTTON_RIGHT, true);
    sink.take();
    host.focused = false;
    clock.advance(5);
    capture.poll();
    assertFalse(host.grabbed);
    assertEquals(
        List.of("k119-@1005", "k" + KeyNames.CTRL + "-@1005", "k" + KeyNames.MOUSE2 + "-@1005"),
        sink.take());
    // Nothing is accepted while unfocused, and the late releases are not sent twice.
    capture.keyPressed(GLFW_KEY_A, 38, 0);
    capture.keyReleased(GLFW_KEY_W, 25, 0);
    capture.mouseMoved(4, 4);
    capture.poll();
    assertEquals(List.of(), sink.take());

    host.focused = true;
    capture.poll();
    assertTrue(host.grabbed);
    capture.keyReleased(GLFW_KEY_W, 25, 0);
    assertEquals(List.of(), sink.take());
  }

  @Test
  void shiftEscapeIsTheEscapeHatch() {
    capture.keyPressed(GLFW_KEY_W, 25, 0);
    sink.take();
    capture.keyPressed(GLFW_KEY_ESCAPE, 9, GLFW_MOD_SHIFT);
    assertEquals(1, hatches);
    assertEquals(List.of("k119-@1000"), sink.take());
    // Plain Escape is K_ESCAPE for the game.
    capture.keyPressed(GLFW_KEY_ESCAPE, 9, 0);
    assertEquals(List.of("k" + KeyNames.ESCAPE + "+@1000"), sink.take());
  }

  @Test
  void closeReleasesKeysAndCursorAndIgnoresLaterInput() {
    capture.poll();
    capture.keyPressed(GLFW_KEY_SPACE, 65, 0);
    sink.take();
    capture.close();
    capture.close();
    assertFalse(host.grabbed);
    assertEquals(1, host.releases);
    assertEquals(List.of("k32-@1000"), sink.take());
    capture.keyPressed(GLFW_KEY_SPACE, 65, 0);
    capture.charTyped('a');
    capture.mouseMoved(1, 1);
    assertEquals(List.of(), sink.take());
    assertTrue(capture.closed());

    capture.open();
    capture.keyPressed(GLFW_KEY_SPACE, 65, 0);
    assertTrue(host.grabbed);
    assertEquals(List.of("k32+@1000"), sink.take());
  }

  @Test
  void aThrowingSinkStillReleasesTheCursorOnClose() {
    Q3InputSink failing =
        new Q3InputSink() {
          @Override
          public void key(int q3Key, boolean down, long time) {
            if (!down) throw new IllegalStateException("sink failed");
          }

          @Override
          public void character(int codepoint) {}

          @Override
          public void mouseMove(double dx, double dy, long time) {}
        };
    Q3InputCapture failingCapture =
        new Q3InputCapture(
            failing, Q3KeyMap.forPlatform(Q3KeyMap.Platform.OTHER), clock, host, () -> {});
    failingCapture.keyPressed(GLFW_KEY_W, 25, 0);
    assertTrue(host.grabbed);
    try {
      failingCapture.close();
    } catch (IllegalStateException expected) {
      // The failure propagates to the caller's guard; the cursor must be free regardless.
    }
    assertFalse(host.grabbed);
    assertTrue(failingCapture.closed());
  }
}
