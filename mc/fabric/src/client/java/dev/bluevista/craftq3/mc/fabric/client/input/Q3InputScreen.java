package dev.bluevista.craftq3.mc.fabric.client.input;

import dev.bluevista.craftq3.engine.common.time.Clock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A screen that gives all keyboard, text and mouse input to a {@link Q3InputCapture}: every event
 * is consumed, so Minecraft's own Escape handling, key mappings and widgets never see it. The
 * capture opens when the screen is added and closes (releasing every key and the cursor) when it is
 * removed. Shift+Escape runs {@link #onClose}, which returns to Minecraft by default.
 */
public abstract class Q3InputScreen extends Screen implements Q3InputView {
  private final Q3InputCapture capture;

  /** A capture on Minecraft's window with this platform's key map. Call on the render thread. */
  protected Q3InputScreen(Component title, Q3InputSink sink, Clock clock) {
    super(title);
    this.capture =
        new Q3InputCapture(
            sink,
            Q3KeyMap.forHost(),
            clock,
            new MinecraftInputHost(Minecraft.getInstance()),
            Q3InputScreen::closeCurrent);
  }

  /** The escape hatch: input only reaches the open screen, so that is the one to close. */
  private static void closeCurrent() {
    if (Minecraft.getInstance().gui.screen() instanceof Q3InputScreen screen) screen.onClose();
  }

  @Override
  public final Q3InputCapture inputCapture() {
    return capture;
  }

  @Override
  public void added() {
    super.added();
    capture.open();
  }

  @Override
  public void removed() {
    capture.close();
    super.removed();
  }

  @Override
  public void tick() {
    deliver(capture::poll);
  }

  @Override
  public boolean keyPressed(KeyEvent event) {
    deliver(() -> capture.keyPressed(event.key(), event.scancode(), event.modifiers()));
    return true;
  }

  @Override
  public boolean keyReleased(KeyEvent event) {
    deliver(() -> capture.keyReleased(event.key(), event.scancode(), event.modifiers()));
    return true;
  }

  @Override
  public boolean charTyped(CharacterEvent event) {
    deliver(() -> capture.charTyped(event.codepoint()));
    return true;
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
    deliver(() -> capture.mouseButton(event.button(), true));
    return true;
  }

  @Override
  public boolean mouseReleased(MouseButtonEvent event) {
    deliver(() -> capture.mouseButton(event.button(), false));
    return true;
  }

  @Override
  public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
    return true;
  }

  @Override
  public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
    deliver(() -> capture.mouseScrolled(scrollY));
    return true;
  }

  private void deliver(Runnable delivery) {
    Q3InputView.deliver(minecraft != null ? minecraft : Minecraft.getInstance(), capture, delivery);
  }
}
