package dev.bluevista.craftq3.mc.devharness.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.common.time.ManualClock;
import dev.bluevista.craftq3.engine.formats.FileViewAssets;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.bsp.BspReader;
import dev.bluevista.craftq3.engine.formats.md3.AnimationConfig;
import dev.bluevista.craftq3.engine.formats.md3.Md3Model;
import dev.bluevista.craftq3.engine.formats.md3.Md3Reader;
import dev.bluevista.craftq3.engine.formats.md3.SkinParser;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition;
import dev.bluevista.craftq3.engine.formats.shader.ShaderLibrary;
import dev.bluevista.craftq3.engine.formats.shader.ShaderParser;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import dev.bluevista.craftq3.engine.render.AreaMask;
import dev.bluevista.craftq3.engine.render.DynamicLight;
import dev.bluevista.craftq3.engine.render.RenderScene;
import dev.bluevista.craftq3.engine.render.SceneTextures;
import dev.bluevista.craftq3.engine.render.ViewBasis;
import dev.bluevista.craftq3.engine.render.ViewDefinition;
import dev.bluevista.craftq3.engine.render.frame.CgameFrame;
import dev.bluevista.craftq3.engine.render.frame.SceneAssets;
import dev.bluevista.craftq3.mc.fabric.client.CraftQ3ClientMod;
import dev.bluevista.craftq3.mc.fabric.client.debug.Q3MapViewScreen;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3RenderBackend;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3RenderContext;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.TitleScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renderer effects on the real GPU with the maintainer's installation ({@code CRAFTQ3_Q3ROOT};
 * skipped when unset):
 *
 * <ul>
 *   <li>On q3dm17, a rail core, rail rings, a beam and a lightning bolt crossing the spawn view,
 *       plus Sarge with a projected shadow: the pixels along each path must differ from the same
 *       view without them.
 *   <li>With no map loaded, a menu-style {@code RDF_NOWORLDMODEL} view of Sarge ({@code
 *       models/players/sarge/{lower,upper,head}.md3} on their tags, default skins, the ui's two
 *       lights) must cover part of its viewport, and an {@code RDF_HYPERSPACE} view must fill its
 *       viewport with the time's grey.
 *   <li>A {@code videoMap} surface playing {@code video/idlogo.roq} must change over shader time.
 * </ul>
 *
 * Screenshots go to the run directory's {@code screenshots/}; numbers to {@code
 * craftq3-effects-report-<backend>.txt}.
 */
public final class Q3EffectsRenderClientTest implements FabricClientGameTest {
  private static final Logger LOGGER = LoggerFactory.getLogger(Q3EffectsRenderClientTest.class);
  private static final int WIDTH = 1920, HEIGHT = 1080;
  private static final Vec3f X = new Vec3f(1, 0, 0), Y = new Vec3f(0, 1, 0), Z = new Vec3f(0, 0, 1);

  /** Mean RGB difference a beam must make along its path. */
  private static final double MIN_BEAM_DIFFERENCE = 12;

  /** Fraction of the menu viewport the player model must cover. */
  private static final double MIN_MODEL_COVERAGE = 0.04;

  private static final int BACKGROUND = 0x182030ff;
  private static final String VIDEO_SHADER = "craftq3test/video";

