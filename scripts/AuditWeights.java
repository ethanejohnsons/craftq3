import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.botlib.script.ScriptSources;
import dev.bluevista.craftq3.botlib.weight.WeightConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Read-only original weight corpus audit, optionally compared with an isolated native oracle. */
class AuditWeights {
  private record Evaluated(String name, float value) {}
  public static void main(String[] args) throws Exception {
    if (args.length != 1 && args.length != 3)
      throw new IllegalArgumentException("AuditWeights <installation> [native oracle executable original PK3]");
    int files = 0, weights = 0, evaluations = 0, mismatches = 0;
    double maxError = 0;
    try (var fs = Pk3FileSystem.mount(Path.of(args[0]), "baseq3"); var sources = new ScriptSources(fs)) {
      for (var path : fs.list("botfiles/bots")) {
        if (!path.value().endsWith("_i.c") && !path.value().endsWith("_w.c")) continue;
        var config = WeightConfig.load(sources, path.value());
        files++; weights += config.weights().size();
        var expected = new ArrayList<Evaluated>();
        var commands = new StringBuilder();
        for (int scenario = 0; scenario < 9; scenario++) {
          int[] inventory = inventory(scenario);
          for (int i = 0; i < inventory.length; i++) commands.append("set ").append(i).append(' ').append(inventory[i]).append('\n');
          commands.append("all\n");
          for (int index = 0; index < config.weights().size(); index++) {
            float value = config.evaluate(index, inventory);
            if (!Float.isFinite(value)) throw new AssertionError("Non-finite weight " + path);
            expected.add(new Evaluated(config.names().get(index), value));
            evaluations++;
          }
        }
        if (args.length == 3) {
          var actual = oracle(args[1], args[2], path.value(), commands.toString());
          if (actual.size() != expected.size()) throw new AssertionError("Oracle result count differs for " + path);
          for (int i = 0; i < expected.size(); i++) {
            if (!actual.get(i).name().equals(expected.get(i).name())) throw new AssertionError("Oracle weight order differs for " + path);
            float a = actual.get(i).value(), b = expected.get(i).value();
            if (Float.floatToRawIntBits(a) != Float.floatToRawIntBits(b)) {
              if (mismatches++ < 10) System.out.println(path.value() + " " + expected.get(i).name() + " scenario="
                  + i / config.weights().size() + " native=" + a + " Java=" + b);
              maxError = Math.max(maxError, Math.abs((double) a - b));
            }
          }
        }
      }
      if (sources.openCount() != 0) throw new AssertionError("Weight loader leaked script handles");
    }
    System.out.printf("Weight files=%d weights=%d deterministic evaluations=%d; native float-bit mismatches=%d maxAbsoluteError=%.10g%n",
        files, weights, evaluations, mismatches, maxError);
    if (files == 0 || mismatches != 0) throw new AssertionError("Weight corpus/oracle audit failed");
  }

  private static int[] inventory(int scenario) {
    int[] result = new int[256];
    int[] levels = {0, 1, 5, 15, 50, 100, 200, 1000};
    if (scenario < levels.length) Arrays.fill(result, levels[scenario]);
    else for (int i = 0; i < result.length; i++) result[i] = (i * 37 + 11) % 211;
    return result;
  }

  private static List<Evaluated> oracle(String executable, String pk3, String path, String commands) throws Exception {
    String nativePath = path.startsWith("botfiles/") ? path.substring(9) : path;
    Process process = new ProcessBuilder(executable, pk3, nativePath).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
      var text = readers.submit(() -> {
        byte[] bytes = process.getInputStream().readNBytes(1024 * 1024 + 1);
        if (bytes.length > 1024 * 1024) throw new IllegalStateException("Oracle output budget exceeded");
        return new String(bytes, StandardCharsets.UTF_8);
      });
      try (var input = process.getOutputStream()) { input.write(commands.getBytes(StandardCharsets.US_ASCII)); }
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("Native oracle timed out");
      }
      String output = text.get(5, TimeUnit.SECONDS);
      if (process.exitValue() != 0) throw new IllegalStateException("Native oracle failed for " + nativePath + ": " + output);
      var values = new ArrayList<Evaluated>();
      for (String line : output.lines().toList()) {
        if (!line.startsWith("VALUE ")) continue;
        int separator = line.lastIndexOf(' ');
        values.add(new Evaluated(line.substring(6, separator), Float.parseFloat(line.substring(separator + 1))));
      }
      return List.copyOf(values);
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }
}
