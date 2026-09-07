package dev.bluevista.craftq3.client;

import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.render.CgameFrame;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Engine console using the mounted Quake charset and shader, with no platform font dependency. */
public final class EngineConsole {
  private static final int[] COLORS = {
    0x000000ff, 0xff0000ff, 0x00ff00ff, 0xffff00ff,
    0x0000ffff, 0x00ffffff, 0xff00ffff, 0xffffffff
  };
  private final ClientAssets assets;
  private final String background, charset, white;

  public EngineConsole(VirtualFileSystem files, AudioBackend audio, Consumer<String> output)
      throws IOException {
    assets = new ClientAssets(files, audio, output);
    background = assets.shader(assets.shader("console", false, false));
    charset = assets.shader(assets.shader("gfx/2d/bigchars", false, false));
    white = assets.shader(assets.shader("white", false, false));
  }

  public CgameFrame frame(
      List<String> lines, String input, int cursor, int width, int height, int time, int scroll) {
    if (width < 1
        || height < 1
        || width > 32768
        || height > 32768
        || input.length() > 1024
        || cursor < 0
        || cursor > input.length()
        || scroll < 0) throw new IllegalArgumentException("Invalid console viewport or cursor");
    float cell = Math.max(8, height / 60f), bottom = height / 2f;
    int columns = Math.clamp((int) (width / cell) - 2, 4, 240);
    int rows = Math.clamp((int) (bottom / cell) - 3, 1, 100);
    var output = new ArrayList<CgameFrame.Command>();
    output.add(quad(0, 0, width, bottom, 0, 0, 1, 1, background, -1));
    output.add(quad(0, bottom, width, Math.max(1, cell / 8), 0, 0, 1, 1, white, 0xff0000ff));
    var wrapped = new ArrayList<String>();
    for (String line : lines.stream().skip(Math.max(0, lines.size() - 256)).toList())
      wrap(line, columns, wrapped);
    int end = Math.max(0, wrapped.size() - Math.min(scroll, wrapped.size()));
    int begin = Math.max(0, end - rows);
    float y = bottom - cell * 3 - (end - begin - 1) * cell;
    for (int index = begin; index < end; index++, y += cell)
      text(output, wrapped.get(index), cell, y, cell, columns);
    int start = Math.max(0, cursor - columns + 3);
    String visible = input.substring(start, Math.min(input.length(), start + columns - 1));
    text(output, "]" + visible, cell, bottom - cell * 1.5f, cell, columns);
    if ((time / 256 & 1) == 0)
      glyph(output, 11, cell * (2 + cursor - start), bottom - cell * 1.5f, cell, -1);
    return new CgameFrame(output, assets.snapshot(), time);
  }

  private static void wrap(String line, int columns, List<String> output) {
    StringBuilder row = new StringBuilder();
    int count = 0;
    String color = "";
    for (int i = 0; i < Math.min(line.length(), 2048); i++) {
      char value = line.charAt(i);
      if (value == '^' && i + 1 < line.length() && line.charAt(i + 1) != '^') {
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
      if (value >= 32 && value <= 255) {
        row.append(value);
        count++;
      }
    }
    output.add(row.toString());
  }

  private void text(
      List<CgameFrame.Command> output, String text, float x, float y, float cell, int columns) {
    int color = -1, count = 0;
    for (int i = 0; i < text.length() && count < columns; i++) {
      char value = text.charAt(i);
      if (value == '^' && i + 1 < text.length() && text.charAt(i + 1) != '^') {
        color = COLORS[(text.charAt(++i) - '0') & 7];
        continue;
      }
      if (value != ' ') glyph(output, value & 255, x + count * cell, y, cell, color);
      count++;
    }
  }

  private void glyph(
      List<CgameFrame.Command> output, int code, float x, float y, float cell, int color) {
    float s = (code & 15) / 16f, t = (code >> 4) / 16f;
    output.add(quad(x, y, cell, cell, s, t, s + 1 / 16f, t + 1 / 16f, charset, color));
  }

  private static CgameFrame.Quad quad(
      float x,
      float y,
      float width,
      float height,
      float s,
      float t,
      float s2,
      float t2,
      String shader,
      int color) {
    return new CgameFrame.Quad(x, y, width, height, s, t, s2, t2, shader, color);
  }
}
