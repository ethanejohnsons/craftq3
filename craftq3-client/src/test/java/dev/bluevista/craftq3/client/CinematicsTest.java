package dev.bluevista.craftq3.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.*;
import dev.bluevista.craftq3.client.video.Cinematics;
import dev.bluevista.craftq3.client.video.RoqAudioStream;
import dev.bluevista.craftq3.core.command.*;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.render.CgameFrame;
import dev.bluevista.craftq3.server.Q3Server;
import dev.bluevista.craftq3.vm.Opcode;
import java.io.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CinematicsTest {
  private static byte[] bytes(int... values) {
    byte[] result = new byte[values.length];
    for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
    return result;
  }

  private static void chunk(ByteArrayOutputStream out, int id, int argument, byte[] data)
      throws IOException {
    out.write(
        bytes(
            id,
            id >>> 8,
            data.length,
            data.length >>> 8,
            data.length >>> 16,
            data.length >>> 24,
            argument,
            argument >>> 8));
    out.write(data);
  }

  static byte[] movie(int frames, int audioSamples) throws IOException {
    var out = new ByteArrayOutputStream();
    out.write(bytes(0x84, 0x10, 255, 255, 255, 255, 30, 0));
    chunk(out, 0x1001, 0, bytes(16, 0, 16, 0, 8, 0, 4, 0));
    if (audioSamples > 0) chunk(out, 0x1020, 0, new byte[audioSamples]);
    for (int i = 0; i < frames; i++) {
      chunk(out, 0x1002, 257, bytes(i, i, i, i, 128, 128, 0, 0, 0, 0));
      chunk(out, 0x1011, 0, bytes(0, 0xaa, 0, 0, 0, 0));
    }
    return out.toByteArray();
  }

  @Test
  void silentHandlesHoldLoopScaleAndReleaseBorrowedServices() throws Exception {
    var fs = new ClientTestData.Files(Map.of("video/test.roq", movie(3, 0)));
    var audio = new ClientTestData.Audio();
    try (var cin = new Cinematics(fs, audio, () -> 0, s -> fail(s))) {
      int h = cin.play("test", 0, 0, 640, 480, Cinematics.SILENT | Cinematics.HOLD, 1000);
      assertEquals(0, h);
      assertEquals(Cinematics.PLAY, cin.run(h, 1034));
      assertEquals(1, cin.info(h).orElseThrow().frame());
      assertEquals(Cinematics.IDLE, cin.run(h, 1100));
      cin.setExtents(h, 10, 20, 320, 240);
      var frame = cin.draw(h, 1280, 960).orElseThrow();
      assertEquals(20, frame.x());
      assertEquals(40, frame.y());
      assertEquals(640, frame.width());
      assertEquals(480, frame.height());
      assertSame(frame.pixels(), cin.draw(h, 1280, 960).orElseThrow().pixels());
      assertEquals(Cinematics.EOF, cin.stop(h));
      assertTrue(cin.draw(h, 640, 480).isEmpty());
      h = cin.play("video/TEST.RoQ", 0, 0, 640, 480, Cinematics.SILENT | Cinematics.LOOP, 0);
      assertEquals(Cinematics.PLAY, cin.run(h, 1000));
      assertEquals(10, cin.info(h).orElseThrow().cycle());
      assertNotEquals(frame.stream(), cin.draw(h, 640, 480).orElseThrow().stream());
      cin.clear();
      assertEquals(0, cin.active());
      assertEquals(0, cin.play("test", 0, 0, 640, 480, Cinematics.SILENT, 0));
      assertEquals(Cinematics.EOF, cin.run(0, 100));
      assertEquals(0, cin.active());
    }
    assertFalse(audio.closed);
    assertFalse(fs.closed);
    assertEquals(0, audio.voices);
  }

  @Test
  void repeatedGuestRunsKeepOneImmutableImageWithinTheSameRendererFrame() throws Exception {
    var fs = new ClientTestData.Files(Map.of("video/test.roq", movie(60, 0)));
    try (var cin = new Cinematics(fs, new ClientTestData.Audio(), () -> 0, s -> fail(s))) {
      int h = cin.play("test", 0, 0, 640, 480, Cinematics.SILENT, 0);
      cin.beginFrame();
      cin.run(h, 34);
      var first = cin.draw(h, 640, 480).orElseThrow();
      cin.run(h, 67);
      assertSame(first.pixels(), cin.draw(h, 640, 480).orElseThrow().pixels());
      cin.beginFrame();
      cin.run(h, 67);
      assertEquals(2, cin.info(h).orElseThrow().frame());
      assertNotSame(first.pixels(), cin.draw(h, 640, 480).orElseThrow().pixels());
    }
  }

  @Test
  void prefetchWaitsForNativeStartAndUsesAudioClockInsteadOfEngineClock() throws Exception {
    var fs = new ClientTestData.Files(Map.of("video/test.roq", movie(60, 22050)));
    var audio = new ClientTestData.Audio();
    var nanos = new AtomicLong(1_000_000_000);
    try (var cin = new Cinematics(fs, audio, nanos::get, s -> fail(s))) {
      int h = cin.play("test", 0, 0, 640, 480, 0, 0);
      var queue = (RoqAudioStream) audio.streams.values().iterator().next();
      queue.completion().get(3, java.util.concurrent.TimeUnit.SECONDS);
      assertTrue(queue.ready());
      assertEquals(22050, queue.submittedFrames());
      assertEquals(Cinematics.PLAY, cin.run(h, 10000));
      assertEquals(0, cin.info(h).orElseThrow().frame());
      queue.started(nanos.get());
      nanos.addAndGet(50_000_000);
      cin.run(h, 20000);
      assertEquals(50, cin.info(h).orElseThrow().milliseconds());
      assertEquals(1, cin.info(h).orElseThrow().frame());
      nanos.addAndGet(1_950_000_000);
      assertEquals(Cinematics.EOF, cin.run(h, 30000));
      assertTrue(queue.closed());
      assertTrue(audio.streams.isEmpty());
    }
  }

  @Test
  void audioLoopReprimesAtItsExactCycleOriginWithoutRewindingPresentation() throws Exception {
    var fs = new ClientTestData.Files(Map.of("video/test.roq", movie(3, 1102)));
    var audio = new ClientTestData.Audio();
    var nanos = new AtomicLong(1_000_000_000);
    try (var cin = new Cinematics(fs, audio, nanos::get, s -> fail(s))) {
      int h = cin.play("test", 0, 0, 640, 480, Cinematics.LOOP, 0);
      var first = (RoqAudioStream) audio.streams.values().iterator().next();
      first.completion().get(3, java.util.concurrent.TimeUnit.SECONDS);
      first.started(nanos.get());
      nanos.addAndGet(105_000_000);
      assertEquals(Cinematics.PLAY, cin.run(h, 105));
      assertEquals(1, cin.info(h).orElseThrow().cycle());
      assertTrue(first.closed());
      var second = (RoqAudioStream) audio.streams.values().iterator().next();
      assertNotSame(first, second);
      second.completion().get(3, java.util.concurrent.TimeUnit.SECONDS);
      second.started(nanos.get());
      nanos.addAndGet(20_000_000);
      assertEquals(Cinematics.PLAY, cin.run(h, 125));
      assertEquals(120, cin.info(h).orElseThrow().milliseconds());
      assertEquals(0, cin.info(h).orElseThrow().frame());
    }
  }

  @Test
  void failedAssetsBudgetsAndInterruptedAudioDoNotLeakHandles() throws Exception {
    var fs =
        new ClientTestData.Files(
            Map.of("video/test.roq", movie(30, 22050), "video/bad.roq", new byte[8]));
    var audio = new ClientTestData.Audio();
    var errors = new ArrayList<String>();
    try (var cin = new Cinematics(fs, audio, () -> 0, errors::add)) {
      for (String path : List.of("missing", "bad", "../../outside"))
        assertEquals(-1, cin.play(path, 0, 0, 640, 480, 0, 0));
      assertEquals(0, cin.active());
      int h = cin.play("test", 0, 0, 640, 480, 0, 0);
      var queue = (RoqAudioStream) audio.streams.values().iterator().next();
      queue.close();
      // Cancellation may race natural decoding EOF, but unread samples still make it incomplete.
      queue.completion().get(3, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(Cinematics.EOF, cin.run(h, 1));
      assertTrue(audio.streams.isEmpty());
      for (int i = 0; i < 32; i++)
        assertEquals(i, cin.play("test", 0, 0, 640, 480, Cinematics.SILENT, 0));
      assertEquals(-1, cin.play("test", 0, 0, 640, 480, Cinematics.SILENT, 0));
      cin.clear();
      assertEquals(0, cin.active());
      assertEquals(Cinematics.EOF, cin.stop(-1));
      assertEquals(Cinematics.EOF, cin.run(999, 0));
      assertTrue(errors.stream().anyMatch(s -> s.contains("stopped before completion")));
    }
  }

  private static byte[] guest(boolean ui, int api) {
    var p = new ClientTestData.Program().op(Opcode.ENTER, 64);
    p.op(Opcode.LOCAL, 72).op(Opcode.LOAD4).op(Opcode.CONST, ui ? 5 : 3);
    int draw = p.size();
    p.op(Opcode.EQ, 0);
    p.op(Opcode.CONST, api).op(Opcode.LEAVE, 64);
    p.patch(draw, p.size());
    int play = ui ? 75 : 74;
    p.call(play + 1, 0);
    p.call(play, 200, 0, 0, 640, 480, Cinematics.LOOP | Cinematics.SILENT);
    p.call(play + 2, 0);
    p.call(play + 4, 0, 10, 20, 320, 240);
    p.call(play + 3, 0);
    p.op(Opcode.CONST, 0).op(Opcode.LEAVE, 64);
    System.arraycopy(
        "test".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, p.data, 200, 4);
    return p.bytes();
  }

  @Test
  void modernUiGuestExercisesAllFiveCinematicTrapsAndResize() throws Exception {
    for (var profile : List.of(UiAbi.Q3_132)) {
      var fs =
          new ClientTestData.Files(
              Map.of(
                  "vm/ui.qvm",
                  guest(true, profile == UiAbi.RETAIL_1999 ? 3 : 4),
                  "video/test.roq",
                  movie(3, 0)));
      var audio = new ClientTestData.Audio();
      var vars = new CvarSystem();
      vars.register("sv_cheats", "0", CvarSystem.ROM);
      try (var ui =
          new Q3Ui(
              fs,
              vars,
              new CommandSystem(vars, fs, s -> {}),
              new KeyBindings(),
              audio,
              frame -> {},
              s -> {},
              UiHost.disconnected(),
              profile)) {
        ui.initialize(640, 480);
        var first = (CgameFrame.Image) ui.frame(1000, 640, 480).commands().getFirst();
        assertEquals(320, first.width());
        var resized = (CgameFrame.Image) ui.frame(1016, 1280, 960).commands().getFirst();
        assertEquals(640, resized.width());
        for (int call = 75; call <= 79; call++)
          assertTrue(ui.syscallCounts().getOrDefault(call, 0L) > 0);
      }
      assertFalse(fs.closed);
      assertFalse(audio.closed);
    }
  }

  @Test
  void cgameGuestsExerciseAllFiveCinematicTrapsAcrossBothProfilesAndResize() throws Exception {
    for (var profile : ClientAbi.values()) {
      var fs =
          new ClientTestData.Files(
              Map.of(
                  "vm/qagame.qvm",
                  ClientTestData.server(),
                  "vm/cgame.qvm",
                  guest(false, 0),
                  "video/test.roq",
                  movie(3, 0)));
      var audio = new ClientTestData.Audio();
      try (var server =
          new Q3Server(
              fs,
              "fixture",
              BspReader.read(BspFixture.map(false)),
              null,
              s -> {},
              Clock.systemUTC())) {
        server.initialize(1000, 1);
        server.connect(0, Map.of("name", "Synthetic"));
        try (var client = new Q3Client(fs, server, frame -> {}, audio, s -> {}, profile)) {
          client.initialize(0, 640, 480);
          var first =
              (CgameFrame.Image) client.frame(server.time(), 640, 480).commands().getFirst();
          assertEquals(320, first.width());
          var resized =
              (CgameFrame.Image) client.frame(server.time() + 16, 1280, 960).commands().getFirst();
          assertEquals(640, resized.width());
          for (int call = 74; call <= 78; call++)
            assertTrue(client.syscallCounts().getOrDefault(call, 0L) > 0);
        }
      }
      assertFalse(audio.closed);
    }
  }
}
