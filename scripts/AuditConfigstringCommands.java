import dev.bluevista.craftq3.client.demo.LocalDemoFeed;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

/** Compares production outbound cs/bcs text to authored calls of unchanged SV_SendConfigstring. */
class AuditConfigstringCommands {
  public static void main(String[] args) throws Exception {
    Path oracle = Path.of(args.length == 0 ? ".tools/configstring-send-oracle/probe" : args[0]);
    var process =
        new ProcessBuilder(oracle.toAbsolutePath().toString())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
    int cases = 0, commands = 0, bytes = 0;
    try (var input = process.outputWriter(StandardCharsets.US_ASCII);
        var output = process.inputReader(StandardCharsets.US_ASCII)) {
      var random = new Random(0x4353);
      int[] lengths = {0, 1, 998, 999, 1000, 1001, 1997, 1998, 1999, 2997, 2998, 4095, 8191};
      for (int index : new int[] {0, 99, 1023}) {
        for (int length : lengths) {
          byte[] raw = new byte[length];
          for (int i = 0; i < raw.length; i++) raw[i] = (byte) (1 + random.nextInt(255));
          String value = new String(raw, StandardCharsets.ISO_8859_1);
          input.write(index + " " + (raw.length == 0 ? "-" : HexFormat.of().formatHex(raw)) + "\n");
          input.flush();
          var nativeCommands = new ArrayList<String>();
          for (String line; !(line = output.readLine()).equals("END"); ) {
            String[] fields = line.split(" ", 3);
            if (!fields[0].equals("COMMAND"))
              throw new AssertionError("Unexpected oracle output: " + line);
            byte[] payload = HexFormat.of().parseHex(fields[2]);
            if (payload.length != Integer.parseInt(fields[1]))
              throw new AssertionError("Observer length mismatch");
            nativeCommands.add(new String(payload, StandardCharsets.ISO_8859_1));
          }
          List<String> actual = LocalDemoFeed.configstringCommands(index, value);
          if (!actual.equals(nativeCommands))
            throw new AssertionError(
                "Outbound text mismatch at index=" + index + " length=" + length);
          cases++;
          commands += actual.size();
          bytes += actual.stream().mapToInt(String::length).sum();
        }
      }
    }
    if (process.waitFor() != 0) throw new AssertionError("Native observer failed");
    System.out.println(
        "PASS configstrings="
            + cases
            + " commands="
            + commands
            + " bytes="
            + bytes
            + " differences=0");
  }
}
