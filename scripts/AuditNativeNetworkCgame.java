import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.client.*;
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
public class AuditNativeNetworkCgame {
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
    var info = Map.of("name", "CraftQ3 cgame QA", "rate", "25000", "snaps", "20",
        "model", "sarge/default", "headmodel", "sarge/default", "handicap", "100");
    var handshake = new Protocol68Handshake(135792468, 32124, info);
    var packets = new ArrayList<String>();
    var audio = new Audio();
    try (var socket = UdpTransport.open(peer);
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
      long deadline = System.nanoTime() + 8_000_000_000L, nextRequest = 0;
      while (handshake.state() != Protocol68Handshake.State.CONNECTED) {
        if (System.nanoTime() >= deadline) throw new AssertionError("Handshake timed out");
        if (System.nanoTime() >= nextRequest) {
          send(socket, handshake.request(), packets);
          nextRequest = System.nanoTime() + 500_000_000L;
        }
        var packet = socket.poll();
        if (packet.status() != UdpTransport.Status.PACKET) { Thread.sleep(1); continue; }
        packets.add("receive " + HexFormat.of().formatHex(packet.payload()));
        var result = handshake.receive(packet.payload());
        if (result == Protocol68Handshake.Result.CHALLENGE_ACCEPTED) nextRequest = 0;
        if (result == Protocol68Handshake.Result.PRINT) throw new AssertionError(handshake.print());
      }
      var wire = new Protocol68ClientSession(handshake.challenge(), handshake.qport());
      var cvars = new CvarSystem();
      var commands = new CommandSystem(cvars, fs, System.out::print);
      var pendingInput = new ArrayList<byte[]>();
      Q3Client client = null;
      int gameStates = 0, snapshots = 0, frames = 0, views = 0, quads = 0, entities = 0;
      int serverTime = 0, levelFrames = 0, resized = 0, serverCommands = 0;
      boolean changeRequested = false;
      double distance = 0;
      float[] previous = null;
      long nextSend = 0, nextFrame = 0;
      deadline = System.nanoTime() + 30_000_000_000L;
      try {
        while (System.nanoTime() < deadline && (gameStates < 2 || levelFrames < 100)) {
          var packet = socket.poll();
          if (packet.status() == UdpTransport.Status.PACKET) {
            packets.add("receive " + HexFormat.of().formatHex(packet.payload()));
            var received = wire.receive(packet.payload());
            if (received.status() == Protocol68ClientSession.Status.REJECTED)
              throw new AssertionError("Rejected native packet: " + received.diagnostic());
            if (received.status() == Protocol68ClientSession.Status.ACCEPTED) {
              serverCommands += received.commands().size();
              for (var operation : received.message().operations()) {
                if (operation instanceof ServerMessageCodec.GameState game) {
                  if (client != null) client.close();
                  pendingInput.clear();
                  var source = new RemoteCgameSource(wire, input -> pendingInput.add(canonical(input)));
                  client = new Q3Client(fs, source, cvars, commands, frame -> {}, audio, System.out::print, ClientAbi.Q3_132);
                  client.initialize(game.clientNumber(), 1280, 720);
                  gameStates++; levelFrames = 0; serverTime = 0; previous = null;
                  System.out.println("CGAME_LEVEL " + gameStates + " baseline=" + wire.gameStateMessageSequence());
                } else if (operation instanceof ServerMessageCodec.Frame frame) {
                  snapshots++;
                  serverTime = Math.max(serverTime, frame.current().time());
                  var player = ByteBuffer.wrap(frame.current().player()).order(ByteOrder.LITTLE_ENDIAN);
                  float[] xyz = {player.getFloat(20), player.getFloat(24), player.getFloat(28)};
                  if (previous != null) distance += Math.sqrt(Math.pow(xyz[0]-previous[0],2)
                      + Math.pow(xyz[1]-previous[1],2) + Math.pow(xyz[2]-previous[2],2));
                  previous = xyz;
                }
              }
            }
          } else if (packet.status() != UdpTransport.Status.EMPTY) {
            throw new AssertionError("Invalid UDP envelope " + packet.status());
          }
          long now = System.nanoTime();
          if (client != null && now >= nextFrame) {
            // Ordinary human input feeds cgame prediction and the real server from the same record.
            serverTime += 16;
            client.userCommand(new UserCommand(serverTime, 0, (frames*24)&65535, 0, 1,
                client.selectedWeapon(), (levelFrames / 100 & 1) == 0 ? 127 : -127, 0,
                levelFrames % 150 == 50 ? 127 : 0));
            var rendered = client.frame(serverTime, levelFrames < 80 ? 1280 : 1024, levelFrames < 80 ? 720 : 768);
            if (levelFrames == 80) resized++;
            for (var command : rendered.commands()) {
              if (command instanceof CgameFrame.View view) { views++; entities += view.entities().size(); }
              else if (command instanceof CgameFrame.Quad ignored) quads++;
            }
            frames++; levelFrames++;
            if (levelFrames == 60) client.consoleCommand(CommandParser.tokenize("+scores"));
            if (levelFrames == 70) client.consoleCommand(CommandParser.tokenize("-scores"));
            if (gameStates == 1 && levelFrames >= 250 && !changeRequested) {
              changeRequested = true;
              System.out.println("CHANGE_LEVEL q3dm17");
            }
            nextFrame = now + 16_000_000L;
          }
          if (now >= nextSend) {
            // Repeat the recent commands to tolerate lost datagrams, bounded by the protocol's 32.
            if (pendingInput.size() > 32) pendingInput.subList(0, pendingInput.size()-32).clear();
            wire.queue(pendingInput);
            while (wire.hasPendingPacket()) send(socket, wire.pollPacket().orElseThrow(), packets);
            if (pendingInput.size() > 3) pendingInput.subList(0, pendingInput.size()-3).clear();
            nextSend = System.nanoTime() + 50_000_000L;
          }
          Thread.sleep(1);
        }
        if (gameStates != 2 || snapshots < 60 || frames < 350 || views < 300 || quads < 1000
            || entities < 500 || audio.voices < 2 || distance < 500 || resized != 2 || serverCommands < 1)
          throw new AssertionError("Incomplete remote cgame: levels=" + gameStates + " snapshots=" + snapshots
              + " frames=" + frames + " views=" + views + " quads=" + quads + " entities=" + entities
              + " voices=" + audio.voices + " distance=" + distance + " resized=" + resized + " commands=" + serverCommands);
        wire.command("disconnect");
        for (int i = 0; i < 3; i++) {
          wire.queue(List.of());
          while (wire.hasPendingPacket()) send(socket, wire.pollPacket().orElseThrow(), packets);
        }
        String result = "{\"gameStates\":" + gameStates + ",\"snapshots\":" + snapshots
            + ",\"frames\":" + frames + ",\"views\":" + views + ",\"quads\":" + quads
            + ",\"entities\":" + entities + ",\"voices\":" + audio.voices + ",\"distance\":" + distance
            + ",\"rendererRestarts\":" + resized + ",\"serverCommands\":" + serverCommands + "}";
        Files.writeString(out.resolve("cgame-result.json"), result + "\n");
        System.out.println("PASS " + result);
      } finally {
        if (client != null) client.close();
        Files.write(out.resolve("cgame-packets.txt"), packets);
      }
    }
  }

  private static byte[] canonical(UserCommand command) {
    return ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(command.serverTime())
        .putInt(command.pitch()).putInt(command.yaw()).putInt(command.roll()).putInt(command.buttons())
        .put((byte)command.weapon()).put((byte)command.forward()).put((byte)command.right()).put((byte)command.up()).array();
  }

  private static void send(UdpTransport socket, byte[] bytes, List<String> packets) throws Exception {
    long deadline = System.nanoTime() + 500_000_000L;
    while (!socket.send(bytes)) {
      if (System.nanoTime() >= deadline) throw new AssertionError("Loopback send blocked");
      Thread.sleep(1);
    }
    packets.add("send " + HexFormat.of().formatHex(bytes));
  }
}
