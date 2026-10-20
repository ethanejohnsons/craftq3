package dev.bluevista.craftq3.engine.formats.shader;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.formats.AssetLookup;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every shader defined by the {@code scripts/*.shader} files of a search path.
 *
 * <p><b>Precedence.</b> The original renderer reads the files in {@code FS_ListFiles} order
 * (highest-priority source first), concatenates their text in <em>reverse</em> of that order and
 * indexes shader names from the start of the combined text, so when two different files define the
 * same name the definition from the file listed <em>last</em> wins: a base {@code pak0} script
 * beats a later pak or a mod that redefines the name in a differently named file. Overriding a
 * definition therefore means shipping a script with the same file name, which replaces the whole
 * file through the normal search order. Within one file the first definition wins.
 *
 * <p><b>Leniency.</b> A damaged shader is skipped (logged once per script) without affecting the
 * rest of its file; a script over the size limits is skipped as a whole. Names without a definition
 * resolve to the standard implicit material.
 */
public final class ShaderLibrary {
  public static final int MAX_FILES = 4096;
  public static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
  public static final int MAX_TOTAL_DEFINITIONS = 65_536;
  public static final int MAX_DIAGNOSTICS = 16_384;
  private static final Log LOG = Log.get(ShaderLibrary.class);

  private final Map<String, ShaderDefinition> definitions;
  private final Map<String, String> scripts;
  private final List<ShaderDiagnostic> diagnostics;

  private ShaderLibrary(
      Map<String, ShaderDefinition> definitions,
      Map<String, String> scripts,
      List<ShaderDiagnostic> diagnostics) {
    this.definitions = Map.copyOf(definitions);
    this.scripts = Map.copyOf(scripts);
    this.diagnostics = List.copyOf(diagnostics);
  }

  /** A script's path and text. */
  public record Script(String path, String text) {}

  /** Loads {@code scripts/*.shader} through {@code lookup} (see {@link AssetLookup#list}). */
  public static ShaderLibrary load(AssetLookup lookup) {
    List<String> paths = lookup.list("scripts", "shader");
    List<Script> scripts = new ArrayList<>(Math.min(paths.size(), MAX_FILES));
    long total = 0;
    for (String path : paths) {
      if (scripts.size() >= MAX_FILES) {
        LOG.warnOnce("shader-files", "More than " + MAX_FILES + " shader scripts; rest ignored");
        break;
      }
      Optional<byte[]> bytes = lookup.read(path);
      if (bytes.isEmpty()) continue;
      total += bytes.get().length;
      if (total > MAX_TOTAL_BYTES) {
        LOG.warnOnce(
            "shader-bytes", "Shader scripts exceed " + MAX_TOTAL_BYTES + " bytes; rest ignored");
        break;
      }
      scripts.add(new Script(path, new String(bytes.get(), StandardCharsets.ISO_8859_1)));
    }
    return of(scripts);
  }

  /** Builds a library from scripts given in {@code FS_ListFiles} order (see class notes). */
  public static ShaderLibrary of(List<Script> scriptsInListOrder) {
    Map<String, ShaderDefinition> definitions = new LinkedHashMap<>();
    Map<String, String> sources = new HashMap<>();
    List<ShaderDiagnostic> diagnostics = new ArrayList<>();
    for (int i = scriptsInListOrder.size() - 1; i >= 0; i--) {
      Script script = scriptsInListOrder.get(i);
      ShaderParser.Result result;
      try {
        result = ShaderParser.parse(script.path(), script.text());
      } catch (ShaderFormatException ex) {
        LOG.warnOnce("shader-script:" + script.path(), "Shader script skipped: " + ex.getMessage());
        add(diagnostics, new ShaderDiagnostic(script.path(), 0, ex.getMessage()));
        continue;
      }
      for (ShaderDiagnostic diagnostic : result.diagnostics()) {
        if (diagnostic.message().startsWith("malformed shader"))
          LOG.warnOnce(
              "shader-malformed:" + script.path(),
              script.path() + ":" + diagnostic.line() + ": " + diagnostic.message());
        add(diagnostics, diagnostic);
      }
      for (ShaderDefinition definition : result.definitions()) {
        if (definitions.containsKey(definition.name())) {
          add(
              diagnostics,
              new ShaderDiagnostic(
                  script.path(),
                  0,
                  "duplicate shader "
                      + definition.name()
                      + " ignored; "
                      + sources.get(definition.name())
                      + " takes precedence"));
          continue;
        }
        if (definitions.size() >= MAX_TOTAL_DEFINITIONS) {
          LOG.warnOnce(
              "shader-count", "More than " + MAX_TOTAL_DEFINITIONS + " shaders; rest ignored");
          break;
        }
        definitions.put(definition.name(), definition);
        sources.put(definition.name(), script.path());
      }
    }
    return new ShaderLibrary(definitions, sources, diagnostics);
  }

  private static void add(List<ShaderDiagnostic> diagnostics, ShaderDiagnostic diagnostic) {
    if (diagnostics.size() < MAX_DIAGNOSTICS) diagnostics.add(diagnostic);
  }

  /** Definitions by canonical name. */
  public Map<String, ShaderDefinition> definitions() {
    return definitions;
  }

  public List<ShaderDiagnostic> diagnostics() {
    return diagnostics;
  }

  public Optional<ShaderDefinition> find(String name) {
    return Optional.ofNullable(definitions.get(ShaderDefinition.canonicalName(name)));
  }

  /** The script that supplied the winning definition of {@code name}. */
  public Optional<String> scriptOf(String name) {
    return Optional.ofNullable(scripts.get(ShaderDefinition.canonicalName(name)));
  }

  /** The defined shader, or the implicit material (image plus optional lightmap stage). */
  public ShaderDefinition resolve(String name, boolean lightmapped) {
    return find(name).orElseGet(() -> ShaderDefinition.implicit(name, lightmapped));
  }
}
