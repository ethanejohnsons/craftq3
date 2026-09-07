package dev.bluevista.craftq3.fabric.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Host loading/error surface while the original UI and installation are opened. */
public final class Q3LaunchScreen extends Screen {
  private final Screen parent;
  private String status = "Opening Quake III…";

  public Q3LaunchScreen(Screen parent) {
    super(Component.literal("CraftQ3"));
    this.parent = parent;
  }

  public void status(String message) {
    status = message;
  }

  @Override
  protected void init() {
    addRenderableWidget(
        Button.builder(Component.literal("Back"), button -> onClose())
            .bounds(width / 2 - 100, height / 2 + 30, 200, 20)
            .build());
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float delta) {
    super.extractRenderState(graphics, x, y, delta);
    graphics.centeredText(font, "CraftQ3", width / 2, height / 2 - 50, 0xffffffff);
    graphics.centeredText(font, status, width / 2, height / 2 - 15, 0xffdddddd);
  }

  @Override
  public void onClose() {
    minecraft.gui.setScreen(parent);
  }
}
