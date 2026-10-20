package dev.bluevista.craftq3.mc.fabric.client.debug;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.bluevista.craftq3.engine.common.time.Clock;
import dev.bluevista.craftq3.mc.fabric.client.debug.Q3SpikeScene.Layer;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3FullscreenView;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3RenderContext;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3ViewParams;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * {@code /q3debug spike}: the Phase 0 Blaze3D spike. Renders {@link Q3SpikeScene} full screen
 * through the render takeover; every animation is driven by the per-view time uniform, the static
 * mesh is uploaded once per {@link Q3RenderContext}, and only the dynamic cell touches the vertex
 * ring each frame.
 */
public final class Q3SpikeScreen extends Screen implements Q3FullscreenView {
  private static final Vector4f CLEAR = new Vector4f(0.05f, 0.05f, 0.08f, 1);

  private final Q3RenderContext context;
  private final Clock clock;
  private final Screen parent;
  private final long startMillis;
  private long framesRendered;

  public Q3SpikeScreen(Q3RenderContext context, Clock clock, Screen parent) {
    super(Component.literal("CraftQ3 Blaze3D spike"));
    this.context = context;
    this.clock = clock;
    this.parent = parent;
    this.startMillis = clock.millis();
  }

  /** Frames this screen has drawn through the takeover pass. */
  public long framesRendered() {
    return framesRendered;
  }

  @Override
  public void renderFullscreen(RenderTarget target) {
    float time = (clock.millis() - startMillis) / 1000f;
    var device = RenderSystem.getDevice();
    boolean zZeroToOne = device.getDeviceInfo().isZZeroToOne();
    Matrix4f projection =
        new Matrix4f()
            .setOrtho(
                0, Q3SpikeScene.CANVAS_WIDTH, Q3SpikeScene.CANVAS_HEIGHT, 0, -1, 1, zZeroToOne);
    float eyeX = Q3SpikeScene.CANVAS_WIDTH / 2f + 300 * (float) Math.cos(time);
    float eyeY = Q3SpikeScene.CANVAS_HEIGHT / 2f + 300 * (float) Math.sin(time);

    // Everything that maps or uploads happens before the pass opens.
    var mesh = context.staticMesh("spike/grid", Q3SpikeScene::staticVertices);
    GpuBufferSlice dynamic = context.writeDynamicVertices(Q3SpikeScene.dynamicVertices());
    GpuBufferSlice view = context.viewUniform(new Q3ViewParams(projection, eyeX, eyeY, 600, time));
    GpuTextureView lightmap =
        context.texture(Q3SpikeScene.LIGHTMAP, 16, 16, Q3SpikeScene::lightmap);
    GpuSampler repeat = context.sampler(AddressMode.REPEAT, FilterMode.LINEAR);
    GpuSampler clamp = context.sampler(AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR);
    List<Draw> draws = new ArrayList<>();
    for (int cell = 0; cell < Q3SpikeScene.CELLS.size(); cell++)
      for (Layer layer : Q3SpikeScene.CELLS.get(cell))
        draws.add(draw(layer, mesh.buffer().slice(), cell * Q3SpikeScene.VERTICES_PER_CELL));
    draws.add(draw(Q3SpikeScene.DYNAMIC_LAYER, dynamic, 0));

    try (RenderPass pass =
        device
            .createCommandEncoder()
            .createRenderPass(
                () -> "CraftQ3 spike", target.getColorTextureView(), Optional.of(CLEAR))) {
      RenderPipeline bound = null;
      for (Draw draw : draws) {
        RenderPipeline pipeline = context.pipeline(draw.layer().blend());
        if (pipeline != bound) {
          pass.setPipeline(pipeline);
          pass.setUniform(Q3RenderContext.VIEW_UNIFORM, view);
          bound = pipeline;
        }
        pass.setUniform(Q3RenderContext.STAGE_UNIFORM, draw.stage());
        pass.bindTexture(Q3RenderContext.DIFFUSE_SAMPLER, draw.texture(), repeat);
        pass.bindTexture(Q3RenderContext.LIGHTMAP_SAMPLER, lightmap, clamp);
        pass.setVertexBuffer(0, draw.vertices());
        pass.draw(Q3SpikeScene.VERTICES_PER_CELL, 1, draw.firstVertex(), 0);
      }
    }
    context.endFrame();
    framesRendered++;
  }

  private Draw draw(Layer layer, GpuBufferSlice vertices, int firstVertex) {
    GpuTextureView texture =
        switch (layer.texture()) {
          case Q3SpikeScene.WHITE -> context.texture(layer.texture(), 1, 1, Q3SpikeScene::white);
          case Q3SpikeScene.CHECKER ->
              context.texture(layer.texture(), 64, 64, Q3SpikeScene::checker);
          case Q3SpikeScene.MASK -> context.texture(layer.texture(), 64, 64, Q3SpikeScene::mask);
          case Q3SpikeScene.ENV -> context.texture(layer.texture(), 64, 64, Q3SpikeScene::env);
          default -> throw new IllegalArgumentException("Unknown spike texture " + layer.texture());
        };
    return new Draw(layer, context.stageUniform(layer.stage()), texture, vertices, firstVertex);
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
    super.extractRenderState(graphics, mouseX, mouseY, a);
    graphics.text(
        font,
        "CraftQ3 Blaze3D spike - " + RenderSystem.getDevice().getDeviceInfo().backendName(),
        4,
        height - 12,
        0xffffffff);
  }

  /** The takeover pass is the background; vanilla's blur and menu texture would hide it. */
  @Override
  public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {}

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  @Override
  public void onClose() {
    minecraft.gui.setScreen(parent);
  }

  private record Draw(
      Layer layer,
      GpuBufferSlice stage,
      GpuTextureView texture,
      GpuBufferSlice vertices,
      int firstVertex) {}
}
