import dev.bluevista.craftq3.core.net.MessageReader;
import dev.bluevista.craftq3.core.net.MessageWriter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Observes wire codes and compares mixed bit fields with unchanged native MSG/Huffman objects. */
class AuditMessages {
  public static void main(String[] args) throws Exception {
    var oracle = Path.of(args.length == 0 ? ".tools/netchan-oracle/netchan-oracle" : args[0]);
    var process = new ProcessBuilder(oracle.toAbsolutePath().toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    var random = new Random(0x680068);
    int fields = 0, bytes = 0;
    try (var input = process.outputWriter(StandardCharsets.US_ASCII);
         var output = process.inputReader(StandardCharsets.US_ASCII)) {
      // These observations independently reproduce the embedded wire codebook's 256 mappings.
      for (int value = 0; value < 256; value++) {
        var writer = new MessageWriter(); writer.byteValue(value);
        input.write("encode 1 8 " + value + "\n"); input.flush();
        verifyEncoded(output.readLine(), writer);
      }
      for (int trial = 0; trial < 512; trial++) {
        int count = 256;
        int[] widths = new int[count], values = new int[count];
        var writer = new MessageWriter();
        var request = new StringBuilder("encode " + count);
        for (int i = 0; i < count; i++) {
          int width = 1 + (i % 32);
          widths[i] = width == 32 || random.nextBoolean() ? width : -width;
          values[i] = random.nextInt();
          writer.field(values[i], widths[i]);
          request.append(' ').append(widths[i]).append(' ').append(Integer.toUnsignedString(values[i]));
        }
        input.write(request.append('\n').toString()); input.flush();
        verifyEncoded(output.readLine(), writer);
        request = new StringBuilder("decode " + HexFormat.of().formatHex(writer.bytes()) + " " + count);
        for (int width : widths) request.append(' ').append(width);
        input.write(request.append('\n').toString()); input.flush();
        String[] decoded = output.readLine().split(" ");
        require(decoded[0].equals("decoded") && decoded.length == count + 5, "native decoded shape");
        var reader = new MessageReader(writer.bytes(), writer.bitPosition());
        for (int i = 0; i < count; i++) {
          int value = reader.field(widths[i]);
          require(value == Integer.parseInt(decoded[i + 1]), "decoded value trial " + trial + " field " + i);
        }
        require(reader.bitPosition() == Integer.parseInt(decoded[count + 2]), "read bit position");
        fields += count; bytes += writer.bytes().length;
      }
    } finally {
      if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
      require(process.exitValue() == 0, "native exit");
    }
    System.out.printf("Message codec PASS: all256 byte codes, %d mixed signed/unsigned fields in512 messages, %d wire bytes; zero bit/value mismatches%n", fields, bytes);
  }

  static void verifyEncoded(String nativeResult, MessageWriter writer) {
    require(nativeResult != null, "native response");
    String expected = "encoded " + writer.bitPosition() + " " + writer.bytes().length + " " + HexFormat.of().formatHex(writer.bytes());
    require(nativeResult.equals(expected), "wire bytes: native=" + nativeResult + ", java=" + expected);
  }
  static void require(boolean condition, String context) { if (!condition) throw new AssertionError(context); }
}
