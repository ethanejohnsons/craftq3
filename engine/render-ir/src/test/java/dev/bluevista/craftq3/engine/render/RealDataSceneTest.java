package dev.bluevista.craftq3.engine.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.formats.FileViewAssets;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.bsp.BspReader;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition;
import dev.bluevista.craftq3.engine.formats.shader.ShaderLibrary;
import dev.bluevista.craftq3.engine.fs.FileRef;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import dev.bluevista.craftq3.engine.render.frame.CgameFrame;
import dev.bluevista.craftq3.engine.render.frame.FrameBuilder;
import dev.bluevista.craftq3.engine.render.frame.FrameGeometry;
import dev.bluevista.craftq3.engine.render.material.MaterialCompiler;
import dev.bluevista.craftq3.engine.render.material.MaterialProgram;
import dev.bluevista.craftq3.engine.render.material.ProgramLayout;
import dev.bluevista.craftq3.engine.render.material.StageProgram;
import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Builds the {@link RenderScene} of every BSP in {@code baseq3} and each mod directory, compiles
 * every shader each game's scripts define, and exercises one frame from each map's spawn point
 * (visibility, portal views, dynamic geometry). Writes {@code build/realdata/render-ir.txt}.
 */
@RealData
class RealDataSceneTest {
  private record MapResult(
      String game,
      String map,
      int vertices,
      int indices,
      int batches,
      int materials,
      int cpuMaterials,
      int portals,
      int visibleBatches,
      int visibleRuns,
      double buildMs,
      double frameMs) {}

  @Test
  void buildsEverySceneAndCompilesEveryMaterial() throws IOException {
    Path root = RealDataRoot.require();
    List<String> games = new ArrayList<>();
    games.add(SearchPath.BASE_GAME);
    try (Stream<Path> entries = Files.list(root)) {
      entries
          .filter(Files::isDirectory)
          .filter(RealDataSceneTest::hasPacks)
          .map(p -> p.getFileName().toString())
          .filter(name -> !name.equalsIgnoreCase(SearchPath.BASE_GAME))
          .sorted()
          .forEach(games::add);
    }
    List<MapResult> results = new ArrayList<>();
    List<String> failures = new ArrayList<>();
    TreeMap<String, List<String>> unexpressed = new TreeMap<>();
    int shaders = 0;
    for (String game : games) {
      try (SearchPath search = SearchPath.mount(root, game)) {
        FileViewAssets assets = new FileViewAssets(search.view());
        ShaderLibrary library = ShaderLibrary.load(assets);
        for (ShaderDefinition definition : library.definitions().values()) {
          shaders++;
          try {
            MaterialProgram program = MaterialCompiler.compile(definition);
            float[] block = new float[ProgramLayout.STAGE_FLOATS];
            for (StageProgram stage : program.stages()) ProgramLayout.packStage(stage, block, 0);
            if (!program.unexpressed().isEmpty())
              unexpressed.put(game + ":" + definition.name(), program.unexpressed());
          } catch (RuntimeException e) {
            failures.add(game + ": shader " + definition.name() + ": " + describe(e));
          }
        }
        for (String path : assets.list("maps", "bsp")) {
          Optional<FileRef> ref = search.view().find(VirtualPath.of(path));
          if (ref.isEmpty()) continue;
          if (!game.equals(SearchPath.BASE_GAME)
              && !ref.get().mount().game().equalsIgnoreCase(game)) continue;
          try {
            results.add(map(game, path, search.view().read(VirtualPath.of(path)), library));
          } catch (RuntimeException e) {
            failures.add(game + ": " + path + ": " + describe(e));
          }
        }
      }
    }
    StringBuilder out = new StringBuilder();
    out.append("Render IR real-data sweep: ")
        .append(results.size())
        .append(" maps, ")
        .append(shaders)
        .append(" shader definitions compiled, ")
        .append(failures.size())
        .append(" failures, ")
        .append(unexpressed.size())
        .append(" materials with unexpressed features\n\n");
    out.append(
        String.format(
            Locale.ROOT,
            "%-12s %-28s %9s %9s %7s %6s %4s %4s %6s %6s %8s %7s%n",
            "game",
            "map",
            "vertices",
            "indices",
            "batches",
            "mats",
            "cpu",
            "prt",
            "visB",
            "runs",
            "buildMs",
            "frameMs"));
    long vertices = 0, indices = 0, batches = 0;
    double buildMs = 0, worstBuild = 0;
    for (MapResult r : results) {
      vertices += r.vertices();
      indices += r.indices();
      batches += r.batches();
      buildMs += r.buildMs();
      worstBuild = Math.max(worstBuild, r.buildMs());
      out.append(
          String.format(
              Locale.ROOT,
              "%-12s %-28s %9d %9d %7d %6d %4d %4d %6d %6d %8.1f %7.2f%n",
              r.game(),
              r.map().replace("maps/", ""),
              r.vertices(),
              r.indices(),
              r.batches(),
              r.materials(),
              r.cpuMaterials(),
              r.portals(),
              r.visibleBatches(),
              r.visibleRuns(),
              r.buildMs(),
              r.frameMs()));
    }
    out.append(
        String.format(
            Locale.ROOT,
            "%nTotals: %d vertices, %d indices, %d batches; build %.0f ms total, %.1f ms mean,"
                + " %.1f ms worst%n",
            vertices,
            indices,
            batches,
            buildMs,
            results.isEmpty() ? 0 : buildMs / results.size(),
            worstBuild));
    out.append("\nFailures:\n");
    failures.forEach(f -> out.append("  ").append(f).append('\n'));
    out.append("\nUnexpressed:\n");
    unexpressed.forEach((k, v) -> out.append("  ").append(k).append(": ").append(v).append('\n'));
    Path report = Path.of("build", "realdata", "render-ir.txt");
    Files.createDirectories(report.getParent());
    Files.writeString(report, out);
    System.out.println(out.substring(0, Math.min(out.length(), 6000)));
    assertTrue(
        failures.isEmpty(), () -> failures.size() + " failures, e.g. " + failures.getFirst());
    assertEquals(0, unexpressed.size(), () -> "unexpressed: " + unexpressed.firstKey());
    assertTrue(results.size() > 200, "every map of the installation was built");
  }

