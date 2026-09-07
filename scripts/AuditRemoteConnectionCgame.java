import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.client.*;
import dev.bluevista.craftq3.client.net.RemoteConnection;
import dev.bluevista.craftq3.core.command.*;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.*;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.core.net.*;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.platform.net.UdpTransport;
import dev.bluevista.craftq3.render.CgameFrame;
import dev.bluevista.craftq3.server.UserCommand;
import java.io.IOException;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Private UDP to unchanged native qagame, with its matching original cgame in the Java VM. */
public class AuditRemoteConnectionCgame {
  private static final class Audio implements AudioBackend {
    int sounds; long voices;
    public int register(String name, PcmSound sound) { return ++sounds; }
    public long play(Playback playback) { return ++voices; }
    public void updateEntity(int entity, Vec3 origin) {}
    public void beginFrame() {}
    public void submitLoop(Loop loop) {}
    public void endFrame(Listener listener) {}
    public void clearLoops() {}
    public void stop(long voice) {}
    public void stopAll() {}
    public void volume(float gain) {}
    public Diagnostics diagnostics() { return new Diagnostics(true, sounds, 0, 0, 0, voices, 0, "CPU audit"); }
    public void close() {}
  }

  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(args[0]);
    Path out = Path.of(args[1]), root = Path.of(args[2]);
    var peer = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port);
    var info = Map.of("name", "CraftQ3 pump QA", "rate", "25000", "snaps", "20",
        "model", "sarge/default", "headmodel", "sarge/default", "handicap", "100");
    var audio = new Audio();
    long started = System.nanoTime();
    try (var connection = RemoteConnection.open(peer, info, 246813579, 32125, 0);
        var disk = Pk3FileSystem.mount(root.resolve(".tools/pak0-audit/games"), "baseq3")) {
      VirtualFileSystem fs = new VirtualFileSystem() {
        public Optional<Origin> which(VirtualPath path) { return disk.which(path); }
        public List<VirtualPath> list(String directory) { return disk.list(directory); }
        public List<Origin> searchOrder() { return disk.searchOrder(); }
        public byte[] read(VirtualPath path) throws IOException {
          if (path.value().equals("vm/cgame.qvm"))
            return Files.readAllBytes(root.resolve(".tools/ioquake3-qvm-audit-build/Release/baseq3/vm/cgame.qvm"));
          return disk.read(path);
        }
        public void close() {}
      };
      var cvars = new CvarSystem();
      var commands = new CommandSystem(cvars, fs, System.out::print);
      var systemInfo = new RemoteSystemInfo(cvars, System.out::println);
      var clock = new RemoteServerClock();
      Q3Client client = null;
      int generation = 0, gameStates = 0, snapshots = 0, frames = 0, levelFrames = 0;
      int views = 0, quads = 0, entities = 0, resizes = 0, serverCommands = 0, changes = 0;
      int restarts = 0, sent = 0, received = 0, rejected = 0;
      boolean changeRequested = false, restartRequested = false;
      double distance = 0;
      float[] previous = null;
      long nextFrame = 0;
      try {
        while ((System.nanoTime()-started)/1_000_000 < 30000 && (gameStates < 2 || levelFrames < 110)) {
          long now = (System.nanoTime()-started)/1_000_000;
          var pumped = connection.pump(now);
          sent += pumped.sentDatagrams(); received += pumped.receivedDatagrams();
          if (pumped.state() == RemoteConnection.State.FAILED || pumped.state() == RemoteConnection.State.TIMED_OUT)
            throw new AssertionError("Connection failed: " + connection.diagnostic());
          for (var event : pumped.events()) {
            if (event.kind() == RemoteConnection.EventKind.REJECTED) {
              rejected++; System.out.println("REJECTED " + event.diagnostic());
            }
            if (event.kind() != RemoteConnection.EventKind.MESSAGE) continue;
            serverCommands += event.received().commands().size();
            for (var command : event.received().commands()) {
              if (CommandParser.tokenize(command.text()).argument(0).equals("map_restart")) restarts++;
            }
            for (var operation : event.received().message().operations()) {
              if (operation instanceof ServerMessageCodec.GameState game) {
                gameStates++; clock.reset(); previous = null;
              } else if (operation instanceof ServerMessageCodec.Frame frame) {
                clock.snapshot(frame.current().time(), frame.current().flags()); snapshots++;
                var player = ByteBuffer.wrap(frame.current().player()).order(ByteOrder.LITTLE_ENDIAN);
                float[] xyz = {player.getFloat(20), player.getFloat(24), player.getFloat(28)};
                if (previous != null) distance += Math.sqrt(Math.pow(xyz[0]-previous[0],2)
                    + Math.pow(xyz[1]-previous[1],2) + Math.pow(xyz[2]-previous[2],2));
                previous = xyz;
              }
            }
          }
          var wire = connection.session().orElse(null);
          if (wire != null && wire.gameStateMessageSequence() != generation) {
            if (client != null) client.close();
            var game = wire.initialGameState().orElseThrow();
            RemoteSystemInfo.parse(game.configstrings().get(1)).requireGame("baseq3");
            var source = new RemoteCgameSource(wire, connection::userCommand, connection::snapshotPing, text -> {
              systemInfo.apply(text); System.out.println("SYSTEMINFO " + wire.serverId());
            });
            client = new Q3Client(fs, source, cvars, commands, frame -> {}, audio, System.out::print, ClientAbi.Q3_132);
            client.initialize(game.clientNumber(), 1280, 720);
            generation = wire.gameStateMessageSequence(); levelFrames = 0; changes++;
            // Native primed clients send actual input before their first usable snapshot.
            client.userCommand(UserCommand.idle(0));
            System.out.println("CGAME_LEVEL " + gameStates + " baseline=" + generation);
          }
          if (client != null && now >= nextFrame) {
            var time = clock.frame((int)now, 0, 1);
            if (time.isPresent()) {
              int serverTime = time.getAsInt();
              client.userCommand(new UserCommand(serverTime, 0, (frames*24)&65535, 0, 1,
                  client.selectedWeapon(), (levelFrames/100 & 1) == 0 ? 127 : -127, 0,
                  levelFrames%150 == 50 ? 127 : 0));
              var rendered = client.frame(serverTime, levelFrames<80 ? 1280:1024, levelFrames<80 ? 720:768);
              if (levelFrames == 80) resizes++;
              for (var command : rendered.commands()) {
                if (command instanceof CgameFrame.View view) { views++; entities += view.entities().size(); }
                else if (command instanceof CgameFrame.Quad ignored) quads++;
              }
              frames++; levelFrames++;
              if (levelFrames == 60) client.consoleCommand(CommandParser.tokenize("+scores"));
              if (levelFrames == 70) client.consoleCommand(CommandParser.tokenize("-scores"));
              if (gameStates == 1 && levelFrames >= 160 && !restartRequested) {
                restartRequested = true; System.out.println("RESTART_LEVEL");
              }
              if (gameStates == 1 && levelFrames >= 300 && !changeRequested) {
                changeRequested = true; System.out.println("CHANGE_LEVEL q3dm17");
              }
            }
            nextFrame = now+16;
          }
          Thread.sleep(1);
        }
        if (gameStates != 2 || changes != 2 || snapshots < 90 || frames < 410 || views < 800
            || quads < 1000 || entities < 500 || audio.voices < 2 || distance < 500
            || resizes != 2 || restarts != 1 || serverCommands < 3 || rejected != 0)
          throw new AssertionError("Incomplete pump cgame proof: maps=" + gameStates + " snapshots=" + snapshots
              + " frames=" + frames + " restarts=" + restarts + " rejected=" + rejected);
        long now = (System.nanoTime()-started)/1_000_000;
        connection.disconnectAndClose(now);
        String result = "{\"gameStates\":"+gameStates+",\"snapshots\":"+snapshots+",\"frames\":"+frames
            +",\"views\":"+views+",\"quads\":"+quads+",\"entities\":"+entities+",\"voices\":"+audio.voices
            +",\"distance\":"+distance+",\"rendererRestarts\":"+resizes+",\"mapRestarts\":"+restarts
            +",\"sentDatagrams\":"+sent+",\"receivedDatagrams\":"+received+",\"rejections\":"+rejected+"}";
        Files.writeString(out.resolve("pump-cgame-result.json"), result+"\n");
        System.out.println("PASS "+result);
      } finally { if (client != null) client.close(); }
    }
  }
}
