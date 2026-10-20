package dev.bluevista.craftq3.engine.render.material;

import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An append-only table of compiled materials with stable indices. The world's materials come first
 * (in {@code RenderScene} order); materials first used by cgame submissions are appended as they
 * appear, so a GPU backend can upload new entries by watching {@link #size()}. Owned by the render
 * thread; not thread-safe.
 */
public final class MaterialRegistry {
  /** Resolves a shader name to its definition (implicit when undefined). */
  @FunctionalInterface
  public interface Source {
    ShaderDefinition resolve(String name, boolean lightmapped);
  }

  public static final int MAX_MATERIALS = 1 << 16;

  private final Source source;
  private final List<MaterialProgram> programs = new ArrayList<>();
  private final List<ShaderDefinition> definitions = new ArrayList<>();
  private final Map<ShaderDefinition, Integer> byDefinition = new HashMap<>();
  private final Map<String, Integer> byName = new HashMap<>();

  public MaterialRegistry(Source source) {
    this.source = Objects.requireNonNull(source, "source");
  }

  /** A registry seeded with {@code initial} at indices 0..n-1. */
  public MaterialRegistry(Source source, List<ShaderDefinition> initial) {
    this(source);
    for (ShaderDefinition definition : initial) add(definition);
  }

  /** The index of the material {@code name} resolves to, compiling it on first use. */
  public int index(String name, boolean lightmapped) {
    String key = (lightmapped ? "1:" : "0:") + name;
    Integer known = byName.get(key);
    if (known != null) return known;
    int index = add(source.resolve(name, lightmapped));
    byName.put(key, index);
    return index;
  }

  /** The index of {@code definition}, compiling it on first use. */
  public int add(ShaderDefinition definition) {
    Integer known = byDefinition.get(definition);
    if (known != null) return known;
    if (programs.size() >= MAX_MATERIALS)
      throw new IllegalStateException("More than " + MAX_MATERIALS + " materials registered");
    int index = programs.size();
    programs.add(MaterialCompiler.compile(definition));
    definitions.add(definition);
    byDefinition.put(definition, index);
    return index;
  }

  public MaterialProgram program(int index) {
    return programs.get(index);
  }

  public ShaderDefinition definition(int index) {
    return definitions.get(index);
  }

  public int size() {
    return programs.size();
  }

  /** An unmodifiable snapshot of the compiled programs in index order. */
  public List<MaterialProgram> programs() {
    return List.copyOf(programs);
  }
}
