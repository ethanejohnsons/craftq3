package dev.bluevista.craftq3.engine.formats.image;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A resolved image, or the missing-texture checker when no candidate file exists or decodes. The
 * diagnostics say which candidates were absent, unsupported or damaged.
 */
public record LoadedImage(Q3Image image, Optional<String> source, List<String> diagnostics) {
  public LoadedImage {
    Objects.requireNonNull(image, "image");
    Objects.requireNonNull(source, "source");
    diagnostics = List.copyOf(diagnostics);
  }

  public boolean missing() {
    return source.isEmpty();
  }
}
