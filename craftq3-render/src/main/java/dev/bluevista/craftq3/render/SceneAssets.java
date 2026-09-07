package dev.bluevista.craftq3.render;

import dev.bluevista.craftq3.assets.image.Q3Image;
import dev.bluevista.craftq3.assets.shader.ShaderDefinition;
import java.util.Map;

/** Registered cgame material resources, decoded before reaching the host graphics boundary. */
public record SceneAssets(Map<String, ShaderDefinition> shaders, Map<String, Q3Image> images) {
  public static final SceneAssets EMPTY = new SceneAssets(Map.of(), Map.of());

  public SceneAssets {
    shaders = Map.copyOf(shaders);
    images = Map.copyOf(images);
    if (shaders.size() > 16384 || images.size() > 16384)
      throw new IllegalArgumentException("Cgame resource count exceeds budget");
    long bytes = 0;
    for (var image : images.values()) bytes += (long) image.width() * image.height() * 4;
    if (bytes > 256L * 1024 * 1024)
      throw new IllegalArgumentException("Cgame decoded images exceed 256 MiB");
  }

  public ShaderDefinition resolve(String name) {
    String key = name.isBlank() ? "$missing" : ShaderDefinition.canonicalName(name);
    var shader = shaders.get(name);
    if (shader == null) shader = shaders.get(key);
    return shader == null ? ShaderDefinition.implicit(key, false) : shader;
  }
}
