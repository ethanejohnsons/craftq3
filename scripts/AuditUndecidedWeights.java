import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.botlib.script.ScriptSources;
import dev.bluevista.craftq3.botlib.weight.WeightConfig;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.IntSupplier;

/** Read-only differential of both float bits and exact random-stream consumption. */
class AuditUndecidedWeights {
  static final class Samples implements IntSupplier {
    int state, calls, constant; boolean sequence;
    public int getAsInt() { calls++; if (!sequence) return constant; state = state * 1664525 + 1013904223; return state >>> 1; }
  }
  public static void main(String[] args) throws Exception {
    Path install = Path.of(args.length > 0 ? args[0] : "run/craftq3/games");
    Path executable = Path.of(args.length > 1 ? args[1] : ".tools/undecided-oracle/probe");
    Path pack = Path.of(args.length > 2 ? args[2] : install.resolve("baseq3/pak0.pk3").toString());
    var random = new Random(0xdec1ded); int files = 0, evaluations = 0, draws = 0;
    try (var fs = Pk3FileSystem.mount(install, "baseq3"); var sources = new ScriptSources(fs)) {
      for (var path : fs.list("botfiles/bots")) {
        if (!path.value().endsWith("_i.c") && !path.value().endsWith("_w.c")) continue;
        var config = WeightConfig.load(sources, path.value());
        var process = new ProcessBuilder(executable.toAbsolutePath().toString(), pack.toAbsolutePath().toString(), path.value().substring(9))
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try (var input = process.outputWriter(StandardCharsets.US_ASCII); var output = process.inputReader(StandardCharsets.US_ASCII)) {
          require(("LOADED " + config.weights().size()).equals(output.readLine()), "Native weight count " + path);
          for (int scenario = 0; scenario < 32; scenario++) {
            var samples = new Samples(); samples.sequence = scenario >= 2;
            if (samples.sequence) { samples.state = random.nextInt(); send(input, "seed " + Integer.toUnsignedString(samples.state)); }
            else { samples.constant = scenario == 0 ? 0 : 32767; send(input, "random " + samples.constant); }
            int[] inventory = new int[256];
            int[] levels = {0, 100, 1, 5, 15, 50, 200, 1000, 999998, 999999, 1000000};
            for (int i = 0; i < inventory.length; i++) inventory[i] = scenario < levels.length ? levels[scenario] : random.nextInt(1101) - 100;
            var command = new StringBuilder("inventory"); for (int value : inventory) command.append(' ').append(value);
            send(input, command.toString()); send(input, "all");
            for (int weight = 0; weight < config.weights().size(); weight++) {
              String line = output.readLine(); require(line != null, "Native result missing");
              int countAt = line.lastIndexOf(" CALLS "), valueAt = line.lastIndexOf(' ', countAt - 1);
              require(line.substring(6, valueAt).equals(config.weights().get(weight).name()), "Native weight name");
              float nativeValue = Float.parseFloat(line.substring(valueAt + 1, countAt));
              int nativeCalls = Integer.parseInt(line.substring(countAt + 7));
              samples.calls = 0;
              float value = config.evaluateUndecided(weight, inventory, samples);
              require(Float.floatToRawIntBits(nativeValue) == Float.floatToRawIntBits(value) && nativeCalls == samples.calls,
                  path + " scenario=" + scenario + " weight=" + weight + " native=" + nativeValue + "/" + nativeCalls + " java=" + value + "/" + samples.calls);
              evaluations++; draws += samples.calls;
            }
          }
        } finally {
          if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
          require(process.exitValue() == 0, "Native exit " + path);
        }
        files++;
      }
      require(sources.openCount() == 0, "Leaked script source");
    }
    System.out.printf("Undecided weights files=%d evaluations=%d randomDraws=%d; zero float-bit/draw-count mismatches%n", files, evaluations, draws);
  }
  private static void send(BufferedWriter input, String command) throws IOException { input.write(command); input.newLine(); input.flush(); }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
