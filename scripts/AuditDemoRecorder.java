import dev.bluevista.craftq3.client.demo.Protocol68DemoRecorder;
import dev.bluevista.craftq3.core.demo.*;
import dev.bluevista.craftq3.core.net.*;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.*;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded production-recorder checks against the authored unchanged-native recording observer. */
class AuditDemoRecorder {
  static final HexFormat HEX = HexFormat.of();

  static String hex(byte[] bytes) {
    return bytes.length == 0 ? "-" : HEX.formatHex(bytes);
  }

  static final class Oracle implements AutoCloseable {
    final Process process;
    final BufferedWriter input;
    final BufferedReader output;

    Oracle() throws IOException {
      process =
          new ProcessBuilder(".tools/demo-playback-oracle/probe")
              .redirectError(ProcessBuilder.Redirect.INHERIT)
              .start();
      input = process.outputWriter(StandardCharsets.US_ASCII);
      output = process.inputReader(StandardCharsets.US_ASCII);
    }

    List<String> call(String text) throws IOException {
      input.write(text);
      input.newLine();
      input.flush();
      var lines = new ArrayList<String>();
      for (String line; (line = output.readLine()) != null; ) {
        if (line.equals("END")) return lines;
        lines.add(line);
      }
      throw new EOFException(
          "Native demo observer stopped during " + text.substring(0, Math.min(80, text.length())));
    }

    byte[] bytes() throws IOException {
      String line =
          call("state").stream()
              .filter(value -> value.startsWith("OUTPUT "))
              .findFirst()
              .orElseThrow();
      return line.equals("OUTPUT -") ? new byte[0] : HEX.parseHex(line.substring(7));
    }

    @Override
    public void close() throws IOException {
      input.close();
      output.close();
      process.destroy();
    }
  }

  public static void main(String[] args) throws Exception {
    Random random = new Random(0x443638);
    int padding = 0, records = 0;
    long bytes = 0;
    try (var oracle = new Oracle()) {
      for (int test = 0; test < 32; test++) {
        oracle.call("reset");
        int sequence = 100 + test * 10,
            ack = random.nextInt(),
            commands = random.nextInt(),
            client = test % 32,
            feed = random.nextInt();
        var strings = new TreeMap<Integer, String>();
        var baselines = new ArrayList<byte[]>();
        oracle.call("baseline 0 7 123.5");
        baselines.add(entity(0, 7, 123.5f));
        for (int i = 0; i < test % 5; i++) {
          int index = (test + i * 173) % 1024;
          String value =
              "^"
                  + i
                  + " authored record "
                  + test
                  + "\\key\\value;\n"
                  + "x".repeat(random.nextInt(200));
          strings.put(index, value);
          oracle.call("cs " + index + " " + hex(value.getBytes(StandardCharsets.ISO_8859_1)));
        }
        for (int i = 1; i <= test % 4; i++) {
          int kind = random.nextInt(12);
          float x = (random.nextInt(20000) - 10000) / 8f;
          oracle.call("baseline " + i + " " + kind + " " + x);
          baselines.add(entity(i, kind, x));
        }
        oracle.call(
            "record 8 1 " + sequence + " " + ack + " " + commands + " " + client + " " + feed);
        byte[] nativeInitial = oracle.bytes();
        var game = new GameState(commands, strings, new Baselines(baselines), client, feed);
        var output = new ByteArrayOutputStream();
        try (var recorder = new Protocol68DemoRecorder(output, 65536)) {
          recorder.start(game, sequence, ack);
          byte[] actualInitial = output.toByteArray();
          try (var nativeReader = new DemoReader(new ByteArrayInputStream(nativeInitial));
              var actualReader = new DemoReader(new ByteArrayInputStream(actualInitial))) {
            var first = nativeReader.next().orElseThrow();
            var actual = actualReader.next().orElseThrow();
            check(
                first.sequence() == actual.sequence() && first.sequence() == sequence - 1,
                "initial sequence");
            var reader = new MessageReader(first.payload());
            ServerMessageCodec.read(reader, first.sequence(), Baselines.EMPTY, ignored -> null);
            check(
                Arrays.equals(Arrays.copyOf(nativeInitial, 8), Arrays.copyOf(actualInitial, 8)),
                "initial header");
            for (int bit = 0; bit < reader.bitPosition(); bit++)
              check(
                  ((first.payload()[bit / 8] ^ actual.payload()[bit / 8]) & (1 << (bit % 8))) == 0,
                  "meaningful initial bits");
            if (!Arrays.equals(first.payload(), actual.payload())) padding++;
          }
          var pending = new Message(ack, List.of(new Command(commands + 1, "print waiting")));
          var pendingRaw = raw(sequence, pending);
          oracle.call("packet 1 " + hex(packet(pendingRaw)));
          check(!recorder.accept(pendingRaw, pending), "pending commands ignored");
          check(Arrays.equals(nativeInitial, oracle.bytes()), "native waits before full snapshot");
          var snap =
              new Snapshot(sequence + 1, 500 + test, 0, new byte[0], new byte[468], List.of());
          var firstFull =
              new Message(
                  ack,
                  List.of(
                      new Command(commands + 2, "print before"),
                      new Frame(snap, null),
                      new Command(commands + 3, "print after")));
          var canonical = raw(sequence + 1, firstFull);
          byte[] tail = Arrays.copyOf(canonical.payload(), canonical.payload().length + 3);
          random.nextBytes(tail);
          System.arraycopy(canonical.payload(), 0, tail, 0, canonical.payload().length);
          var fullRaw = new DemoRecord(sequence + 1, tail);
          oracle.call("packet 0 " + hex(packet(fullRaw)));
          check(recorder.accept(fullRaw, firstFull), "same full packet recorded");
          var laterRaw = raw(sequence + 2, pending);
          oracle.call("packet 0 " + hex(packet(laterRaw)));
          check(recorder.accept(laterRaw, pending), "post-full commands recorded");
          oracle.call("stop");
          recorder.finish();
          recorder.finish();
          byte[] nativeAll = oracle.bytes(), actualAll = output.toByteArray();
          check(
              Arrays.equals(
                  Arrays.copyOfRange(nativeAll, nativeInitial.length, nativeAll.length),
                  Arrays.copyOfRange(actualAll, actualInitial.length, actualAll.length)),
              "complete later records and canonical end");
          check(
              recorder.recordsWritten() == 3 && recorder.bytesWritten() == actualAll.length,
              "recorder accounting");
          records += 3;
          bytes += actualAll.length;
        }
      }
    }
    System.out.println(
        "PASS initialGamestates=32 meaningfulBitDifferences=0 paddingResidues="
            + padding
            + " records="
            + records
            + " bytes="
            + bytes
            + " exactLaterPackets=64");
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static byte[] entity(int number, int kind, float x) {
    byte[] bytes = new byte[208];
    ByteBuffer.wrap(bytes)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(0, number)
        .putInt(4, kind)
        .putFloat(92, x);
    return bytes;
  }

  private static DemoRecord raw(int sequence, Message message) {
    var output = new MessageWriter();
    ServerMessageCodec.write(output, message, Baselines.EMPTY);
    return new DemoRecord(sequence, output.bytes());
  }

  private static byte[] packet(DemoRecord record) {
    return ByteBuffer.allocate(4 + record.payload().length)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(record.sequence())
        .put(record.payload())
        .array();
  }
}
