import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.server.Q3Server;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Opt-in original-map startup check; user data stays in its original archives. */
class AuditStartup {
  public static void main(String[] args) throws Exception {
    if (args.length < 1 || args.length > 2)
      throw new IllegalArgumentException("AuditStartup <installation> [qagame.qvm]");
    byte[] replacement = args.length == 2 ? Files.readAllBytes(Path.of(args[1])) : null;
    var failures = new ArrayList<String>();
    try (var packs = Pk3FileSystem.mount(Path.of(args[0]), "baseq3")) {
      var files =
          new VirtualFileSystem() {
            public byte[] read(VirtualPath path) throws java.io.IOException {
              return replacement != null && path.value().equals("vm/qagame.qvm")
                  ? replacement.clone()
                  : packs.read(path);
            }

            public Optional<Origin> which(VirtualPath path) {
              return packs.which(path);
            }

            public List<VirtualPath> list(String directory) {
              return packs.list(directory);
            }

            public List<Origin> searchOrder() {
              return packs.searchOrder();
            }

            public void close() {}
          };
      var names =
          packs.list("maps").stream()
              .map(VirtualPath::value)
              .filter(name -> name.endsWith(".aas"))
              .map(name -> name.substring(5, name.length() - 4))
              .sorted()
              .toList();
      if (names.isEmpty()) throw new AssertionError("No playable AAS maps discovered");
      int passed = 0;
      for (String name : names) {
        String phase = "map loading";
        try {
          var map = BspReader.read(files.read(new VirtualPath("maps/" + name + ".bsp")));
          try (var server =
              new Q3Server(
                  files,
                  name,
                  map,
                  null,
                  System.out::print,
                  Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"), ZoneOffset.UTC))) {
            phase = "initialization";
            server.initialize(1000, 42);
            if (server.time() != 1400 || server.frameNumber() != 4)
              throw new AssertionError("Incorrect map startup clock");
            phase = "player connection";
            server.connect(0, Map.of("name", "StartupAudit", "model", "sarge", "handicap", "100"));
            var spawn = ByteBuffer.wrap(server.playerState(0)).order(ByteOrder.LITTLE_ENDIAN);
            if (spawn.getInt(184) <= 0) throw new AssertionError("No live player after connection");
            int end = server.time() + 2000;
            while (server.time() < end) {
              phase = "frame " + (server.time() + 50);
              server.runFrame(server.time() + 50);
            }
            passed++;
            System.out.println(
                "STARTUP PASS "
                    + name
                    + " ABI="
                    + server.abi()
                    + " time="
                    + server.time()
                    + " frames="
                    + server.frameNumber());
          }
        } catch (RuntimeException | AssertionError failure) {
          String message = name + " during " + phase + ": " + failure.getMessage();
          failures.add(message);
          System.out.println("STARTUP FAILURE " + message);
        }
      }
      System.out.println(
          "Startup maps=" + names.size() + " passed=" + passed + " failures=" + failures.size());
    }
    if (!failures.isEmpty()) throw new AssertionError(failures.toString());
  }
}
