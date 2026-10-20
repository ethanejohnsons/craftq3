package dev.bluevista.craftq3.assets.shader;

import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Indexed scripts from the active VFS. Missing names receive standard implicit materials. */
public record ShaderLibrary(
    Map<String, ShaderDefinition> definitions, List<ShaderDiagnostic> diagnostics) {
  private static final int MAX_FILES = 4096;
  private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
  private static final int MAX_TOTAL_DEFINITIONS = 65_536;
  private static final int MAX_DIAGNOSTICS = 16_384;

  public ShaderLibrary {
    definitions = Map.copyOf(definitions);
    diagnostics = List.copyOf(diagnostics);
  }

  public static ShaderLibrary load(VirtualFileSystem fs) throws IOException {
    List<VirtualPath> scripts =
        fs.list("scripts").stream()
            .filter(path -> path.value().endsWith(".shader"))
            .filter(path -> path.value().substring("scripts/".length()).indexOf('/') < 0)
            .sorted(
                Comparator.comparingInt(
                        (VirtualPath path) ->
                            fs.which(path).map(fs.searchOrder()::indexOf).orElse(Integer.MAX_VALUE))
                    .thenComparing(Comparator.naturalOrder()))
            .toList();
    if (scripts.size() > MAX_FILES) throw new ShaderFormatException("Too many shader script files");
    Map<String, ShaderDefinition> definitions = new LinkedHashMap<>();
    List<ShaderDiagnostic> diagnostics = new ArrayList<>();
    long bytesRead = 0;
    for (VirtualPath script : scripts) {
      byte[] bytes = fs.read(script);
      bytesRead += bytes.length;
      if (bytesRead > MAX_TOTAL_BYTES)
        throw new ShaderFormatException("Shader library exceeds byte limit");
      ShaderParser.Result result;
      try {
        if (bytes.length > ShaderParser.MAX_SCRIPT_CHARS) {
          throw new ShaderFormatException("Shader script exceeds byte limit");
        }
        result = ShaderParser.parse(script.value(), new String(bytes, StandardCharsets.ISO_8859_1));
      } catch (ShaderFormatException ex) {
        if (diagnostics.size() < MAX_DIAGNOSTICS) {
          diagnostics.add(new ShaderDiagnostic(script.value(), 0, ex.getMessage()));
        }
        continue;
      }
      for (ShaderDefinition definition : result.definitions()) {
        if (!definitions.containsKey(definition.name())
            && definitions.size() >= MAX_TOTAL_DEFINITIONS) {
          throw new ShaderFormatException("Too many shader definitions in library");
        }
        if (definitions.putIfAbsent(definition.name(), definition) != null
            && diagnostics.size() < MAX_DIAGNOSTICS) {
          diagnostics.add(
              new ShaderDiagnostic(
                  script.value(),
                  0,
                  "duplicate shader "
                      + definition.name()
                      + "; higher priority/earlier definition kept"));
        }
      }
      result.diagnostics().stream()
          .limit(MAX_DIAGNOSTICS - diagnostics.size())
          .forEach(diagnostics::add);
    }
    return new ShaderLibrary(definitions, diagnostics);
  }

  public Optional<ShaderDefinition> find(String name) {
    return Optional.ofNullable(definitions.get(ShaderDefinition.canonicalName(name)));
  }

  public ShaderDefinition resolve(String name, boolean lightmapped) {
    return find(name).orElseGet(() -> ShaderDefinition.implicit(name, lightmapped));
  }
}
