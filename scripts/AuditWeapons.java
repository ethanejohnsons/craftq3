import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.botlib.script.ScriptSources;
import dev.bluevista.craftq3.botlib.weapon.BotWeapons;
import dev.bluevista.craftq3.botlib.weapon.WeaponInfo;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/** Read-only weapon corpus and optional native differential audit; no native mod dependency. */
class AuditWeapons {
  public static void main(String[] args) throws Exception {
    if (args.length != 1 && args.length != 3) throw new IllegalArgumentException("Usage: AuditWeapons.java installation [native-oracle original-pak0]");
    Process reference = args.length == 3
        ? new ProcessBuilder(Path.of(args[1]).toAbsolutePath().toString(), Path.of(args[2]).toAbsolutePath().toString(), "weapons.c")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start() : null;
    try (var fs = Pk3FileSystem.mount(Path.of(args[0]), "baseq3");
        var sources = new ScriptSources(fs); var weapons = new BotWeapons(sources)) {
      var input = reference == null ? null : new BufferedReader(new InputStreamReader(reference.getInputStream(), StandardCharsets.ISO_8859_1));
      var output = reference == null ? null : new PrintWriter(new OutputStreamWriter(reference.getOutputStream(), StandardCharsets.ISO_8859_1));
      if (weapons.setup("weapons.c") != 0) throw new IllegalStateException("Weapon setup failed");
      int handle = weapons.allocate(), nativeHandle = 0, metadata = 0, files = 0, choices = 0;
      if (reference != null) {
        if (!"SETUP 0".equals(input.readLine())) throw new IllegalStateException("Native setup failed");
        nativeHandle = Integer.parseInt(exchange(output, input, "alloc").substring(7));
      }
      for (int number = 1; number < 32; number++) {
        ByteBuffer actual = ByteBuffer.allocate(WeaponInfo.BYTE_SIZE); weapons.weaponInfo(handle, number).writeTo(actual, 0);
        if (reference != null) {
          byte[] expected = HexFormat.of().parseHex(exchange(output, input, "bytes " + nativeHandle + " " + number));
          if (!Arrays.equals(expected, actual.array())) throw new IllegalStateException("Weapon metadata ABI differs at slot " + number);
        }
        metadata++;
      }
      for (var path : fs.list("botfiles/bots")) {
        if (!path.value().endsWith("_w.c")) continue;
        if (weapons.loadWeaponWeights(handle, path.value()) != 0) throw new IllegalStateException("Weight load failed: " + path.value());
        if (reference != null && !"LOAD 0".equals(exchange(output, input, "load " + nativeHandle + " " + path.value().substring(9))))
          throw new IllegalStateException("Native weight load failed: " + path.value());
        for (int sample = 0; sample < 64; sample++) {
          int[] inventory = inventory(sample);
          int actual = weapons.chooseBestFightWeapon(handle, inventory);
          if (reference != null) {
            var row = new StringBuilder("inventory");
            for (int value : inventory) row.append(' ').append(value);
            exchange(output, input, row.toString());
            int expected = Integer.parseInt(exchange(output, input, "choose " + nativeHandle).substring(7));
            if (actual != expected) throw new IllegalStateException("Weapon choice differs: " + path.value() + " sample=" + sample + " expected=" + expected + " actual=" + actual);
          }
          if (sample == 63) {
            weapons.reset(handle);
            if (reference != null) exchange(output, input, "reset " + nativeHandle);
            if (weapons.chooseBestFightWeapon(handle, inventory) != actual) throw new IllegalStateException("Reset lost weapon weights");
          }
          choices++;
        }
        files++; System.out.println("PASS " + path.value());
      }
      if (sources.openCount() != 0) throw new IllegalStateException("Weapon service leaked script handle");
      System.out.printf("Weapons files=%d metadataSlots=%d choices=%d native=%s%n", files, metadata, choices, reference != null);
      if (reference != null) {
        output.close(); input.close();
        if (!reference.waitFor(5, TimeUnit.SECONDS) || reference.exitValue() != 0) throw new IllegalStateException("Native weapon oracle failed");
      }
    } finally {
      if (reference != null && reference.isAlive()) reference.destroyForcibly();
    }
  }
  private static int[] inventory(int sample) {
    int[] values = new int[256];
    if (sample == 0) return values;
    if (sample == 1) { Arrays.fill(values, 100); return values; }
    if (sample <= 10) {
      int[] weapons = {4, 6, 5, 7, 8, 9, 10, 11, 13};
      values[weapons[sample - 2]] = 1;
      for (int i = 15; i <= 24; i++) values[i] = 100;
      values[200] = 100; return values;
    }
    Random random = new Random(0x5745504f4eL + sample);
    for (int i = 0; i < values.length; i++) values[i] = random.nextInt(-10, 800);
    for (int i = 4; i <= 14; i++) values[i] = random.nextBoolean() ? 1 : 0;
    for (int i = 15; i <= 23; i++) values[i] = random.nextInt(0, 101);
    return values;
  }
  private static String exchange(PrintWriter output, BufferedReader input, String command) throws Exception {
    output.println(command); output.flush();
    String result = input.readLine(); if (result == null) throw new IllegalStateException("Native weapon oracle ended early");
    return result;
  }
}
