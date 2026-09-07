import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.botlib.chat.BotChat;
import dev.bluevista.craftq3.botlib.chat.ChatLibrary;
import dev.bluevista.craftq3.botlib.script.ScriptSources;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;

/** Read-only original chat corpus audit. Optional oracle must expose the documented authored protocol. */
class AuditChats {
  private record Value(int count, int length, String message) {}
  public static void main(String[] args) throws Exception {
    if (args.length != 1 && args.length != 3 && args.length != 4)
      throw new IllegalArgumentException("AuditChats <installation> [native oracle executable original PK3 [fixed-random-0..32766]]");
    int files = 0, types = 0, messages = 0, expansions = 0, mismatches = 0;
    int fixedRandom = args.length == 4 ? Integer.parseInt(args[3]) : 0;
    if (fixedRandom < 0 || fixedRandom > 32766) throw new IllegalArgumentException("Random probe out of range");
    RandomGenerator zero = new RandomGenerator() { @Override public long nextLong() { return 0; } @Override public float nextFloat() { return fixedRandom / 32767.0f; } };
    try (var fs = Pk3FileSystem.mount(Path.of(args[0]), "baseq3"); var sources = new ScriptSources(fs); var chat = new BotChat(sources, () -> 100, zero)) {
      chat.setup(); var library = chat.library();
      System.out.printf("Shared random lists=%d synonym groups=%d match rules=%d reply rules=%d%n", library.randomLists().size(), library.synonyms().size(), library.matches().size(), library.replies().size());
      for (var path : fs.list("botfiles/bots")) {
        if (!path.value().endsWith("_t.c")) continue;
        for (var definition : ChatLibrary.loadChats(sources, path.value())) {
          int handle = chat.allocate();
          if (!chat.load(handle, path.value(), definition.name())) throw new AssertionError("Missing chat definition");
          files++; var expected = new ArrayList<Value>(); var commands = new StringBuilder("time 100\nseed " + fixedRandom + "\n");
          for (var entry : definition.types().entrySet()) {
            types++; messages += entry.getValue().size();
            for (int i = 0; i < entry.getValue().size(); i++) {
              commands.append("initial ").append(entry.getKey()).append(" 1\nget\n");
              if (!chat.initial(handle, entry.getKey(), 1, List.of("ZERO", "ONE", "TWO"))) throw new AssertionError("Missing chat type");
              int length = chat.length(handle); String message = chat.takeMessage(handle);
              expected.add(new Value(entry.getValue().size(), length, message)); expansions++;
              if (chat.length(handle) != 0 || message.length() > 255) throw new AssertionError("Chat message bound/consume failure");
            }
          }
          if (args.length >= 3) {
            var actual = oracle(args[1], args[2], path.value(), definition.name(), commands.toString());
            if (actual.size() != expected.size()) throw new AssertionError("Native chat count differs for " + path + ": " + actual.size() + " vs " + expected.size());
            for (int i = 0; i < actual.size(); i++) if (!actual.get(i).equals(expected.get(i))) {
              if (mismatches++ < 12) System.out.println(path.value() + " entry=" + i + " native=" + actual.get(i) + " Java=" + expected.get(i));
            }
          }
          chat.free(handle);
        }
      }
      if (sources.openCount() != 0) throw new AssertionError("Chat loader leaked source handles");
    }
    System.out.printf("Chat files=%d types=%d messages=%d expansions=%d native comparisons=%s mismatches=%d%n", files, types, messages, expansions, args.length >= 3, mismatches);
    if (files == 0 || mismatches != 0) throw new AssertionError("Chat corpus/oracle audit failed");
  }

  private static List<Value> oracle(String executable, String pk3, String path, String name, String commands) throws Exception {
    Process process = new ProcessBuilder(executable, pk3, path.startsWith("botfiles/") ? path.substring(9) : path, name).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var output = executor.submit(() -> {
        byte[] bytes = process.getInputStream().readNBytes(2 * 1024 * 1024 + 1);
        if (bytes.length > 2 * 1024 * 1024) throw new IllegalStateException("Chat oracle output budget exceeded");
        return new String(bytes, StandardCharsets.US_ASCII);
      });
      try (var input = process.getOutputStream()) { input.write(commands.getBytes(StandardCharsets.US_ASCII)); }
      if (!process.waitFor(30, TimeUnit.SECONDS)) throw new IllegalStateException("Chat oracle timed out");
      String text = output.get(5, TimeUnit.SECONDS);
      if (process.exitValue() != 0) throw new IllegalStateException("Chat oracle failed: " + process.exitValue());
      var values = new ArrayList<Value>(); int count = -1, length = -1;
      for (String line : text.lines().toList()) {
        if (line.startsWith("COUNT ")) count = Integer.parseInt(line.substring(6));
        else if (line.startsWith("LENGTH ")) length = Integer.parseInt(line.substring(7));
        else if (line.startsWith("MESSAGEHEX ")) {
          int end = line.indexOf(" LENGTH ");
          String message = new String(HexFormat.of().parseHex(line.substring(11, end)), StandardCharsets.ISO_8859_1);
          if (!line.endsWith(" LENGTH 0")) throw new AssertionError("Native message not consumed");
          values.add(new Value(count, length, message));
        }
      }
      return List.copyOf(values);
    } finally { if (process.isAlive()) process.destroyForcibly(); }
  }
}
