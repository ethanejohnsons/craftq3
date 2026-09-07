import dev.bluevista.craftq3.assets.audio.WavReader;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import java.nio.file.Path;

/** Optional local-only WAV corpus audit; commercial samples are never extracted or committed. */
class AuditSounds {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("AuditSounds <installation>");
    int count = 0, failures = 0;
    long frames = 0;
    double seconds = 0;
    try (var fs = Pk3FileSystem.mount(Path.of(args[0]), "baseq3")) {
      for (var path : fs.list("")) {
        if (!path.value().endsWith(".wav")) continue;
        try {
          var sound = WavReader.read(fs.read(path));
          count++; frames += sound.frames(); seconds += sound.durationSeconds();
        } catch (Exception e) {
          failures++; System.err.println(path.value() + ": " + e.getMessage());
        }
      }
    }
    System.out.printf("WAV files=%d frames=%d seconds=%.3f failures=%d%n", count, frames, seconds, failures);
    if (failures != 0) throw new AssertionError("WAV audit failed");
  }
}
