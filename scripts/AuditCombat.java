import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.server.Q3Server;
import dev.bluevista.craftq3.server.UserCommand;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

/** Two original local qagame clients; game VM owns teleport cheats, shots, damage and scoring. */
class AuditCombat {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("AuditCombat <installation>");
    try (var files = Pk3FileSystem.mount(Path.of(args[0]), "baseq3")) {
      var map = BspReader.read(files.read(new VirtualPath("maps/q3dm17.bsp")));
      try (var server = new Q3Server(files, "q3dm17", map, null, System.out::print,
          Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"), ZoneOffset.UTC))) {
        server.cvars().set("sv_cheats", "1", CvarSystem.Source.ENGINE);
        server.initialize(1000, 42);
        int startTime = server.time();
        server.connect(0, Map.of("name", "CombatShooter", "model", "sarge", "handicap", "100"));
        server.connect(1, Map.of("name", "CombatTarget", "model", "sarge", "handicap", "100"));
        var start = state(server, 0);
        float x = start.getFloat(20), y = start.getFloat(24), z = start.getFloat(28);
        server.clientCommand(1, "setviewpos " + (x + 96) + " " + y + " " + z + " 180");
        var target = state(server, 1);
        if (Math.abs(target.getFloat(20) - x - 96) > 1 || Math.abs(target.getFloat(24) - y) > 1)
          throw new AssertionError("Original qagame did not accept the test positioning command");
        int weapon = start.getInt(144), initialAmmo = start.getInt(376 + weapon * 4);
        int score = start.getInt(248), initialHealth = target.getInt(184), deathTime = 0;
        for (int elapsed = 50; elapsed <= 9000; elapsed += 50) {
          int time = startTime + elapsed;
          var shooter = state(server, 0); target = state(server, 1);
          double dx = target.getFloat(20) - shooter.getFloat(20);
          double dy = target.getFloat(24) - shooter.getFloat(24);
          double dz = target.getFloat(28) + 20 - shooter.getFloat(28) - shooter.getInt(164);
          double yaw = Math.toDegrees(Math.atan2(dy, dx));
          double pitch = -Math.toDegrees(Math.atan2(dz, Math.hypot(dx, dy)));
          server.userCommand(0, new UserCommand(time, angle(pitch) - shooter.getInt(56),
              angle(yaw) - shooter.getInt(60), 0, elapsed >= 500 ? 1 : 0, weapon, 0, 0, 0));
          server.userCommand(1, UserCommand.idle(time));
          server.runFrame(time);
          if (state(server, 1).getInt(184) <= 0) { deathTime = time; break; }
        }
        var after = state(server, 0); target = state(server, 1);
        if (deathTime == 0 || after.getInt(248) <= score || after.getInt(376 + weapon * 4) >= initialAmmo)
          throw new AssertionError("QVM combat failed: targetHealth=" + target.getInt(184)
              + " score=" + after.getInt(248) + " ammo=" + after.getInt(376 + weapon * 4));
        System.out.println("Original two-client combat PASS: target health=" + initialHealth + " -> "
            + target.getInt(184) + ", score=" + score + " -> " + after.getInt(248)
            + ", ammo=" + initialAmmo + " -> " + after.getInt(376 + weapon * 4) + ", deathTime=" + deathTime);
      }
    }
  }
  private static int angle(double degrees) { return (int) (degrees * 65536 / 360) & 65535; }
  private static ByteBuffer state(Q3Server server, int client) {
    return ByteBuffer.wrap(server.playerState(client)).order(ByteOrder.LITTLE_ENDIAN);
  }
}
