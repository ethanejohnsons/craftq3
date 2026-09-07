import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.client.Q3Client;
import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.render.BspSceneBuilder;
import dev.bluevista.craftq3.render.CgameFrame;
import dev.bluevista.craftq3.render.SubmittedGeometry;
import dev.bluevista.craftq3.server.Q3Server;
import dev.bluevista.craftq3.server.UserCommand;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Original bot/cgame scene submission through production CPU geometry; no GPU is opened. */
class AuditBotRendering {
  public static void main(String[] args) throws Exception {
    if (args.length != 2)
      throw new IllegalArgumentException("AuditBotRendering <installation> <map>");
    int duration = Integer.getInteger("craftq3.audit.renderMilliseconds", 20000);
    if (duration < 1000 || duration > 120000)
      throw new IllegalArgumentException("Render duration must be 1000..120000");
    try (var files = Pk3FileSystem.mount(Path.of(args[0]), "baseq3")) {
      var bsp = BspReader.read(files.read(new VirtualPath("maps/" + args[1] + ".bsp")));
      var world = BspSceneBuilder.build(args[1], bsp, 8);
      var geometry = new SubmittedGeometry();
      try (var server =
          new Q3Server(
              files,
              args[1],
              bsp,
              null,
              System.out::print,
              Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"), ZoneOffset.UTC))) {
        server.cvars().set("bot_enable", "1", CvarSystem.Source.ENGINE);
        server.initialize(1000, 42);
        server.connect(
            0, Map.of("name", "BotRenderAudit", "model", "sarge/default", "handicap", "100"));
        try (var client =
            new Q3Client(files, server, frame -> {}, new Audio(), System.out::print)) {
          client.initialize(0, 1600, 1000);
          int start = server.time(), nextServer = start + 50, views = 0;
          long surfaces = 0, entities = 0;
          Map<String, Integer> suspectModels = new LinkedHashMap<>();
          for (String name : List.of("sarge", "visor", "anarki"))
            if (!server.consoleCommand(CommandParser.tokenize("addbot " + name + " 3")))
              throw new AssertionError("Original addbot not recognized");
          for (int time = start; time <= start + duration; time += 16) {
            client.userCommand(UserCommand.idle(time));
            while (nextServer <= time) {
              server.runFrame(nextServer);
              nextServer += 50;
            }
            var frame = client.frame(time, 1600, 1000);
            for (var command : frame.commands()) {
              if (!(command instanceof CgameFrame.View view)) continue;
              views++;
              for (int index = 0; index < view.entities().size(); index++) {
                var entity = view.entities().get(index);
                if (entity.type() != CgameFrame.EntityType.MODEL
                    || entity.model() == null
                    || !entity.visible(false)) continue;
                entities++;
                var t = entity.transform();
                double determinant = dot(t.axisX(), cross(t.axisY(), t.axisZ()));
                if (Math.abs(determinant) < 1e-12) {
                  String model = entity.model().name();
                  suspectModels.merge(model, 1, Integer::sum);
                  boolean allZero =
                      dot(t.axisX(), t.axisX()) == 0
                          && dot(t.axisY(), t.axisY()) == 0
                          && dot(t.axisZ(), t.axisZ()) == 0;
                  if (suspectModels.get(model) <= 8)
                    System.out.printf(
                        "MODEL TRANSFORM time=%d serverTime=%d entityIndex=%d model=%s"
                            + " determinant=%s allZero=%s frame=%d oldFrame=%d backlerp=%s"
                            + " origin=%s axisX=%s axisY=%s axisZ=%s oldOrigin=%s renderFx=%d"
                            + " shaderTime=%s customShader=%s rgba=%08x%n",
                        time,
                        server.time(),
                        index,
                        model,
                        Double.toHexString(determinant),
                        allZero,
                        entity.frame(),
                        entity.oldFrame(),
                        entity.backlerp(),
                        t.origin(),
                        t.axisX(),
                        t.axisY(),
                        t.axisZ(),
                        entity.oldOrigin(),
                        entity.renderFx(),
                        entity.shaderTime(),
                        entity.customShader(),
                        entity.rgba());
                }
              }
              try {
                surfaces += geometry.build(world, view, false).size();
              } catch (RuntimeException failure) {
                System.out.printf(
                    "BOT GEOMETRY CHECKPOINT time=%d serverTime=%d views=%d entities=%d surfaces=%d"
                        + " suspectModels=%s%n",
                    time, server.time(), views, entities, surfaces, suspectModels);
                throw failure;
              }
            }
          }
          if (entities == 0 || surfaces == 0)
            throw new AssertionError("No submitted model geometry");
          System.out.printf(
              "Bot CPU geometry PASS views=%d entities=%d surfaces=%d suspectModels=%s%n",
              views, entities, surfaces, suspectModels);
        }
      }
    }
  }

  private static double dot(Vec3 a, Vec3 b) {
    return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
  }

  private static Vec3 cross(Vec3 a, Vec3 b) {
    return new Vec3(
        a.y() * b.z() - a.z() * b.y(),
        a.z() * b.x() - a.x() * b.z(),
        a.x() * b.y() - a.y() * b.x());
  }

  private static final class Audio implements AudioBackend {
    int sounds;
    long voices;

    public int register(String name, PcmSound sound) {
      return ++sounds;
    }

    public long play(Playback playback) {
      return ++voices;
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
      return new Diagnostics(true, sounds, 0, 0, 0, voices, 0, "CPU audit");
    }

    public void close() {}
  }
}
