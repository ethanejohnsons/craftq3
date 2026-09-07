import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.core.demo.*;
import java.io.*;
import java.nio.file.Path;
import java.util.Arrays;

/** Reads original demo records through the VFS; byte-exact round trips remain only in memory. */
class AuditDemos {
  public static void main(String[] args) throws Exception {
    int passed = 0;
    try (var fs = Pk3FileSystem.mount(Path.of(args.length == 0 ? "run/craftq3/games" : args[0]), "baseq3")) {
      for (var path : fs.list("demos")) {
        if (!path.value().endsWith(".dm3") && !path.value().matches(".*\\.dm_[0-9]+")) continue;
        byte[] original = fs.read(path);
        var output = new ByteArrayOutputStream();
        int records = 0, first = 0, last = 0, largest = 0;
        try (var reader = new DemoReader(new ByteArrayInputStream(original));
             var writer = new DemoWriter(output)) {
          while (true) {
            var next = reader.next(); if (next.isEmpty()) break;
            var record = next.orElseThrow();
            if (records++ == 0) first = record.sequence(); last = record.sequence();
            largest = Math.max(largest, record.payload().length); writer.append(record);
          }
          if (reader.end() != DemoReader.End.MARKER) throw new IOException("No clean demo marker: " + path);
        }
        if (!Arrays.equals(original, output.toByteArray())) throw new IOException("Demo roundtrip differs: " + path);
        System.out.printf("%s: %d records, sequence %d..%d, max message %d, %d bytes exact%n", path.value(), records, first, last, largest, original.length);
        passed++;
      }
    }
    if (passed == 0) throw new IllegalStateException("No original demos found");
    System.out.printf("Demo framing PASS: %d files; message decoding/playback not claimed%n", passed);
  }
}
