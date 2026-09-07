import java.io.File;
import javax.imageio.ImageIO;

/** Run with Java 25. Backend capture check, not an ioquake3 fidelity benchmark. */
class CompareCaptures {
  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 3)
      throw new IllegalArgumentException(
          "Expected OpenGL and Vulkan PNG paths, optionally --channel-tolerance=0..255");
    int tolerance = 0;
    if (args.length == 3) {
      if (!args[2].startsWith("--channel-tolerance="))
        throw new IllegalArgumentException("Unknown comparison option: " + args[2]);
      tolerance = Integer.parseInt(args[2].substring("--channel-tolerance=".length()));
      if (tolerance < 0 || tolerance > 255)
        throw new IllegalArgumentException("Channel tolerance must be between 0 and 255");
    }
    var first = ImageIO.read(new File(args[0]));
    var second = ImageIO.read(new File(args[1]));
    if (first == null || second == null || first.getWidth() != second.getWidth()
        || first.getHeight() != second.getHeight()) throw new AssertionError("Image dimensions differ");
    long different = 0, aboveTolerance = 0, geometryA = 0, geometryB = 0, absoluteRgbError = 0;
    int maximumDelta = 0;
    int backgroundA = first.getRGB(0, 0), backgroundB = second.getRGB(0, 0);
    for (int y = 0; y < first.getHeight(); y++) {
      for (int x = 0; x < first.getWidth(); x++) {
        int a = first.getRGB(x, y), b = second.getRGB(x, y);
        if (a != backgroundA) geometryA++;
        if (b != backgroundB) geometryB++;
        if (a != b) different++;
        int pixelDelta = 0;
        for (int shift = 0; shift < 32; shift += 8) {
          int delta = Math.abs(((a >>> shift) & 255) - ((b >>> shift) & 255));
          pixelDelta = Math.max(pixelDelta, delta);
          if (shift < 24) absoluteRgbError += delta;
        }
        maximumDelta = Math.max(maximumDelta, pixelDelta);
        if (pixelDelta > tolerance) aboveTolerance++;
      }
    }
    long pixels = (long) first.getWidth() * first.getHeight();
    if (geometryA < 1000 || geometryB < 1000) throw new AssertionError("Capture is blank or nearly blank");
    System.out.printf("%dx%d: geometry pixels %d / %d; exact differing pixels %d (%.6f%%)%n",
        first.getWidth(), first.getHeight(), geometryA, geometryB, different,
        different * 100.0 / pixels);
    System.out.printf("Channel tolerance %d: above-tolerance pixels %d (%.6f%%); "
            + "maximum RGBA channel delta %d; mean absolute RGB channel error %.10f / 255%n",
        tolerance, aboveTolerance, aboveTolerance * 100.0 / pixels, maximumDelta,
        absoluteRgbError / (pixels * 3.0));
    if (aboveTolerance / (double) pixels > 0.001)
      throw new AssertionError("Backend images differ by more than 0.1% at channel tolerance "
          + tolerance);
  }
}
