import dev.bluevista.craftq3.core.demo.*;
import dev.bluevista.craftq3.core.net.*;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.*;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Baselines;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Authored native recording/framing comparisons; never starts an engine, socket, or renderer. */
class AuditNativeDemos {
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

  static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    int trials = args.length == 0 ? 1500 : Integer.parseInt(args[0]);
    Random random = new Random(0x6844454d4fL);
    long bytes = 0;
    int paddingDifferences = 0;
    try (Oracle nativeDemo = new Oracle()) {
      for (int trial = 0; trial < trials; trial++) {
        nativeDemo.call("reset");
        int sequence = 1 + random.nextInt(1000000),
            ack = random.nextInt(),
            command = random.nextInt();
        int client = random.nextInt(64), feed = random.nextInt();
        var strings = new TreeMap<Integer, String>();
        for (int i = 0; i < trial % 11; i++) {
          int index = (i * 97 + trial) % 1024;
          String value =
              "^"
                  + i
                  + " authored demo "
                  + trial
                  + "\\key\\value;\n"
                  + "x".repeat(random.nextInt(300));
          strings.put(index, value);
          nativeDemo.call("cs " + index + " " + hex(value.getBytes(StandardCharsets.ISO_8859_1)));
        }
        var baselineBytes = new ArrayList<byte[]>();
        for (int i = 1; i <= trial % 9; i++) {
          int kind = random.nextInt(16);
          float x = (random.nextInt(200000) - 100000) / 16f;
          nativeDemo.call("baseline " + i + " " + kind + " " + x);
          byte[] entity = new byte[208];
          var fields = ByteBuffer.wrap(entity).order(ByteOrder.LITTLE_ENDIAN);
          fields.putInt(0, i);
          fields.putInt(4, kind);
          fields.putFloat(92, x);
          baselineBytes.add(entity);
        }
        List<String> started =
            nativeDemo.call(
                "record 8 1 " + sequence + " " + ack + " " + command + " " + client + " " + feed);
        check(started.contains("OPEN demos/fixture.dm_68"), "native protocol68 filename");
        byte[] initial = nativeDemo.bytes();
        DemoRecord first;
        try (var reader = new DemoReader(new ByteArrayInputStream(initial))) {
          first = reader.next().orElseThrow();
        }
        check(first.sequence() == sequence - 1, "initial sequence");
        var payloadReader = new MessageReader(first.payload());
        var parsed =
            ServerMessageCodec.read(
                payloadReader, first.sequence(), Baselines.EMPTY, ignored -> null);
        check(
            parsed.reliableAcknowledge() == ack && parsed.operations().size() == 1,
            "initial acknowledgement/operations");
        GameState game = (GameState) parsed.operations().getFirst();
        check(
            game.commandSequence() == command
                && game.clientNumber() == client
                && game.checksumFeed() == feed,
            "initial metadata");
        check(game.configstrings().equals(strings), "initial configstrings");
        check(game.baselines().entities().size() == baselineBytes.size(), "initial baseline count");
        for (int i = 0; i < baselineBytes.size(); i++)
          check(
              Arrays.equals(baselineBytes.get(i), game.baselines().entities().get(i)),
              "initial baseline bytes");
        var encoded = new MessageWriter();
        ServerMessageCodec.write(encoded, parsed, Baselines.EMPTY);
        byte[] canonical = encoded.bytes();
        check(encoded.bitPosition() == payloadReader.bitPosition(), "initial gamestate bit count");
        check(canonical.length == first.payload().length, "initial gamestate payload size");
        byte[] normalized = first.payload();
        for (int bit = 0; bit < encoded.bitPosition(); bit++)
          check(
              ((canonical[bit / 8] ^ normalized[bit / 8]) & (1 << (bit % 8))) == 0,
              "initial meaningful payload bits at " + trial);
        if (!Arrays.equals(canonical, normalized)) {
          if (paddingDifferences++ == 0)
            Files.writeString(
                Path.of(".tools/demo-playback-oracle/padding-example.log"),
                "bits="
                    + encoded.bitPosition()
                    + "\ncanonical="
                    + hex(canonical)
                    + "\nnative="
                    + hex(normalized)
                    + "\n");
        }

        int length = trial % 50 == 0 ? 16384 : random.nextInt(2048);
        byte[] packet = new byte[length];
        random.nextBytes(packet);
        int header = length == 0 ? 0 : random.nextInt(Math.min(16, length) + 1);
        int liveSequence = random.nextInt();
        nativeDemo.call("write " + header + " " + liveSequence + " " + hex(packet));
        nativeDemo.call("stop");
        byte[] stream = nativeDemo.bytes();
        var rewritten = new ByteArrayOutputStream();
        try (var writer = new DemoWriter(rewritten)) {
          writer.append(first);
          writer.append(
              new DemoRecord(liveSequence, Arrays.copyOfRange(packet, header, packet.length)));
        }
        check(Arrays.equals(stream, rewritten.toByteArray()), "native record framing at " + trial);
        bytes += stream.length;

        byte[] framed =
            ByteBuffer.allocate(8 + packet.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(liveSequence)
                .putInt(packet.length)
                .put(packet)
                .array();
        nativeDemo.call("file 17");
        nativeDemo.call("input " + hex(framed));
        var read = nativeDemo.call("read");
        check(
            read.contains("PARSE " + liveSequence + " " + packet.length + " 0 0 " + hex(packet)),
            "native read bytes");
      }
    }
    System.out.printf(
        "Native demos PASS: %,d synthesized gamestates (all meaningful bits; %,d ignored padding"
            + " differences), %,d framed record streams / %,d bytes, %,d raw playback records%n",
        trials, paddingDifferences, trials, bytes, trials);
  }
}