  private static MapResult map(String game, String path, byte[] bytes, ShaderLibrary library) {
    BspMap map = BspReader.read(bytes, path);
    long start = System.nanoTime();
    RenderScene scene = RenderScene.build(path, map, library::resolve);
    double buildMs = (System.nanoTime() - start) / 1e6;
    int cpu = 0;
    for (MaterialProgram program : scene.materials()) if (program.needsCpuDeform()) cpu++;
    WorldGeometry world = scene.world();
    if (world.indexCount() % 3 != 0) throw new IllegalStateException("partial triangle");
    for (int i = 0; i < world.indexCount(); i++)
      if (world.indices()[i] < 0 || world.indices()[i] >= world.vertexCount())
        throw new IllegalStateException("index outside the vertex buffer");

    // One frame from the spawn point: visibility, portal views and the dynamic geometry path.
    long frameStart = System.nanoTime();
    ViewDefinition view = ViewDefinition.of(scene.spawnCamera(), 4f / 3);
    VisibleSet visible = new VisibleSet();
    scene.visibility().select(view, true, true, visible);
    PortalViews.collect(scene, view, visible, List.of(), PortalViews.DEFAULT_MAX_DEPTH);
    CgameFrame.Refdef refdef =
        new CgameFrame.Refdef(
            0,
            0,
            640,
            480,
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
    FrameBuilder builder = new FrameBuilder(scene, scene.newMaterialRegistry());
    builder.build(
        new CgameFrame.View(refdef, List.of(), List.of(), List.of()),
        view,
        visible,
        new FrameGeometry());
    double frameMs = (System.nanoTime() - frameStart) / 1e6;
    return new MapResult(
        game,
        path,
        world.vertexCount(),
        world.indexCount(),
        world.batches().size(),
        scene.materials().size(),
        cpu,
        scene.portalSurfaces().length,
        visible.batchCount(),
        visible.runCount(),
        buildMs,
        frameMs);
  }

  private static boolean hasPacks(Path directory) {
    try (Stream<Path> files = Files.list(directory)) {
      return files.anyMatch(
          p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pk3"));
    } catch (IOException e) {
      throw new IllegalStateException("Cannot list " + directory, e);
    }
  }

  private static String describe(RuntimeException e) {
    StringWriter trace = new StringWriter();
    e.printStackTrace(new PrintWriter(trace));
    String text = trace.toString();
    return text.substring(0, Math.min(600, text.length())).replace('\n', ' ');
  }
}
