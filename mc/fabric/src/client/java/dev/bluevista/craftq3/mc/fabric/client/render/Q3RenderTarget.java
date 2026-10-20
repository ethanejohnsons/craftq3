package dev.bluevista.craftq3.mc.fabric.client.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

/**
 * An offscreen target: a reversed-Z {@code D32_FLOAT} depth attachment and, for portal and mirror
 * views, an RGBA8 colour attachment that later passes sample.
 */
final class Q3RenderTarget implements AutoCloseable {
  private final int width;
  private final int height;
  private final GpuTexture depth;
  private final GpuTextureView depthView;
  private final GpuTexture color;
  private final GpuTextureView colorView;

  Q3RenderTarget(String label, int width, int height, boolean withColor) {
    GpuDevice device = RenderSystem.getDevice();
    this.width = width;
    this.height = height;
    depth =
        device.createTexture(
            label + " depth",
            GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST,
            GpuFormat.D32_FLOAT,
            width,
            height,
            1,
            1);
    depthView = device.createTextureView(depth);
    if (withColor) {
      color =
          device.createTexture(
              label + " colour",
              GpuTexture.USAGE_RENDER_ATTACHMENT
                  | GpuTexture.USAGE_TEXTURE_BINDING
                  | GpuTexture.USAGE_COPY_DST,
              GpuFormat.RGBA8_UNORM,
              width,
              height,
              1,
              1);
      colorView = device.createTextureView(color);
    } else {
      color = null;
      colorView = null;
    }
  }

  int width() {
    return width;
  }

  int height() {
    return height;
  }

  GpuTextureView depthView() {
    return depthView;
  }

  GpuTextureView colorView() {
    return colorView;
  }

  @Override
  public void close() {
    depthView.close();
    depth.close();
    if (colorView != null) colorView.close();
    if (color != null) color.close();
  }
}
