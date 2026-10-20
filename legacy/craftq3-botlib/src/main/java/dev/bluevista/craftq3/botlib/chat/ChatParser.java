package dev.bluevista.craftq3.botlib.chat;

import static dev.bluevista.craftq3.botlib.chat.ChatLibrary.*;

import dev.bluevista.craftq3.botlib.script.ScriptException;
import dev.bluevista.craftq3.botlib.script.ScriptSources;
import dev.bluevista.craftq3.botlib.script.ScriptToken;
import dev.bluevista.craftq3.botlib.script.SourceLocation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Grammar-specific reader over the bounded virtual preprocessor; each source is always freed. */
final class ChatParser implements AutoCloseable {
  private static final int MAX_NODES = 100_000, MAX_TEXT = 4 * 1024 * 1024;
  private final ScriptSources sources;
  private final int handle;
  private ScriptToken next;
  private int nodes, text;

  private ChatParser(ScriptSources sources, String path) throws IOException {
    this.sources = sources;
    handle = sources.load(path);
    next = sources.read(handle).orElse(null);
  }

  static ChatLibrary library(ScriptSources sources) throws IOException {
    var random = new LinkedHashMap<String, List<Template>>();
    try (var p = new ChatParser(sources, "rnd.c")) {
      while (p.next != null) {
        String name = p.typed(ScriptToken.NAME).text();
        p.require("=");
        random.putIfAbsent(name, p.templates());
      }
    }
    var synonyms = new ArrayList<SynonymGroup>();
    try (var p = new ChatParser(sources, "syn.c")) {
      while (p.next != null) {
        int context = p.integer();
        p.require("{");
        while (!p.accept("}")) {
          p.require("[");
          var entries = new ArrayList<Synonym>();
          do {
            p.require("(");
            String value = p.string();
            p.require(",");
            float weight = p.number();
            p.require(")");
            if (value.isEmpty() || weight < 0) throw p.error("Invalid synonym text/weight");
            entries.add(new Synonym(value, weight));
          } while (p.accept(","));
          p.require("]");
          synonyms.add(new SynonymGroup(context, entries));
        }
      }
    }
    var matches = new ArrayList<MatchRule>();
    try (var p = new ChatParser(sources, "match.c")) {
      while (p.next != null) {
        int context = p.integer();
        p.require("{");
        while (!p.accept("}")) {
          SourceLocation location = p.location();
          var parts = p.pattern("=");
          p.require("=");
          p.require("(");
          int type = p.integer();
          p.require(",");
          int subtype = p.integer();
          p.require(")");
          p.require(";");
          matches.add(new MatchRule(context, parts, type, subtype, location));
        }
      }
    }
    var replies = new ArrayList<ReplyRule>();
    try (var p = new ChatParser(sources, "rchat.c")) {
      while (p.next != null) {
        SourceLocation location = p.location();
        p.require("[");
        var conditions = new ArrayList<ReplyCondition>();
        while (!p.accept("]")) {
          Constraint constraint =
              p.accept("&")
                  ? Constraint.REQUIRED
                  : p.accept("!") ? Constraint.FORBIDDEN : Constraint.ANY;
          ReplyKey key;
          if (p.accept("(")) {
            key = new PatternKey(p.pattern(")"));
            p.require(")");
          } else if (p.next != null && p.next.type() == ScriptToken.STRING)
            key = new WordKey(p.string());
          else {
            String special = p.typed(ScriptToken.NAME).text();
            if (!Set.of("name", "female", "male", "it").contains(special))
              throw p.error("Unsupported reply key " + special);
            key = new SpecialKey(special);
          }
          conditions.add(new ReplyCondition(constraint, key));
          if (!p.is("]")) p.require(",");
        }
        p.require("=");
        if (p.is("-") || p.is("+")) throw p.error("Reply priority must be an unsigned number");
        float priority = p.number();
        replies.add(new ReplyRule(conditions, priority, p.templates(), location));
      }
    }
    return new ChatLibrary(random, synonyms, matches, replies);
  }

