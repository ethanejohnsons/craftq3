import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.client.Q3Client;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.render.CgameFrame;
import dev.bluevista.craftq3.server.Q3Server;
import dev.bluevista.craftq3.server.UserCommand;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Runs user-supplied, unchanged QVMs and assets. No game code or data is embedded. */
class AuditCgame {
  private record Result(String digest, int models, int shaders, int sounds, long marks,
      int views, int quads, int entities, long voices) {}

  public static void main(String[] args) throws Exception {
    if (args.length < 1 || args.length > 4)
      throw new IllegalArgumentException("Usage: AuditCgame <games directory> [map] [qagame.qvm] [cgame.qvm]");
    Result first = run(args), second = run(args);
    if (!first.equals(second)) throw new AssertionError("Original cgame replay was not deterministic: " + first + " / " + second);
    System.out.println("Original qagame + cgame, 140 movement/firing frames and viewport restart"
        + (Boolean.getBoolean("craftq3.auditRestart") ? ", two retained-client map restarts" : "") + ": PASS");
    System.out.println(first);
  }

  private static Result run(String[] args) throws Exception {
    String mapName = args.length > 1 ? args[1] : "q3dm17";
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (var disk = Pk3FileSystem.mount(Path.of(args[0]), "baseq3")) {
      VirtualFileSystem fs = new VirtualFileSystem() {
        public Optional<Origin> which(VirtualPath path) { return disk.which(path); }
        public List<VirtualPath> list(String directory) { return disk.list(directory); }
        public List<Origin> searchOrder() { return disk.searchOrder(); }
        public byte[] read(VirtualPath path) throws IOException {
          if (args.length > 2 && path.value().equals("vm/qagame.qvm")) return Files.readAllBytes(Path.of(args[2]));
          if (args.length > 3 && path.value().equals("vm/cgame.qvm")) return Files.readAllBytes(Path.of(args[3]));
          return disk.read(path);
        }
        public void close() {}
      };
      var map = BspReader.read(fs.read(new VirtualPath("maps/" + mapName + ".bsp")));
      var audio = new Audio();
      try (var server = new Q3Server(fs, mapName, map, null, System.out::print, Clock.systemUTC())) {
        server.initialize(1000, 42);
        int startTime = server.time();
        server.connect(0, Map.of("name", "CgameAudit", "ip", "localhost", "model", "sarge/default",
            "handicap", "100", "rate", "25000", "snaps", "20"));
        try (var client = new Q3Client(fs, server, frame -> {}, audio, System.out::print)) {
          try {
            client.initialize(0, 1280, 720);
            int views = 0, quads = 0, entities = 0;
            Vec3 initial = origin(server.playerState(0));
            double moved = 0;
            for (int frame = 0; frame <= 140; frame++) {
              int time = startTime + frame * 50;
              if (frame != 0) {
                client.userCommand(new UserCommand(time, 0, 0, 0, 1, client.selectedWeapon(), 127, 0, frame == 10 ? 127 : 0));
                server.runFrame(time);
              }
              var submitted = client.frame(time, frame < 80 ? 1280 : 1024, frame < 80 ? 720 : 768);
              int frameViews = 0, frameQuads = 0;
              for (var command : submitted.commands()) {
                if (command instanceof CgameFrame.View view) {
                  frameViews++;
                  entities += view.entities().size();
                  var ref=view.refdef();
                  hash(digest,ref.x()+":"+ref.y()+":"+ref.width()+":"+ref.height()+":"+ref.fovX()+":"+ref.fovY()
                      +":"+ref.origin()+":"+ref.axisX()+":"+ref.axisY()+":"+ref.axisZ()+":"+ref.timeMillis()+":"+ref.rdflags()+":"+ref.text());
                  digest.update(ref.areaMask().copy());
                  for (var entity : view.entities()) {
                    hash(digest, entity.type() + ":" + (entity.model() == null ? entity.inlineModel() : entity.model().name())
                        + ":" + entity.transform() + ":" + entity.frame() + ":" + entity.oldFrame() + ":" + entity.backlerp()
                        + ":" + entity.customShader() + ":" + entity.rgba());
                  }
                  for (var polygon : view.polygons()) hash(digest, polygon.toString());
                  for (var light : view.lights()) hash(digest, light.toString());
                } else if (command instanceof CgameFrame.Quad quad) {
                  frameQuads++;
                  hash(digest, quad.toString());
                }
              }
              if (frameViews == 0 || frameQuads == 0) throw new AssertionError("Original cgame omitted view or HUD at " + time);
              views += frameViews;
              quads += frameQuads;
              byte[] state = server.playerState(0);
              digest.update(state);
              Vec3 position = origin(state);
              double dx = position.x() - initial.x(), dy = position.y() - initial.y(), dz = position.z() - initial.z();
              moved = Math.max(moved, Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
            if (entities == 0 || audio.voices == 0 || moved < 64) throw new AssertionError("No meaningful entities, sounds or movement");
            if (Boolean.getBoolean("craftq3.auditRestart")) {
              int modelsBefore = client.registeredModels(), shadersBefore = client.registeredShaders();
              for (int restart = 1; restart <= 2; restart++) {
                long callsBefore = client.vmStats().invocations();
                server.restart(42);
                var restarted = client.frame(server.time(), 1024, 768);
                if (server.snapshotFlags() != (restart % 2) * 4 || server.restartCount() != restart)
                  throw new AssertionError("Restart snapshot flags/counter diverged");
                if (client.vmStats().invocations() <= callsBefore || client.registeredModels() != modelsBefore
                    || client.registeredShaders() != shadersBefore || restarted.commands().isEmpty())
                  throw new AssertionError("Fast restart replaced the client VM or its asset registrations");
                if (ByteBuffer.wrap(server.playerState(0)).order(ByteOrder.LITTLE_ENDIAN).getInt(184) <= 0)
                  throw new AssertionError("Original game did not reconnect a live player");
                for (int step = 0; step < 10; step++) {
                  int next = server.time() + 50;
                  client.userCommand(new UserCommand(next, 0, 0, 0, 1, client.selectedWeapon(), 127, 0, 0));
                  server.runFrame(next);
                  if (client.frame(next, 1024, 768).commands().isEmpty()) throw new AssertionError("No post-restart presentation");
                  digest.update(server.playerState(0));
                }
              }
            }
            return new Result(HexFormat.of().formatHex(digest.digest()), client.registeredModels(), client.registeredShaders(),
                client.registeredSounds(), client.syscallCounts().getOrDefault(27, 0L), views, quads, entities, audio.voices);
          } catch (RuntimeException failure) {
            System.err.println(client.vmStats());
            System.err.println(client.syscallCounts());
            throw failure;
          }
        }
      }
    }
  }

  private static Vec3 origin(byte[] bytes) {
    var memory = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    return new Vec3(memory.getFloat(20), memory.getFloat(24), memory.getFloat(28));
  }
  private static void hash(MessageDigest digest, String text) { digest.update(text.getBytes(StandardCharsets.UTF_8)); }

  private static final class Audio implements AudioBackend {
    int sounds;
    long voices;
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
}
