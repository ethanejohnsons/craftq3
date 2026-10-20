package dev.bluevista.craftq3.botlib.chat;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.botlib.script.ScriptException;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

final class ChatRepliesTest {
  @Test
  void prioritiesUseLastSourceTieAndOnlyExpandTheFinalSelectedReply() throws Exception {
    try (var chat = chat("[\"x\"]=3{\"first\";} [\"x\"]=3{\"last\";} [\"x\"]=1{unknown;}", 0)) {
      assertTrue(chat.reply(1, "x", 0, 0, List.of()));
      assertEquals("last", chat.message(1));
      assertFalse(chat.reply(1, "missing", 0, 0, List.of()));
      assertEquals("last", chat.takeMessage(1));
    }
    try (var chat = chat("[\"x\"]=0{\"zero\";}", 0)) {
      assertTrue(chat.reply(1, "x", 0, 0, List.of()));
      assertEquals("zero", chat.takeMessage(1));
    }
  }

  @Test
  void recentCountIndexesTheFullListAndHistoryIsSharedBetweenBots() throws Exception {
    for (float random : new float[] {0, .5f, .9f}) {
      try (var chat = chat("[\"x\"]=1{\"a\";\"b\";\"c\";}", random)) {
        for (int i = 0; i < 5; i++) {
          assertTrue(chat.reply(1, "x", 0, 0, List.of()));
          assertEquals(
              random == 0 ? "c" : random == .5f ? "b" : i == 0 ? "a" : i == 1 ? "b" : "c",
              chat.takeMessage(1));
        }
      }
    }
    try (var chat = chat("[\"x\"]=1{\"a\";\"b\";\"c\";\"d\";}", .9f)) {
      int other = chat.allocate();
      chat.reply(1, "x", 0, 0, List.of());
      assertEquals("a", chat.takeMessage(1));
      chat.reply(other, "x", 0, 0, List.of());
      assertEquals("b", chat.takeMessage(other));
      chat.reply(1, "x", 0, 0, List.of());
      assertEquals("c", chat.takeMessage(1));
    }
  }

  @Test
  void requiredForbiddenAndGenderKeysStillNeedAnOrdinarySuccessfulKey() throws Exception {
    try (var chat = chat("[\"a\",&\"b\",!\"c\",&female]=1{\"yes\";} [&\"z\"]=2{\"never\";}", 0)) {
      chat.setGender(1, 1);
      for (String text : List.of("a b", "b a")) assertTrue(chat.reply(1, text, 0, 0, List.of()));
      for (String text : List.of("a", "b", "a b c", "z"))
        assertFalse(chat.reply(1, text, 0, 0, List.of()));
      chat.setGender(1, 2);
      assertFalse(chat.reply(1, "a b", 0, 0, List.of()));
    }
    try (var chat = chat("[it]=1{\"it\";} [female]=1{\"female\";} [male]=1{\"male\";}", 0)) {
      for (int gender = 0; gender < 3; gender++) {
        chat.setGender(1, gender);
        assertTrue(chat.reply(1, "", 0, 0, List.of()));
        assertEquals(List.of("it", "female", "male").get(gender), chat.takeMessage(1));
      }
    }
  }

  @Test
  void wordScannerPreservesNativeBoundaryAndLeadingDelimiterBehavior() throws Exception {
    try (var chat = chat("[\"hello\"]=1{\"yes\";}", 0)) {
      for (String text :
          List.of("hello", "HELLO!", "a hello", "hello,", "x.hello", "  hello", " hello hello"))
        assertTrue(chat.reply(1, text, 0, 0, List.of()), text);
      for (String text :
          List.of(
              " hello", "hello?", "hello\t", "x\thello", "x  hello", "hello-world", "shelloworld"))
        assertFalse(chat.reply(1, text, 0, 0, List.of()), text);
    }
    try (var chat = chat("[name]=1{\"yes\";}", 0)) {
      chat.setName(1, "TestBot", 3);
      assertTrue(chat.reply(1, "^1testbotSuffix", 0, 0, List.of()));
      assertFalse(chat.reply(1, "OtherBot", 0, 0, List.of()));
    }
  }

