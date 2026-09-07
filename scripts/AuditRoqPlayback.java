import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.assets.video.RoqDecoder;
import dev.bluevista.craftq3.client.video.RoqPlayback;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Runs every original movie through VFS streaming and the presentation clock against saved hashes. */
class AuditRoqPlayback {
  static final class Sound implements RoqPlayback.AudioSink {
    final MessageDigest hash;
    long samples;
    int begins, ends;
    Sound() throws Exception { hash = MessageDigest.getInstance("SHA-256"); }
    public void begin(long cycle, long start, int units) {
      if (cycle != 0 || start != 0) throw new AssertionError("Unexpected cycle");
      begins++;
    }
    public void samples(RoqDecoder.Audio audio) {
      if (audio.firstSample() != samples) throw new AssertionError("Discontinuous audio");
      samples += audio.sound().frames();
      hash.update(audio.sound().pcm());
    }
    public void end() { ends++; }
  }

  public static void main(String[] args) throws Exception {
    long totalFrames = 0, totalSamples = 0;
    int movies = 0;
    try (var fs = Pk3FileSystem.mount(Path.of(args[0]), "baseq3")) {
      for (var path : fs.list("video").stream().filter(p -> p.value().endsWith(".roq")).toList()) {
        String stem = path.value().substring(6, path.value().length() - 4);
        var reference = Files.list(Path.of(args[1]));
        Path log;
        try (reference) {
          log = reference.filter(p -> p.getFileName().toString().equalsIgnoreCase(stem + "-java.log"))
              .findFirst().orElseThrow();
        }
        var lines = Files.readAllLines(log);
        var hashes = lines.stream().filter(l -> l.startsWith("V ")).map(l -> l.split(" ")[2]).toList();
        String audioHash = lines.stream().filter(l -> l.startsWith("A ")).findFirst().orElseThrow().substring(2);
        var sound = new Sound();
        int frames = 0;
        long time = 0;
        try (var player = new RoqPlayback(fs, path, RoqPlayback.Mode.ONCE, false, sound)) {
          while (player.advance(time) == RoqPlayback.State.PLAYING) {
            if (player.catchingUp()) throw new AssertionError("Normal playback required catch-up");
            var frame = player.video().orElseThrow();
            if (frame.number() == frames) {
              String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(frame.yuv()));
              if (frames >= hashes.size() || !hash.equals(hashes.get(frames)))
                throw new AssertionError("Frame mismatch " + path + " #" + frames);
              frames++;
            } else if (frame.number() != frames - 1) throw new AssertionError("Skipped frame");
            time += 16;
            if (time > 600_000) throw new AssertionError("Movie failed to terminate");
          }
          if (player.state() != RoqPlayback.State.ENDED || player.video().isPresent())
            throw new AssertionError("Bad EOF lifecycle");
        }
        if (frames != hashes.size() || !HexFormat.of().formatHex(sound.hash.digest()).equals(audioHash)
            || sound.begins != 1 || sound.ends != 1) throw new AssertionError("Movie mismatch " + path);
        totalFrames += frames;
        totalSamples += sound.samples;
        movies++;
        System.out.println("PASS " + path.value() + " frames=" + frames + " audioSamples=" + sound.samples + " endMs=" + time);
      }
    }
    if (movies != 11 || totalFrames != 7715 || totalSamples != 5444948)
      throw new AssertionError("Original movie coverage changed");
    System.out.println("PASS movies=" + movies + " frames=" + totalFrames + " samples=" + totalSamples);
  }
}
