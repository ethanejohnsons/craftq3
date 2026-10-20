package dev.bluevista.craftq3.botlib.chat;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.botlib.script.ScriptException;
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

final class BotChatTest {
  @Test
  void rejectsAdjacentCapturesIncludingAnOptionalEmptySeparator() throws Exception {
    for (String pattern : List.of("0,1", "0,\"\",1", "0,\"a\"|\"\",1")) {
      var files = files("");
      files.put("botfiles/match.c", "1 { " + pattern + " = (1,1); }");
      try (var source = new ScriptSources(memory(files))) {
        assertThrows(ScriptException.class, () -> ChatLibrary.load(source));
        assertEquals(0, source.openCount());
      }
    }
  }

  @Test
  void expandsNestedRandomVariablesAndConsumesProtectionMarkers() throws Exception {
    try (var sources =
            sources("chat \"bot\" { type \"greet\" { \"~hello \",0,\" \",outer,\" \",7; } }");
        var chat = new BotChat(sources, () -> 0, fixed(0))) {
      chat.setup();
      int h = chat.allocate();
      assertTrue(chat.load(h, "test.c", "bot"));
      assertTrue(chat.initial(h, "greet", 0, List.of("Ada")));
      assertEquals("hello Ada last ", chat.message(h));
      assertEquals(16, chat.length(h));
      assertFalse(chat.initial(h, "missing", 0, List.of()));
      assertEquals("hello Ada last ", chat.takeMessage(h));
      assertEquals(0, chat.length(h));
      assertEquals("", chat.takeMessage(h));
      assertEquals(0, sources.openCount());
    }
  }

  @Test
  void nativeZeroRandomSelectionAvoidsRecentMessagesForTwentySeconds() throws Exception {
    double[] time = {0};
    try (var sources = sources("chat \"bot\" { type \"choice\" { \"a\"; \"b\"; \"c\"; } }");
        var chat = new BotChat(sources, () -> time[0], fixed(0))) {
      chat.setup();
      int h = chat.allocate();
      chat.load(h, "test.c", "bot");
      for (String expected : List.of("c", "b", "a", "c")) {
        chat.initial(h, "choice", 0, List.of());
        assertEquals(expected, chat.takeMessage(h));
      }
      time[0] = 21;
      chat.initial(h, "choice", 0, List.of());
      assertEquals("c", chat.takeMessage(h));
    }
  }

  @Test
  void consoleQueuePeeksOldestRetainsTimestampsAndReclaimsGlobalCapacity() throws Exception {
    double[] time = {2.25};
    try (var sources = sources("");
        var chat =
            new BotChat(sources, () -> time[0], fixed(0), new BotChat.Limits(2, 2, 1024, 4, 16))) {
      int a = chat.allocate(), b = chat.allocate();
      assertEquals(0, chat.allocate());
      assertTrue(chat.queue(a, 7, "first"));
      time[0] = 3;
      assertTrue(chat.queue(b, 8, "x".repeat(300)));
      assertFalse(chat.queue(a, 9, "overflow"));
      var message = chat.next(a).orElseThrow();
      assertEquals(2.25f, message.time());
      assertEquals(7, message.type());
      assertEquals("first", message.text());
      assertEquals(message, chat.next(a).orElseThrow());
      assertEquals(255, chat.next(b).orElseThrow().text().length());
      assertTrue(chat.remove(a, message.id()));
      assertFalse(chat.remove(a, message.id()));
      assertTrue(chat.next(a).isEmpty());
      assertTrue(chat.queue(a, 9, "again"));
      assertTrue(chat.free(a));
      assertFalse(chat.free(a));
      int c = chat.allocate();
      assertNotEquals(a, c);
      assertThrows(IllegalArgumentException.class, () -> chat.next(a));
      assertTrue(chat.queue(c, 0, "new"));
    }
  }

  @Test
  void retainsParsedContextsCapturesReplyConditionsAndImmutableData() throws Exception {
    var files = files("chat \"bot\" { type \"same\" { \"old\"; } type \"same\" { \"new\"; } }");
    files.put(
        "botfiles/match.c", "#define CONTEXT 4\nCONTEXT { 0, \" at \"|\" near \", 1 = (2,8); }");
    files.put("botfiles/rchat.c", "[\"hi\", &name, !female, (\"tell \",0)] = 5 { outer; }");
    files.put("botfiles/rnd.c", "outer = { \"first\"; } outer = { \"ignored\"; }");
    try (var sources = new ScriptSources(memory(files));
        var chat = new BotChat(sources, () -> 0, fixed(0))) {
      chat.setup();
      var lib = chat.library();
      assertEquals(4, lib.matches().getFirst().context());
      assertEquals(3, lib.matches().getFirst().parts().size());
      assertEquals(
          ChatLibrary.Constraint.FORBIDDEN,
          lib.replies().getFirst().conditions().get(2).constraint());
      assertThrows(UnsupportedOperationException.class, () -> lib.randomLists().clear());
      int h = chat.allocate();
      chat.load(h, "test.c", "bot");
      assertEquals(1, chat.initialCount(h, "same"));
      chat.initial(h, "same", 0, List.of());
      assertEquals("new", chat.takeMessage(h));
      assertEquals(
          new ChatLibrary.Text("first"),
          lib.randomLists().get("outer").getFirst().parts().getFirst());
      assertEquals(0, sources.openCount());
    }
  }

