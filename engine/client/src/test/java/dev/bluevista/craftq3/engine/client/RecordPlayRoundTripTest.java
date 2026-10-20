package dev.bluevista.craftq3.engine.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.client.demo.DemoPlayback;
import dev.bluevista.craftq3.engine.client.demo.DemoRecorder;
import dev.bluevista.craftq3.engine.client.demo.Protocol68DemoRecorder;
import dev.bluevista.craftq3.engine.client.host.CgameHost;
import dev.bluevista.craftq3.engine.client.net.ClientConnection;
import dev.bluevista.craftq3.engine.client.net.FakeServer;
import dev.bluevista.craftq3.engine.client.pipeline.SnapshotPipeline;
import dev.bluevista.craftq3.engine.client.pipeline.UserCmdHistory;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec;
import dev.bluevista.craftq3.engine.net.transport.LoopbackTransport;
import dev.bluevista.craftq3.engine.render.frame.CgameFrame;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout.UserCmd;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A live connection to a fake server through a synthetic cgame is recorded with {@link
 * DemoRecorder}; playing the recording back through {@link DemoPlayback} and a fresh cgame gives
 * the same sequence of frame digests (one per distinct snapshot drawn).
 */
final class RecordPlayRoundTripTest {
  @TempDir Path install;

  static String digest(CgameFrame frame) {
    StringBuilder text = new StringBuilder();
    for (CgameFrame.Command command : frame.commands())
      if (command instanceof CgameFrame.Quad q)
        text.append(q.x()).append(',').append(q.y()).append(',').append(q.shader()).append(';');
    return text.toString();
  }

  static List<String> distinct(List<CgameFrame> frames) {
    List<String> out = new ArrayList<>();
    for (CgameFrame frame : frames) {
      String d = digest(frame);
      if (d.isEmpty() || (!out.isEmpty() && out.getLast().equals(d))) continue;
      out.add(d);
    }
    return out;
  }

  @Test
  void recordedDemoReplaysTheSameFrames() throws Exception {
    Files.createDirectories(install.resolve("baseq3/vm"));
    Files.write(install.resolve("baseq3/vm/cgame.qvm"), TestQvm.snapshotCgame());
    ByteArrayOutputStream demo = new ByteArrayOutputStream();
    List<String> live;
    try (SearchPath path = SearchPath.mount(install, "baseq3");
        ClientRig rig = new ClientRig(path.view(), null, true)) {
      LoopbackTransport.Pair link = LoopbackTransport.pair();
      FakeServer server = new FakeServer(link.server());
      SnapshotPipeline pipeline = new SnapshotPipeline();
      UserCmdHistory commands = new UserCmdHistory();
      ClientConnection[] connection = new ClientConnection[1];
      CgameHost cgame =
          new CgameHost(
              rig.services,
              pipeline,
              commands,
              new CgameHost.Link() {
                @Override
                public void clientCommand(String text) {
                  connection[0].command(text);
                }

                @Override
                public void systemInfo(String info) {
                  connection[0].presentServerId(info);
                }
              });
      List<ServerMessageCodec.GameState> levels = new ArrayList<>();
      connection[0] =
          new ClientConnection(
              link.client(),
              link.client().remoteAddress(),
              "loopback",
              Map.of(
                  "name", "CraftQ3 round trip", "rate", "25000", "snaps", "20", "model", "sarge"),
              7,
              99,
              pipeline,
              commands,
              rig.services.cvars(),
              ClientConnection.Settings.DEFAULT,
              new ClientConnection.Listener() {
                @Override
                public void levelReady(ServerMessageCodec.GameState gameState) {
                  levels.add(gameState);
                }
              },
              0);
      DemoRecorder recorder = null;
      int recordingFrom = -1;
      for (int frame = 0; frame < 400; frame++) {
        rig.advance(10);
        int now = rig.services.milliseconds();
        if (frame % 5 == 0) server.tick();
        connection[0].pump(now);
        if (!levels.isEmpty() && cgame.state() != CgameHost.State.RUNNING) {
          cgame.start();
          connection[0].cgameLoaded();
        }
        if (cgame.state() != CgameHost.State.RUNNING) continue;
        commands.add(UserCmd.idle(now));
        OptionalInt time = connection[0].serverTime(now);
        if (frame == 100)
          recorder =
              DemoRecorder.start(
                  pipeline,
                  demo,
                  connection[0].session().orElseThrow().reliableSequence(),
                  connection[0]::requestFullSnapshots);
        if (recorder != null
            && recordingFrom < 0
            && recorder.state() == Protocol68DemoRecorder.State.RECORDING)
          recordingFrom = rig.frames.size();
        if (time.isEmpty()) continue;
        rig.services.renderer().beginFrame(now);
        cgame.drawActiveFrame(time.getAsInt());
        rig.services.renderer().endFrame();
      }
      assertNotNull(recorder);
      assertTrue(recordingFrom > 0, "the recorder never saw a full snapshot");
      recorder.close();
      assertEquals(0, cgame.degradationTotal());
      live = distinct(rig.frames.subList(recordingFrom, rig.frames.size()));
      cgame.shutdown();
    }
    List<String> replay;
    try (SearchPath path = SearchPath.mount(install, "baseq3");
        ClientRig rig = new ClientRig(path.view(), null, true)) {
      SnapshotPipeline pipeline = new SnapshotPipeline();
      DemoPlayback playback =
          DemoPlayback.open(
              "roundtrip.dm_68", new ByteArrayInputStream(demo.toByteArray()), pipeline);
      CgameHost cgame =
          new CgameHost(
              rig.services,
              pipeline,
              new UserCmdHistory(),
              new CgameHost.Link() {
                @Override
                public void clientCommand(String text) {}

                @Override
                public boolean demoPlayback() {
                  return true;
                }
              });
      playback.prime();
      cgame.start();
      for (int i = 0; i < 10_000 && !playback.ended(); i++) {
        rig.advance(10);
        OptionalInt time = playback.frame(10, 0, 1, false, false, rig.services.milliseconds());
        if (time.isEmpty()) continue;
        rig.services.renderer().beginFrame(rig.services.milliseconds());
        cgame.drawActiveFrame(time.getAsInt());
        rig.services.renderer().endFrame();
      }
      assertTrue(playback.ended());
      assertEquals(0, cgame.degradationTotal());
      replay = distinct(rig.frames);
      cgame.shutdown();
      playback.close();
    }
    assertTrue(replay.size() >= 20, "replay drew " + replay.size() + " snapshots");
    int start = Collections.indexOfSubList(live, replay.subList(0, 1));
    assertTrue(start >= 0, "the replay's first frame was never drawn live");
    // Playback stops at the last recorded snapshot; live may have drawn one more.
    assertEquals(live.subList(start, start + replay.size()), replay);
  }
}
