import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.botlib.character.BotCharacters;
import dev.bluevista.craftq3.botlib.script.ScriptSources;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Read-only character corpus audit; optional differential oracle is a separate native executable. */
class AuditCharacters {
  public static void main(String[] args) throws Exception {
    if (args.length != 1 && args.length != 3) throw new IllegalArgumentException("Usage: AuditCharacters.java installation [native-oracle staged-native-assets]");
    Process reference = args.length == 3
        ? new ProcessBuilder(Path.of(args[1]).toAbsolutePath().toString(), Path.of(args[2]).toAbsolutePath().toString())
            .redirectError(ProcessBuilder.Redirect.DISCARD).start() : null;
    try (var fs = Pk3FileSystem.mount(Path.of(args[0]), "baseq3")) {
      var input = reference == null ? null : new BufferedReader(new InputStreamReader(reference.getInputStream(), StandardCharsets.ISO_8859_1));
      var output = reference == null ? null : new PrintWriter(new OutputStreamWriter(reference.getOutputStream(), StandardCharsets.ISO_8859_1));
      int files = 0, profiles = 0, comparisons = 0, mismatches = 0, floatDifferences = 0, integerDifferences = 0, stringDifferences = 0;
      long maxFloatUlps = 0;
      for (var path : fs.list("botfiles/bots")) {
        if (!path.value().endsWith("_c.c")) continue;
        try (var sources = new ScriptSources(fs); var characters = new BotCharacters(sources)) {
          if (reference != null) exchange(output, input, "shutdown");
          for (float skill : new float[] {1, 1.25f, 2, 2.5f, 3, 3.75f, 4, 4.5f, 5}) {
            int handle = characters.loadCharacter(path.value(), skill);
            if (handle == 0) throw new IllegalStateException("Character load failed: " + path.value() + " skill " + skill);
            profiles++;
            if (reference == null) continue;
            int nativeHandle = Integer.parseInt(exchange(output, input, "load " + path.value().substring(9) + " " + skill).substring(2));
            if (nativeHandle == 0) throw new IllegalStateException("Native character load failed: " + path.value());
            for (int index = 0; index < 80; index++) {
              float expectedFloat = Float.parseFloat(exchange(output, input, "f " + nativeHandle + " " + index).substring(2));
              int expectedInt = Integer.parseInt(exchange(output, input, "i " + nativeHandle + " " + index).substring(2));
              String expectedString = exchange(output, input, "s " + nativeHandle + " " + index).substring(2);
              float actualFloat = characters.characteristicFloat(handle, index);
              int actualInt = characters.characteristicInteger(handle, index);
              String actualString = characters.characteristicString(handle, index);
              comparisons += 3;
              if (Float.floatToIntBits(expectedFloat) != Float.floatToIntBits(actualFloat)) {
                floatDifferences++;
                maxFloatUlps = Math.max(maxFloatUlps, Math.abs((long) Float.floatToIntBits(expectedFloat) - Float.floatToIntBits(actualFloat)));
              }
              if (expectedInt != actualInt) integerDifferences++;
              if (!expectedString.equals(actualString)) stringDifferences++;
              if (Float.floatToIntBits(expectedFloat) != Float.floatToIntBits(actualFloat) || expectedInt != actualInt || !expectedString.equals(actualString)) {
                mismatches++;
                if (mismatches <= 12) System.out.printf("MISMATCH %s skill=%s index=%d float=%s/%s int=%d/%d string=%s/%s%n",
                    path.value(), skill, index, expectedFloat, actualFloat, expectedInt, actualInt, expectedString, actualString);
              }
            }
          }
          if (sources.openCount() != 0) throw new IllegalStateException("Character leaked script handle");
          files++; System.out.println("PASS " + path.value());
        }
      }
      System.out.printf("Characters files=%d profiles=%d nativeComparisons=%d mismatches=%d floatDifferences=%d integerDifferences=%d stringDifferences=%d maxFloatUlps=%d%n", files, profiles, comparisons, mismatches, floatDifferences, integerDifferences, stringDifferences, maxFloatUlps);
      if (reference != null) {
        output.close(); input.close();
        if (!reference.waitFor(5, TimeUnit.SECONDS) || reference.exitValue() != 0) throw new IllegalStateException("Native oracle failed");
      }
      if (mismatches != 0) throw new IllegalStateException("Character differential mismatches: " + mismatches);
    } finally {
      if (reference != null && reference.isAlive()) reference.destroyForcibly();
    }
  }
  private static String exchange(PrintWriter output, BufferedReader input, String command) throws Exception {
    output.println(command); output.flush();
    String result = input.readLine();
    if (result == null) throw new IllegalStateException("Native oracle ended early");
    return result;
  }
}
