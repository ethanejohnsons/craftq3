package dev.bluevista.craftq3.engine.audio;

import static dev.bluevista.craftq3.engine.audio.TestSounds.FORWARD;
import static dev.bluevista.craftq3.engine.audio.TestSounds.LEFT;
import static dev.bluevista.craftq3.engine.audio.TestSounds.UP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.AssetLookup;
import dev.bluevista.craftq3.engine.formats.FileViewAssets;
import dev.bluevista.craftq3.engine.formats.roq.RoqDecoder;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Plays every sound, every music track and the intro cinematic's audio of the user's baseq3 through
 * the software mixer. Writes {@code build/realdata/audio.txt}.
 */
@RealData
class RealDataAudioTest {
  private static final int RATE = SoftwareMixer.DEFAULT_OUTPUT_RATE;
  private static final int BATCH = 512;
  private static final List<String> REPORT = new ArrayList<>();
  private static SearchPath searchPath;
  private static AssetLookup assets;

  @BeforeAll
  static void mount() throws IOException {
    searchPath = SearchPath.mount(RealDataRoot.require(), SearchPath.BASE_GAME);
    assets = new FileViewAssets(searchPath.view());
  }

  @AfterAll
  static void report() throws IOException {
    if (searchPath != null) searchPath.close();
    Path file = Path.of("build", "realdata", "audio.txt");
    Files.createDirectories(file.getParent());
    Files.write(file, REPORT);
  }

  private static List<String> filesUnder(String directory, String extension) {
    List<String> result = new ArrayList<>(assets.list(directory, extension));
    for (String child : assets.list(directory, "/")) result.addAll(filesUnder(child, extension));
    return result;
  }

  @Test
  void everySoundPlays() {
    List<String> names = filesUnder("sound", "wav");
    assertTrue(names.size() > 500, "baseq3 has hundreds of sounds, found " + names.size());
    var mixer = new SoftwareMixer(RATE, 128);
    var sound = new SoundSystem(assets, mixer, SoundSystem.Limits.DEFAULT);
    sound.claimThread();
    sound.respatialize(0, Vec3f.ZERO, FORWARD, LEFT, UP, false);
    int frames = RATE * 50 / 1000;
    short[] out = new short[frames * 2];
    int played = 0, silent = 0, skipped = 0;
    List<String> silentNames = new ArrayList<>();
    for (int start = 0; start < names.size(); start += BATCH) {
      sound.reset(assets);
      for (String name : names.subList(start, Math.min(names.size(), start + BATCH))) {
        int handle = sound.registerSound(name, false);
        if (handle == 0) {
          skipped++; // longer than MAX_QPATH: the original cannot name it either
          continue;
        }
        // Alternate local and spatialised starts so both paths run.
        if ((played + silent) % 2 == 0) sound.startLocalSound(handle, SoundChannel.LOCAL);
        else sound.startSound(new Vec3f(200, -100, 0), 5, SoundChannel.AUTO, handle);
        if (sound.soundInfo(handle).orElseThrow().placeholder()) {
          silent++;
          silentNames.add(name);
        } else {
          played++;
          assertEquals(1, mixer.playingVoices(), name);
        }
        mixer.mix(out, frames);
        sound.update(50);
        sound.stopAllSounds();
      }
    }
    sound.close();
    REPORT.add(
        String.format(
            Locale.ROOT,
            "sounds: %d files, %d played, %d silent placeholders, %d names too long",
            names.size(),
            played,
            silent,
            skipped));
    silentNames.forEach(name -> REPORT.add("  silent: " + name));
    assertTrue(played > names.size() * 9 / 10);
  }

  @Test
  void everyMusicTrackStreams() {
    List<String> tracks = assets.list("music", "wav");
    var mixer = new SoftwareMixer(RATE, 8);
    var sound = new SoundSystem(assets, mixer, SoundSystem.Limits.DEFAULT);
    sound.claimThread();
    sound.setMusicVolume(1f);
    short[] out = new short[441 * 2];
    int streamed = 0;
    long peak = 0;
    for (String track : tracks) {
      sound.startBackgroundTrack(track, track);
      if (!sound.backgroundTrackPlaying()) {
        REPORT.add("  music not playable: " + track);
        continue;
      }
      for (int i = 0; i < 50; i++) { // 50 x 20 ms
        mixer.mix(out, 441);
        for (short sample : out) peak = Math.max(peak, Math.abs(sample));
        sound.update(20);
      }
      assertTrue(sound.backgroundTrackPlaying(), track);
      streamed++;
      sound.stopBackgroundTrack();
    }
    REPORT.add(
        String.format(
            Locale.ROOT,
            "music: %d tracks, %d streamed for 1 s, peak %d, %d stream chunks allocated",
            tracks.size(),
            streamed,
            peak,
            mixer.chunksAllocated()));
    assertTrue(streamed > 0);
    assertTrue(peak > 0, "music is audible");
  }

  @Test
  void introCinematicAudioQueuesAsRawSamples() throws IOException {
    var path = VirtualPath.of("video/intro.RoQ");
    if (!searchPath.exists(path)) {
      REPORT.add("cinematic: video/intro.RoQ not present");
      return;
    }
    var mixer = new SoftwareMixer(RATE, 8);
    var sound = new SoundSystem(assets, mixer, SoundSystem.Limits.DEFAULT);
    sound.claimThread();
    long frames = 0, blocks = 0, videoFrames = 0;
    short[] out = new short[0];
    try (var decoder = new RoqDecoder(searchPath.open(path))) {
      for (var event = decoder.next(); event.isPresent(); event = decoder.next()) {
        if (event.get() instanceof RoqDecoder.Audio audio) {
          sound.rawSamples(audio.sound(), 1f);
          int count = audio.sound().frames();
          frames += count;
          blocks++;
          // Play the block at once so the queue stays within its backlog limit.
          int output = (int) ((long) count * RATE / audio.sound().sampleRate()) + 1;
          if (out.length < output * 2) out = new short[output * 2];
          mixer.mix(out, output);
        } else {
          videoFrames++;
        }
      }
    }
    sound.close();
    REPORT.add(
        String.format(
            Locale.ROOT,
            "cinematic: intro.RoQ %d video frames, %d audio blocks, %d sample frames (%.1f s)",
            videoFrames,
            blocks,
            frames,
            frames / 22050.0));
    assertTrue(blocks > 0);
  }
}
