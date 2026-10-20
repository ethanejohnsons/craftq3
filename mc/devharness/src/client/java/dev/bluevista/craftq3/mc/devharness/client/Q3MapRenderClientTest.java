package dev.bluevista.craftq3.mc.devharness.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.common.time.ManualClock;
import dev.bluevista.craftq3.engine.formats.FileViewAssets;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.bsp.BspReader;
import dev.bluevista.craftq3.engine.formats.image.Q3Image;
import dev.bluevista.craftq3.engine.formats.md3.Md3Model;
import dev.bluevista.craftq3.engine.formats.md3.Md3Reader;
import dev.bluevista.craftq3.engine.formats.shader.ShaderLibrary;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import dev.bluevista.craftq3.engine.render.AreaMask;
import dev.bluevista.craftq3.engine.render.DynamicLight;
import dev.bluevista.craftq3.engine.render.RenderScene;
import dev.bluevista.craftq3.engine.render.SceneTextures;
import dev.bluevista.craftq3.engine.render.ViewBasis;
import dev.bluevista.craftq3.engine.render.ViewDefinition;
import dev.bluevista.craftq3.engine.render.WorldGeometry;
import dev.bluevista.craftq3.engine.render.frame.CgameFrame;
import dev.bluevista.craftq3.engine.render.material.TextureBundle;
import dev.bluevista.craftq3.mc.fabric.client.CraftQ3ClientMod;
import dev.bluevista.craftq3.mc.fabric.client.debug.Q3MapViewScreen;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3RenderBackend;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3RenderContext;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * The Quake III renderer on the real GPU with the maintainer's installation ({@code
 * CRAFTQ3_Q3ROOT}; skipped when unset; {@code CRAFTQ3_TEST_MAPS=a,b} limits the maps). For each
 * map: the spawn view (info_player_deathmatch) with every inline model, a view pitched up 25°, a
 * view facing the largest skybox surface, a portal or mirror surface and a fogged surface where the
 * map has them. On q3dm17 the spawn frame is a whole {@link CgameFrame}: an MD3 item, a sprite, a
 * rail, a poly, a dynamic light, a world-less HUD view in a corner, 2D quads and a cinematic frame.
 *
 * <p>Screenshots must not be flat and skybox views must not be black. The backend's CPU time
 * (beginFrame..endFrame) over 200 frames at 1920×1080 must have a p99 within 8 ms; the numbers go
 * to {@code craftq3-map-report-<backend>.txt} in the run directory. Reloading a map must leave the
 * same textures and map objects resident, and closing the backend must release them.
 */
public final class Q3MapRenderClientTest implements FabricClientGameTest {
  private static final Logger LOGGER = LoggerFactory.getLogger(Q3MapRenderClientTest.class);
  private static final List<String> MAPS =
      // pasctf3 ships the installation's only videoMap shaders (scripts/proto2.shader, naming
      // Team Arena RoQs that are absent), though none of its faces use them; a map whose faces
      // do also gets a view of its largest videoMap surface. Q3EffectsRenderClientTest plays one.
      // gon3 has a skyparms near box.
      List.of(
          "q3dm17",
          "q3dm1",
          "q3tourney2",
          "q3dm0",
          "q3ctf1",
          "q3dm11",
          "simpsons_q3",
          "pasctf3",
          "gon3");
  private static final int WIDTH = 1920, HEIGHT = 1080;
  private static final int TIMED_FRAMES = 200;

  /** REWRITE.md §3: in-game p99 under 8 ms at 1080p. */
  private static final double BUDGET_P99_MILLIS = 8;

  private static final double MIN_DEVIATION = 12;
  private static final double MAX_SKY_BLACK = 0.05;

  private record Prepared(RenderScene scene, SceneTextures textures) {}

