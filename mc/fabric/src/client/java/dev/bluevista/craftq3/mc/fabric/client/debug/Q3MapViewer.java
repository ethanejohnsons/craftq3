package dev.bluevista.craftq3.mc.fabric.client.debug;

import dev.bluevista.craftq3.engine.common.time.Clock;
import dev.bluevista.craftq3.engine.formats.FileViewAssets;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.bsp.BspReader;
import dev.bluevista.craftq3.engine.formats.shader.ShaderLibrary;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import dev.bluevista.craftq3.engine.render.RenderScene;
import dev.bluevista.craftq3.engine.render.SceneTextures;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3RenderBackend;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3RenderContext;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;

/**
 * Loads a map of the configured installation for {@code /q3debug map}: mounting, shader scripts,
 * the BSP, the {@link RenderScene} and every texture decode on a virtual thread; only the GPU
 * upload and the screen run on the render thread. The screen owns the backend and search path.
 */
public final class Q3MapViewer {
  private Q3MapViewer() {}

  private static void closeQuietly(SearchPath search, Throwable cause) {
    if (search == null) return;
    try {
      search.close();
    } catch (java.io.IOException e) {
      cause.addSuppressed(e);
    }
  }

  /** What the background load produced. */
  private record Loaded(
      SearchPath search,
      FileViewAssets assets,
      ShaderLibrary library,
      RenderScene scene,
      SceneTextures textures) {}

  /**
   * Opens {@code map} ({@code maps/<map>.bsp}) of {@code game} under {@code root}; completes with
   * the scene name once the screen is open.
   */
  public static CompletableFuture<String> open(
      Minecraft client, Q3RenderContext context, Path root, String game, String map) {
    CompletableFuture<Loaded> load = new CompletableFuture<>();
    Thread.ofVirtual()
        .name("craftq3-map-load")
        .start(
            () -> {
              SearchPath search = null;
              try {
                search = SearchPath.mount(root, game);
                FileViewAssets assets = new FileViewAssets(search.view());
                ShaderLibrary library = ShaderLibrary.load(assets);
                String path = "maps/" + map + ".bsp";
                BspMap bsp = BspReader.read(search.view().read(VirtualPath.of(path)), path);
                RenderScene scene = RenderScene.build(path, bsp, library::resolve);
                load.complete(
                    new Loaded(search, assets, library, scene, SceneTextures.load(assets, scene)));
              } catch (Throwable t) {
                closeQuietly(search, t);
                load.completeExceptionally(t);
              }
            });
    return load.thenApplyAsync(
        loaded -> {
          Q3RenderBackend backend =
              new Q3RenderBackend(context, loaded.library()::resolve, loaded.assets());
          try {
            backend.loadMap(loaded.scene(), loaded.textures(), loaded.assets());
          } catch (RuntimeException e) {
            backend.close();
            closeQuietly(loaded.search(), e);
            throw e;
          }
          RenderScene scene = loaded.scene();
          Q3MapViewScreen.Scene view =
              (width, height, time) -> Q3RenderBackend.spawnView(scene, width, height, time);
          AutoCloseable owned =
              () -> {
                backend.close();
                loaded.search().close();
              };
          client.gui.setScreen(
              new Q3MapViewScreen(backend, view, Clock.system(), client.gui.screen(), owned));
          return scene.name();
        },
        client);
  }
}
