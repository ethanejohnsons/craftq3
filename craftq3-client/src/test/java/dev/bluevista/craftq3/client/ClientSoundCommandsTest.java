package dev.bluevista.craftq3.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.assets.bsp.BspFixture;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.core.command.CommandSystem;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.server.Q3Server;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings("try")
final class ClientSoundCommandsTest {
  @Test
  void playLoadsBoundedWavAssetsAndUsesTheObservedLocalSoundChannelInArgumentOrder()
      throws Exception {
    var fs = new ClientTestData.Files(Map.of("sound/a.wav", wave(), "sound/b.wav", wave()));
    var audio = new Audio();
    var messages = new ArrayList<String>();
    var commands = new CommandSystem(new CvarSystem(), fs, messages::add);
    var assets = new ClientAssets(fs, audio, messages::add);
    try (var sounds = new ClientSoundCommands(commands, assets, audio, () -> 7, messages::add)) {
      commands.submit("play sound/a SOUND/B.WAV sound/a.wav", CommandSystem.Execution.NOW);
      assertEquals(List.of("sound/a.wav", "sound/b.wav"), audio.names);
      assertEquals(
          List.of(1, 2, 1), audio.playbacks.stream().map(AudioBackend.Playback::sound).toList());
      for (var playback : audio.playbacks) {
        assertEquals(AudioBackend.Spatial.LOCAL, playback.spatial());
        assertEquals(7, playback.entity());
        assertEquals(6, playback.channel());
        assertNull(playback.origin());
        assertEquals(1, playback.gain());
        assertEquals(1, playback.pitch());
      }
      assertTrue(messages.isEmpty());
    }
    assertFalse(fs.closed);
    assertFalse(audio.closed);
  }

  @Test
  void usageMissingAndInvalidSoundsDoNotAbortLaterArguments() throws Exception {
    var fs = new ClientTestData.Files(Map.of("valid.wav", wave(), "broken.wav", new byte[8]));
    var audio = new Audio();
    var messages = new ArrayList<String>();
    var commands = new CommandSystem(new CvarSystem(), fs, messages::add);
    try (var sounds =
        new ClientSoundCommands(
            commands, new ClientAssets(fs, audio, messages::add), audio, () -> 0, messages::add)) {
      commands.submit("play", CommandSystem.Execution.NOW);
      commands.submit("play \"\" missing ../escape broken valid", CommandSystem.Execution.NOW);
      assertEquals(1, audio.playbacks.size());
      assertEquals(List.of("valid.wav"), audio.names);
      assertTrue(messages.stream().anyMatch(s -> s.startsWith("Usage: play")));
      assertTrue(messages.stream().anyMatch(s -> s.contains("Missing cgame sound: missing.wav")));
      assertTrue(messages.stream().anyMatch(s -> s.contains("Cannot play sound '../escape'")));
      assertTrue(messages.stream().anyMatch(s -> s.contains("Cannot play sound 'broken'")));
    }
  }

  @Test
  void borrowedCommandHandlerIsPreservedAndOwnedRegistrationClosesIdempotently() throws Exception {
    var fs = new ClientTestData.Files(Map.of());
    var audio = new Audio();
    var commands = new CommandSystem(new CvarSystem(), fs, ignored -> {});
    var assets = new ClientAssets(fs, audio, ignored -> {});
    var received = new ArrayList<String>();
    commands.register("play", command -> received.add(command.argument(1)));
    var borrowed = new ClientSoundCommands(commands, assets, audio, () -> 0, ignored -> {});
    borrowed.close();
    commands.submit("play retained", CommandSystem.Execution.NOW);
    assertEquals(List.of("retained"), received);
    commands.unregister("play");
    var owned = new ClientSoundCommands(commands, assets, audio, () -> 0, ignored -> {});
    assertTrue(commands.complete("play").contains("play"));
    owned.close();
    owned.close();
    assertFalse(commands.complete("play").contains("play"));
  }

  @Test
  void clientSoundCommandSurvivesGuestViewportRestartAndIsRemovedOnClientClose() throws Exception {
    for (var profile : ClientAbi.values()) {
      var fs =
          new ClientTestData.Files(
              Map.of(
                  "vm/qagame.qvm",
                  ClientTestData.server(),
                  "vm/cgame.qvm",
                  ClientTestData.client(profile),
                  "cue.wav",
                  wave()));
      var audio = new Audio();
      try (var server =
          new Q3Server(
              fs,
              "fixture",
              BspReader.read(BspFixture.map(false)),
              null,
              ignored -> {},
              Clock.systemUTC())) {
        server.initialize(1000, 1);
        server.connect(0, Map.of("name", "Synthetic"));
        var commands = new CommandSystem(server.cvars(), fs, ignored -> {});
        commands.register("host_only", ignored -> {});
        try (var client =
            new Q3Client(fs, server, ignored -> {}, audio, ignored -> {}, profile, commands)) {
          client.initialize(0, 640, 480);
          commands.submit("play cue", CommandSystem.Execution.NOW);
          client.frame(server.time(), 1024, 768);
          commands.submit("play cue.wav", CommandSystem.Execution.NOW);
          server.commands().submit("play cue", CommandSystem.Execution.APPEND);
          server.runFrame(server.time() + 50);
          assertEquals(3, audio.playbacks.size());
          assertEquals(List.of("cue.wav"), audio.names);
          assertTrue(commands.complete("play").contains("play"));
        }
        assertFalse(commands.complete("play").contains("play"));
        assertFalse(server.commands().complete("play").contains("play"));
        assertTrue(commands.complete("host_only").contains("host_only"));
        assertFalse(audio.closed);
      }
    }
  }

  private static byte[] wave() {
    var out = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
    out.putInt(0x46464952).putInt(38).putInt(0x45564157).putInt(0x20746d66).putInt(16);
    out.putShort((short) 1)
        .putShort((short) 1)
        .putInt(8000)
        .putInt(8000)
        .putShort((short) 1)
        .putShort((short) 8);
    out.putInt(0x61746164).putInt(2).put((byte) 128).put((byte) 128);
    return out.array();
  }

  private static final class Audio implements AudioBackend {
    final List<String> names = new ArrayList<>();
    final List<Playback> playbacks = new ArrayList<>();
    boolean closed;

    public int register(String name, PcmSound sound) {
      names.add(name);
      return names.size();
    }

    public long play(Playback playback) {
      playbacks.add(playback);
      return playbacks.size();
    }

    public void updateEntity(int entity, Vec3 origin) {}

    public void beginFrame() {}

    public void submitLoop(Loop loop) {}

    public void endFrame(Listener listener) {}

    public void clearLoops() {}

    public void stop(long voice) {}

    public void stopAll() {}

    public void volume(float gain) {}

    public Diagnostics diagnostics() {
      return new Diagnostics(true, names.size(), 0, 0, 0, playbacks.size(), 0, "");
    }

    public void close() {
      closed = true;
    }
  }
}
