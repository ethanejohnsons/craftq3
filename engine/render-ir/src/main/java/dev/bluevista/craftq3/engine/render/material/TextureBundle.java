package dev.bluevista.craftq3.engine.render.material;

import java.util.List;
import java.util.Objects;

/**
 * The image source of one stage. {@link Kind#IMAGE} and {@link Kind#ANIMATED} name image files
 * (resolved through the image loader's extension fallbacks); the other kinds are engine images the
 * backend supplies itself.
 *
 * @param images one name for every kind except {@link Kind#ANIMATED}, which lists its frames
 * @param frequency animation frames per second ({@link Kind#ANIMATED} only)
 * @param clamp clamp texture coordinates to the edge instead of repeating ({@code clampMap})
 * @param mipmaps sample with mipmaps (false for {@code nomipmaps} materials)
 * @param picmip allow the backend's texture-detail reduction (false for {@code nopicmip})
 */
public record TextureBundle(
    Kind kind,
    List<String> images,
    float frequency,
    boolean clamp,
    boolean mipmaps,
    boolean picmip) {
  public static final String LIGHTMAP = "$lightmap";
  public static final String WHITE = "$whiteimage";

  /** Where a stage's pixels come from. */
  public enum Kind {
    /** One image file ({@code map}, {@code clampMap}). */
    IMAGE,
    /** Up to 64 frames cycled at {@link #frequency} ({@code animMap}). */
    ANIMATED,
    /** The surface's lightmap page ({@code $lightmap}). */
    LIGHTMAP,
    /** A white texel ({@code $whiteimage}, {@code *white}). */
    WHITE,
    /** A cinematic stream ({@code videoMap}); the image name is the RoQ file. */
    VIDEO,
    /** Another engine-provided image: {@code $missing}, {@code $portal}, {@code $cinematic/N}. */
    BUILTIN
  }

  public TextureBundle {
    Objects.requireNonNull(kind, "kind");
    images = List.copyOf(images);
    if (images.isEmpty()) throw new IllegalArgumentException("Texture bundle without an image");
    if (kind != Kind.ANIMATED && images.size() != 1)
      throw new IllegalArgumentException("Only animated bundles have several images");
    if (!Float.isFinite(frequency) || frequency < 0)
      throw new IllegalArgumentException("Invalid animation frequency");
  }

  /** A single image of {@code kind}, repeating, mipmapped. */
  public static TextureBundle of(Kind kind, String image) {
    return new TextureBundle(kind, List.of(image), 0, false, true, true);
  }

  /**
   * The animation frame shown at {@code seconds}, as the original renderer chooses it: {@code
   * floor(seconds * frequency)} modulo the frame count, frame 0 for negative or non-finite times.
   */
  public int frameAt(double seconds) {
    if (images.size() == 1 || frequency == 0 || !Double.isFinite(seconds)) return 0;
    double frame = Math.floor(Math.max(0, seconds) * frequency);
    return (int) (frame % images.size());
  }

  /** The image name shown at {@code seconds}. */
  public String imageAt(double seconds) {
    return images.get(frameAt(seconds));
  }

  /** True when the backend must load the named images from game files. */
  public boolean fileBacked() {
    return kind == Kind.IMAGE || kind == Kind.ANIMATED || kind == Kind.VIDEO;
  }

  /** True when the named files are images to decode ahead of time (not a RoQ to stream). */
  public boolean decodedImages() {
    return kind == Kind.IMAGE || kind == Kind.ANIMATED;
  }
}