  @Test
  void rejectsMalformedSourcesAndExpansionCyclesWithoutLeakingHandles() throws Exception {
    var files = files("chat \"bot\" { type \"cycle\" { loop; } type \"bad\" { missing; } }");
    files.put("botfiles/rnd.c", "loop = { loop; }");
    try (var sources = new ScriptSources(memory(files));
        var chat = new BotChat(sources, () -> 0, fixed(0), new BotChat.Limits(2, 4, 1024, 3, 8))) {
      chat.setup();
      int h = chat.allocate();
      chat.load(h, "test.c", "bot");
      assertThrows(IllegalStateException.class, () -> chat.initial(h, "cycle", 0, List.of()));
      assertThrows(IllegalStateException.class, () -> chat.initial(h, "bad", 0, List.of()));
      assertFalse(chat.reply(h, "hi", 1, 1, List.of()));
      assertEquals(0, sources.openCount());
    }
    try (var sources = sources("chat \"bad\" { type \"x\" { 8; } }")) {
      assertThrows(ScriptException.class, () -> ChatLibrary.loadChats(sources, "test.c"));
      assertEquals(0, sources.openCount());
    }
  }

  @Test
  void failedLoadIsTransactionalAndBorrowedSourcesRemainOpenAfterClose() throws Exception {
    var sources = sources("chat \"bot\" { type \"x\" { \"value\"; } }");
    try {
      var chat = new BotChat(sources, () -> 0, fixed(0));
      chat.setup();
      int h = chat.allocate();
      assertTrue(chat.load(h, "test.c", "bot"));
      assertFalse(chat.load(h, "test.c", "absent"));
      assertEquals(1, chat.initialCount(h, "x"));
      chat.setName(h, "Name", 3);
      chat.setGender(h, 2);
      assertEquals("Name", chat.name(h));
      assertEquals(3, chat.client(h));
      assertEquals(2, chat.gender(h));
      chat.close();
      chat.close();
      assertThrows(IllegalStateException.class, chat::allocate);
      int source = sources.load("test.c");
      assertTrue(sources.free(source));
    } finally {
      sources.close();
    }
  }

  @Test
  void weightedSynonymsAreBoundedAndRespectContextAndProtection() throws Exception {
    try (var sources = sources("chat \"bot\" { type \"x\" { \"~hello hello ZERO\"; } }");
        var chat = new BotChat(sources, () -> 0, fixed(0.75f))) {
      chat.setup();
      int h = chat.allocate();
      chat.load(h, "test.c", "bot");
      chat.initial(h, "x", 1, List.of());
      assertEquals("hello hi zeroalt", chat.takeMessage(h));
      chat.initial(h, "x", 2, List.of());
      assertEquals("hello hello ZERO", chat.takeMessage(h));
      assertEquals("hello ZERO", chat.replaceSynonyms("hi zeroalt", 1));
    }
  }

  @Test
  void textHelpersSearchAsciiAndNormalizeTheObservedWhitespaceAlphabet() {
    assertEquals(3, BotChat.stringContains("abcDEF", "Def", false));
    assertEquals(-1, BotChat.stringContains("abcDEF", "Def", true));
    assertEquals(0, BotChat.stringContains("", "", false));
    assertEquals(-1, BotChat.stringContains("abc", "abcd", false));
    assertEquals("a b!c", BotChat.unifyWhiteSpaces(" a\t\t b!c "));
    assertEquals("don't (x), y?", BotChat.unifyWhiteSpaces("don't (x), y?"));
  }

  private static RandomGenerator fixed(float value) {
    return new RandomGenerator() {
      @Override
      public long nextLong() {
        return 0;
      }

      @Override
      public float nextFloat() {
        return value;
      }
    };
  }

  private static ScriptSources sources(String chat) {
    return new ScriptSources(memory(files(chat)));
  }

  private static Map<String, String> files(String chat) {
    var files = new HashMap<String, String>();
    files.put("botfiles/test.c", chat);
    files.put("botfiles/rnd.c", "outer = { inner; } inner = { \"first\"; \"last\"; }");
    files.put("botfiles/syn.c", "1 { [(\"ZERO\",1),(\"zeroalt\",1)] [(\"hello\",1),(\"hi\",1)] }");
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
