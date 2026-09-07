import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.server.Q3Server;
import dev.bluevista.craftq3.server.UserCommand;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.List;
import java.util.Optional;

/** Local-only original-qagame integration probe. Never bundles or extracts the supplied game data. */
class AuditGameplay {
  public static void main(String[] args) throws Exception {
    if (args.length < 1 || args.length > 3) throw new IllegalArgumentException("AuditGameplay <installation> [map] [isolated audit qagame.qvm]");
    String mapName = args.length >= 2 ? args[1] : "q3dm17";
    try (var mounted = Pk3FileSystem.mount(Path.of(args[0]), "baseq3")) {
      VirtualFileSystem fs = args.length == 3 ? withAuditVm(mounted, Path.of(args[2])) : mounted;
      var map = BspReader.read(fs.read(new VirtualPath("maps/" + mapName + ".bsp")));
      byte[][] replay = new byte[2][];
      for (int attempt = 0; attempt < 2; attempt++) {
        try (var server = new Q3Server(fs, mapName, map, null, System.out::print,
            Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"), ZoneOffset.UTC))) {
          server.initialize(1000, 42);
          int startTime = server.time(), initialFrames = server.frameNumber();
          server.connect(0, Map.of("name", "CraftQ3Probe", "ip", "localhost", "model", "sarge/default", "handicap", "100", "rate", "25000", "snaps", "20"));
          byte[] before = server.playerState(0);
          int weapon = ByteBuffer.wrap(before).order(ByteOrder.LITTLE_ENDIAN).getInt(144);
          if (weapon < 1 || weapon >= 16) throw new AssertionError("No starting weapon from qagame");
          int initialAmmo = ByteBuffer.wrap(before).order(ByteOrder.LITTLE_ENDIAN).getInt(376 + weapon * 4);
          show("spawn", before);
          for (int elapsed = 50; elapsed <= 5000; elapsed += 50) {
            int time = startTime + elapsed;
            // Forward, fire, then jump; all movement/weapon rules and traces originate in QVM.
            server.userCommand(0, new UserCommand(time, 0, 0, 0, elapsed >= 750 && elapsed <= 1250 ? 1 : 0, weapon, elapsed <= 2000 ? 127 : 0, 0, elapsed == 1500 ? 127 : 0));
            server.runFrame(time);
          }
          replay[attempt] = server.playerState(0);
          int finalAmmo = ByteBuffer.wrap(replay[attempt]).order(ByteOrder.LITTLE_ENDIAN).getInt(376 + weapon * 4);
          if (initialAmmo <= 0 || finalAmmo >= initialAmmo) throw new AssertionError("Original qagame did not fire its starting weapon: weapon=" + weapon + " ammo=" + initialAmmo + " -> " + finalAmmo);
          show("end", replay[attempt]);
          if (Arrays.equals(Arrays.copyOfRange(before, 20, 32), Arrays.copyOfRange(replay[attempt], 20, 32)))
            throw new AssertionError("Original qagame did not move the player");
          if (server.frameNumber() != initialFrames + 100 || server.entityCount() < 1)
            throw new AssertionError("No running qagame simulation");
          System.out.println("frames=" + server.frameNumber() + " entities=" + server.entityCount() + " visibleEntityStates=" + server.entityStates(0).size() + " ammo=" + initialAmmo + " -> " + finalAmmo);
          if (health(server.playerState(0)) > 0) server.clientCommand(0, "kill");
          if (health(server.playerState(0)) > 0) throw new AssertionError("qagame did not enter death state");
          for (int elapsed = 5050; elapsed <= 7000; elapsed += 50) {
            int time = startTime + elapsed;
            server.userCommand(0, new UserCommand(time, 0, 0, 0, elapsed >= 6550 ? 1 : 0, weapon, 0, 0, 0));
            server.runFrame(time);
          }
          replay[attempt] = server.playerState(0);
          if (health(replay[attempt]) <= 0) throw new AssertionError("Original qagame did not respawn on attack");
          show("respawn", replay[attempt]);
          System.out.println("VM " + server.vmStats());
          System.out.println("Syscalls " + server.syscallCounts());
        }
      }
      if (!Arrays.equals(replay[0], replay[1])) throw new AssertionError("Fixed-seed QVM replay diverged");
      System.out.println("Original qagame startup, spawn, movement, firing, death/respawn and deterministic 140-frame replay passed.");
    }
  }
  private static int health(byte[] state) { return ByteBuffer.wrap(state).order(ByteOrder.LITTLE_ENDIAN).getInt(184); }
  private static void show(String label, byte[] state) {
    var b = ByteBuffer.wrap(state).order(ByteOrder.LITTLE_ENDIAN);
    System.out.printf("%n%s origin=(%.3f %.3f %.3f) velocity=(%.3f %.3f %.3f) health=%d%n", label,
        b.getFloat(20), b.getFloat(24), b.getFloat(28), b.getFloat(32), b.getFloat(36), b.getFloat(40), b.getInt(184));
  }

  private static VirtualFileSystem withAuditVm(VirtualFileSystem mounted, Path file) throws Exception {
    if (Files.size(file) > 64 * 1024 * 1024) throw new IllegalArgumentException("Audit VM too large");
    byte[] code = Files.readAllBytes(file);
    System.out.println("QA-only qagame override: " + file.toAbsolutePath());
    return new VirtualFileSystem() {
      @Override public Optional<Origin> which(VirtualPath path) {
        return path.value().equals("vm/qagame.qvm") ? Optional.of(new Origin("baseq3", file.toString(), false)) : mounted.which(path);
      }
      @Override public List<VirtualPath> list(String directory) { return mounted.list(directory); }
      @Override public List<Origin> searchOrder() { return mounted.searchOrder(); }
      @Override public byte[] read(VirtualPath path) throws java.io.IOException {
        return path.value().equals("vm/qagame.qvm") ? code.clone() : mounted.read(path);
      }
      @Override public void close() {} // The enclosing audit owns the mounted filesystem.
    };
  }
}
