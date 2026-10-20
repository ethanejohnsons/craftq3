package dev.bluevista.craftq3.botlib.script;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ScriptSourcesTest {
  @Test
  void unrecognizedPunctuationDoesNotPublishAPartialTokenList() {
    var error =
        assertThrows(
            ScriptException.class, () -> ScriptLexer.tokenize("fixture.c", "name >>= ## -> @"));
    assertEquals(16, error.location().column());
  }

  @Test
  void numericStringAndLiteralFieldsFollowThePcAbi() throws Exception {
    var t =
        ScriptLexer.tokenize(
            "fixture.c", "\"a\\n\" 'x' 0xffUL 075 09 0.25 12.5 1e2 name >>= ## ->");
    assertEquals(
        new ScriptToken(1, 4, 0, 0, "a\n", new SourceLocation("fixture.c", 1, 1)), t.get(0));
    assertEquals(ScriptToken.LITERAL, t.get(1).type());
    assertEquals('x', t.get(1).subtype());
    assertEquals("'x'", t.get(1).text());
    assertEquals(255, t.get(2).intValue());
    assertEquals("0xff", t.get(2).text());
    assertEquals(
        ScriptToken.HEX | ScriptToken.INTEGER | ScriptToken.UNSIGNED | ScriptToken.LONG,
        t.get(2).subtype());
    assertEquals(61, t.get(3).intValue());
    assertEquals(9, t.get(4).intValue());
    assertEquals(.25f, t.get(5).floatValue());
    assertEquals(ScriptToken.OCTAL | ScriptToken.FLOAT, t.get(5).subtype());
    assertEquals(12, t.get(6).intValue());
    assertEquals(100, t.get(7).floatValue());
    assertEquals(4, t.get(8).subtype());
    assertEquals(List.of(1, 4, 23), t.subList(9, 12).stream().map(ScriptToken::subtype).toList());
  }

  @Test
  void decimalTokenFieldsPreserveOriginalPerDigitFloatRounding() throws Exception {
    var tokens = ScriptLexer.tokenize("decimal.c", "0.45 0.65 1.2345");
    assertEquals(
        Float.floatToRawIntBits(0.45000002f), Float.floatToRawIntBits(tokens.get(0).floatValue()));
    assertEquals(
        Float.floatToRawIntBits(0.65000004f), Float.floatToRawIntBits(tokens.get(1).floatValue()));
    assertEquals(
        Float.floatToRawIntBits(1.23449993f), Float.floatToRawIntBits(tokens.get(2).floatValue()));
  }

  @Test
  void malformedTokensFailWithPhysicalSourcePositions() {
    var bad =
        assertThrows(
            ScriptException.class,
            () -> ScriptLexer.tokenize("bad.c", "// first\r\n\r\n\"unfinished"));
    assertEquals(new SourceLocation("bad.c", 3, 1), bad.location());
    assertThrows(ScriptException.class, () -> ScriptLexer.tokenize("bad.c", "\"\\z\""));
    assertThrows(ScriptException.class, () -> ScriptLexer.tokenize("bad.c", "/* unfinished"));
    assertThrows(ScriptException.class, () -> ScriptLexer.tokenize("bad.c", "4294967296"));
    assertThrows(ScriptException.class, () -> ScriptLexer.tokenize("bad.c", "@"));
  }

  @Test
  void functionMacrosRescanAcrossObjectAliasesAndNestedArguments() throws Exception {
    var fs =
        memory(
            Map.of(
                "main.c",
                """
        #define ADD(a,b) ((a)+(b))
        #define CALL ADD
        #define TWICE(x) ADD(x,x)
        #define VALUE 3
        $evalint(CALL(2,VALUE)*2)
        $evalint(TWICE(TWICE(2)))
        CALL
        (4,5)
        """));
    try (var sources = new ScriptSources(fs)) {
      var tokens = all(sources, sources.load("main.c"));
      assertEquals(10, tokens.get(0).intValue());
      assertEquals(8, tokens.get(1).intValue());
      assertEquals(
          List.of("(", "(", "4", ")", "+", "(", "5", ")", ")"),
          tokens.subList(2, tokens.size()).stream().map(ScriptToken::text).toList());
    }
  }

  @Test
  void stringificationTokenPasteAndEmptyArgumentsKeepTheirDistinctExpansionRules()
      throws Exception {
    var fs =
        memory(
            Map.of(
                "main.c",
                """
        #define VALUE 9
        #define STR(x) #x
        #define CAT(a,b) a ## b
        #define PREFIX(a,b) before a ## b
        #define item7 21
        STR(VALUE + 1); STR(a+b); CAT(item,7); CAT(,tail); PREFIX(,tail);
        """));
    try (var sources = new ScriptSources(fs)) {
      assertEquals(
          List.of("VALUE + 1", ";", "a+b", ";", "21", ";", "tail", ";", "before", "tail", ";"),
          texts(sources, sources.load("main.c")));
    }
  }

  @Test
  void conditionsUsePrecedenceDefinedAndLazyBranches() throws Exception {
    var fs =
        memory(
            Map.of(
                "main.c",
                """
        #define ENABLED 1
        #if defined(ENABLED) && (3 + 4 * 2 == 11) && (0 ? 1/0 : 1)
        yes
        #elif 1
        wrong
        #else
        wrong
        #endif
        #if 0 && 1/0
        wrong
        #elif !defined MISSING && (1 || 1/0)
        also
        #endif
        #ifndef MISSING
        #if 0
        #error unreachable
        #include "missing.h"
        #endif
        final
        #endif
        """));
    try (var sources = new ScriptSources(fs)) {
      assertEquals(List.of("yes", "also", "final"), texts(sources, sources.load("main.c")));
    }
  }

  @Test
  void includeGuardsAndVirtualFallbackPreserveMacroCallLocations() throws Exception {
    var fs =
        memory(
            Map.of(
                "botfiles/bots/main.c", "#include \"shared.h\"\r\nPAIR\r\n__FILE__ __LINE__\r\n",
                "botfiles/shared.h",
                    "#ifndef GUARD\n#define GUARD\n#include \"shared.h\"\n#define PAIR __LINE__ 42\ninside\n#endif\n"));
    try (var sources = new ScriptSources(fs)) {
      int handle = sources.load("bots/main.c");
      var t = all(sources, handle);
      assertEquals(
          List.of("inside", "2", "42", "botfiles/bots/main.c", "3"),
          t.stream().map(ScriptToken::text).toList());
      assertEquals("botfiles/shared.h", t.getFirst().location().path());
      assertEquals(5, t.getFirst().location().line());
      assertEquals(new SourceLocation("botfiles/bots/main.c", 2, 1), t.get(1).location());
      assertEquals(new SourceLocation("botfiles/bots/main.c", 4, 1), sources.location(handle));
    }
  }

  @Test
  void evaluationPreservesIntegerPrecisionAndSeparatesNegativeSign() throws Exception {
    var fs =
        memory(
            Map.of(
                "main.c",
                "$evalint(16777217 + 1) $evalint(-3) $evalfloat(1.234) $evalfloat(0 ? 1/0 : -2.5) $evalint(80 * 0.1)"));
    try (var sources = new ScriptSources(fs)) {
      var tokens = all(sources, sources.load("main.c"));
      assertEquals(16777218, tokens.getFirst().intValue());
      assertEquals(
          List.of("16777218", "-", "3", "1.23", "-", "2.50", "0"),
          tokens.stream().map(ScriptToken::text).toList());
      assertEquals(1.234f, tokens.get(3).floatValue());
      assertEquals(
          ScriptToken.DECIMAL | ScriptToken.FLOAT | ScriptToken.LONG, tokens.get(3).subtype());
    }
  }

  @Test
  void continuationsAndAdjacentStringsAreProcessedBeforePublication() throws Exception {
    var fs =
        memory(
            Map.of(
                "main.c",
                "#define SUM(x) x + \\\r\n 2\r\n$evalint(SUM(3)) \"left\" /* gap */ \"right\"\n"));
    try (var sources = new ScriptSources(fs)) {
      var tokens = all(sources, sources.load("main.c"));
      assertEquals(List.of("5", "leftright"), tokens.stream().map(ScriptToken::text).toList());
      assertEquals(3, tokens.getFirst().location().line());
      assertEquals(11, tokens.get(1).subtype());
    }
  }

  @Test
  void lineSplicingAlsoWorksInsideIdentifiersStringsAndComments() throws Exception {
    var fs =
        memory(
            Map.of(
                "main.c",
                "#define NA\\\nME 7\nNAME \"ab\\\ncd\"; // hidden\\\n still hidden\n__LINE__\n"));
    try (var sources = new ScriptSources(fs)) {
      var tokens = all(sources, sources.load("main.c"));
      assertEquals(List.of("7", "abcd", ";", "6"), tokens.stream().map(ScriptToken::text).toList());
      assertEquals(3, tokens.getFirst().location().line());
    }
  }

  @Test
  void retainedStorageAndGlobalUpdatesFailWithoutLosingExistingSources() throws Exception {
    var limits = new ScriptLimits(64, 128, 5, 100, 8, 8, 2, 4, 32);
    try (var sources =
        new ScriptSources(memory(Map.of("main.c", "a b c", "value.c", "V")), limits)) {
      int first = sources.load("main.c");
      assertThrows(ScriptException.class, () -> sources.load("main.c"));
      assertEquals(1, sources.openCount());
      assertTrue(sources.free(first));
      assertTrue(sources.load("main.c") > first);
      sources.addGlobalDefine("V 1");
      assertThrows(ScriptException.class, () -> sources.addGlobalDefine("V " + "x".repeat(100)));
      assertEquals(List.of("1"), texts(sources, sources.load("value.c")));
    }
  }

  @Test
  void handlesGlobalDefinesAndBorrowedFilesystemHaveSeparateLifetimes() throws Exception {
    var fs = memory(Map.of("main.c", "VERSION"));
    var sources = new ScriptSources(fs);
    sources.addGlobalDefine("VERSION 1");
    int old = sources.load("main.c");
    sources.addGlobalDefine("VERSION 2");
    int newer = sources.load("main.c");
    assertEquals(List.of("1"), texts(sources, old));
    assertEquals(List.of("2"), texts(sources, newer));
    assertTrue(sources.free(old));
    assertFalse(sources.free(old));
    assertThrows(IllegalArgumentException.class, () -> sources.read(old));
    int third = sources.load("main.c");
    assertNotEquals(old, third);
    assertTrue(sources.removeGlobalDefine("VERSION"));
    assertFalse(sources.removeGlobalDefine("VERSION"));
    assertEquals(List.of("VERSION"), texts(sources, sources.load("main.c")));
    sources.close();
    assertFalse(sources.free(third));
    assertThrows(IllegalStateException.class, () -> sources.read(newer));
    assertEquals("VERSION", fs.readText(new VirtualPath("main.c")));
  }

  @Test
  void malformedDirectivesAndUnsafeIncludesFailTransactionally() throws Exception {
    for (String text :
        List.of(
            "#include \"../secret\"",
            "#include \"/tmp/secret\"",
            "#pragma once",
            "#else",
            "#if 1\nhello",
            "#define V(...) x\n",
            "$unknown(1)",
            "$evalint(1/0)")) {
      try (var sources = new ScriptSources(memory(Map.of("bad.c", text)))) {
        assertThrows(ScriptException.class, () -> sources.load("bad.c"), text);
        assertEquals(0, sources.openCount());
      }
    }
  }

  @Test
  void recursiveSourcesAndMacroWorkCannotEscapeBudgets() throws Exception {
    var limits = new ScriptLimits(1000, 8000, 100, 100, 8, 8, 8, 2, 128);
    var fs =
        memory(
            Map.of(
                "cycle.c",
                "#include \"cycle.c\"",
                "macro.c",
                "#define A(x) x x\nA(A(A(A(A(A(1))))))",
                "self.c",
                "#define A B\n#define B A\nA",
                "short.c",
                "a b"));
    try (var sources = new ScriptSources(fs, limits)) {
      assertThrows(ScriptException.class, () -> sources.load("cycle.c"));
      assertThrows(ScriptException.class, () -> sources.load("macro.c"));
      assertEquals(List.of("A"), texts(sources, sources.load("self.c")));
      int second = sources.load("short.c");
      assertThrows(IllegalStateException.class, () -> sources.load("short.c"));
      assertTrue(sources.free(second));
      assertTrue(sources.load("short.c") > second);
    }
  }

  private static List<ScriptToken> all(ScriptSources sources, int handle) {
    var result = new ArrayList<ScriptToken>();
    Optional<ScriptToken> token;
    while ((token = sources.read(handle)).isPresent()) result.add(token.orElseThrow());
    return List.copyOf(result);
  }

  private static List<String> texts(ScriptSources sources, int handle) {
    return all(sources, handle).stream().map(ScriptToken::text).toList();
  }

  private static VirtualFileSystem memory(Map<String, String> files) {
    return new VirtualFileSystem() {
      private boolean closed;

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
        if (closed) throw new IllegalStateException("Fixture VFS was closed");
        String value = files.get(path.value());
        if (value == null) throw new NoSuchFileException(path.value());
        return value.getBytes(StandardCharsets.ISO_8859_1);
      }

      @Override
      public void close() {
        closed = true;
      }
    };
  }
}
