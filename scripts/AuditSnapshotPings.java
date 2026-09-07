package dev.bluevista.craftq3.client.net;

import java.nio.charset.StandardCharsets;
import java.util.Random;

/** Authored packet metadata compared against unchanged CL_ParseSnapshot. No socket or asset I/O. */
class AuditSnapshotPings {
  public static void main(String[] args) throws Exception {
    if (args.length < 1 || args.length > 2)
      throw new IllegalArgumentException("AuditSnapshotPings nativeOracle [queries=20000]");
    int queries = args.length == 2 ? Integer.parseInt(args[1]) : 20000;
    if (queries < 1 || queries > 100000)
      throw new IllegalArgumentException("Invalid ping corpus count");
    var process =
        new ProcessBuilder(args[0]).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    Random random = new Random(680506);
    try (var writer = process.outputWriter(StandardCharsets.US_ASCII);
        var reader = process.inputReader(StandardCharsets.US_ASCII)) {
      for (int trial = 0; trial < queries; trial++) {
        var history = new SnapshotPingHistory();
        StringBuilder commands = new StringBuilder("reset\n");
        int count = trial % 71, sequence = 1 + random.nextInt(100000), outgoing = 1;
        int realtime =
            trial % 5 == 0
                ? Integer.MAX_VALUE - 100
                : trial % 5 == 1 ? Integer.MIN_VALUE : random.nextInt(1000000);
        int commandTime = random.nextInt(2001) - 1000;
        for (int i = 0; i < count; i++) {
          int inputTime = i % 7 == 0 ? commandTime : random.nextInt(2001) - 1000;
          if (trial % 13 == 0) inputTime = random.nextInt();
          realtime += random.nextInt(31);
          history.sent(sequence, inputTime, realtime);
          commands
              .append("packet ")
              .append(sequence & 31)
              .append(' ')
              .append(random.nextInt())
              .append(' ')
              .append(inputTime)
              .append(' ')
              .append(realtime)
              .append('\n');
          outgoing = ++sequence;
        }
        realtime += random.nextInt(2000) - 50;
        if (trial % 19 == 0) commandTime = Integer.MIN_VALUE;
        if (trial % 23 == 0) commandTime = Integer.MAX_VALUE;
        int expected = history.received(trial + 1, commandTime, realtime);
        commands
            .append("parse ")
            .append(outgoing)
            .append(' ')
            .append(realtime)
            .append(' ')
            .append(commandTime)
            .append(' ')
            .append(random.nextInt())
            .append(' ')
            .append(trial + 1)
            .append('\n');
        writer.write(commands.toString());
        writer.flush();
        if (!"RESET".equals(reader.readLine())) throw new AssertionError("Native reset failed");
        for (int i = 0; i < count; i++)
          if (!"PACKET".equals(reader.readLine()))
            throw new AssertionError("Native metadata fixture failed");
        String row = reader.readLine();
        if (row == null
            || !row.startsWith("PING " + expected + " valid1 number" + (trial + 1) + " "))
          throw new AssertionError(
              "Ping query " + trial + " expected=" + expected + " native=" + row);
      }
      writer.write(
          "reset\n"
              + "write 1 100 0 0 999\n"
              + "write 2 200 1 0 456\n"
              + "write 3 300 2 1 123\n"
              + "write 4 400 5 3 456\n");
      writer.flush();
      if (!"RESET".equals(reader.readLine()))
        throw new AssertionError("Native writer reset failed");
      for (String expected :
          new String[] {
            "TRANSMIT slot1 command0 time0 realtime100 bytes4",
            "WRITTEN next2",
            "TRANSMIT slot2 command1 time0 realtime200 bytes4",
            "WRITTEN next3",
            "TRANSMIT slot3 command2 time123 realtime300 bytes6",
            "WRITTEN next4",
            "TRANSMIT slot4 command5 time456 realtime400 bytes10",
            "WRITTEN next5"
          })
        if (!expected.equals(reader.readLine()))
          throw new AssertionError("Native writer metadata differs: " + expected);
      System.out.println(
          "Snapshot ping PASS queries=" + queries + "; writer metadata=4; differences=0");
    } finally {
      process.destroy();
    }
  }
}
