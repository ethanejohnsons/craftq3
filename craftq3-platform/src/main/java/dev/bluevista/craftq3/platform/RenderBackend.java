package dev.bluevista.craftq3.platform;

import dev.bluevista.craftq3.render.RenderScene;

/** Only the host implementation owns GPU resources; scene coordinates remain Q3-native. */
public interface RenderBackend extends AutoCloseable {
  void render(RenderScene scene, int width, int height);

  @Override
  void close();
}
