package dev.bluevista.craftq3.engine.client.console;

import dev.bluevista.craftq3.engine.client.render.ClientAssets;
import dev.bluevista.craftq3.engine.client.render.ClientRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Draws the drop-down console into the current frame with the game's own art ({@code console}
 * background, {@code gfx/2d/bigchars} charset), as {@code Con_DrawSolidConsole}: the scroll-back
 * above the input line, colour codes ({@code ^0}..{@code ^7}), a blinking cursor and a red rule at
 * the bottom edge. It needs no platform font.
 */
public final class ConsoleOverlay {
  private static final int[] COLORS = {
    0x000000ff, 0xff0000ff, 0x00ff00ff, 0xffff00ff, 0x0000ffff, 0x00ffffff, 0xff00ffff, 0xffffffff
  };

  private final ClientRenderer renderer;
  private final ClientConsole console;
  private int background = -1, charset, white;

  public ConsoleOverlay(ClientRenderer renderer, ClientConsole console) {
    this.renderer = Objects.requireNonNull(renderer);
    this.console = Objects.requireNonNull(console);
  }

  private void register() {
    if (background >= 0) return;
    ClientAssets assets = renderer.assets();
    background = assets.shader("console", ClientAssets.Usage.TWO_D);
    charset = assets.shader("gfx/2d/bigchars", ClientAssets.Usage.TWO_D);
    white = assets.shader("white", ClientAssets.Usage.TWO_D);
  }

  /**
   * Draws the console covering {@code fraction} (0..1) of the screen height at {@code time}.
   * Nothing is drawn for a fraction of 0.
   */
  public void draw(float fraction, int time) {
    if (!(fraction > 0)) return;
    register();
    int width = renderer.width(), height = renderer.height();
    float bottom = height * Math.min(fraction, 1);
    float cell = Math.max(8, height / 60f);
    int columns = Math.clamp((int) (width / cell) - 2, 4, 240);
    int rows = Math.clamp((int) (bottom / cell) - 3, 1, 100);
    renderer.setColor(-1);
    renderer.drawStretchPic(0, 0, width, bottom, 0, 0, 1, 1, background);
    renderer.setColor(0xff0000ff);
    renderer.drawStretchPic(0, bottom, width, Math.max(1, cell / 8), 0, 0, 1, 1, white);
    List<String> wrapped = new ArrayList<>();
    List<String> lines = console.lines();
    for (String line : lines.subList(Math.max(0, lines.size() - 256), lines.size()))
      wrap(line, columns, wrapped);
    int end = Math.max(0, wrapped.size() - Math.min(console.scroll(), wrapped.size()));
    int begin = Math.max(0, end - rows);
    float y = bottom - cell * 3 - (end - begin - 1) * cell;
    for (int i = begin; i < end; i++, y += cell) text(wrapped.get(i), cell, y, cell, columns);
    String input = console.field().text();
    int cursor = console.field().cursor();
    int start = Math.max(0, cursor - columns + 3);
    String visible = input.substring(start, Math.min(input.length(), start + columns - 1));
    text("]" + visible, cell, bottom - cell * 1.5f, cell, columns);
    if ((time / 256 & 1) == 0) {
      renderer.setColor(-1);
      glyph(11, cell * (2 + cursor - start), bottom - cell * 1.5f, cell);
    }
    renderer.setColor(-1);
  }

  private static void wrap(String line, int columns, List<String> output) {
    StringBuilder row = new StringBuilder();
    String color = "";
    int count = 0;
    for (int i = 0; i < Math.min(line.length(), 2048); i++) {
      char c = line.charAt(i);
      if (c == '^' && i + 1 < line.length() && line.charAt(i + 1) != '^') {
        color = line.substring(i, i + 2);
        row.append(color);
        i++;
        continue;
      }
      if (count == columns) {
        output.add(row.toString());
        row.setLength(0);
        row.append(color);
        count = 0;
      }
      if (c >= ' ' && c <= 255) {
        row.append(c);
        count++;
      }
    }
    output.add(row.toString());
  }

  private void text(String text, float x, float y, float cell, int columns) {
    renderer.setColor(-1);
    int count = 0;
    for (int i = 0; i < text.length() && count < columns; i++) {
      char c = text.charAt(i);
      if (c == '^' && i + 1 < text.length() && text.charAt(i + 1) != '^') {
        renderer.setColor(COLORS[(text.charAt(++i) - '0') & 7]);
        continue;
      }
      if (c != ' ') glyph(c & 255, x + count * cell, y, cell);
      count++;
    }
  }

  private void glyph(int code, float x, float y, float cell) {
    float s = (code & 15) / 16f, t = (code >> 4) / 16f;
    renderer.drawStretchPic(x, y, cell, cell, s, t, s + 1 / 16f, t + 1 / 16f, charset);
  }
}
