package dev.bluevista.craftq3.engine.net.demo;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec;
import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * Parses every demo inside every PK3 of the user's installation. PK3s are read with {@code
 * java.util.zip} directly (engine:fs is a separate module) using ISO-8859-1 entry names, since one
 * retail-era archive has a non-UTF-8 name; zero-byte archives are skipped as Quake III does.
 */
@RealData
final class DemoCorpusTest {
  private record Demo(String source, String name, byte[] bytes) {
    @Override
    public String toString() {
      return source + ":" + name;
    }
  }

  private record Totals(
      long records, long gameStates, long snapshots, long deltas, long entities, long commands) {}

  @Test
  void everySupportedDemoParsesToItsEndWithEveryDeltaResolved() throws Exception {
    var demos = demos();
    var failures = new ArrayList<String>();
    int parsed = 0;
    for (var demo : demos) {
      var protocol = DemoProtocol.fromFileName(demo.name()).orElse(null);
      if (protocol == null || !protocol.supported()) continue;
      try {
        var totals = parse(demo, protocol);
        parsed++;
        System.out.println("demo " + demo + " protocol " + protocol.number() + " " + totals);
        assertEquals(1, totals.gameStates(), demo + " gamestates");
        assertTrue(totals.snapshots() > 0, demo + " has snapshots");
      } catch (IOException | RuntimeException | AssertionError failure) {
        failures.add(demo + ": " + failure);
      }
    }
    assertEquals(List.of(), failures);
    assertTrue(
        parsed >= 7,
        "expected the retail, freefall, moteof, CPMA and three protocol-48 demos, got " + parsed);
  }

  @Test
  void retailFourIsTheSameRecordingUnderProtocols66And68() throws Exception {
    var demos = demos();
    byte[] four66 = find(demos, "pak6.pk3", "demos/four.dm_66");
    byte[] four68 = find(demos, "pak8.pk3", "demos/four.dm_68");
    assertArrayEquals(four66, four68, "id shipped one attract demo under both extensions");
  }

  /**
   * Protocol 48 (see {@link DemoProtocol#Q3_1_27}): every message of demo000, demo001 (1.25,
   * announcing 46) and demo127 (1.27) decodes to its end-of-message padding with the strict reader,
   * every delta finds its exact base, and the decoded states move continuously: a player entity's
   * position follows from the previous snapshot's position and velocity except at the few teleports
   * and respawns, and the player state's command time tracks the server time.
   */
  @Test
  void protocol48DemosDecodeEveryMessageWithContinuousMotion() throws Exception {
    var expectedBaselines = java.util.Map.of("demo000", 81, "demo001", 48, "demo127", 44);
    var expectedProtocol = java.util.Map.of("demo000", 46, "demo001", 46, "demo127", 48);
    int checked = 0;
    for (var demo : demos()) {
      if (DemoProtocol.fromFileName(demo.name()).orElse(null) != DemoProtocol.Q3_1_27) continue;
      checked++;
      String key =
          demo.name().substring(demo.name().lastIndexOf('/') + 1, demo.name().indexOf('.'));
      int frames = 0, deltas = 0, playerPairs = 0, discontinuities = 0;
      try (var reader =
          new DemoReader(
              new ByteArrayInputStream(demo.bytes()),
              DemoProtocol.Q3_1_27,
              DemoRecordReader.Policy.STRICT)) {
        for (var next = reader.next(); next.isPresent(); next = reader.next()) {
          for (var operation : next.get().message().operations()) {
            if (operation instanceof ServerMessageCodec.GameState game) {
              assertEquals(expectedBaselines.get(key), game.baselines().entities().size(), key);
              assertTrue(game.clientNumber() >= 0 && game.clientNumber() < Protocol68.MAX_CLIENTS);
            }
            if (!(operation instanceof ServerMessageCodec.Frame frame)) continue;
            frames++;
            var current = frame.current();
            assertTrue(
                Math.abs(word(current.player(), 0) - current.time()) < 1000,
                key + " commandTime follows serverTime");
            for (byte[] entity : current.entities())
              for (int offset : new int[] {24, 28, 32})
                assertTrue(Math.abs(real(entity, offset)) < 16384, key + " position in the map");
            if (frame.previous() == null) continue;
            deltas++;
            var before = new TreeMap<Integer, byte[]>();
            for (byte[] entity : frame.previous().entities()) before.put(word(entity, 0), entity);
            double seconds = (current.time() - frame.previous().time()) / 1000.0;
            for (byte[] entity : current.entities()) {
              byte[] previous = before.get(word(entity, 0));
              if (previous == null
                  || word(entity, 4) != ET_PLAYER
                  || word(previous, 4) != ET_PLAYER) continue;
              playerPairs++;
              double dx = real(entity, 24) - real(previous, 24) - real(previous, 36) * seconds;
              double dy = real(entity, 28) - real(previous, 28) - real(previous, 40) * seconds;
              if (Math.hypot(dx, dy) > PLAYER_MOTION_TOLERANCE) discontinuities++;
            }
          }
        }
        assertEquals(DemoRecordReader.End.MARKER, reader.end(), key + " ends with its marker");
        assertEquals(expectedProtocol.get(key), reader.announcedProtocol().orElseThrow(), key);
      }
      System.out.println(
          "demo "
              + demo
              + " protocol 48: "
              + frames
              + " snapshots, "
              + deltas
              + " deltas, "
              + playerPairs
              + " player moves, "
              + discontinuities
              + " jumps");
      assertTrue(frames > 1500, key + " snapshots");
      assertEquals(frames - 1, deltas, key + " every snapshot after the first is a resolved delta");
      assertTrue(playerPairs > 5000, key + " player moves");
      assertTrue(discontinuities * 200 < playerPairs, key + " teleports and respawns only");
    }
    assertEquals(3, checked, "expected demo000, demo001 and demo127");
  }