  @Override
  public void runTest(ClientGameTestContext context) {
    String root = System.getenv("CRAFTQ3_Q3ROOT");
    if (root == null || root.isBlank() || !Files.isDirectory(Path.of(root))) {
      LOGGER.warn("CRAFTQ3_Q3ROOT is not set; skipping the Quake III effects render test");
      return;
    }
    String backendName =
        context.computeOnClient(c -> RenderSystem.getDevice().getDeviceInfo().backendName());
    String tag = backendName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    context.waitForScreen(TitleScreen.class);
    context.getInput().resizeWindow(WIDTH, HEIGHT);
    context.waitTicks(3);
    StringBuilder report = new StringBuilder("CraftQ3 effects on " + backendName + "\n");
    List<String> failures = new ArrayList<>();
    try (SearchPath search = SearchPath.mount(Path.of(root), SearchPath.BASE_GAME)) {
      FileViewAssets assets = new FileViewAssets(search.view());
      ShaderLibrary library = ShaderLibrary.load(assets);
      Q3RenderContext renderContext = clientMod().renderContext();
      Q3RenderBackend backend =
          context.computeOnClient(
              c -> new Q3RenderBackend(renderContext, library::resolve, assets));
      ManualClock clock = new ManualClock(5000);
      Sarge sarge = Sarge.load(search);
      String name = "craftq3-effects-" + tag;

      // 1. Beams across the q3dm17 spawn view.
      RenderScene scene = scene(search, "q3dm17", library);
      SceneTextures textures = SceneTextures.load(assets, scene);
      context.runOnClient(c -> backend.loadMap(scene, textures, assets));
      ViewDefinition spawn = Q3RenderBackend.spawnView(scene, WIDTH, HEIGHT, 0);
      Beams beams = new Beams(spawn);
      MutableScene shown = new MutableScene(beams.frame(List.of(), List.of()));
      Q3MapViewScreen screen =
          context.computeOnClient(c -> new Q3MapViewScreen(backend, shown, clock, null, null));
      screen.showStats(false);
      context.setScreen(() -> screen);
      context.waitForScreen(Q3MapViewScreen.class);
      context.waitTicks(3);
      long[] first = screen.recentCpuNanos(8);
      report.append(
          String.format(
              Locale.ROOT,
              "q3dm17 first frames cpu ms: %s%n",
              java.util.Arrays.toString(
                  java.util.Arrays.stream(first).mapToDouble(n -> n / 1e6).toArray())));
      BufferedImage empty = read(context.takeScreenshot(name + "-q3dm17-empty"));
      shown.frame = beams.frame(beams.entities(sarge, spawn), List.of());
      context.waitTicks(3);
      BufferedImage drawn = read(context.takeScreenshot(name + "-q3dm17-beams"));
      for (Beams.Path path : beams.paths()) {
        double difference = pathDifference(empty, drawn, spawn, path);
        report.append(
            String.format(Locale.ROOT, "%s: path difference %.1f%n", path.label(), difference));
        if (difference < MIN_BEAM_DIFFERENCE)
          failures.add(path.label() + " is not visible along its path (" + difference + ")");
      }
      context.setScreen(() -> null);

      // 2. No map: a menu player model and a hyperspace view.
      context.runOnClient(c -> backend.unloadMap());
      shown.frame = menuFrame(sarge, 5000);
      context.setScreen(() -> screen);
      context.waitForScreen(Q3MapViewScreen.class);
      context.waitTicks(3);
      BufferedImage menu = read(context.takeScreenshot(name + "-menu-sarge"));
      double coverage = coverage(menu, MENU_VIEW);
      report.append(String.format(Locale.ROOT, "menu model coverage %.3f%n", coverage));
      if (coverage < MIN_MODEL_COVERAGE)
        failures.add("No-world player model covers only " + coverage + " of its viewport");
      int grey = centre(menu, HYPERSPACE_VIEW);
      report.append(String.format(Locale.ROOT, "hyperspace centre 0x%06x%n", grey & 0xffffff));
      int expected = Math.round((5000 & 255) / 255f * 255);
      for (int shift : new int[] {0, 8, 16})
        if (Math.abs((grey >> shift & 255) - expected) > 12)
          failures.add(
              "Hyperspace view is 0x"
                  + Integer.toHexString(grey & 0xffffff)
                  + ", expected grey "
                  + expected);

      // 3. A videoMap surface over time.
      ShaderDefinition video =
          ShaderParser.parse(
                  "craftq3test.shader",
                  VIDEO_SHADER + "\n{\n  cull none\n  {\n    videoMap idlogo.roq\n  }\n}\n")
              .definitions()
              .getFirst();
      List<BufferedImage> movie = new ArrayList<>();
      for (int millis : new int[] {1500, 3000, 4500}) {
        clock.set(5000 + millis);
        shown.frame = videoFrame(video);
        context.waitTicks(8);
        movie.add(read(context.takeScreenshot(name + "-videomap-" + millis)));
      }
      double change = 0;
      for (int i = 1; i < movie.size(); i++)
        change = Math.max(change, difference(movie.get(i - 1), movie.get(i), VIDEO_VIEW));
      int streams = context.computeOnClient(c -> backend.videoStreams());
      report.append(
          String.format(Locale.ROOT, "videoMap change %.1f over %d stream(s)%n", change, streams));
      if (change < MIN_BEAM_DIFFERENCE / 2)
        failures.add("The videoMap surface does not change over time (" + change + ")");
      if (deviation(movie.getLast(), VIDEO_VIEW) < 4) failures.add("The videoMap surface is flat");
      context.setScreen(() -> null);
      context.runOnClient(c -> backend.close());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    report.append("failures: ").append(failures).append('\n');
    LOGGER.info("\n{}", report);
    try {
      Files.writeString(Path.of("craftq3-effects-report-" + tag + ".txt"), report);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (!failures.isEmpty()) throw new AssertionError(String.join("; ", failures));
  }

  /** A scene whose whole frame the test swaps between screenshots. */
  private static final class MutableScene implements Q3MapViewScreen.Scene {
    volatile FrameMaker frame;

    MutableScene(FrameMaker frame) {
      this.frame = frame;
    }

    @Override
    public ViewDefinition view(int width, int height, int timeMillis) {
      throw new UnsupportedOperationException("frames only");
    }

    @Override
    public CgameFrame frame(int width, int height, int timeMillis) {
      return frame.make(width, height, timeMillis);
    }
  }

  @FunctionalInterface
  private interface FrameMaker {
    CgameFrame make(int width, int height, int timeMillis);
  }

  /** The spawn view with beams in front of it. */
  private record Beams(ViewDefinition spawn) {
    record Path(String label, Vec3f from, Vec3f to, boolean discs) {}

    Vec3f at(float forward, float right, float up) {
      ViewBasis b = spawn.basis();
      return spawn
          .origin()
          .multiplyAdd(forward, b.forward())
          .multiplyAdd(right, b.right())
          .multiplyAdd(up, b.up());
    }

    List<Path> paths() {
      return List.of(
          new Path("rail core", at(120, -80, 34), at(120, 80, 34), false),
          new Path("rail rings", at(120, -80, 14), at(120, 80, 14), true),
          new Path("beam", at(120, -80, -6), at(120, 80, -6), false),
          new Path("lightning", at(120, -80, -26), at(120, 80, -26), false));
    }

    List<CgameFrame.RefEntity> entities(Sarge sarge, ViewDefinition view) {
      List<Path> p = paths();
      List<CgameFrame.RefEntity> out = new ArrayList<>();
      out.add(beam(CgameFrame.EntityType.RAIL_CORE, p.get(0), "railCore", 0xffffffff));
      out.add(beam(CgameFrame.EntityType.RAIL_RINGS, p.get(1), "railDisc", 0x40c0ffff));
      out.add(beam(CgameFrame.EntityType.BEAM, p.get(2), "railCore", 0xffffffff));
      out.add(beam(CgameFrame.EntityType.LIGHTNING, p.get(3), "lightningBoltNew", 0xffffffff));
      // Sarge on the floor to the right, facing the camera, with a projected shadow.
      Vec3f feet = at(190, 70, 0).subtract(new Vec3f(0, 0, 26 + 24));
      Vec3f origin = feet.add(new Vec3f(0, 0, 24));
      Vec3f facing = view.basis().forward().negate();
      Vec3f flat = new Vec3f(facing.x(), facing.y(), 0).normalize();
      out.addAll(sarge.entities(origin, flat, CgameFrame.RF_SHADOW_PLANE, feet.z() + 1));
      return out;
    }

    FrameMaker frame(List<CgameFrame.RefEntity> entities, List<DynamicLight> lights) {
      return (width, height, time) -> {
        ViewDefinition main = sized(spawn, width, height, time);
        return new CgameFrame(
            List.of(
                new CgameFrame.View(
                    refdef(main, 0, 0, width, height, 0), entities, List.of(), lights)),
            SceneAssets.EMPTY);
      };
    }

    private CgameFrame.RefEntity beam(
        CgameFrame.EntityType type, Path path, String shader, int rgba) {
      // The cgame's rail: origin at the muzzle, oldorigin at the impact.
      return new CgameFrame.RefEntity(
          type,
          0,
          null,
          -1,
          new Md3Model.Tag("", path.from(), X, Y, Z),
          path.to(),
          0,
          0,
          0,
          path.from(),
          0,
          0,
          Optional.empty(),
          Optional.of(shader),
          rgba,
          0,
          0,
          0,
          0,
          0);
    }
  }

  /** Sarge's three parts and default skins. */
  private record Sarge(
      Md3Model lower,
      Md3Model upper,
      Md3Model head,
      SkinParser.Skin lowerSkin,
      SkinParser.Skin upperSkin,
      SkinParser.Skin headSkin,
      int legsFrame,
      int torsoFrame) {
    static Sarge load(SearchPath search) throws IOException {
      String dir = "models/players/sarge/";
      AnimationConfig animations =
          AnimationConfig.parse(
              new String(
                  search.view().read(VirtualPath.of(dir + "animation.cfg")),
                  StandardCharsets.ISO_8859_1));
      return new Sarge(
          md3(search, dir + "lower.md3"),
          md3(search, dir + "upper.md3"),
          md3(search, dir + "head.md3"),
          skin(search, dir + "lower_default.skin"),
          skin(search, dir + "upper_default.skin"),
          skin(search, dir + "head_default.skin"),
          animations.animations().get(AnimationConfig.PlayerAnimation.LEGS_IDLE).firstFrame(),
          animations.animations().get(AnimationConfig.PlayerAnimation.TORSO_STAND).firstFrame());
    }

    /**
     * The legs at {@code origin} facing {@code facing}, the torso on {@code tag_torso} and the head
     * on {@code tag_head}, all lit from the legs' origin as the cgame does.
     */
    List<CgameFrame.RefEntity> entities(
        Vec3f origin, Vec3f facing, int renderFx, float shadowPlane) {
      Vec3f left = Z.cross(facing).normalize();
      Md3Model.Tag legs = new Md3Model.Tag("", origin, facing, left, Z);
      Md3Model.Tag torso = legs.compose(lower.tag(legsFrame, "tag_torso").orElseThrow());
      Md3Model.Tag skull = torso.compose(upper.tag(torsoFrame, "tag_head").orElseThrow());
      return List.of(
          part(lower, legsFrame, legs, lowerSkin, renderFx, shadowPlane, origin),
          part(upper, torsoFrame, torso, upperSkin, renderFx, shadowPlane, origin),
          part(head, 0, skull, headSkin, renderFx, shadowPlane, origin));
    }

    private static CgameFrame.RefEntity part(
        Md3Model model,
        int frame,
        Md3Model.Tag transform,
        SkinParser.Skin skin,
        int renderFx,
        float shadowPlane,
        Vec3f lightingOrigin) {
      return new CgameFrame.RefEntity(
          CgameFrame.EntityType.MODEL,
          renderFx | CgameFrame.RF_LIGHTING_ORIGIN,
          model,
          -1,
          transform,
          transform.origin(),
          frame,
          frame,
          0,
          lightingOrigin,
          shadowPlane,
          0,
          Optional.of(skin),
          Optional.empty(),
          0xffffffff,
          0,
          0,
          0,
          0,
          0);
    }

    private static Md3Model md3(SearchPath search, String path) throws IOException {
      return Md3Reader.read(search.view().read(VirtualPath.of(path)), path);
    }

    private static SkinParser.Skin skin(SearchPath search, String path) throws IOException {
      return SkinParser.parse(
          new String(search.view().read(VirtualPath.of(path)), StandardCharsets.ISO_8859_1));
    }
  }

  /** Viewports of the no-world frame as fractions of the screen: x, y, width, height. */
  private static final float[] MENU_VIEW = {0.35f, 0.1f, 0.3f, 0.8f};

  private static final float[] HYPERSPACE_VIEW = {0.05f, 0.05f, 0.15f, 0.2f};
  private static final float[] VIDEO_VIEW = {0.3f, 0.2f, 0.4f, 0.6f};

  /**
   * The ui's player view: a dark background quad, then Sarge 85 units in front of a camera at the
   * origin with the ui's two lights, and a small hyperspace view.
   */
  private static FrameMaker menuFrame(Sarge sarge, int time) {
    return (width, height, now) -> {
      List<CgameFrame.Command> commands = new ArrayList<>();
      commands.add(new CgameFrame.Quad(0, 0, width, height, 0, 0, 1, 1, "*white", BACKGROUND));
      int[] v = pixels(MENU_VIEW, width, height);
      Vec3f origin = new Vec3f(85, 0, -4);
      List<CgameFrame.RefEntity> parts = sarge.entities(origin, new Vec3f(-1, 0, 0), 0, 0);
      Vec3f first = origin.add(new Vec3f(-100, 100, 100));
      Vec3f second = first.add(new Vec3f(-100, -100, -100));
      List<DynamicLight> lights =
          List.of(
              new DynamicLight(first, 500, new Vec3f(1, 1, 1)),
              new DynamicLight(second, 500, new Vec3f(1, 0, 0)));
      commands.add(
          new CgameFrame.View(
              menuRefdef(v, now, ViewDefinition.RDF_NOWORLDMODEL), parts, List.of(), lights));
      int[] h = pixels(HYPERSPACE_VIEW, width, height);
      commands.add(
          new CgameFrame.View(
              menuRefdef(h, time, ViewDefinition.RDF_NOWORLDMODEL | ViewDefinition.RDF_HYPERSPACE),
              List.of(),
              List.of(),
              List.of()));
      return new CgameFrame(commands, SceneAssets.EMPTY);
    };
  }

  /** A no-world view of a square textured with the video shader, filling {@link #VIDEO_VIEW}. */
  private static FrameMaker videoFrame(ShaderDefinition video) {
    return (width, height, now) -> {
      List<CgameFrame.Command> commands = new ArrayList<>();
      commands.add(new CgameFrame.Quad(0, 0, width, height, 0, 0, 1, 1, "*white", BACKGROUND));
      int[] v = pixels(VIDEO_VIEW, width, height);
      CgameFrame.Refdef refdef = menuRefdef(v, now, ViewDefinition.RDF_NOWORLDMODEL);
      // A quad facing the camera at x = 64, covering most of the view.
      float half = 64 * (float) Math.tan(Math.toRadians(refdef.fovX() / 2)) * 0.9f;
      float halfY = 64 * (float) Math.tan(Math.toRadians(refdef.fovY() / 2)) * 0.9f;
      float[] positions = {64, half, halfY, 64, -half, halfY, 64, -half, -halfY, 64, half, -halfY};
      CgameFrame.Poly poly =
          new CgameFrame.Poly(
              VIDEO_SHADER,
              positions,
              new float[] {0, 0, 1, 0, 1, 1, 0, 1},
              new int[] {-1, -1, -1, -1});
      commands.add(new CgameFrame.View(refdef, List.of(), List.of(poly), List.of()));
      return new CgameFrame(commands, new SceneAssets(Map.of(VIDEO_SHADER, video), Map.of()));
    };
  }

  private static CgameFrame.Refdef menuRefdef(int[] v, int time, int rdflags) {
    float fovX = 40;
    float fovY =
        (float) Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(fovX) / 2) * v[3] / v[2]));
    return new CgameFrame.Refdef(
        v[0],
        v[1],
        v[2],
        v[3],
        fovX,
        fovY,
        Vec3f.ZERO,
        X,
        Y,
        Z,
        time,
        rdflags,
        AreaMask.NONE,
        List.of());
  }

  private static int[] pixels(float[] fraction, int width, int height) {
    return new int[] {
      Math.round(fraction[0] * width),
      Math.round(fraction[1] * height),
      Math.round(fraction[2] * width),
      Math.round(fraction[3] * height)
    };
  }

  /** Mean of the largest per-sample difference in a window around points along the path. */
  private static double pathDifference(
      BufferedImage before, BufferedImage after, ViewDefinition spawn, Beams.Path path) {
    double sum = 0;
    int samples = 0;
    int window = path.discs() ? 24 : 4;
    for (int i = 1; i <= 7; i++) {
      float f = i / 8f;
      Vec3f p = path.from().lerp(path.to(), f);
      if (path.discs()) {
        // Disc centres sit every 32 units from the impact end.
        float length = path.to().subtract(path.from()).length();
        int disc = Math.min(4, i);
        p = path.to().lerp(path.from(), Math.min(1, 32f * disc / length));
      }
      float[] screen = project(spawn, p, after.getWidth(), after.getHeight());
      if (screen == null) continue;
      sum += windowDifference(before, after, (int) screen[0], (int) screen[1], window);
      samples++;
    }
    return samples == 0 ? 0 : sum / samples;
  }

  private static float[] project(ViewDefinition view, Vec3f p, int width, int height) {
    Vec3f d = p.subtract(view.origin());
    float depth = d.dot(view.basis().forward());
    if (depth <= 1) return null;
    double tanX = Math.tan(Math.toRadians(view.fovX()) / 2);
    double tanY = tanX * height / width;
    float x = (float) (width / 2.0 * (1 + d.dot(view.basis().right()) / depth / tanX));
    float y = (float) (height / 2.0 * (1 - d.dot(view.basis().up()) / depth / tanY));
    return new float[] {x, y};
  }

  private static double windowDifference(
      BufferedImage a, BufferedImage b, int cx, int cy, int radius) {
    double best = 0;
    for (int y = cy - radius; y <= cy + radius; y++)
      for (int x = cx - radius; x <= cx + radius; x++) {
        if (x < 0 || y < 0 || x >= a.getWidth() || y >= a.getHeight()) continue;
        best = Math.max(best, rgbDifference(a.getRGB(x, y), b.getRGB(x, y)));
      }
    return best;
  }

  private static double rgbDifference(int p, int q) {
    return (Math.abs((p >> 16 & 255) - (q >> 16 & 255))
            + Math.abs((p >> 8 & 255) - (q >> 8 & 255))
            + Math.abs((p & 255) - (q & 255)))
        / 3.0;
  }

  /** Fraction of the region's pixels that are not the background colour. */
  private static double coverage(BufferedImage image, float[] region) {
    int[] r = pixels(region, image.getWidth(), image.getHeight());
    int background = BACKGROUND >>> 8;
    long hit = 0, n = 0;
    for (int y = r[1]; y < r[1] + r[3]; y += 2)
      for (int x = r[0]; x < r[0] + r[2]; x += 2) {
        if (rgbDifference(image.getRGB(x, y), background) > 24) hit++;
        n++;
      }
    return (double) hit / n;
  }

  private static int centre(BufferedImage image, float[] region) {
    int[] r = pixels(region, image.getWidth(), image.getHeight());
    return image.getRGB(r[0] + r[2] / 2, r[1] + r[3] / 2);
  }

  private static double difference(BufferedImage a, BufferedImage b, float[] region) {
    int[] r = pixels(region, a.getWidth(), a.getHeight());
    double sum = 0;
    long n = 0;
    for (int y = r[1]; y < r[1] + r[3]; y += 2)
      for (int x = r[0]; x < r[0] + r[2]; x += 2) {
        sum += rgbDifference(a.getRGB(x, y), b.getRGB(x, y));
        n++;
      }
    return sum / n;
  }

  private static double deviation(BufferedImage image, float[] region) {
    int[] r = pixels(region, image.getWidth(), image.getHeight());
    double sum = 0, squares = 0;
    long n = 0;
    for (int y = r[1]; y < r[1] + r[3]; y += 2)
      for (int x = r[0]; x < r[0] + r[2]; x += 2) {
        int p = image.getRGB(x, y);
        double l = 0.299 * (p >> 16 & 255) + 0.587 * (p >> 8 & 255) + 0.114 * (p & 255);
        sum += l;
        squares += l * l;
        n++;
      }
    double mean = sum / n;
    return Math.sqrt(Math.max(0, squares / n - mean * mean));
  }

  private static RenderScene scene(SearchPath search, String map, ShaderLibrary library)
      throws IOException {
    String path = "maps/" + map + ".bsp";
    BspMap bsp = BspReader.read(search.view().read(VirtualPath.of(path)), path);
    return RenderScene.build(path, bsp, library::resolve);
  }

  private static ViewDefinition sized(ViewDefinition view, int width, int height, int time) {
    float fovY =
        (float)
            Math.toDegrees(
                2 * Math.atan(Math.tan(Math.toRadians(view.fovX()) / 2) * height / width));
    return new ViewDefinition(
        view.kind(),
        view.origin(),
        view.basis(),
        view.fovX(),
        fovY,
        0,
        0,
        width,
        height,
        time,
        view.rdflags(),
        view.areaMask(),
        view.clipPlane(),
        view.pvsOrigin(),
        view.portalSurface(),
        view.depth());
  }

  private static CgameFrame.Refdef refdef(
      ViewDefinition view, int x, int y, int width, int height, int rdflags) {
    return new CgameFrame.Refdef(
        x,
        y,
        width,
        height,
        view.fovX(),
        view.fovY(),
        view.origin(),
        view.basis().forward(),
        view.basis().left(),
        view.basis().up(),
        view.timeMillis(),
        rdflags,
        AreaMask.NONE,
        List.of());
  }

  private static CraftQ3ClientMod clientMod() {
    return FabricLoader.getInstance().getEntrypoints("client", ClientModInitializer.class).stream()
        .filter(CraftQ3ClientMod.class::isInstance)
        .map(CraftQ3ClientMod.class::cast)
        .findFirst()
        .orElseThrow(() -> new AssertionError("CraftQ3ClientMod entrypoint not found"));
  }

  private static BufferedImage read(Path path) {
    try {
      BufferedImage image = ImageIO.read(path.toFile());
      if (image == null) throw new AssertionError("Unreadable screenshot " + path);
      return image;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
