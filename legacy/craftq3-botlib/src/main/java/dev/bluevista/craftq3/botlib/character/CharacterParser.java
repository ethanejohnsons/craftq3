package dev.bluevista.craftq3.botlib.character;

import dev.bluevista.craftq3.botlib.character.BotCharacter.*;
import dev.bluevista.craftq3.botlib.script.ScriptException;
import dev.bluevista.craftq3.botlib.script.ScriptSources;
import dev.bluevista.craftq3.botlib.script.ScriptToken;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Parses only the requested skill block; includes and macros belong to the shared script layer. */
final class CharacterParser {
  private CharacterParser() {}

  static Optional<Map<Integer, Value>> read(ScriptSources sources, String path, int skill)
      throws IOException {
    int source = sources.load(path);
    try {
      int blocks = 0;
      for (var token = sources.read(source); token.isPresent(); token = sources.read(source)) {
        if (++blocks > 64) throw error(sources, source, "Character skill-block budget exceeded");
        if (token.get().type() != ScriptToken.NAME || !token.get().text().equals("skill"))
          throw error(sources, source, "Expected skill block");
        ScriptToken level = next(sources, source);
        if (!integer(level)) throw error(sources, source, "Expected integer skill level");
        expect(sources, source, "{");
        if (level.intValue() != skill) {
          int nesting = 1;
          while (nesting != 0) {
            ScriptToken skipped = next(sources, source);
            if (symbol(skipped, "{")) nesting++;
            if (symbol(skipped, "}")) nesting--;
            if (nesting > 32) throw error(sources, source, "Character brace nesting exceeded");
          }
          continue;
        }
        var values = new HashMap<Integer, Value>();
        while (true) {
          ScriptToken index = next(sources, source);
          if (symbol(index, "}")) return Optional.of(Map.copyOf(values));
          if (!integer(index)
              || index.intValue() < 0
              || index.intValue() >= BotCharacters.MAX_CHARACTERISTICS)
            throw error(sources, source, "Characteristic index must be in [0, 79]");
          if (values.containsKey(index.intValue()))
            throw error(sources, source, "Duplicate characteristic index " + index.intValue());
          ScriptToken value = next(sources, source);
          Value parsed;
          if (value.type() == ScriptToken.STRING) parsed = new StringValue(value.text());
          else if (value.type() == ScriptToken.NUMBER && (value.subtype() & ScriptToken.FLOAT) != 0)
            parsed = new FloatValue(value.floatValue());
          else if (integer(value)) parsed = new IntegerValue(value.intValue());
          else throw error(sources, source, "Expected integer, float or string characteristic");
          values.put(index.intValue(), parsed);
        }
      }
      return Optional.empty();
    } finally {
      sources.free(source);
    }
  }

  private static boolean integer(ScriptToken token) {
    return token.type() == ScriptToken.NUMBER && (token.subtype() & ScriptToken.INTEGER) != 0;
  }

  private static ScriptToken next(ScriptSources sources, int source) throws ScriptException {
    return sources
        .read(source)
        .orElseThrow(() -> error(sources, source, "Unexpected end of character file"));
  }

  private static void expect(ScriptSources sources, int source, String text)
      throws ScriptException {
    if (!symbol(next(sources, source), text))
      throw error(sources, source, "Expected '" + text + "'");
  }

  private static boolean symbol(ScriptToken token, String text) {
    return token.type() == ScriptToken.PUNCTUATION && token.text().equals(text);
  }

  private static ScriptException error(ScriptSources sources, int source, String text) {
    return new ScriptException(sources.location(source), text);
  }
}
