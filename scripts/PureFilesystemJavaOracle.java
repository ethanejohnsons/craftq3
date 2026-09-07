import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/** Authored line adapter around production filesystem APIs; no filesystem rules reimplemented. */
public final class PureFilesystemJavaOracle {
  private static String decode(String hex) {
    return hex.equals("-")
        ? ""
        : new String(HexFormat.of().parseHex(hex), StandardCharsets.ISO_8859_1);
  }

  private static void text(String label, String value) {
    System.out.println(
        "QA_"
            + label
            + " "
            + (value.isEmpty()
                ? "-"
                : HexFormat.of().formatHex(value.getBytes(StandardCharsets.ISO_8859_1))));
  }

  public static void main(String[] args) throws Exception {
    try (var fs = Pk3FileSystem.mount(Path.of(args[0]), args.length > 1 ? args[1] : "baseq3");
        var input =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.ISO_8859_1))) {
      System.out.println("QA_READY");
      for (String line; (line = input.readLine()) != null; ) {
        String[] a = line.split(" ");
        switch (a[0]) {
          case "list" -> {
            var packs = fs.packs();
            text(
                "LOADED_NAMES",
                packs.stream().map(Pk3FileSystem.Pack::name).collect(Collectors.joining(" ")));
            text(
                "LOADED_CHECKSUMS",
                packs.stream().map(p -> p.checksum() + " ").collect(Collectors.joining()));
            text(
                "LOADED_PURE",
                packs.stream().map(p -> p.pureChecksum() + " ").collect(Collectors.joining()));
            var referenced =
                packs.stream()
                    .filter(p -> p.references() != 0 || !p.origin().game().equals("baseq3"))
                    .toList();
            text(
                "REFERENCED_NAMES",
                referenced.stream()
                    .map(p -> p.origin().game() + "/" + p.name())
                    .collect(Collectors.joining(" ")));
            text(
                "REFERENCED_CHECKSUMS",
                referenced.stream().map(p -> p.checksum() + " ").collect(Collectors.joining()));
            text("REFERENCED_PURE", fs.referencedPureChecksums());
          }
          case "pure" -> {
            String sums = decode(a[1]);
            fs.pureServerPaks(
                sums.isBlank()
                    ? List.of()
                    : Arrays.stream(sums.trim().split(" +")).map(Integer::valueOf).toList());
            System.out.println("QA_PURE");
          }
          case "feed" -> {
            fs.restartView(Integer.parseInt(a[1]));
            System.out.println("QA_FEED");
          }
          case "clear" -> {
            fs.clearReferences(Integer.parseInt(a[1]));
            System.out.println("QA_CLEAR");
          }
          case "exists" ->
              System.out.println(
                  "QA_EXISTS " + (fs.which(new VirtualPath(decode(a[1]))).isPresent() ? 1 : 0));
          case "read", "open" -> {
            try {
              byte[] bytes = fs.read(new VirtualPath(decode(a[1])));
              System.out.print("QA_FILE " + bytes.length + " 1");
              if (a[0].equals("read")) {
                int count = Math.min(32, bytes.length);
                System.out.print(
                    " "
                        + count
                        + " "
                        + (count == 0 ? "-" : HexFormat.of().formatHex(bytes, 0, count)));
              }
              System.out.println();
            } catch (FileNotFoundException absent) {
              System.out.println("QA_FILE -1 0");
            }
          }
          case "files" -> {
            String directory = decode(a[1]);
            var files = fs.list(directory);
            System.out.println("QA_COUNT " + files.size());
            for (var path : files)
              text(
                  "ENTRY",
                  directory.isEmpty()
                      ? path.value()
                      : path.value().substring(directory.length() + 1));
          }
          case "quit" -> {
            System.out.println("QA_END");
            return;
          }
          default -> throw new IllegalArgumentException("Unknown authored operation " + a[0]);
        }
        System.out.println("QA_END");
        System.out.flush();
      }
    }
  }
}
