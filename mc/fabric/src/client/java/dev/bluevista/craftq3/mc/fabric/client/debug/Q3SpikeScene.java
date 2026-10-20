package dev.bluevista.craftq3.mc.fabric.client.debug;

import dev.bluevista.craftq3.mc.fabric.client.render.Q3Blend;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3RenderContext;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3StageParams;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3StageParams.AlphaFunc;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3StageParams.TcGen;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3StageParams.TcMod;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3StageParams.Wave;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3StageParams.WaveFunc;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * The Blaze3D spike's content: a 4×3 grid of cells on a 1024×768 canvas, each a tessellated quad
 * exercising one stage feature. Eleven cells come from one static mesh uploaded once; the last
 * cell's vertices are rewritten into the dynamic ring every frame. Cell indices and colours are
 * part of the devharness contract ({@code Q3SpikeClientTest}).
 */
public final class Q3SpikeScene {
  public static final int CANVAS_WIDTH = 1024;
  public static final int CANVAS_HEIGHT = 768;
  public static final int COLUMNS = 4;
  public static final int ROWS = 3;
  public static final int CELL_SIZE = 240;
  private static final int CELL_PITCH = 252;
  private static final int MARGIN = 16;
  private static final int SUBDIVISIONS = 8;
  public static final int VERTICES_PER_CELL = SUBDIVISIONS * SUBDIVISIONS * 6;

  /** Cell 0: {@code rgbGen const ( 0.2 0.6 0.8 )}, never animated. */
  public static final int STATIC_CELL = 0;

  public static final int[] STATIC_RGB = {51, 153, 204};

  /** Cells whose pixels change with shader time alone. */
  public static final int[] ANIMATED_CELLS = {1, 2, 3, 4, 5, 6};

  /** Cell 11: drawn from the dynamic ring with {@code rgbGen vertex} and this vertex colour. */
  public static final int DYNAMIC_CELL = 11;

  public static final int[] DYNAMIC_RGB = {255, 128, 0};

  static final String WHITE = "spike/white";
  static final String CHECKER = "spike/checker";
  static final String MASK = "spike/mask";
  static final String ENV = "spike/env";
  static final String LIGHTMAP = "spike/lightmap";

  private static final Q3StageParams BASE = Q3StageParams.identity();
  private static final Q3StageParams DARK_BASE = BASE.withRgbConst(0.25f, 0.25f, 0.25f);

  /** The stages drawn in each static cell, in order. */
  static final List<List<Layer>> CELLS =
      List.of(
          List.of(new Layer(Q3Blend.OPAQUE, WHITE, BASE.withRgbConst(0.2f, 0.6f, 0.8f))),
          List.of(
              new Layer(
                  Q3Blend.OPAQUE,
                  CHECKER,
                  BASE.withRgbWave(new Wave(WaveFunc.SIN, 0.5f, 0.5f, 0, 1)))),
          List.of(
              new Layer(Q3Blend.OPAQUE, CHECKER, BASE.withTcMods(new TcMod.Scroll(0.5f, 0.25f)))),
          List.of(new Layer(Q3Blend.OPAQUE, CHECKER, BASE.withTcMods(new TcMod.Rotate(90)))),
          List.of(
              new Layer(Q3Blend.OPAQUE, CHECKER, BASE.withTcMods(new TcMod.Turb(0.1f, 0, 0.5f)))),
          List.of(
              new Layer(
                  Q3Blend.OPAQUE,
                  CHECKER,
                  BASE.withTcMods(
                      new TcMod.Scale(2, 2),
                      new TcMod.Stretch(new Wave(WaveFunc.SIN, 1, 0.3f, 0, 0.5f))))),
          List.of(new Layer(Q3Blend.OPAQUE, ENV, BASE.withTcGen(TcGen.ENVIRONMENT))),
          List.of(
              new Layer(Q3Blend.OPAQUE, WHITE, DARK_BASE),
              new Layer(
                  Q3Blend.OPAQUE,
                  MASK,
                  BASE.withAlphaFunc(AlphaFunc.GE128).withTcMods(new TcMod.Scroll(0.25f, 0)))),
          List.of(
              new Layer(Q3Blend.OPAQUE, CHECKER, BASE),
              new Layer(
                  Q3Blend.BLEND,
                  WHITE,
                  BASE.withRgbConst(1, 0.2f, 0.2f)
                      .withAlphaConst(0.5f)
                      .withTcMods(new TcMod.Rotate(45)))),
          List.of(
              new Layer(Q3Blend.OPAQUE, CHECKER, DARK_BASE),
              new Layer(Q3Blend.ADD, ENV, BASE.withTcMods(new TcMod.Scroll(0.1f, 0.1f)))),
          List.of(
              new Layer(Q3Blend.OPAQUE, CHECKER, BASE.withLightmap()),
              new Layer(Q3Blend.FILTER, ENV, BASE.withTcMods(new TcMod.Scroll(0, 0.2f)))));

  static final Layer DYNAMIC_LAYER = new Layer(Q3Blend.OPAQUE, WHITE, BASE.withRgbVertex());

