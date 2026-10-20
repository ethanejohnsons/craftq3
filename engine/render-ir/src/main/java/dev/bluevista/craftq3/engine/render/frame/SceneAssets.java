package dev.bluevista.craftq3.engine.render.frame;

import dev.bluevista.craftq3.engine.formats.image.Q3Image;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition;
import dev.bluevista.craftq3.engine.render.material.MaterialRegistry;
import java.util.Map;

/**
 * Materials and images the cgame registered ({@code trap_R_RegisterShader}, skins, models), decoded
 * before they reach the render thread.
 */
public record SceneAssets(Map<String, ShaderDefinition> shaders, Map<String, Q3Image> images) {
  public static final SceneAssets EMPTY = new SceneAssets(Map.of(), Map.of());
  public static final int MAX_RESOURCES = 16384;
  public static final long MAX_IMAGE_BYTES = 256L * 1024 * 1024;

  public SceneAssets {
    shaders = Map.copyOf(shaders);
    images = Map.copyOf(images);
    if (shaders.size() > MAX_RESOURCES || images.size() > MAX_RESOURCES)
      throw new IllegalArgumentException("Cgame resource count exceeds budget");
    long bytes = 0;
    for (Q3Image image : images.values()) bytes += (long) image.width() * image.height() * 4;
    if (bytes > MAX_IMAGE_BYTES)
      throw new IllegalArgumentException("Cgame decoded images exceed 256 MiB");
  }

  /** The registered definition of {@code name}, or the implicit vertex-lit material. */
  public ShaderDefinition resolve(String name) {
    String key = name.isBlank() ? "$missing" : ShaderDefinition.canonicalName(name);
    ShaderDefinition shader = shaders.get(name);
    if (shader == null) shader = shaders.get(key);
    return shader == null ? ShaderDefinition.implicit(key, false) : shader;
  }

  /** A registry source preferring registered definitions, then {@code fallback}. */
  public MaterialRegistry.Source over(MaterialRegistry.Source fallback) {
    return (name, lightmapped) -> {
      ShaderDefinition shader = shaders.get(name);
      if (shader == null && !name.isBlank())
        shader = shaders.get(ShaderDefinition.canonicalName(name));
      return shader != null ? shader : fallback.resolve(name, lightmapped);
    };
  }
}
