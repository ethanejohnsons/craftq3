package dev.bluevista.craftq3.botlib.chat;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.botlib.script.ScriptSources;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

final class ChatExpansionTest {
  @Test
  void expandsOneLevelPerPassAndSubstitutesTheEntireIntermediateMessage() throws Exception {
    var files =
        files(
            "leaf = { \"red\"; } branch = { leaf; }",
            """
        chat "bot" {
          type "literal" { "red"; }
          type "variable" { "red ",0," red"; }
          type "reference" { "red ",leaf," red"; }
          type "nested" { "red ",branch," red"; }
          type "twice" { leaf," ",leaf; }
        }
        """);
    var random = new CountedRandom();
    try (var sources = new ScriptSources(memory(files));
        var chat = new BotChat(sources, () -> 100, random)) {
      chat.setup();
      int handle = chat.allocate();
      chat.load(handle, "test.c", "bot");
      String[] types = {"literal", "variable", "reference", "nested", "twice"};
      String[] expected = {
        "rose", "rose ZERO rose", "pink rose pink", "pink pink pink", "pink rose"
      };
      int[] draws = {3, 3, 6, 9, 7};
      for (int i = 0; i < types.length; i++) {
        random.calls = 0;
        assertTrue(chat.initial(handle, types[i], 4, List.of("ZERO")));
        assertEquals(expected[i], chat.takeMessage(handle));
        assertEquals(draws[i], random.calls);
      }
    }
  }

  @Test
  void stopsAtTenPassesWithTheOriginalUnresolvedReferenceEncoding() throws Exception {
    var rnd = new StringBuilder();
    for (int i = 1; i < 20; i++)
      rnd.append("deep").append(i).append(" = { deep").append(i + 1).append("; }\n");
    rnd.append("deep20 = { \"end\"; }");
    var random = new CountedRandom();
    try (var sources =
            new ScriptSources(
                memory(files(rnd.toString(), "chat \"bot\" { type \"deep\" { deep1; } }")));
        var chat = new BotChat(sources, () -> 100, random)) {
      chat.setup();
      int handle = chat.allocate();
      chat.load(handle, "test.c", "bot");
      chat.initial(handle, "deep", 0, List.of());
      assertEquals("\u0001rdeep11\u0001", chat.takeMessage(handle));
      assertEquals(11, random.calls);
    }
  }

  @Test
  void smallerConfiguredBudgetsFailWithoutPublishingPartialMessages() throws Exception {
    var data =
        files(
            "leaf = { leaf; }",
            "chat \"bot\" { type \"deep\" { leaf; } type \"safe\" { \"safe\"; } }");
    try (var sources = new ScriptSources(memory(data));
        var chat =
            new BotChat(
                sources, () -> 100, new CountedRandom(), new BotChat.Limits(1, 1, 10000, 2, 64))) {
      chat.setup();
      int handle = chat.allocate();
      chat.load(handle, "test.c", "bot");
      chat.initial(handle, "safe", 0, List.of());
      assertThrows(IllegalStateException.class, () -> chat.initial(handle, "deep", 0, List.of()));
      assertEquals("safe", chat.takeMessage(handle));
    }
  }

  private static final class CountedRandom implements RandomGenerator {
    int calls;

    @Override
    public long nextLong() {
      throw new AssertionError("Expected a float draw");
    }

    @Override
    public float nextFloat() {
      calls++;
      return 16384 / 32767.0f;
    }
  }

  private static Map<String, String> files(String random, String chat) {
    var files = new HashMap<String, String>();
    files.put("botfiles/rnd.c", random);
    files.put("botfiles/test.c", chat);
    files.put("botfiles/syn.c", "4 { [(\"rose\",1),(\"pink\",1)] [(\"red\",1),(\"rose\",1)] }");
    files.put("botfiles/match.c", "");
    files.put("botfiles/rchat.c", "");
    return files;
  }

  private static VirtualFileSystem memory(Map<String, String> files) {
    return new VirtualFileSystem() {
      @Override
      public Optional<Origin> which(VirtualPath path) {
        return files.containsKey(path.value())
            ? Optional.of(new Origin("test", "memory", false))
            : Optional.empty();
      }

      @Override
      public List<VirtualPath> list(String directory) {
        return files.keySet().stream().map(VirtualPath::new).toList();
      }

      @Override
      public List<Origin> searchOrder() {
        return List.of();
      }

      @Override
      public byte[] read(VirtualPath path) throws NoSuchFileException {
        String text = files.get(path.value());
        if (text == null) throw new NoSuchFileException(path.value());
        return text.getBytes(StandardCharsets.ISO_8859_1);
      }

      @Override
      public void close() {}
    };
  }
}
