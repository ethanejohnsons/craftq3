package dev.bluevista.craftq3.render;

import dev.bluevista.craftq3.assets.image.ImageLoader;
import dev.bluevista.craftq3.assets.image.Q3Image;
import dev.bluevista.craftq3.assets.shader.ShaderDefinition;
import dev.bluevista.craftq3.assets.shader.ShaderLibrary;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Worker-loaded, owned material data. The render thread never reads a PK3 or decodes an image. */
public record MaterialLibrary(
    Map<Integer, ShaderDefinition> surfaces,
    Map<String, Q3Image> images,
    List<Q3Image> lightmaps,
    Map<String, ShaderDefinition> effects,
    List<String> diagnostics) {
  private static final long MAX_IMAGE_BYTES = 256L * 1024 * 1024;

  public MaterialLibrary {
    surfaces = Map.copyOf(surfaces);
    images = Map.copyOf(images);
    lightmaps = List.copyOf(lightmaps);
    effects = Map.copyOf(effects);
    diagnostics = List.copyOf(diagnostics);
  }

  public static MaterialLibrary load(VirtualFileSystem fs, RenderScene scene) throws IOException {
    ShaderLibrary scripts = ShaderLibrary.load(fs);
    var materials = new LinkedHashMap<Integer, ShaderDefinition>();
    var images = new LinkedHashMap<String, Q3Image>();
    var effects = new LinkedHashMap<String, ShaderDefinition>();
    var messages = new ArrayList<String>();
    scripts.diagnostics().stream().limit(100).forEach(d -> messages.add(d.toString()));
    images.put("$whiteimage", ImageLoader.whiteTexture());
    images.put("$missing", ImageLoader.missingTexture());
    for (var surface : scene.surfaces()) {
      ShaderDefinition material = scripts.resolve(surface.shaderName(), surface.lightmap() >= 0);
      materials.put(surface.id(), material);
      if (material.noDraw()) continue;
      for (var stage : material.stages())
        for (String texture : stage.texture().frames()) loadImage(fs, images, messages, texture);
      if (material.sky().isPresent()) {
        for (String box : List.of(material.sky().orElseThrow().farBox()))
          if (!box.equals("-"))
            for (String side : List.of("rt", "lf", "bk", "ft", "up", "dn"))
              loadImage(fs, images, messages, box + "_" + side);
      }
    }
    var lightmaps = new ArrayList<Q3Image>();
    if (scene.bsp() != null) {
      for (var effect : scene.bsp().effects())
        scripts.find(effect.name()).ifPresent(shader -> effects.put(effect.name(), shader));
      for (var lightmap : scene.bsp().lightmaps()) {
        byte[] rgba = new byte[128 * 128 * 4];
        for (int i = 0; i < 128 * 128; i++) {
          // BSP lighting is stored with two bits of headroom. Restore intensity while retaining
          // the RGB ratio when a channel saturates; no global Minecraft gamma ramp is changed.
          int r = lightmap.unsigned(i * 3) * 4;
          int g = lightmap.unsigned(i * 3 + 1) * 4;
          int b = lightmap.unsigned(i * 3 + 2) * 4;
          float scale = 255f / Math.max(255, Math.max(r, Math.max(g, b)));
          rgba[i * 4] = (byte) Math.round(r * scale);
          rgba[i * 4 + 1] = (byte) Math.round(g * scale);
          rgba[i * 4 + 2] = (byte) Math.round(b * scale);
          rgba[i * 4 + 3] = (byte) 255;
        }
        lightmaps.add(new Q3Image(128, 128, rgba));
      }
    }
    return new MaterialLibrary(materials, images, lightmaps, effects, messages);
  }

  private static void loadImage(
      VirtualFileSystem fs, Map<String, Q3Image> images, List<String> messages, String name)
      throws IOException {
    if (name.startsWith("$") || images.containsKey(name)) return;
    var image = ImageLoader.load(fs, name);
    long bytes = (long) image.image().width() * image.image().height() * 4;
    for (var existing : images.values()) bytes += (long) existing.width() * existing.height() * 4;
    if (bytes > MAX_IMAGE_BYTES)
      throw new IOException("Map textures exceed 256 MiB decoded image budget");
    images.put(name, image.image());
    image.diagnostic().ifPresent(messages::add);
  }
}
