import dev.bluevista.craftq3.core.hash.Md4;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Random;

/** Authored arbitrary-buffer differential against unchanged Com_BlockChecksum. */
final class AuditBlockChecksums {
  public static void main(String[] args) throws Exception {
    Path binary =
        Path.of(args.length > 0 ? args[0] : ".tools/block-checksum-oracle/probe").toAbsolutePath();
    int count = args.length > 1 ? Integer.parseInt(args[1]) : 20_000;
    Process process =
        new ProcessBuilder(binary.toString())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
    var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
    var writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
    Random random = new Random(0x4D4434C0L);
    long totalBytes = 0;
    for (int i = 0; i < count; i++) {
      int size = i < 257 ? i : random.nextInt(4097);
      byte[] bytes = new byte[size];
      random.nextBytes(bytes);
      writer.write("hex " + HexFormat.of().formatHex(bytes));
      writer.newLine();
      writer.flush();
      String actual = reader.readLine();
      String expected = "BLOCK " + HexFormat.of().toHexDigits(Md4.blockChecksum(bytes));
      if (!expected.equals(actual))
        throw new AssertionError(
            "Case " + i + ", length " + size + ": " + expected + " vs " + actual);
      totalBytes += size;
    }
    writer.close();
    if (process.waitFor() != 0) throw new AssertionError("Native checksum observer failed");
    System.out.printf(
        "PASS %,d arbitrary buffers / %,d bytes, exact checksum bits%n", count, totalBytes);
  }
}
