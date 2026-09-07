import dev.bluevista.craftq3.assets.fs.Pk3Checksums;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Random;
import java.util.zip.ZipFile;

/** Authored CRC-array differential; optional PK3 access reads central-directory metadata only. */
final class AuditPackChecksums {
  private final Process process;
  private final BufferedReader read;
  private final BufferedWriter write;
  private int comparisons;

  AuditPackChecksums(Path nativeBinary) throws Exception {
    process =
        new ProcessBuilder(nativeBinary.toString())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
    read = new BufferedReader(new InputStreamReader(process.getInputStream()));
    write = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
  }

  private void compare(int[] crcs, Integer feed) throws Exception {
    ByteBuffer bytes =
        ByteBuffer.allocate((crcs.length + (feed == null ? 0 : 1)) * 4)
            .order(ByteOrder.LITTLE_ENDIAN);
    if (feed != null) bytes.putInt(feed);
    for (int crc : crcs) bytes.putInt(crc);
    write.write("hex " + HexFormat.of().formatHex(bytes.array()));
    write.newLine();
    write.flush();
    int expected = feed == null ? Pk3Checksums.normal(crcs) : Pk3Checksums.pure(crcs, feed);
    String observed = read.readLine();
    String value = "BLOCK " + HexFormat.of().toHexDigits(expected);
    if (!value.equals(observed))
      throw new AssertionError(
          "Case "
              + comparisons
              + ", entries "
              + crcs.length
              + ", feed "
              + feed
              + ": "
              + value
              + " vs "
              + observed);
    comparisons++;
  }

  private void run(Path pak) throws Exception {
    Random random = new Random(0x504B334CL);
    for (int i = 0; i < 20_000; i++) {
      int count = i < 257 ? i : random.nextInt(1025);
      int[] crcs = new int[count];
      for (int at = 0; at < count; at++) crcs[at] = random.nextInt();
      compare(crcs, null);
      compare(
          crcs,
          switch (i % 5) {
            case 0 -> 0;
            case 1 -> -1;
            case 2 -> 0x12345678;
            case 3 -> Integer.MIN_VALUE;
            default -> random.nextInt();
          });
    }
    int[] maximum = new int[Pk3Checksums.MAX_ENTRIES];
    for (int i = 0; i < maximum.length; i++) maximum[i] = random.nextInt();
    compare(maximum, null);
    compare(maximum, 0x12345678);
    if (pak != null) {
      var values = new ArrayList<Integer>();
      int zero = 0;
      try (var zip = new ZipFile(pak.toFile(), StandardCharsets.ISO_8859_1)) {
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
          var entry = entries.nextElement();
          if (entry.getSize() == 0) zero++;
          else {
            if (entry.getSize() < 0 || entry.getCrc() < 0)
              throw new AssertionError("Missing ZIP metadata");
            values.add((int) entry.getCrc());
          }
        }
      }
      int[] crcs = values.stream().mapToInt(Integer::intValue).toArray();
      compare(crcs, null);
      System.out.printf(
          "PK3 included=%d zeroSizeExcluded=%d normal=%d%n",
          crcs.length, zero, Pk3Checksums.normal(crcs));
      for (int feed : new int[] {0, 42, -1, 0x12345678}) {
        compare(crcs, feed);
        System.out.printf("PK3 feed=%d pure=%d%n", feed, Pk3Checksums.pure(crcs, feed));
      }
    }
    write.close();
    if (process.waitFor() != 0) throw new AssertionError("Native checksum observer failed");
    System.out.printf("PASS %,d ordered-CRC checksum comparisons, exact bits%n", comparisons);
  }

  public static void main(String[] args) throws Exception {
    Path binary =
        Path.of(args.length > 0 ? args[0] : ".tools/block-checksum-oracle/probe").toAbsolutePath();
    new AuditPackChecksums(binary).run(args.length > 1 ? Path.of(args[1]) : null);
  }
}
