package dev.bluevista.craftq3.mc.fabric.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Shown instead of Quake III when no usable installation is configured: what is missing and how to
 * point CraftQ3 at the game data with {@code /q3 path}. CraftQ3 ships no game data.
 */
public final class Q3MissingInstallationScreen extends Screen {
  private static final int TEXT_WIDTH = 360;

  private final Screen parent;
  private final Component reason;

  public Q3MissingInstallationScreen(Screen parent, String reason) {
    super(Component.literal("Quake III Arena is not installed"));
    this.parent = parent;
    this.reason = Component.literal(reason);
  }

  @Override
  protected void init() {
    addRenderableWidget(
        Button.builder(CommonComponents.GUI_BACK, button -> onClose())
            .bounds(width / 2 - 100, height - 40, 200, 20)
            .build());
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
    super.extractRenderState(graphics, mouseX, mouseY, a);
    int left = width / 2 - TEXT_WIDTH / 2;
    int y = Math.max(20, height / 2 - 70);
    graphics.centeredText(font, title, width / 2, y, 0xffffffff);
    y += 20;
    graphics.textWithWordWrap(font, reason, left, y, TEXT_WIDTH, 0xffff8080);
    y += font.wordWrapHeight(reason, TEXT_WIDTH) + 12;
    Component help =
        Component.literal(
            "CraftQ3 needs your own copy of Quake III Arena: the directory that contains"
                + " baseq3 with pak0.pk3. Open a chat (in any world, or a server with the mod)"
                + " and run:");
    graphics.textWithWordWrap(font, help, left, y, TEXT_WIDTH, 0xffd0d0d0);
    y += font.wordWrapHeight(help, TEXT_WIDTH) + 8;
    graphics.centeredText(
        font, Component.literal("/q3 path <directory>"), width / 2, y, 0xffffff55);
    y += 16;
    Component more =
        Component.literal(
            "Then press CraftQ3 again, or use /q3 menu. /q3 status shows what was found. The path"
                + " is saved in config/craftq3/craftq3.properties.");
    graphics.textWithWordWrap(font, more, left, y, TEXT_WIDTH, 0xffd0d0d0);
  }

  @Override
  public void onClose() {
    minecraft.gui.setScreen(parent);
  }
}
