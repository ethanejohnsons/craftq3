package dev.bluevista.craftq3.mc.devharness.client;

import com.mojang.blaze3d.platform.Window;
import dev.bluevista.craftq3.engine.common.time.ManualClock;
import dev.bluevista.craftq3.engine.console.KeyNames;
import dev.bluevista.craftq3.mc.fabric.client.input.Q3InputCapture;
import dev.bluevista.craftq3.mc.fabric.client.input.Q3InputScreen;
import dev.bluevista.craftq3.mc.fabric.client.input.Q3InputSink;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Minecraft-to-Quake input layer in a real client, driven through Fabric's test input (which
 * calls Minecraft's own keyboard and mouse handlers):
 *
 * <ol>
 *   <li>In a singleplayer world a {@link Q3InputScreen} with a recording sink opens and grabs the
 *       cursor on its first poll, without setting Minecraft's own grab flag.
 *   <li>Keys arrive as Quake key numbers stamped by a {@link ManualClock}; text arrives as
 *       characters; the console key sends {@code `} and the character it types is swallowed; mouse
 *       buttons and the wheel arrive as MOUSE1 and MWHEELUP.
 *   <li>Cursor motion reaches the sink as relative deltas through {@code MouseHandlerMixin}: the
 *       screen's {@code mouseMoved} never fires and the player's camera does not turn.
 *   <li>Window focus loss releases a held key and the cursor; focus return grabs it again.
 *   <li>Shift+Escape releases Shift, closes the screen and frees the cursor; afterwards the same
 *       cursor motion turns the camera again (the control showing the check above is live).
 * </ol>
 *
 * Fabric's test harness never moves the real OS cursor ({@code grabOrReleaseMouse} is a no-op under
 * it), so the grab is asserted on the capture's state.
 */
public final class Q3InputClientTest implements FabricClientGameTest {
  private static final long START = 5000;
  private static final Logger LOGGER = LoggerFactory.getLogger(Q3InputClientTest.class);

  @Override
  public void runTest(ClientGameTestContext context) {
    try (TestSingleplayerContext world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      context.waitTicks(5);
      TestInput input = context.getInput();
      float[] camera = context.computeOnClient(c -> rotation(c.player));

      ManualClock clock = new ManualClock(START);
      Recorder sink = new Recorder();
      context.setScreen(() -> new RecordingScreen(sink, clock));
      context.waitForScreen(RecordingScreen.class);
      context.waitTicks(2);
      Q3InputCapture capture = context.computeOnClient(c -> screen(c).inputCapture());
      check(context.computeOnClient(c -> capture.cursorGrabbed()), "cursor grabbed on open");
      check(
          !context.computeOnClient(c -> c.mouseHandler.isMouseGrabbed()),
          "Minecraft's own grab flag stays clear under the capture");

      // Keys with timestamps from the injected clock.
      input.holdKey(GLFW.GLFW_KEY_W);
      context.runOnClient(c -> clock.set(START + 16));
      input.releaseKey(GLFW.GLFW_KEY_W);
      input.typeChars("hi");
      // The console key: '`' for Quake, its layout character swallowed.
      input.holdKey(GLFW.GLFW_KEY_GRAVE_ACCENT);
      input.typeChar('^');
      input.releaseKey(GLFW.GLFW_KEY_GRAVE_ACCENT);
      input.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
      input.scroll(1);
      context.waitTick();
      expect(
          sink.keysAndChars(context),
          List.of(
              "k119+@5000",
              "k119-@5016",
              "c104",
              "c105",
              "k96+@5016",
              "k96-@5016",
              "k" + KeyNames.MOUSE1 + "+@5016",
              "k" + KeyNames.MOUSE1 + "-@5016",
              "k" + KeyNames.MWHEELUP + "+@5016",
              "k" + KeyNames.MWHEELUP + "-@5016"),
          "keys and characters");

      // Relative motion: the first move after a grab only sets the origin.
      input.moveCursor(1, 1);
      context.waitTicks(2);
      sink.motion(context);
      input.moveCursor(30, -12);
      context.waitTicks(2);
      double[] moved = sink.motion(context);
      check(moved[0] == 30 && moved[1] == -12, "mouse delta (30, -12), got " + str(moved));
      check(
          context.computeOnClient(c -> screen(c).mouseMovedCalls) == 0,
          "the screen's mouseMoved never sees captured motion");
      float[] after = context.computeOnClient(c -> rotation(c.player));
      check(
          after[0] == camera[0] && after[1] == camera[1],
          "camera did not rotate: " + str(camera) + " -> " + str(after));

      // Focus loss releases held keys and the cursor; focus return grabs again.
      input.holdKey(GLFW.GLFW_KEY_SPACE);
      context.runOnClient(c -> setFocused(c.getWindow(), false));
      context.waitTicks(2);
      check(
          !context.computeOnClient(c -> capture.cursorGrabbed()), "cursor released on focus loss");
      context.runOnClient(c -> setFocused(c.getWindow(), true));
      context.waitTicks(2);
      check(
          context.computeOnClient(c -> capture.cursorGrabbed()), "cursor grabbed on focus return");
      input.releaseKey(GLFW.GLFW_KEY_SPACE);
      expect(
          sink.keysAndChars(context),
          List.of("k32+@5016", "k32-@5016"),
          "space released once, on focus loss");

      // Shift+Escape: the escape hatch back to Minecraft.
      input.holdShift();
      input.pressKey(GLFW.GLFW_KEY_ESCAPE);
      input.releaseShift();
      context.waitTicks(2);
      check(
          context.computeOnClient(c -> !(c.gui.screen() instanceof RecordingScreen)),
          "Shift+Escape closed the screen");
      check(context.computeOnClient(c -> capture.closed()), "capture closed with the screen");
      check(!context.computeOnClient(c -> capture.cursorGrabbed()), "cursor released on close");
      expect(
          sink.keysAndChars(context),
          List.of("k" + KeyNames.SHIFT + "+@5016", "k" + KeyNames.SHIFT + "-@5016"),
          "Shift released by the escape hatch, Escape never sent");

      // Control: without the capture, the same motion turns the camera.
      input.moveCursor(1, 1);
      context.waitTicks(2);
      float[] before = context.computeOnClient(c -> rotation(c.player));
      input.moveCursor(40, 0);
      context.waitTicks(2);
      float[] turned = context.computeOnClient(c -> rotation(c.player));
      check(turned[0] != before[0], "vanilla motion turns the camera once the capture is closed");
      check(sink.motion(context)[0] == 0, "no motion reaches a closed capture");
      LOGGER.info(
          "Q3InputClientTest passed: delta {}, camera {} -> {} under capture, {} -> {} after close",
          str(moved),
          str(camera),
          str(after),
          str(before),
          str(turned));
    }
  }

  private static RecordingScreen screen(net.minecraft.client.Minecraft client) {
    return (RecordingScreen) client.gui.screen();
  }

  private static float[] rotation(LocalPlayer player) {
    return new float[] {player.getYRot(), player.getXRot()};
  }

  /** Stands in for the OS focus callback, which Fabric's test harness disconnects. */
  private static void setFocused(Window window, boolean focused) {
    try {
      Field field = Window.class.getDeclaredField("focused");
      field.setAccessible(true);
      field.setBoolean(window, focused);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("Cannot change Window.focused", e);
    }
  }

  private static void check(boolean condition, String what) {
    if (!condition) throw new AssertionError("Input capture: " + what);
  }

  private static void expect(List<String> got, List<String> expected, String what) {
    if (!got.equals(expected))
      throw new AssertionError(
          "Input capture: " + what + ": expected " + expected + ", got " + got);
  }

  private static String str(float[] v) {
    return "(" + v[0] + ", " + v[1] + ")";
  }

  private static String str(double[] v) {
    return "(" + v[0] + ", " + v[1] + ")";
  }

  /** A Quake input screen that only records what its capture delivers. */
  private static final class RecordingScreen extends Q3InputScreen {
    int mouseMovedCalls;

    RecordingScreen(Recorder sink, ManualClock clock) {
      super(Component.literal("CraftQ3 input test"), sink, clock);
    }

    @Override
    public void mouseMoved(double x, double y) {
      mouseMovedCalls++;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int x, int y, float a) {}

    @Override
    public boolean isPauseScreen() {
      return false;
    }
  }

  /** Written on the render thread; read there too, through {@code computeOnClient}. */
  private static final class Recorder implements Q3InputSink {
    private final List<String> events = new ArrayList<>();
    private double dx;
    private double dy;

    @Override
    public void key(int q3Key, boolean down, long time) {
      events.add("k" + q3Key + (down ? "+" : "-") + "@" + time);
    }

    @Override
    public void character(int codepoint) {
      events.add("c" + codepoint);
    }

    @Override
    public void mouseMove(double x, double y, long time) {
      dx += x;
      dy += y;
    }

    List<String> keysAndChars(ClientGameTestContext context) {
      return context.computeOnClient(
          c -> {
            List<String> copy = List.copyOf(events);
            events.clear();
            return copy;
          });
    }

    double[] motion(ClientGameTestContext context) {
      return context.computeOnClient(
          c -> {
            double[] sum = {dx, dy};
            dx = dy = 0;
            return sum;
          });
    }
  }
}
