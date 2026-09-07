import dev.bluevista.craftq3.core.net.ConnectionlessMessage;
import dev.bluevista.craftq3.core.net.ConnectionlessPacket;
import dev.bluevista.craftq3.core.net.Protocol68Datagram;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Authored raw-byte/cursor comparison with unchanged native OOB formatting and MSG functions. */
class AuditConnectionless {
  static final HexFormat HEX = HexFormat.of();
  static int lines, prints, prefixes;

  public static void main(String[] args) throws Exception {
    Path path = Path.of(args.length == 0 ? ".tools/connectionless-oracle/probe" : args[0]);
    Random random = new Random(0x4f4f423638L);
    try (Oracle oracle = new Oracle(path)) {
      for (int symbol = 0; symbol < 256; symbol++) {
        for (int position : new int[] {0, 1, 1022, 1023, 1024}) {
          byte[] payload = new byte[position + 6];
          Arrays.fill(payload, (byte) 'a');
          payload[position] = (byte) symbol;
          payload[position + 1] = '\n';
          payload[position + 2] = '%';
          payload[position + 3] = 0;
          payload[position + 4] = (byte) 255;
          line(oracle, payload);
        }
        print(oracle, new byte[] {(byte) symbol, '%', '\n', (byte) 200});
        byte[] prefix = {(byte) symbol, -1, -1, -1};
        prefix(oracle, prefix);
      }
      for (int length = 0; length <= 1100; length++) {
        byte[] payload = new byte[length];
        Arrays.fill(payload, (byte) 'x');
        line(oracle, payload);
        for (byte terminator : new byte[] {0, '\n'}) {
          byte[] terminated = Arrays.copyOf(payload, length + 2);
          terminated[length] = terminator;
          terminated[length + 1] = (byte) 255;
          line(oracle, terminated);
        }
      }
      for (int length : new int[] {0, 1, 1023, 1024, 16378, 16379, 16380, 16381, 16384}) {
        byte[] text = new byte[length];
        Arrays.fill(text, (byte) 'x');
        print(oracle, text);
      }
      for (int trial = 0; trial < 10000; trial++) {
        byte[] payload = new byte[random.nextInt(ConnectionlessPacket.MAX_PAYLOAD + 1)];
        random.nextBytes(payload);
        // Long nonterminated prefixes exercise the capacity path as well as random terminators.
        if (trial % 3 == 0) Arrays.fill(payload, 0, Math.min(payload.length, 1100), (byte) 'a');
        line(oracle, payload);
        byte[] packet = new byte[random.nextInt(16385)];
        random.nextBytes(packet);
        if (packet.length >= 4 && trial % 5 == 0) Arrays.fill(packet, 0, 4, (byte) 255);
        prefix(oracle, packet);
        if (trial < 2000) {
          byte[] text = new byte[random.nextInt(16385)];
          for (int i = 0; i < text.length; i++) text[i] = (byte) (1 + random.nextInt(255));
          print(oracle, text);
        }
      }
      for (int length = 0; length < 4; length++) prefix(oracle, new byte[length]);
    }
    System.out.printf(
        "Connectionless protocol68 PASS: %d raw lines/cursors/bodies, %d native printf frames, %d"
            + " prefix/classification requests; zero mismatches%n",
        lines, prints, prefixes);
  }

  static void line(Oracle oracle, byte[] payload) throws IOException {
    byte[] packet = ConnectionlessPacket.encode(payload);
    String response = oracle.command("line " + hex(packet));
    String[] fields = response.split(" ", 6);
    var actual = ConnectionlessMessage.parse(packet);
    require(
        fields.length == 6 && fields[0].equals("line") && fields[1].equals("-1"),
        "line format " + response);
    require(actual.readCount() == Integer.parseInt(fields[2]), "readcount at line " + lines);
    require(actual.bitPosition() == Integer.parseInt(fields[3]), "bit cursor at line " + lines);
    require(
        hex(actual.line().getBytes(StandardCharsets.ISO_8859_1)).equals(fields[4]),
        "line text at " + lines);
    require(hex(actual.body()).equals(fields[5]), "raw body at " + lines);
    lines++;
  }

  static void print(Oracle oracle, byte[] text) throws IOException {
    String actual = oracle.command("print " + hex(text));
    int length = 0;
    // Native printf is an observed C-string formatter, unlike the exact ConnectionlessPacket.text
    // API.
    while (length < text.length && length < 16379 && text[length] != 0) length++;
    byte[] packet = ConnectionlessPacket.encode(Arrays.copyOf(text, length));
    require(actual.equals("packet " + hex(packet)), "printf frame at " + prints);
    prints++;
  }

  static void prefix(Oracle oracle, byte[] packet) throws IOException {
    String response = oracle.command("prefix " + hex(packet));
    String[] fields = response.split(" ");
    require(fields.length == 4 && fields[0].equals("prefix"), "prefix format");
    int marker = Integer.parseInt(fields[1]);
    if (packet.length >= 4) {
      require(
          marker == ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getInt(), "raw prefix");
      require(fields[2].equals("4") && fields[3].equals("32"), "prefix cursors");
    } else {
      require(marker == -1, "truncated native prefix sentinel");
    }
    var expected =
        packet.length < 4
            ? Protocol68Datagram.Kind.MALFORMED
            : marker == -1
                ? Protocol68Datagram.Kind.CONNECTIONLESS
                : packet.length > 1400
                    ? Protocol68Datagram.Kind.MALFORMED
                    : Protocol68Datagram.Kind.SEQUENCED;
    require(Protocol68Datagram.classify(packet) == expected, "classification at " + prefixes);
    prefixes++;
  }

  static String hex(byte[] bytes) {
    return bytes.length == 0 ? "-" : HEX.formatHex(bytes);
  }

  static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  static final class Oracle implements AutoCloseable {
    final Process process;
    final BufferedWriter input;
    final BufferedReader output;

    Oracle(Path path) throws IOException {
      process =
          new ProcessBuilder(path.toAbsolutePath().toString())
              .redirectError(ProcessBuilder.Redirect.INHERIT)
              .start();
      input = process.outputWriter(StandardCharsets.US_ASCII);
      output = process.inputReader(StandardCharsets.US_ASCII);
    }

    String command(String command) throws IOException {
      input.write(command);
      input.newLine();
      input.flush();
      String line = output.readLine();
      if (line == null) throw new EOFException("Native connectionless oracle exited");
      return line;
    }

    @Override
    public void close() throws IOException {
      input.close();
      try {
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        else if (process.exitValue() != 0)
          throw new IOException("Native connectionless exit " + process.exitValue());
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        process.destroyForcibly();
        throw new IOException(failure);
      } finally {
        output.close();
      }
    }
  }
}
