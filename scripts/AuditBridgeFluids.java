import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.collision.*;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.server.*;
import java.nio.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;

/** Original world effects and swimming against mutable host fluid volumes. */
class AuditBridgeFluids {
  static TraceWorld world;

  static void liquid(int contents) {
    var floor = new BoxTraceWorld(new Vec3(-512, -512, -32), new Vec3(512, 512, 0), 1, 0, 1022);
    world =
        contents == 0
            ? floor
            : new CompositeTraceWorld(
                List.of(
                    floor,
                    new BoxTraceWorld(
                        new Vec3(-512, -512, 0), new Vec3(512, 512, 192), contents, 0, 1022)));
  }

  static int health(Q3Server game) {
    return ByteBuffer.wrap(game.playerState(0)).order(ByteOrder.LITTLE_ENDIAN).getInt(184);
  }

  static double height(Q3Server game) {
    return ByteBuffer.wrap(game.playerState(0)).order(ByteOrder.LITTLE_ENDIAN).getFloat(28);
  }

  static void advance(Q3Server game, int millis, int up) {
    for (int i = 0; i < millis; i += 8) {
      int now = game.time() + 8;
      game.userCommand(0, new UserCommand(now, 0, 0, 0, 0, 2, 0, 0, up));
      game.runFrame(now);
    }
  }

  public static void main(String[] args) throws Exception {
    liquid(0);
    var terrain =
        new TraceWorld() {
          public TraceResult trace(TraceRequest request) {
            return world.trace(request);
          }

          public int pointContents(Vec3 point, int mask, int ignore) {
            return world.pointContents(point, mask, ignore);
          }
        };
    var vars = new CvarSystem();
    vars.register("sv_cheats", "1", CvarSystem.ROM | CvarSystem.SYSTEMINFO);
    vars.register("bot_enable", "0", 0);
    try (var fs = Pk3FileSystem.mount(Path.of(args[0]), "baseq3");
        var game =
            new Q3Server(
                fs,
                "craftq3_bridge",
                ExternalWorld.combat(terrain, new Vec3(0, 0, 25), 0),
                null,
                x -> {},
                Clock.systemUTC(),
                null,
                vars)) {
      game.initialize(1000, 42);
      game.connect(0, Map.of("name", "Fluid QA", "model", "sarge/default", "ip", "localhost"));
      advance(game, 1000, 0);
      game.clientCommand(0, "give health");
      advance(game, 8, 0);
      int initial = health(game);
      double floor = height(game);
      liquid(32);
      advance(game, 1000, 127);
      double swimming = height(game);
      if (swimming - floor < 40)
        throw new AssertionError("No original upward swimming: " + swimming + " floor=" + floor);
      advance(game, 7000, 0);
      if (health(game) != initial)
        throw new AssertionError(
            "Water caused damage before original air expired: "
                + health(game)
                + " initial="
                + initial);
      int drops = 0, previous = health(game);
      for (int i = 0; i < 9000 && drops < 2; i += 8) {
        advance(game, 8, 0);
        int next = health(game);
        if (previous - next > 1) drops++;
        previous = next;
      }
      if (drops < 2 || health(game) <= 0)
        throw new AssertionError(
            "Missing original drowning: health=" + health(game) + " drops=" + drops);
      int drowned = health(game);
      liquid(0);
      advance(game, 2000, 0);
      int surfaced = health(game);
      advance(game, 2000, 0);
      if (health(game) != surfaced) throw new AssertionError("Drowning continued in host air");
      game.clientCommand(0, "give health");
      advance(game, 8, 0);
      int beforeLava = health(game);
      liquid(8);
      for (int i = 0; i < 1000 && health(game) == beforeLava; i += 8) advance(game, 8, 0);
      int burned = health(game);
      if (burned >= beforeLava || burned <= 0)
        throw new AssertionError("Missing original lava damage: " + burned);
      liquid(0);
      advance(game, 1000, 0);
      game.clientCommand(0, "give health");
      advance(game, 8, 0);
      game.clientCommand(0, "give Battle Suit");
      advance(game, 8, 0);
      if (PlayerLoadout.capture(game.playerState(0), game.time()).powerupMillis().get(1) <= 0)
        throw new AssertionError("Original Battle Suit not granted");
      int protectedHealth = health(game);
      liquid(8);
      advance(game, 2000, 0);
      if (health(game) != protectedHealth)
        throw new AssertionError("Original Battle Suit did not protect from lava");
      System.out.println(
          "PASS bridge fluids "
              + game.abi()
              + " swim-rise="
              + (swimming - floor)
              + " drowning="
              + initial
              + "->"
              + drowned
              + " air-stops-damage=true lava="
              + beforeLava
              + "->"
              + burned
              + " battle-suit=true");
    }
  }
}
