import dev.bluevista.craftq3.core.net.*;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.*;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Native command retention around an invalid demo snapshot; no production parser modification. */
class AuditDemoInvalidDelta {
  static Snapshot frame(int sequence, int time, int commandTime) {
    byte[] player = new byte[468];
    ByteBuffer.wrap(player).order(ByteOrder.LITTLE_ENDIAN).putInt(commandTime);
    return new Snapshot(sequence, time, 0, new byte[0], player, List.of());
  }

  static List<String> parse(
      BufferedWriter input, BufferedReader output, int sequence, Message message)
      throws IOException {
    var writer = new MessageWriter();
    ServerMessageCodec.write(writer, message, Baselines.EMPTY);
    input.write("parse " + sequence + " " + HexFormat.of().formatHex(writer.bytes()) + "\n");
    input.flush();
    var lines = new ArrayList<String>();
    for (String line; (line = output.readLine()) != null; ) {
      if (line.equals("end")) return lines;
      lines.add(line);
    }
    throw new EOFException("Native parser stopped");
  }

  public static void main(String[] args) throws Exception {
    Process nativeParser =
        new ProcessBuilder(".tools/server-message-oracle/message-oracle")
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
    try (var input = nativeParser.outputWriter(StandardCharsets.US_ASCII);
        var output = nativeParser.inputReader(StandardCharsets.US_ASCII)) {
      input.write("reset\n");
      input.flush();
      if (!"reset".equals(output.readLine())) throw new AssertionError();
      var previous =
          parse(input, output, 90, new Message(0, List.of(new Frame(frame(90, 1000, 900), null))));
      String retained =
          previous.stream().filter(line -> line.startsWith("snapshot ")).findFirst().orElseThrow();
      var missing =
          parse(
              input,
              output,
              100,
              new Message(
                  0,
                  List.of(
                      new Command(5, "print before"),
                      new Frame(frame(100, 1500, 1400), frame(99, 1450, 1350)),
                      new Command(6, "print after"))));
      if (!missing.contains(retained))
        throw new AssertionError("Invalid snapshot changed latest valid frame: " + missing);
      for (var pair : Map.of(5, "print before", 6, "print after").entrySet())
        if (!missing.contains(
            "command "
                + pair.getKey()
                + " "
                + HexFormat.of().formatHex(pair.getValue().getBytes(StandardCharsets.US_ASCII))))
          throw new AssertionError("Lost command " + pair.getKey());
      for (String line : missing)
        if (line.startsWith("message ") || line.startsWith("command ")) System.out.println(line);
      System.out.println(
          "RETAINED latest snapshot90; invalid delta99 at message100 omitted; commands5/6 both"
              + " retained");
    } finally {
      nativeParser.destroy();
    }
  }
}