  @Test
  void patternCapturesMergeInReverseKeyOrderAndAcrossImprovingRules() throws Exception {
    try (var chat = chat("[(0,\" right\"),(0,\" hi \",1)]=1{0,\"|\",1;}", 0)) {
      assertTrue(chat.reply(1, "left hi right", 0, 0, List.of()));
      assertEquals("left hi|right", chat.takeMessage(1));
    }
    try (var chat = chat("[(0,\" hi \",1)]=2{0,\"|\",1,\"|\",2;} [(2,\" \",3)]=1{2;}", 0)) {
      assertTrue(chat.reply(1, "left hi right", 0, 0, List.of()));
      assertEquals("left|right|left", chat.takeMessage(1));
    }
    try (var chat = chat("[!\"left\",(0,\" right\")]=3{0;} [(0,\" hi \",1)]=1{0,\"|\",1;}", 0)) {
      assertTrue(chat.reply(1, "left hi right", 0, 0, List.of()));
      assertEquals("left|right", chat.takeMessage(1));
    }
  }

  @Test
  void callerVariablesOverrideCapturesIncludingExplicitEmptyValues() throws Exception {
    try (var chat = chat("[(0,\" hi \",1)]=1{0,\"|\",1,\"|\",2;}", 0)) {
      assertTrue(chat.reply(1, "left hi right", 0, 0, Arrays.asList(null, "override", "~Q")));
      assertEquals("left|override|Q", chat.takeMessage(1));
      assertTrue(chat.reply(1, "left hi right", 0, 0, List.of("", "")));
      assertEquals("||", chat.takeMessage(1));
      assertThrows(
          IllegalArgumentException.class,
          () -> chat.reply(1, "x", 0, 0, List.of("", "", "", "", "", "", "", "", "")));
    }
  }

  @Test
  void variableContextNormalizesBothCapturedAndSuppliedValuesBeforeMessageExpansion()
      throws Exception {
    try (var chat =
        chat("[(0,\" says \",1)]=1{0,\"|\",1,\"|blue\";}", "1 {[(\"red\",1),(\"blue\",1)]}", 0)) {
      assertTrue(chat.reply(1, "blue says blue", 0, 1, List.of()));
      assertEquals("red|red|blue", chat.takeMessage(1));
      assertTrue(chat.reply(1, "left says right", 0, 1, List.of("blue", "blue")));
      assertEquals("red|red|blue", chat.takeMessage(1));
      assertTrue(chat.reply(1, "blue says blue", 0, 0, List.of()));
      assertEquals("blue|blue|blue", chat.takeMessage(1));
    }
  }

  @Test
  void rejectsUnsupportedReplyKeysAndSignedPriorities() {
    for (String rules : List.of("[botnames]=1{\"x\";}", "[\"x\"]=-1{\"x\";}", "[\"x\"]=+1{\"x\";}"))
      assertThrows(
          ScriptException.class,
          () -> {
            try (var invalid = chat(rules, 0)) {
              fail("Invalid reply accepted: " + invalid.library());
            }
          });
  }

  private static BotChat chat(String replies, float value) throws Exception {
    return chat(replies, "", value);
  }

  private static BotChat chat(String replies, String synonyms, float value) throws Exception {
    var files =
        Map.of(
            "botfiles/rnd.c",
            "",
            "botfiles/syn.c",
            synonyms,
            "botfiles/match.c",
            "",
            "botfiles/rchat.c",
            replies);
    VirtualFileSystem fs =
        new VirtualFileSystem() {
          public Optional<Origin> which(VirtualPath path) {
            return files.containsKey(path.value())
                ? Optional.of(new Origin("test", "memory", false))
                : Optional.empty();
          }

          public List<VirtualPath> list(String directory) {
            return files.keySet().stream().map(VirtualPath::new).toList();
          }

          public List<Origin> searchOrder() {
            return List.of();
          }

          public byte[] read(VirtualPath path) throws NoSuchFileException {
            String text = files.get(path.value());
            if (text == null) throw new NoSuchFileException(path.value());
            return text.getBytes(StandardCharsets.ISO_8859_1);
          }

          public void close() {}
        };
    var chat =
        new BotChat(
            fs,
            () -> 5,
            new RandomGenerator() {
              public long nextLong() {
                return 0;
              }

              public float nextFloat() {
                return value;
              }
            });
    try {
      chat.setup();
      chat.allocate();
      return chat;
    } catch (Exception e) {
      chat.close();
      throw e;
    }
  }
}
