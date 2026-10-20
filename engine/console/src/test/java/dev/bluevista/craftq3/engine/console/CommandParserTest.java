package dev.bluevista.craftq3.engine.console;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class CommandParserTest {
  // Ported from legacy EngineServicesTest.
  @Test
  void adjacentQuotesStartNewTokensIncludingAnUnmatchedEmptyTail() {
    var command = CommandParser.tokenize("cs 100 \"ab\"c\"");
    assertEquals(List.of("cs", "100", "ab", "c", ""), command.tokens());
    assertEquals("ab c ", command.argsFrom(2));
    assertEquals(
        List.of("cs", "100", "ab", "c", "d"),
        CommandParser.tokenize("cs 100 \"ab\"c\"d\"").tokens());
  }

  // Ported from legacy EngineServicesTest.
  @Test
  void reliableCommandTokenizationPreservesQuotedNewlinesWithoutExecutingSeparators() {
    var command = CommandParser.tokenize("print \"hello; echo unsafe\nworld\"\n");
    assertEquals(List.of("print", "hello; echo unsafe\nworld"), command.tokens());
    assertEquals(List.of("cs", "5"), CommandParser.tokenize("cs /* note */ 5 // tail").tokens());
    assertEquals(List.of("foo;bar", "baz"), CommandParser.tokenize("foo;bar baz").tokens());
    assertEquals(2, CommandParser.parse("echo a; echo b").size());
  }

  // Ported from legacy EngineServicesTest (first half of
  // commandsRespectQuotesCommentsInsertionAndWait).
  @Test
  void lineSplittingRespectsQuotesAndComments() {
    var parsed =
        CommandParser.parse(
            "seta name \"one;two // three\"; /* ignored; */ echo yes // no\necho end");
    assertEquals(3, parsed.size());
    assertEquals("one;two // three", parsed.getFirst().argv(2));
    assertEquals(List.of("echo", "yes"), parsed.get(1).tokens());
    assertEquals(List.of("echo", "end"), parsed.get(2).tokens());
  }

  @Test
  void plainTokensEndAtCommentsAndQuotesLikeTheOriginal() {
    // Unquoted URLs are cut at "//", a known Quake III behaviour.
    assertEquals(List.of("say", "http:"), CommandParser.tokenize("say http://example").tokens());
    assertEquals(List.of("say", "a", "b"), CommandParser.tokenize("say a/*x*/b").tokens());
    assertEquals(List.of("say", "a", "b c"), CommandParser.tokenize("say a\"b c\"").tokens());
    assertEquals(List.of("a/b"), CommandParser.tokenize("a/b").tokens());
  }

  @Test
  void unterminatedBlockCommentEndsTokenizing() {
    assertEquals(List.of("echo"), CommandParser.tokenize("echo /* open").tokens());
    assertEquals(List.of(), CommandParser.tokenize("// only a comment").tokens());
    assertEquals(List.of(), CommandParser.tokenize("   \t ").tokens());
  }

  @Test
  void blockCommentsMaySpanLinesButLineCommentsDoNot() {
    var parsed = CommandParser.parse("echo a /* one\n two; */ b\necho c // x; echo d\necho e");
    assertEquals(
        List.of(List.of("echo", "a", "b"), List.of("echo", "c"), List.of("echo", "e")),
        parsed.stream().map(CommandArgs::tokens).toList());
  }

  @Test
  void quotesAreResetAtNewlinesWhenSplitting() {
    var parsed = CommandParser.parse("echo \"open\necho next; echo last");
    assertEquals(3, parsed.size());
    assertEquals(List.of("echo", "open"), parsed.getFirst().tokens());
  }

  @Test
  void cmdArgsJoinsTokensWithSingleSpaces() {
    var command = CommandParser.tokenize("  bind   g  \"echo hello; echo world\"  ");
    assertEquals(3, command.argc());
    assertEquals("g echo hello; echo world", command.args());
    assertEquals("", command.argv(9));
    assertEquals("", command.argv(-1));
  }

  @Test
  void tokenAndLengthLimitsTruncateInsteadOfFailing() {
    String many = "x ".repeat(CommandParser.MAX_TOKENS + 10);
    assertEquals(CommandParser.MAX_TOKENS, CommandParser.tokenize(many).argc());
    String longText = "echo " + "y".repeat(CommandParser.MAX_TOKENIZE * 2);
    assertEquals(CommandParser.MAX_TOKENIZE - 1, CommandParser.tokenize(longText).text().length());
    String longLine = "echo " + "z".repeat(CommandParser.MAX_LINE * 2);
    var parsed = CommandParser.parse(longLine + "\necho after");
    assertEquals(CommandParser.MAX_LINE - 1, parsed.getFirst().text().length());
    assertEquals("after", parsed.get(1).argv(1));
  }

  @Test
  void nulEndsTheTextLikeACString() {
    assertEquals(List.of("echo", "x"), CommandParser.tokenize("echo x\0 hidden").tokens());
    assertEquals(1, CommandParser.parse("echo x\0; quit").size());
  }
}