  private Q3SpikeScene() {}

  /**
   * Pixel rectangle {x0, y0, x1, y1} of a cell's centre region for a {@code width×height} frame.
   */
  public static int[] cellCentre(int cell, int width, int height, int inset) {
    int x0 = MARGIN + (cell % COLUMNS) * CELL_PITCH;
    int y0 = MARGIN + (cell / COLUMNS) * CELL_PITCH;
    float cx = (x0 + CELL_SIZE / 2f) * width / CANVAS_WIDTH;
    float cy = (y0 + CELL_SIZE / 2f) * height / CANVAS_HEIGHT;
    float half =
        CELL_SIZE / 2f * Math.min((float) width / CANVAS_WIDTH, (float) height / CANVAS_HEIGHT);
    int r = Math.max(1, (int) half - inset);
    return new int[] {(int) cx - r, (int) cy - r, (int) cx + r, (int) cy + r};
  }

  /** Vertices of every static cell, {@link #VERTICES_PER_CELL} per cell, in cell order. */
  static ByteBuffer staticVertices() {
    ByteBuffer out =
        ByteBuffer.allocateDirect(
                CELLS.size() * VERTICES_PER_CELL * Q3RenderContext.STAGE_FORMAT.getVertexSize())
            .order(ByteOrder.nativeOrder());
    for (int cell = 0; cell < CELLS.size(); cell++) writeCell(out, cell, 0xffffffff);
    return out.flip();
  }

  /** The dynamic cell's vertices; rebuilt and re-uploaded each frame to exercise the ring. */
  static ByteBuffer dynamicVertices() {
    ByteBuffer out =
        ByteBuffer.allocateDirect(VERTICES_PER_CELL * Q3RenderContext.STAGE_FORMAT.getVertexSize())
            .order(ByteOrder.nativeOrder());
    int abgr = 0xff000000 | DYNAMIC_RGB[2] << 16 | DYNAMIC_RGB[1] << 8 | DYNAMIC_RGB[0];
    writeCell(out, DYNAMIC_CELL, abgr);
    return out.flip();
  }

  private static void writeCell(ByteBuffer out, int cell, int abgr) {
    float x0 = MARGIN + (cell % COLUMNS) * CELL_PITCH;
    float y0 = MARGIN + (cell / COLUMNS) * CELL_PITCH;
    float step = (float) CELL_SIZE / SUBDIVISIONS;
    for (int j = 0; j < SUBDIVISIONS; j++)
      for (int i = 0; i < SUBDIVISIONS; i++) {
        int[][] corners = {{i, j}, {i, j + 1}, {i + 1, j + 1}, {i, j}, {i + 1, j + 1}, {i + 1, j}};
        for (int[] c : corners) {
          float u = (float) c[0] / SUBDIVISIONS, v = (float) c[1] / SUBDIVISIONS;
          out.putFloat(x0 + c[0] * step).putFloat(y0 + c[1] * step).putFloat(0);
          out.putFloat(u).putFloat(v);
          out.putFloat(u).putFloat(v);
          out.putFloat(0).putFloat(0).putFloat(1);
          // RGBA8_UNORM in memory order R, G, B, A.
          out.put((byte) abgr)
              .put((byte) (abgr >>> 8))
              .put((byte) (abgr >>> 16))
              .put((byte) (abgr >>> 24));
        }
      }
  }

  static byte[] white() {
    return new byte[] {(byte) 255, (byte) 255, (byte) 255, (byte) 255};
  }

  static byte[] checker() {
    return image(
        64,
        64,
        (x, y) -> ((x / 8 + y / 8) & 1) == 0 ? rgba(230, 230, 230, 255) : rgba(40, 90, 160, 255));
  }

  static byte[] mask() {
    return image(
        64,
        64,
        (x, y) -> {
          int dx = x % 32 - 16, dy = y % 32 - 16;
          return rgba(250, 200, 40, dx * dx + dy * dy < 100 ? 255 : 0);
        });
  }

  static byte[] env() {
    return image(64, 64, (x, y) -> rgba(x * 4, y * 4, 255 - x * 4, 255));
  }

  static byte[] lightmap() {
    return image(16, 16, (x, y) -> rgba(96 + x * 10, 80 + y * 10, 64 + (x + y) * 4, 255));
  }

  private interface Pixel {
    int at(int x, int y);
  }

  private static int rgba(int r, int g, int b, int a) {
    return r << 24 | g << 16 | b << 8 | a;
  }

  private static byte[] image(int width, int height, Pixel pixel) {
    byte[] out = new byte[width * height * 4];
    for (int y = 0; y < height; y++)
      for (int x = 0; x < width; x++) {
        int p = pixel.at(x, y), i = (y * width + x) * 4;
        out[i] = (byte) (p >>> 24);
        out[i + 1] = (byte) (p >>> 16);
        out[i + 2] = (byte) (p >>> 8);
        out[i + 3] = (byte) p;
      }
    return out;
  }

  /** One stage of a cell. */
  record Layer(Q3Blend blend, String texture, Q3StageParams stage) {}
}
