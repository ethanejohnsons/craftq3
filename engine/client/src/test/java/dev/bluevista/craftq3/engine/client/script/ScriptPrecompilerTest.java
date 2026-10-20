package dev.bluevista.craftq3.engine.client.script;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ScriptPrecompilerTest {
  private static List<String> tokens(String text, Map<String, String> files, List<String> globals) {
    ScriptPrecompiler source =
        new ScriptPrecompiler("test.menu", text, path -> Optional.ofNullable(files.get(path)), globals);
    List<String> out = new ArrayList<>();
    for (ScriptToken token = source.next(); token != null; token = source.next())
      out.add(token.pcText());
    return out;
  }

  private static List<String> tokens(String text) {
    return tokens(text, Map.of(), List.of());
  }

  @Test
  void lexesMenuSyntaxWithCommentsAndJoinsAdjacentStrings() {
    assertEquals(
        List.of("itemDef", "{", "name", "a b", "rect", "0", "-", "1.5", "}"),
        tokens("itemDef { // comment\n name \"a\" /* x */ \" b\" rect 0 -1.5 }"));
    assertEquals(
        List.of("cvarStrList", "{", "Low", ",", "0", "}"),
        tokens("cvarStrList { \"Low\", \"0\" }"));
  }

  @Test
  void classifiesNumbersAndPunctuation() {
    ScriptLexer lexer = new ScriptLexer("t", "0x1F 017 0b101 12 3.25 .5 >>= \\");
    ScriptToken hex = lexer.next();
    assertEquals(31, hex.intValue());
    assertEquals(ScriptToken.TT_HEX | ScriptToken.TT_INTEGER, hex.subtype());
    assertEquals(15, lexer.next().intValue());
    assertEquals(5, lexer.next().intValue());
    ScriptToken decimal = lexer.next();
    assertEquals(ScriptToken.TT_DECIMAL | ScriptToken.TT_INTEGER, decimal.subtype());
    ScriptToken real = lexer.next();
    assertEquals(3.25, real.floatValue());
    assertEquals(ScriptToken.TT_DECIMAL | ScriptToken.TT_FLOAT, real.subtype());
    assertEquals(0.5, lexer.next().floatValue());
    ScriptToken shift = lexer.next();
    assertEquals(ScriptToken.TT_PUNCTUATION, shift.type());
    assertEquals(1, shift.subtype(), "P_RSHIFT_ASSIGN");
    assertEquals("\\", lexer.next().text());
    assertNull(lexer.next());
  }

  @Test
  void expandsObjectAndFunctionMacrosWithStringizingAndPasting() {
    String text =
        "#define STYLE 3\n"
            + "#define RECT(x, y) rect x y 10 10\n"
            + "#define NAME(a) #a\n"
            + "#define JOIN(a, b) a ## b\n"
            + "style STYLE RECT(1, 2) NAME(hello) JOIN(item, Def)";
    assertEquals(
        List.of("style", "3", "rect", "1", "2", "10", "10", "hello", "itemDef"), tokens(text));
  }

  @Test
  void includesFilesAndHonoursConditionals() {
    Map<String, String> files =
        Map.of("ui/menudef.h", "#define ITEM_TYPE_TEXT 0\n#define WINDOW_STYLE_FILLED 1\n");
    String text =
        "#include \"ui/menudef.h\"\n"
            + "#ifdef MISSIONPACK\n team\n#else\n solo\n#endif\n"
            + "#if WINDOW_STYLE_FILLED == 1 && !defined(NOPE)\n yes\n#elif 1\n no\n#endif\n"
            + "#ifndef ITEM_TYPE_TEXT\n hidden\n#endif\n"
            + "ITEM_TYPE_TEXT";
    assertEquals(List.of("solo", "yes", "0"), tokens(text, files, List.of()));
    assertEquals(
        List.of("team", "yes", "0"), tokens(text, files, List.of("MISSIONPACK 1")));
  }

  @Test
  void evaluatesExpressions() {
    assertEquals(List.of("14"), tokens("$evalint(2 + 3 * 4)"));
    assertEquals(List.of("7", "2.5"), tokens("#define A 7\nA $evalfloat(5 / 2.0)"));
  }

  @Test
  void errorsEndTheScript() {
    ScriptSources sources =
        new ScriptSources(
            path -> Optional.of(path.equals("bad.menu") ? "a\n#error broken\nb" : ""),
            line -> {});
    int handle = sources.load("bad.menu");
    assertTrue(handle > 0);
    assertEquals("a", sources.next(handle).orElseThrow().pcText());
    assertTrue(sources.next(handle).isEmpty());
    assertTrue(sources.next(handle).isEmpty());
    assertEquals(1, sources.free(handle));
    assertEquals(0, sources.free(handle));
    assertEquals(0, new ScriptSources(path -> Optional.empty(), line -> {}).load("missing"));
  }
}
