package dev.bluevista.craftq3.engine.benchmarks;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.FileViewAssets;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.bsp.BspReader;
import dev.bluevista.craftq3.engine.formats.entity.BspEntity;
import dev.bluevista.craftq3.engine.formats.shader.ShaderLibrary;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import dev.bluevista.craftq3.engine.render.AreaMask;
import dev.bluevista.craftq3.engine.render.Camera;
import dev.bluevista.craftq3.engine.render.PortalViews;
import dev.bluevista.craftq3.engine.render.RenderScene;
import dev.bluevista.craftq3.engine.render.ViewDefinition;
import dev.bluevista.craftq3.engine.render.VisibleSet;
import dev.bluevista.craftq3.engine.render.frame.CgameFrame;
import dev.bluevista.craftq3.engine.render.frame.FrameBuilder;
import dev.bluevista.craftq3.engine.render.frame.FrameGeometry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Render IR costs on a real map (needs {@code CRAFTQ3_Q3ROOT}): {@code sceneBuild} is the one-off
 * {@link RenderScene} build at map load (geometry packing, patch tessellation, material compile);
 * {@code frame} is the per-frame CPU work from every deathmatch spawn in turn: PVS and frustum
 * visibility, portal views and the {@link FrameBuilder} pass over CPU-deformed world surfaces
 * (cgame entities excluded). Sample mode reports p50/p99 for the plan's 8 ms frame budget.
 */
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = "-Xmx2g")
@State(Scope.Thread)
public class RealRenderBenchmark {
  private static final float VIEW_HEIGHT = 26;

  @Param({"q3dm17"})
  public String map;

  private BspMap bsp;
  private ShaderLibrary library;
  private RenderScene scene;
  private FrameBuilder builder;
  private final VisibleSet visible = new VisibleSet();
  private final FrameGeometry geometry = new FrameGeometry();
  private final List<ViewDefinition> views = new ArrayList<>();
  private final List<CgameFrame.View> refdefs = new ArrayList<>();
  private int next;

  @Setup
  public void setUp() throws IOException {
    String path = "maps/" + map + ".bsp";
    try (SearchPath search = SearchPath.mount(RealDataBench.root(), SearchPath.BASE_GAME)) {
      library = ShaderLibrary.load(new FileViewAssets(search.view()));
      bsp = BspReader.read(search.view().read(VirtualPath.of(path)), path);
    }
    scene = RenderScene.build(path, bsp, library::resolve);
    builder = new FrameBuilder(scene, scene.newMaterialRegistry());
    for (Camera camera : cameras(bsp, scene)) {
      ViewDefinition view = ViewDefinition.of(camera, 16f / 9);
      views.add(view);
      CgameFrame.Refdef refdef =
          new CgameFrame.Refdef(
              0,
              0,
              1920,
              1080,
              view.fovX(),
              view.fovY(),
              view.origin(),
              view.basis().forward(),
              view.basis().left(),
              view.basis().up(),
              1000,
              0,
              AreaMask.NONE,
              List.of());
      refdefs.add(new CgameFrame.View(refdef, List.of(), List.of(), List.of()));
    }
  }

  /** Every deathmatch spawn at eye height, looking along its angle, then three more headings. */
  private static List<Camera> cameras(BspMap bsp, RenderScene scene) {
    List<Camera> cameras = new ArrayList<>();
    for (BspEntity entity : bsp.entities()) {
      if (!entity.classname().orElse("").equals("info_player_deathmatch")) continue;
      String[] origin = entity.getOrDefault("origin", "").trim().split("\\s+");
      if (origin.length != 3) continue;
      float yaw = Float.parseFloat(entity.getOrDefault("angle", "0"));
      Vec3f eye =
          new Vec3f(
              Float.parseFloat(origin[0]),
              Float.parseFloat(origin[1]),
              Float.parseFloat(origin[2]) + VIEW_HEIGHT);
      for (int turn = 0; turn < 4; turn++) cameras.add(new Camera(eye, yaw + 90 * turn, 0, 90));
    }
    if (cameras.isEmpty()) cameras.add(scene.spawnCamera());
    return cameras;
  }

  @Benchmark
  @BenchmarkMode(Mode.SingleShotTime)
  @Warmup(iterations = 3)
  @Measurement(iterations = 10)
  public RenderScene sceneBuild() {
    return RenderScene.build("maps/" + map + ".bsp", bsp, library::resolve);
  }

  @Benchmark
  @BenchmarkMode(Mode.SampleTime)
  @Warmup(iterations = 3, time = 1)
  @Measurement(iterations = 5, time = 2)
  public void frame(Blackhole sink) {
    int i = next;
    next = (next + 1) % views.size();
    ViewDefinition view = views.get(i);
    scene.visibility().select(view, true, true, visible);
    sink.consume(
        PortalViews.collect(scene, view, visible, List.of(), PortalViews.DEFAULT_MAX_DEPTH));
    sink.consume(builder.build(refdefs.get(i), view, visible, geometry));
  }
}