  private static final int ET_PLAYER = 1;

  /** Units a player may deviate from linear extrapolation over one snapshot. */
  private static final double PLAYER_MOTION_TOLERANCE = 40;

  private static int word(byte[] state, int offset) {
    return (state[offset] & 255)
        | (state[offset + 1] & 255) << 8
        | (state[offset + 2] & 255) << 16
        | state[offset + 3] << 24;
  }

  private static float real(byte[] state, int offset) {
    return Float.intBitsToFloat(word(state, offset));
  }

  private static Totals parse(Demo demo, DemoProtocol protocol) throws IOException {
    long records = 0, gameStates = 0, snapshots = 0, deltas = 0, entities = 0, commands = 0;
    try (var reader =
        new DemoReader(
            new ByteArrayInputStream(demo.bytes()), protocol, DemoRecordReader.Policy.STRICT)) {
      for (var next = reader.next(); next.isPresent(); next = reader.next()) {
        records++;
        for (var operation : next.get().message().operations()) {
          switch (operation) {
            case ServerMessageCodec.GameState game -> {
              gameStates++;
              assertFalse(game.configstrings().isEmpty(), demo + " configstrings");
              assertTrue(game.clientNumber() >= 0 && game.clientNumber() < Protocol68.MAX_CLIENTS);
            }
            case ServerMessageCodec.Frame frame -> {
              snapshots++;
              entities += frame.current().entities().size();
              if (frame.previous() != null) {
                deltas++;
                assertTrue(frame.previous().sequence() < frame.current().sequence());
              }
            }
            case ServerMessageCodec.Command ignored -> commands++;
            default -> {}
          }
        }
      }
      assertEquals(DemoRecordReader.End.MARKER, reader.end(), demo + " ends with its marker");
      assertEquals(records, reader.recordsRead());
    }
    return new Totals(records, gameStates, snapshots, deltas, entities, commands);
  }

  private static byte[] find(List<Demo> demos, String archive, String name) {
    return demos.stream()
        .filter(demo -> demo.source().endsWith(archive) && demo.name().equalsIgnoreCase(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("missing " + archive + ":" + name))
        .bytes();
  }

  private static List<Demo> demos() throws IOException {
    Path root = RealDataRoot.require();
    List<Path> archives;
    try (Stream<Path> walk = Files.walk(root)) {
      archives =
          walk.filter(Files::isRegularFile)
              .filter(
                  path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pk3"))
              .sorted()
              .toList();
    }
    var demos = new ArrayList<Demo>();
    for (Path archive : archives) {
      if (Files.size(archive) == 0) continue;
      try (var zip = new ZipFile(archive.toFile(), StandardCharsets.ISO_8859_1)) {
        for (var entry : java.util.Collections.list(zip.entries())) {
          if (entry.isDirectory() || DemoProtocol.fromFileName(entry.getName()).isEmpty()) continue;
          try (var input = zip.getInputStream(entry)) {
            demos.add(
                new Demo(
                    root.relativize(archive).toString().replace('\\', '/'),
                    entry.getName(),
                    input.readAllBytes()));
          }
        }
      }
    }
    assertFalse(demos.isEmpty(), "no demos found under " + root);
    return demos;
  }
}
