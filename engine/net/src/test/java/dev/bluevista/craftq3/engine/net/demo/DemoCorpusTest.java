package dev.bluevista.craftq3.engine.net.demo;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.common.info.InfoString;
import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.msg.MessageReader;
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
  void everyProtocol66To68DemoParsesToItsEndWithEveryDeltaResolved() throws Exception {
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
    assertTrue(parsed >= 4, "expected the retail, freefall, moteof and CPMA demos, got " + parsed);
  }

  @Test
  void retailFourIsTheSameRecordingUnderProtocols66And68() throws Exception {
    var demos = demos();
    byte[] four66 = find(demos, "pak6.pk3", "demos/four.dm_66");
    byte[] four68 = find(demos, "pak8.pk3", "demos/four.dm_68");
    assertArrayEquals(four66, four68, "id shipped one attract demo under both extensions");
  }

  /**
   * Protocol 48 is refused. This pins the measured differences documented on {@link
   * DemoProtocol#Q3_1_27}: raw (non-Huffman) bits with the same envelope, configstrings and
   * snapshot header, and per-field player change bits where 68 has a field count.
   */
  @Test
  void protocol48DemosAreRecognisedAndRefusedWithTheirLayoutPinned() throws Exception {
    int checked = 0;
    for (var demo : demos()) {
      if (DemoProtocol.fromFileName(demo.name()).orElse(null) != DemoProtocol.Q3_1_27) continue;
      checked++;
      var records = new ArrayList<DemoRecord>();
      try (var reader =
          new DemoRecordReader(
              new ByteArrayInputStream(demo.bytes()), DemoRecordReader.Policy.PLAYBACK)) {
        for (var record = reader.next(); record.isPresent(); record = reader.next())
          records.add(record.get());
        assertNotEquals(DemoRecordReader.End.NONE, reader.end());
      }
      assertEquals(
          DemoProtocol.Q3_1_27, DemoProtocol.sniff(records.getFirst().payload()).orElseThrow());
      assertThrows(
          UnsupportedDemoProtocolException.class,
          () ->
              DemoReader.open(
                  demo.name(),
                  new ByteArrayInputStream(demo.bytes()),
                  DemoRecordReader.Policy.PLAYBACK));

      var gamestate = raw(records.getFirst());
      gamestate.intValue();
      assertEquals(Protocol68.SVC_GAMESTATE, gamestate.byteValue());
      gamestate.intValue();
      var strings = new TreeMap<Integer, String>();
      int op;
      while ((op = gamestate.byteValue()) == Protocol68.SVC_CONFIGSTRING)
        strings.put(gamestate.shortValue(), gamestate.bigStringValue());
      assertEquals(Protocol68.SVC_BASELINE, op, demo + " configstrings end at the baselines");
      var info =
          InfoString.parse(strings.get(Protocol68.CS_SERVERINFO), Protocol68.BIG_INFO_STRING);
      System.out.println(
          "demo "
              + demo
              + " protocol 48: "
              + records.size()
              + " records, "
              + strings.size()
              + " configstrings, server announces protocol "
              + info.get("protocol"));

      var snapshot =
          records.stream()
              .skip(1)
              .map(DemoCorpusTest::raw)
              .filter(DemoCorpusTest::startsSnapshot)
              .findFirst()
              .orElseThrow();
      int serverTime = snapshot.intValue();
      snapshot.byteValue(); // delta
      snapshot.byteValue(); // flags
      for (int area = snapshot.byteValue(); area > 0; area--) snapshot.byteValue();
      assertEquals(1, snapshot.bits(1), "commandTime carries its own change bit");
      int commandTime = snapshot.intValue();
      assertTrue(Math.abs(serverTime - commandTime) < 1000, demo + " commandTime near serverTime");
    }
    assertEquals(3, checked, "expected demo000, demo001 and demo127");
  }

  /**
   * Field widths of the protocol-48 entity mask in its wire order (entityState_t declaration order
   * with frame before clientNum); 0 marks a float.
   */
  private static final int[] ENTITY_48_WIDTHS = {
    8,
    19,
    8,
    32,
    32,
    0,
    0,
    0,
    0,
    0,
    0, // eType, eFlags, pos
    8,
    32,
    32,
    0,
    0,
    0,
    0,
    0,
    0, // apos
    32,
    32,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0,
    0, // time, time2, origin, origin2, angles, angles2
    10,
    10,
    10,
    32,
    8,
    8,
    8,
    16,
    8,
    24,
    10,
    8,
    16,
    8,
    8,
    8,
    8 // otherEntityNum .. generic1
  };

  private static final int ENTITY_48_ETYPE = 0, ENTITY_48_LOOP_SOUND = 38;
  private static final int EXPLICIT_MASK = 63, LOOPED_SPEAKER_MASK = 41;

  /** Selector 41: eType, pos.trBase, origin, loopSound, eventParm. */
  private static final int[] LOOPED_SPEAKER_FIELDS = {0, 5, 6, 7, 22, 23, 24, 38, 45};

  /**
   * Pins the protocol-48 entity layout documented on {@link DemoProtocol#Q3_1_27}: every gamestate
   * baseline decodes with the 6-bit mask selector (63 = explicit declaration-order mask, 41 = the
   * looped-speaker table entry) and the list ends with a zero byte and zero padding, without
   * svc_EOF, client number or checksum feed.
   */
  @Test
  void protocol48GamestateBaselinesDecodeWithTheMaskSelectorLayout() throws Exception {
    var expected = java.util.Map.of("demo000", 81, "demo001", 48, "demo127", 44);
    int checked = 0;
    for (var demo : demos()) {
      if (DemoProtocol.fromFileName(demo.name()).orElse(null) != DemoProtocol.Q3_1_27) continue;
      checked++;
      DemoRecord first;
      try (var reader =
          new DemoRecordReader(
              new ByteArrayInputStream(demo.bytes()), DemoRecordReader.Policy.PLAYBACK)) {
        first = reader.next().orElseThrow();
      }
      var gamestate = raw(first);
      gamestate.intValue();
      assertEquals(Protocol68.SVC_GAMESTATE, gamestate.byteValue());
      gamestate.intValue();
      int op;
      while ((op = gamestate.byteValue()) == Protocol68.SVC_CONFIGSTRING) {
        gamestate.shortValue();
        gamestate.bigStringValue();
      }
      int baselines = 0, loopedSpeakers = 0, last = -1;
      for (; op == Protocol68.SVC_BASELINE; op = gamestate.byteValue()) {
        int number = gamestate.bits(Protocol68.GENTITYNUM_BITS);
        assertTrue(number > last && number < Protocol68.ENTITYNUM_NONE, demo + " entity order");
        last = number;
        assertEquals(0, gamestate.bits(1), demo + " baselines are never removals");
        int selector = gamestate.bits(6);
        int[] fields;
        if (selector == EXPLICIT_MASK) {
          var mask = new int[ENTITY_48_WIDTHS.length];
          int count = 0;
          for (int i = 0; i < mask.length; i++) if (gamestate.bits(1) != 0) mask[count++] = i;
          fields = java.util.Arrays.copyOf(mask, count);
        } else {
          assertEquals(LOOPED_SPEAKER_MASK, selector, demo + " baseline mask selector");
          fields = LOOPED_SPEAKER_FIELDS;
          loopedSpeakers++;
        }
        for (int field : fields) {
          int width = ENTITY_48_WIDTHS[field];
          int value;
          if (width != 0) value = gamestate.bits(width);
          else if (gamestate.bits(1) != 0) value = gamestate.intValue();
          else value = gamestate.bits(13) - 4096;
          if (field == ENTITY_48_ETYPE) assertTrue(value > 0 && value < 13, demo + " eType");
          if (field == ENTITY_48_LOOP_SOUND && selector == LOOPED_SPEAKER_MASK)
            assertTrue(value > 0, demo + " looped speaker sound");
        }
        baselines++;
      }
      assertEquals(0, op, demo + " the baseline list ends with a zero byte");
      int padding = gamestate.remainingBits();
      assertTrue(padding < 8, demo + " no svc_EOF, client number or checksum feed");
      assertEquals(0, gamestate.bits(padding), demo + " zero padding");
      String key =
          demo.name().substring(demo.name().lastIndexOf('/') + 1, demo.name().indexOf('.'));
      assertEquals(expected.get(key), baselines, demo + " baselines");
      System.out.println(
          "demo "
              + demo
              + " protocol 48: "
              + baselines
              + " baselines decoded, "
              + loopedSpeakers
              + " with table selector 41");
    }
    assertEquals(3, checked, "expected demo000, demo001 and demo127");
  }

  /** Consumes the acknowledgement and opcode, leaving a snapshot reader at its header. */
  private static boolean startsSnapshot(MessageReader reader) {
    if (reader.remainingBits() < 40) return false;
    reader.intValue();
    return reader.byteValue() == Protocol68.SVC_SNAPSHOT;
  }

  private static MessageReader raw(DemoRecord record) {
    return new MessageReader(record.payload()).coding(MessageReader.Coding.RAW);
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
