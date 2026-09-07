package dev.bluevista.craftq3.assets.image;

import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.util.Objects;
import java.util.Optional;

/** A resolved image, or an explicit diagnostic and checker when no matching file exists. */
public record LoadedImage(
    Q3Image image, Optional<VirtualPath> source, Optional<String> diagnostic) {
  public LoadedImage {
    Objects.requireNonNull(image, "image");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(diagnostic, "diagnostic");
  }

  public boolean missing() {
    return source.isEmpty();
  }
}
