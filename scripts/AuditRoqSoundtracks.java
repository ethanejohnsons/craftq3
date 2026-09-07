import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.assets.video.RoqDecoder;
import dev.bluevista.craftq3.client.video.RoqAudioStream;
import dev.bluevista.craftq3.platform.audio.PcmStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Original soundtrack differential check through the bounded asynchronous PCM source. */
class AuditRoqSoundtracks {
  public static void main(String[] args) throws Exception {
    int movies = 0;
    long samples = 0;
    try (var fs = Pk3FileSystem.mount(Path.of(args[0]), "baseq3")) {
      for (var path : fs.list("video").stream().filter(p -> p.value().endsWith(".roq")).toList()) {
        String stem = path.value().substring(6, path.value().length() - 4);
        Path log;
        try (var files = Files.list(Path.of(args[1]))) {
          log = files.filter(p -> p.getFileName().toString().equalsIgnoreCase(stem + "-java.log"))
              .findFirst().orElseThrow();
        }
        String expected = Files.readAllLines(log).stream().filter(l -> l.startsWith("A "))
            .findFirst().orElseThrow().substring(2);
        var format = new PcmStream.Format(22050, 1, 16);
        try (var decoder = new RoqDecoder(fs.open(path))) {
          for (var event = decoder.next(); event.isPresent(); event = decoder.next()) {
            if (event.get() instanceof RoqDecoder.Audio audio) {
              format = new PcmStream.Format(audio.sound().sampleRate(), audio.sound().channels(), audio.sound().bits());
              break;
            }
          }
        }
        var hash = MessageDigest.getInstance("SHA-256");
        try (var stream = new RoqAudioStream(() -> fs.open(path), format)) {
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
          while (!stream.exhausted()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Soundtrack timeout " + path);
            if (!stream.ready()) { Thread.sleep(1); continue; }
            hash.update(stream.read(65536));
          }
          stream.completion().get(5, TimeUnit.SECONDS);
          if (stream.failure().isPresent() || !stream.producerFinished()
              || stream.submittedFrames() != stream.consumedFrames()
              || !HexFormat.of().formatHex(hash.digest()).equals(expected))
            throw new AssertionError("Soundtrack mismatch " + path);
          samples += stream.consumedFrames();
          movies++;
          System.out.println("PASS " + path.value() + " samples=" + stream.consumedFrames());
        }
      }
    }
    if (movies != 11 || samples != 5444948) throw new AssertionError("Original coverage changed");
    System.out.println("PASS movies=" + movies + " samples=" + samples + " independent-producer=true");
  }
}