  static List<Definition> chats(ScriptSources sources, String path) throws IOException {
    try (var p = new ChatParser(sources, path)) {
      var definitions = new ArrayList<Definition>();
      while (p.next != null) {
        p.require("chat");
        String name = p.string();
        p.require("{");
        var types = new LinkedHashMap<String, List<Template>>();
        while (!p.accept("}")) {
          p.require("type");
          String type = p.string();
          if (type.length() >= 32) throw p.error("Chat type exceeds 31 bytes");
          // Native lookup uses the last type block, while random-list lookup retains the first.
          types.put(type, p.templates());
        }
        definitions.add(new Definition(name, types));
      }
      return List.copyOf(definitions);
    }
  }

  private List<Template> templates() throws ScriptException {
    require("{");
    var templates = new ArrayList<Template>();
    while (!accept("}")) {
      SourceLocation location = location();
      var parts = new ArrayList<Part>();
      do {
        if (next == null) throw error("Unterminated chat template");
        if (next.type() == ScriptToken.STRING) parts.add(new Text(string()));
        else if (next.type() == ScriptToken.NUMBER) parts.add(new Variable(variable()));
        else parts.add(new Reference(typed(ScriptToken.NAME).text()));
      } while (accept(","));
      require(";");
      templates.add(new Template(parts, location));
    }
    return List.copyOf(templates);
  }

  private List<MatchPart> pattern(String end) throws ScriptException {
    var parts = new ArrayList<MatchPart>();
    boolean pendingCapture = false;
    while (!is(end)) {
      if (next == null) throw error("Unterminated match pattern");
      if (next.type() == ScriptToken.NUMBER) {
        if (pendingCapture) throw error("Adjacent chat variables require nonempty separating text");
        parts.add(new Capture(variable()));
        pendingCapture = true;
      } else {
        var alternatives = new ArrayList<String>();
        alternatives.add(string());
        while (accept("|")) alternatives.add(string());
        parts.add(new Alternatives(alternatives));
        if (!alternatives.contains("")) pendingCapture = false;
      }
      if (!is(end)) require(",");
    }
    if (parts.isEmpty()) throw error("Empty match pattern");
    return List.copyOf(parts);
  }

  private int variable() throws ScriptException {
    int value = integer();
    if (value < 0 || value >= 8) throw error("Chat variable outside 0..7");
    return value;
  }

  private String string() throws ScriptException {
    var out = new StringBuilder(typed(ScriptToken.STRING).text());
    while (next != null && next.type() == ScriptToken.STRING) out.append(take().text());
    return out.toString();
  }

  private int integer() throws ScriptException {
    boolean negative = accept("-");
    var token = typed(ScriptToken.NUMBER);
    if ((token.subtype() & ScriptToken.INTEGER) == 0) throw error("Expected integer");
    return negative ? -token.intValue() : token.intValue();
  }

  private float number() throws ScriptException {
    boolean negative = accept("-");
    float value = typed(ScriptToken.NUMBER).floatValue();
    if (!Float.isFinite(value)) throw error("Non-finite chat value");
    return negative ? -value : value;
  }

  private ScriptToken typed(int type) throws ScriptException {
    if (next == null || next.type() != type)
      throw error("Unexpected chat token; expected type " + type);
    return take();
  }

  private ScriptToken take() throws ScriptException {
    if (next == null) throw error("Unexpected end of chat source");
    ScriptToken result = next;
    if (++nodes > MAX_NODES || (text += result.text().length()) > MAX_TEXT)
      throw error("Chat data budget exceeded");
    next = sources.read(handle).orElse(null);
    return result;
  }

  private boolean is(String text) {
    return next != null && next.text().equals(text);
  }

  private boolean accept(String text) throws ScriptException {
    if (!is(text)) return false;
    take();
    return true;
  }

  private void require(String text) throws ScriptException {
    if (!accept(text)) throw error("Expected '" + text + "'");
  }

  private SourceLocation location() {
    return next == null ? sources.location(handle) : next.location();
  }

  private ScriptException error(String message) {
    return new ScriptException(location(), message);
  }

  @Override
  public void close() {
    sources.free(handle);
  }
}
