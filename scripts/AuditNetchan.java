import dev.bluevista.craftq3.core.net.Protocol68Channel;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Differential driver: Java framing versus an unchanged native protocol-68 netchan object. */
class AuditNetchan {
  static final HexFormat HEX = HexFormat.of();
  static int messages, packets, receptions, completed;

  public static void main(String[] args) throws Exception {
    Path oracle = Path.of(args.length == 0 ? ".tools/netchan-oracle/netchan-oracle" : args[0]);
    Random random = new Random(0x680068);
    try (Oracle tx = new Oracle(oracle); Oracle rx = new Oracle(oracle)) {
      for (var endpoint : Protocol68Channel.Endpoint.values()) {
        int qport = 49153;
        var sender = new Protocol68Channel(endpoint, qport);
        var opposite = endpoint == Protocol68Channel.Endpoint.CLIENT
            ? Protocol68Channel.Endpoint.SERVER : Protocol68Channel.Endpoint.CLIENT;
        var receiver = new Protocol68Channel(opposite, qport);
        tx.command("reset " + endpoint.ordinal() + " " + qport);
        require("reset".equals(tx.line()), "native transmit reset");
        rx.command("reset " + opposite.ordinal() + " " + qport);
        require("reset".equals(rx.line()), "native receive reset");
        int[] boundaries = {0, 1, 1299, 1300, 1301, 2599, 2600, 2601, 15600, 16384};
        for (int trial = 0; trial < 1000; trial++) {
          int length = trial < boundaries.length ? boundaries[trial] : random.nextInt(16385);
          byte[] payload = new byte[length]; random.nextBytes(payload);
          sender.queue(payload);
          tx.command("send " + hex(payload));
          List<byte[]> datagrams = new ArrayList<>();
          while (sender.hasPendingPacket()) {
            byte[] packet = sender.pollPacket().orElseThrow();
            require(tx.line().equals("packet " + hex(packet)), "transmit bytes, trial " + trial);
            datagrams.add(packet); packets++;
          }
          require(tx.line().equals("sent " + sender.outgoingSequence()), "outgoing sequence");
          messages++;
          // Entire lost messages establish sequence gaps. Within a message, reject a premature
          // second fragment, then deliver all fragments with occasional duplicates.
          if (trial >= boundaries.length && trial % 11 == 0) continue;
          if (datagrams.size() > 1 && trial % 3 == 0) receive(rx, receiver, datagrams.get(1));
          for (byte[] packet : datagrams) {
            receive(rx, receiver, packet);
            if (random.nextInt(4) == 0) receive(rx, receiver, packet);
          }
        }
      }
    }
    System.out.printf("Netchan protocol68 PASS: %d messages, %d packets byte-exact, %d receptions, %d complete; zero mismatches%n",
        messages, packets, receptions, completed);
  }

  static void receive(Oracle oracle, Protocol68Channel receiver, byte[] packet) throws Exception {
    oracle.command("receive " + hex(packet));
    String[] nativeResult = oracle.line().split(" ", 7);
    require(nativeResult.length == 7 && nativeResult[0].equals("received"), "native receive format");
    var result = receiver.receive(packet);
    boolean accepted = result.status() == Protocol68Channel.Status.COMPLETE;
    require(accepted == nativeResult[1].equals("1"), "acceptance " + result.status());
    require(receiver.incomingSequence() == Integer.parseInt(nativeResult[2]), "incoming sequence");
    if (accepted) {
      require(result.dropped() == Integer.parseInt(nativeResult[5]), "sequence gaps");
      require(hex(result.payload()).equals(nativeResult[6]), "reassembled payload");
      completed++;
    }
    receptions++;
  }

  static String hex(byte[] bytes) { return bytes.length == 0 ? "-" : HEX.formatHex(bytes); }
  static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  static class Oracle implements AutoCloseable {
    final Process process;
    final BufferedWriter input;
    final BufferedReader output;
    Oracle(Path program) throws IOException {
      process = new ProcessBuilder(program.toAbsolutePath().toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
      input = process.outputWriter(StandardCharsets.US_ASCII);
      output = process.inputReader(StandardCharsets.US_ASCII);
    }
    void command(String text) throws IOException { input.write(text); input.newLine(); input.flush(); }
    String line() throws IOException {
      String value = output.readLine();
      if (value == null) throw new EOFException("native netchan exited unexpectedly");
      return value;
    }
    public void close() throws IOException {
      input.close();
      try {
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
        else if (process.exitValue() != 0) throw new IOException("Native netchan exit " + process.exitValue());
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt(); process.destroyForcibly(); throw new IOException(failure);
      } finally { output.close(); }
    }
  }
}
