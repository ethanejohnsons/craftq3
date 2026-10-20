package dev.bluevista.craftq3.engine.render;

import dev.bluevista.craftq3.engine.formats.AssetLookup;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.image.ImageLoader;
import dev.bluevista.craftq3.engine.formats.image.LoadedImage;
import dev.bluevista.craftq3.engine.formats.image.Q3Image;
import dev.bluevista.craftq3.engine.render.material.MaterialProgram;
import dev.bluevista.craftq3.engine.render.material.StageProgram;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decoded images for a scene's materials and its lightmap pages, loaded on a worker before the
 * render thread needs them; the render thread never reads a PK3 or decodes an image.
 *
 * @param images file-backed stage images and sky box sides by name, plus {@code $whiteimage} and
 *     {@code $missing}
 * @param lightmaps RGBA lightmap pages with the map's overbright headroom restored
 */
public record SceneTextures(
    Map<String, Q3Image> images, List<Q3Image> lightmaps, List<String> diagnostics) {
  public static final long MAX_IMAGE_BYTES = 256L * 1024 * 1024;
  private static final int MAX_DIAGNOSTICS = 100;

  public SceneTextures {
    images = Map.copyOf(images);
    lightmaps = List.copyOf(lightmaps);
    diagnostics = List.copyOf(diagnostics);
  }

  public static SceneTextures load(AssetLookup assets, RenderScene scene) {
    Map<String, Q3Image> images = new LinkedHashMap<>();
    List<String> notes = new ArrayList<>();
    images.put("$whiteimage", ImageLoader.whiteTexture());
    images.put("$missing", ImageLoader.missingTexture());
    long[] bytes = {0};
    for (MaterialProgram material : scene.materials()) {
      if (material.noDraw()) continue;
      for (StageProgram stage : material.stages())
        if (stage.texture().decodedImages())
          for (String image : stage.texture().images()) load(assets, images, notes, bytes, image);
      material
          .sky()
          .ifPresent(
              sky -> {
                for (String box : List.of(sky.farBox(), sky.nearBox()))
                  if (!box.equals("-"))
                    for (String side : SkyGeometry.SIDES)
                      load(assets, images, notes, bytes, box + "_" + side);
              });
    }
    List<Q3Image> lightmaps = new ArrayList<>();
    for (int page = 0; page < scene.bsp().lightmaps().count(); page++)
      lightmaps.add(lightmap(scene.bsp(), page));
    return new SceneTextures(images, lightmaps, notes);
  }

  /**
   * Lightmap page {@code page} as RGBA. Each channel is shifted left by the overbright bits; when
   * one saturates, all three are scaled down together so the hue is kept. Alpha is opaque.
   */
  public static Q3Image lightmap(BspMap map, int page) {
    int pixels = BspMap.LIGHTMAP_SIZE * BspMap.LIGHTMAP_SIZE;
    byte[] rgba = new byte[pixels * 4];
    BspMap.Lightmaps lightmaps = map.lightmaps();
    int shift = 1 << WorldGeometry.MAP_OVERBRIGHT_BITS;
    for (int i = 0; i < pixels; i++) {
      int r = lightmaps.unsigned(page, i * 3) * shift;
      int g = lightmaps.unsigned(page, i * 3 + 1) * shift;
      int b = lightmaps.unsigned(page, i * 3 + 2) * shift;
      float scale = 255f / Math.max(255, Math.max(r, Math.max(g, b)));
      rgba[i * 4] = (byte) Math.round(r * scale);
      rgba[i * 4 + 1] = (byte) Math.round(g * scale);
      rgba[i * 4 + 2] = (byte) Math.round(b * scale);
      rgba[i * 4 + 3] = (byte) 255;
    }
    return new Q3Image(BspMap.LIGHTMAP_SIZE, BspMap.LIGHTMAP_SIZE, rgba);
  }

  private static void load(
      AssetLookup assets,
      Map<String, Q3Image> images,
      List<String> notes,
      long[] bytes,
      String name) {
    if (name.startsWith("$") || images.containsKey(name)) return;
    LoadedImage loaded = ImageLoader.load(assets, name);
    long size = (long) loaded.image().width() * loaded.image().height() * 4;
    if (bytes[0] + size > MAX_IMAGE_BYTES)
      throw new IllegalStateException("Map textures exceed the 256 MiB decoded image budget");
    bytes[0] += size;
    images.put(name, loaded.image());
    for (String note : loaded.diagnostics()) if (notes.size() < MAX_DIAGNOSTICS) notes.add(note);
  }
}