  @Override
  public void runTest(ClientGameTestContext context) {
    String root = System.getenv("CRAFTQ3_Q3ROOT");
    if (root == null || root.isBlank() || !Files.isDirectory(Path.of(root))) {
      LOGGER.warn("CRAFTQ3_Q3ROOT is not set; skipping the Quake III map render test");
      return;
    }
    String backendName =
        context.computeOnClient(c -> RenderSystem.getDevice().getDeviceInfo().backendName());
    String tag = backendName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    context.waitForScreen(TitleScreen.class);
    context.getInput().resizeWindow(WIDTH, HEIGHT);
    context.waitTicks(3);

    StringBuilder report = new StringBuilder();
    report.append("CraftQ3 map render on ").append(backendName).append('\n');
    List<String> failures = new ArrayList<>();
    try (SearchPath search = SearchPath.mount(Path.of(root), SearchPath.BASE_GAME)) {
      FileViewAssets assets = new FileViewAssets(search.view());
      ShaderLibrary library = ShaderLibrary.load(assets);
      Q3RenderContext renderContext = clientMod().renderContext();
      Q3RenderBackend backend =
          context.computeOnClient(
              c -> new Q3RenderBackend(renderContext, library::resolve, assets));
      ManualClock clock = new ManualClock(2000);
      String firstResident = null;
      int firstObjects = 0;
      String only = System.getenv("CRAFTQ3_TEST_MAPS");
      List<String> maps = only == null || only.isBlank() ? MAPS : List.of(only.split(","));
      for (String map : maps) {
        Prepared prepared = prepare(search, assets, library, map);
        if (prepared == null) {
          report.append(map).append(": not in the installation\n");
          continue;
        }
        RenderScene scene = prepared.scene();
        context.runOnClient(c -> backend.loadMap(scene, prepared.textures(), assets));
        Q3MapViewScreen.Scene view =
            spawnScene(scene, map.equals("q3dm17") ? submissions(scene, search) : null);
        Q3MapViewScreen screen =
            context.computeOnClient(c -> new Q3MapViewScreen(backend, view, clock, null, null));
        screen.showStats(false);
        context.setScreen(() -> screen);
        context.waitForScreen(Q3MapViewScreen.class);
        context.waitTicks(3);
        String name = "craftq3-map-" + tag + "-" + map;
        BufferedImage spawn = read(context.takeScreenshot(name + "-spawn"));
        double deviation = deviation(spawn, 0, 0, spawn.getWidth(), spawn.getHeight());
        report.append(
            String.format(Locale.ROOT, "%s: spawn luminance deviation %.1f%n", map, deviation));
        if (deviation < MIN_DEVIATION)
          failures.add(map + " spawn view is flat (deviation " + deviation + ")");

        // CPU time of the backend over 200 frames of the spawn view.
        long before = screen.framesRendered();
        context.waitFor(c -> screen.framesRendered() >= before + TIMED_FRAMES, 20 * 60);
        long[] cpu = screen.recentCpuNanos(TIMED_FRAMES);
        String stats = context.computeOnClient(c -> backend.stats().toString());
        double p99 = Q3MapViewScreen.percentileMillis(cpu, 99);
        if (p99 > BUDGET_P99_MILLIS)
          failures.add(map + " render submission p99 " + p99 + " ms exceeds " + BUDGET_P99_MILLIS);
        report.append(
            String.format(
                Locale.ROOT,
                "%s: cpu p50 %.2f ms p99 %.2f ms max %.2f ms over %d frames; %s%n",
                map,
                Q3MapViewScreen.percentileMillis(cpu, 50),
                Q3MapViewScreen.percentileMillis(cpu, 99),
                Q3MapViewScreen.percentileMillis(cpu, 100),
                cpu.length,
                stats));

        ViewDefinition spawnView = Q3RenderBackend.spawnView(scene, WIDTH, HEIGHT, 0);
        ViewDefinition horizon =
            new ViewDefinition(
                ViewDefinition.Kind.MAIN,
                spawnView.origin(),
                ViewBasis.of(
                    new dev.bluevista.craftq3.engine.render.Camera(
                        spawnView.origin(), scene.spawnCamera().yaw(), -25, 90)),
                90,
                59,
                0,
                0,
                WIDTH,
                HEIGHT,
                0,
                0,
                AreaMask.NONE,
                Optional.empty(),
                spawnView.origin(),
                -1,
                0);
        screen.scene((w, h, t) -> sized(horizon, w, h, t));
        context.waitTicks(2);
        context.takeScreenshot(name + "-up25");
        int skySurface = surface(scene, WorldGeometry.SURF_SKY, false);
        ViewDefinition skyView = facing(scene, skySurface, 160);
        if (skyView != null
            && scene
                .material(scene.world().surfaceMaterial(skySurface))
                .sky()
                .filter(sky -> !sky.nearBox().equals("-"))
                .isPresent()) {
          // Q3 1.32 never draws the inner box; the opt-in drawing must not break the sky.
          screen.scene((w, h, t) -> sized(skyView, w, h, t));
          context.runOnClient(c -> backend.drawSkyNearBox(true));
          context.waitTicks(2);
          context.takeScreenshot(name + "-sky-nearbox");
          context.runOnClient(c -> backend.drawSkyNearBox(false));
        }
        if (skyView != null) {
          screen.scene((w, h, t) -> sized(skyView, w, h, t));
          context.waitTicks(2);
          BufferedImage sky = read(context.takeScreenshot(name + "-sky"));
          double black =
              blackFraction(
                  sky,
                  sky.getWidth() / 4,
                  sky.getHeight() / 4,
                  sky.getWidth() * 3 / 4,
                  sky.getHeight() * 3 / 4);
          report.append(
              String.format(Locale.ROOT, "%s: sky view black fraction %.3f%n", map, black));
          if (black > MAX_SKY_BLACK) failures.add(map + " sky view is black (" + black + ")");
        }
        int[] portals = scene.portalSurfaces();
        report
            .append(map)
            .append(": ")
            .append(portals.length)
            .append(" portal surfaces, ")
            .append(scene.fog().count())
            .append(" fog volumes\n");
        if (portals.length > 0) {
          ViewDefinition portalView = facing(scene, portals[0], 128);
          if (portalView != null) {
            screen.scene((w, h, t) -> sized(portalView, w, h, t));
            context.waitTicks(2);
            context.takeScreenshot(name + "-portal");
            report
                .append(map)
                .append(": portal view ")
                .append(
                    context.<String, RuntimeException>computeOnClient(
                        c -> backend.stats().toString()))
                .append('\n');
          }
        }
        int videoSurface = videoSurface(scene);
        if (videoSurface >= 0) {
          ViewDefinition videoView = facing(scene, videoSurface, 128);
          if (videoView != null) {
            screen.scene((w, h, t) -> sized(videoView, w, h, t));
            context.waitTicks(4);
            context.takeScreenshot(name + "-videomap");
            report
                .append(map)
                .append(": videoMap surface ")
                .append(videoSurface)
                .append(", streams ")
                .append(
                    context.<Integer, RuntimeException>computeOnClient(c -> backend.videoStreams()))
                .append('\n');
          }
        }
        if (scene.fog().count() > 0) {
          ViewDefinition fogView = facing(scene, surface(scene, 0, true), 192);
          if (fogView != null) {
            screen.scene((w, h, t) -> sized(fogView, w, h, t));
            context.waitTicks(2);
            context.takeScreenshot(name + "-fog");
          }
        }
        String resident = context.computeOnClient(c -> renderContext.residentSummary());
        int objects = context.computeOnClient(c -> backend.mapGpuObjects());
        report
            .append(map)
            .append(": resident ")
            .append(resident)
            .append(", map objects ")
            .append(objects)
            .append('\n');
        if (firstResident == null) {
          firstResident = resident;
          firstObjects = objects;
        }
        context.setScreen(() -> null);
      }

      // Leak check: reloading the first map returns to the same resident set.
      Prepared again = prepare(search, assets, library, maps.getFirst());
      if (again != null) {
        context.runOnClient(c -> backend.loadMap(again.scene(), again.textures(), assets));
        Q3MapViewScreen.Scene view =
            spawnScene(
                again.scene(),
                maps.getFirst().equals("q3dm17") ? submissions(again.scene(), search) : null);
        Q3MapViewScreen screen =
            context.computeOnClient(c -> new Q3MapViewScreen(backend, view, clock, null, null));
        screen.showStats(false);
        context.setScreen(() -> screen);
        context.waitForScreen(Q3MapViewScreen.class);
        context.waitTicks(3);
        context.setScreen(() -> null);
        String resident = context.computeOnClient(c -> renderContext.residentSummary());
        int objects = context.computeOnClient(c -> backend.mapGpuObjects());
        report
            .append("reload ")
            .append(MAPS.getFirst())
            .append(": resident ")
            .append(resident)
            .append(", map objects ")
            .append(objects)
            .append('\n');
        if (!sameResidentTextures(firstResident, resident) || objects != firstObjects)
          failures.add(
              "Reloading "
                  + MAPS.getFirst()
                  + " changed the resident GPU set: "
                  + firstResident
                  + " vs "
                  + resident
                  + ", "
                  + firstObjects
                  + " vs "
                  + objects);
      }
      context.runOnClient(c -> backend.close());
      String closed = context.computeOnClient(c -> renderContext.residentSummary());
      report.append("after close: ").append(closed).append('\n');
      int textures = context.computeOnClient(c -> renderContext.textures().size());
      if (textures > 3) failures.add("Closing the backend left " + textures + " textures resident");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    report.append("failures: ").append(failures).append('\n');
    LOGGER.info("\n{}", report);
    try {
      Files.writeString(Path.of("craftq3-map-report-" + tag + ".txt"), report);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (!failures.isEmpty()) throw new AssertionError(String.join("; ", failures));
  }

  private static boolean sameResidentTextures(String a, String b) {
    return a != null && a.substring(0, a.indexOf(' ')).equals(b.substring(0, b.indexOf(' ')));
  }

  private static Prepared prepare(
      SearchPath search, FileViewAssets assets, ShaderLibrary library, String map) {
    String path = "maps/" + map + ".bsp";
    try {
      if (search.view().find(VirtualPath.of(path)).isEmpty()) return null;
      BspMap bsp = BspReader.read(search.view().read(VirtualPath.of(path)), path);
      RenderScene scene = RenderScene.build(path, bsp, library::resolve);
      return new Prepared(scene, SceneTextures.load(assets, scene));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The spawn view, with optional entity submissions in front of it. */
  private static Q3MapViewScreen.Scene spawnScene(
      RenderScene scene, Q3MapViewScreen.Scene submissions) {
    ViewDefinition spawn = Q3RenderBackend.spawnView(scene, WIDTH, HEIGHT, 0);
    if (submissions != null) return submissions;
    List<CgameFrame.RefEntity> inline = inlineModels(scene);
    return new Q3MapViewScreen.Scene() {
      @Override
      public ViewDefinition view(int width, int height, int timeMillis) {
        return sized(spawn, width, height, timeMillis);
      }

      @Override
      public List<CgameFrame.RefEntity> entities() {
        return inline;
      }
    };
  }

  /** Every inline BSP model (doors, platforms) at rest, as the cgame submits them. */
  private static List<CgameFrame.RefEntity> inlineModels(RenderScene scene) {
    List<CgameFrame.RefEntity> out = new ArrayList<>();
    for (int model = 1; model < scene.bsp().models().size(); model++)
      out.add(
          new CgameFrame.RefEntity(
              CgameFrame.EntityType.MODEL,
              0,
              null,
              model,
              new Md3Model.Tag(
                  "", Vec3f.ZERO, new Vec3f(1, 0, 0), new Vec3f(0, 1, 0), new Vec3f(0, 0, 1)),
              Vec3f.ZERO,
              0,
              0,
              0,
              Vec3f.ZERO,
              0,
              0,
              Optional.empty(),
              Optional.empty(),
              0xffffffff,
              0,
              0,
              0,
              0,
              0));
    return out;
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

  /** A view from {@code distance} units in front of {@code surface}, looking at its centre. */
  private static ViewDefinition facing(RenderScene scene, int surface, float distance) {
    if (surface < 0) return null;
    WorldGeometry world = scene.world();
    Vec3f center =
        new Vec3f(
            (world.surfaceBound(surface, 0) + world.surfaceBound(surface, 3)) / 2,
            (world.surfaceBound(surface, 1) + world.surfaceBound(surface, 4)) / 2,
            (world.surfaceBound(surface, 2) + world.surfaceBound(surface, 5)) / 2);
    float[] n = world.vertexArrays().normals();
    int v = world.surfaceFirstVertex(surface);
    Vec3f normal = new Vec3f(n[v * 3], n[v * 3 + 1], n[v * 3 + 2]).normalize();
    if (normal.lengthSquared() < 0.5f) return null;
    Vec3f eye = center.multiplyAdd(distance, normal);
    Vec3f forward = normal.negate();
    Vec3f up = Math.abs(forward.z()) > 0.9f ? new Vec3f(1, 0, 0) : new Vec3f(0, 0, 1);
    Vec3f right = forward.cross(up).normalize();
    up = right.cross(forward).normalize();
    return new ViewDefinition(
        ViewDefinition.Kind.MAIN,
        eye,
        new ViewBasis(forward, right, up),
        90,
        59,
        0,
        0,
        WIDTH,
        HEIGHT,
        0,
        0,
        AreaMask.NONE,
        Optional.empty(),
        eye,
        -1,
        0);
  }

  /** The largest drawn world surface whose material plays a {@code videoMap}, or -1. */
  private static int videoSurface(RenderScene scene) {
    WorldGeometry world = scene.world();
    int best = -1;
    double bestSize = 0;
    for (int s = 0; s < world.surfaceCount(); s++) {
      if (world.surfaceModel(s) != 0 || world.surfaceBatch(s) < 0) continue;
      boolean video =
          scene.material(world.surfaceMaterial(s)).stages().stream()
              .anyMatch(stage -> stage.texture().kind() == TextureBundle.Kind.VIDEO);
      if (!video) continue;
      double size = 0;
      for (int c = 0; c < 3; c++) size += world.surfaceBound(s, c + 3) - world.surfaceBound(s, c);
      if (size > bestSize) {
        bestSize = size;
        best = s;
      }
    }
    return best;
  }

  /** The drawn surface with the largest extent having {@code flag} (or any fog volume). */
  private static int surface(RenderScene scene, int flag, boolean fogged) {
    WorldGeometry world = scene.world();
    int best = -1;
    double bestSize = 0;
    for (int s = 0; s < world.surfaceCount(); s++) {
      if (world.surfaceModel(s) != 0) continue;
      boolean match =
          fogged
              ? world.surfaceFog(s) >= 0 && world.surfaceBatch(s) >= 0
              : (world.surfaceFlags(s) & flag) != 0
                  && scene.material(world.surfaceMaterial(s)).isSky();
      if (!match) continue;
      double size = 0;
      for (int c = 0; c < 3; c++) size += world.surfaceBound(s, c + 3) - world.surfaceBound(s, c);
      if (size > bestSize) {
        bestSize = size;
        best = s;
      }
    }
    return best;
  }

  /** An MD3 item, a sprite, a rail, a poly, a dynamic light and HUD quads in front of the spawn. */
  private static Q3MapViewScreen.Scene submissions(RenderScene scene, SearchPath search) {
    ViewDefinition spawn = Q3RenderBackend.spawnView(scene, WIDTH, HEIGHT, 0);
    Vec3f o = spawn.origin(),
        f = spawn.basis().forward(),
        r = spawn.basis().right(),
        u = spawn.basis().up();
    Md3Model armor;
    try {
      armor =
          Md3Reader.read(
              search.view().read(VirtualPath.of("models/powerups/armor/armor_red.md3")),
              "armor_red");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Vec3f armorAt = o.multiplyAdd(96, f).multiplyAdd(-16, u);
    List<CgameFrame.RefEntity> entities = new ArrayList<>(inlineModels(scene));
    entities.addAll(
        List.of(
            entity(CgameFrame.EntityType.MODEL, armor, armorAt, armorAt, Optional.empty(), 0),
            entity(
                CgameFrame.EntityType.SPRITE,
                null,
                o.multiplyAdd(160, f).multiplyAdd(48, r).multiplyAdd(16, u),
                o,
                Optional.of("flareShader"),
                24),
            entity(
                CgameFrame.EntityType.RAIL_CORE,
                null,
                o.multiplyAdd(48, f).multiplyAdd(-24, r).multiplyAdd(-12, u),
                o.multiplyAdd(600, f).multiplyAdd(-24, r),
                Optional.of("railCore"),
                3)));
    // A second, world-less view in a corner (a HUD model), as the cgame draws the status bar head.
    CgameFrame.RefEntity hudArmor =
        entity(
            CgameFrame.EntityType.MODEL,
            armor,
            new Vec3f(70, 0, -8),
            new Vec3f(70, 0, -8),
            Optional.empty(),
            0);
    Q3Image movie = checker();
    Vec3f p = o.multiplyAdd(140, f).multiplyAdd(-60, r);
    float[] positions = new float[12];
    Vec3f[] corners = {
      p.multiplyAdd(-16, r).multiplyAdd(16, u),
      p.multiplyAdd(16, r).multiplyAdd(16, u),
      p.multiplyAdd(16, r).multiplyAdd(-16, u),
      p.multiplyAdd(-16, r).multiplyAdd(-16, u)
    };
    for (int i = 0; i < 4; i++) {
      positions[i * 3] = corners[i].x();
      positions[i * 3 + 1] = corners[i].y();
      positions[i * 3 + 2] = corners[i].z();
    }
    CgameFrame.Poly poly =
        new CgameFrame.Poly(
            "flareShader",
            positions,
            new float[] {0, 0, 1, 0, 1, 1, 0, 1},
            new int[] {-1, -1, -1, -1});
    List<DynamicLight> lights =
        List.of(
            new DynamicLight(
                o.multiplyAdd(150, f).multiplyAdd(-30, u), 220, new Vec3f(1, 0.35f, 0.1f)));
    return new Q3MapViewScreen.Scene() {
      @Override
      public ViewDefinition view(int width, int height, int timeMillis) {
        return sized(spawn, width, height, timeMillis);
      }

      @Override
      public List<CgameFrame.RefEntity> entities() {
        return entities;
      }

      @Override
      public List<CgameFrame.Poly> polygons() {
        return List.of(poly);
      }

      @Override
      public List<DynamicLight> lights() {
        return lights;
      }

      @Override
      public CgameFrame frame(int width, int height, int timeMillis) {
        ViewDefinition main = sized(spawn, width, height, timeMillis);
        List<CgameFrame.Command> commands = new ArrayList<>();
        commands.add(
            new CgameFrame.View(
                refdef(main, 0, 0, width, height, 0), entities, List.of(poly), lights));
        commands.add(
            new CgameFrame.View(
                refdef(
                    new ViewDefinition(
                        ViewDefinition.Kind.MAIN,
                        Vec3f.ZERO,
                        new ViewBasis(new Vec3f(1, 0, 0), new Vec3f(0, -1, 0), new Vec3f(0, 0, 1)),
                        40,
                        40,
                        0,
                        0,
                        1,
                        1,
                        timeMillis,
                        0,
                        AreaMask.NONE,
                        Optional.empty(),
                        Vec3f.ZERO,
                        -1,
                        0),
                    width - 340,
                    40,
                    300,
                    300,
                    ViewDefinition.RDF_NOWORLDMODEL),
                List.of(hudArmor),
                List.of(),
                List.of()));
        commands.addAll(overlay(width, height));
        commands.add(new CgameFrame.Image(7, width - 340, height - 240, 300, 200, movie));
        return new CgameFrame(
            commands, dev.bluevista.craftq3.engine.render.frame.SceneAssets.EMPTY);
      }

      @Override
      public List<CgameFrame.Quad> overlay(int width, int height) {
        List<CgameFrame.Quad> quads = new ArrayList<>();
        quads.add(
            new CgameFrame.Quad(
                width / 2f - 16,
                height / 2f - 16,
                32,
                32,
                0,
                0,
                1,
                1,
                "gfx/2d/crosshaira",
                0xffffffff));
        String text = "CRAFTQ3";
        for (int i = 0; i < text.length(); i++) {
          int ch = text.charAt(i);
          float s = (ch & 15) / 16f, t = (ch >> 4) / 16f;
          quads.add(
              new CgameFrame.Quad(
                  32 + i * 32,
                  32,
                  32,
                  32,
                  s,
                  t,
                  s + 1 / 16f,
                  t + 1 / 16f,
                  "gfx/2d/bigchars",
                  0xffcc33ff));
        }
        return quads;
      }
    };
  }

  private static CgameFrame.Refdef refdef(
      ViewDefinition view, int x, int y, int width, int height, int rdflags) {
    float fovY =
        (float)
            Math.toDegrees(
                2 * Math.atan(Math.tan(Math.toRadians(view.fovX()) / 2) * height / width));
    return new CgameFrame.Refdef(
        x,
        y,
        width,
        height,
        view.fovX(),
        fovY,
        view.origin(),
        view.basis().forward(),
        view.basis().left(),
        view.basis().up(),
        view.timeMillis(),
        rdflags,
        AreaMask.NONE,
        List.of());
  }

  /** A 64×64 test card standing in for a RoQ frame. */
  private static Q3Image checker() {
    byte[] rgba = new byte[64 * 64 * 4];
    for (int y = 0; y < 64; y++)
      for (int x = 0; x < 64; x++) {
        int i = (y * 64 + x) * 4;
        boolean on = ((x / 8) + (y / 8)) % 2 == 0;
        rgba[i] = (byte) (on ? 255 : 30);
        rgba[i + 1] = (byte) (on ? 200 : 30);
        rgba[i + 2] = (byte) (on ? 40 : 120);
        rgba[i + 3] = (byte) 255;
      }
    return new Q3Image(64, 64, rgba);
  }

  private static CgameFrame.RefEntity entity(
      CgameFrame.EntityType type,
      Md3Model model,
      Vec3f origin,
      Vec3f oldOrigin,
      Optional<String> shader,
      float radius) {
    return new CgameFrame.RefEntity(
        type,
        0,
        model,
        -1,
        new Md3Model.Tag("", origin, new Vec3f(1, 0, 0), new Vec3f(0, 1, 0), new Vec3f(0, 0, 1)),
        oldOrigin,
        0,
        0,
        0,
        origin,
        0,
        0,
        Optional.empty(),
        shader,
        0xffffffff,
        0,
        0,
        0,
        radius,
        0);
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

  private static double deviation(BufferedImage image, int x0, int y0, int x1, int y1) {
    double sum = 0, squares = 0;
    long n = 0;
    for (int y = y0; y < y1; y += 2)
      for (int x = x0; x < x1; x += 2) {
        int p = image.getRGB(x, y);
        double l = 0.299 * (p >> 16 & 255) + 0.587 * (p >> 8 & 255) + 0.114 * (p & 255);
        sum += l;
        squares += l * l;
        n++;
      }
    double mean = sum / n;
    return Math.sqrt(Math.max(0, squares / n - mean * mean));
  }

  private static double blackFraction(BufferedImage image, int x0, int y0, int x1, int y1) {
    long black = 0, n = 0;
    for (int y = y0; y < y1; y += 2)
      for (int x = x0; x < x1; x += 2) {
        int p = image.getRGB(x, y);
        if ((p >> 16 & 255) < 6 && (p >> 8 & 255) < 6 && (p & 255) < 6) black++;
        n++;
      }
    return (double) black / n;
  }
}
