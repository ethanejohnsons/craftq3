package dev.bluevista.craftq3.botlib.chat;

import dev.bluevista.craftq3.botlib.script.ScriptSources;
import dev.bluevista.craftq3.botlib.script.SourceLocation;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable script data. Reply and match patterns are retained without executing bot decisions. */
public record ChatLibrary(
    Map<String, List<Template>> randomLists,
    List<SynonymGroup> synonyms,
    List<MatchRule> matches,
    List<ReplyRule> replies) {
  public ChatLibrary {
    var copy = new LinkedHashMap<String, List<Template>>();
    randomLists.forEach((name, templates) -> copy.put(name, List.copyOf(templates)));
    randomLists = Collections.unmodifiableMap(copy);
    synonyms = List.copyOf(synonyms);
    matches = List.copyOf(matches);
    replies = List.copyOf(replies);
  }

  public sealed interface Part permits Text, Variable, Reference {}

  public record Text(String value) implements Part {
    public Text {
      Objects.requireNonNull(value);
    }
  }

  public record Variable(int index) implements Part {
    public Variable {
      if (index < 0 || index >= 8) throw new IllegalArgumentException("Chat variable outside 0..7");
    }
  }

  public record Reference(String name) implements Part {
    public Reference {
      Objects.requireNonNull(name);
    }
  }

  public record Template(List<Part> parts, SourceLocation location) {
    public Template {
      parts = List.copyOf(parts);
      Objects.requireNonNull(location);
    }
  }

  public record Synonym(String text, float weight) {
    public Synonym {
      Objects.requireNonNull(text);
      if (text.isEmpty() || !Float.isFinite(weight) || weight < 0)
        throw new IllegalArgumentException("Invalid synonym");
    }
  }

  public record SynonymGroup(int context, List<Synonym> entries) {
    public SynonymGroup {
      entries = List.copyOf(entries);
      if (entries.isEmpty()) throw new IllegalArgumentException("Empty synonym group");
    }
  }

  public sealed interface MatchPart permits Alternatives, Capture {}

  public record Alternatives(List<String> texts) implements MatchPart {
    public Alternatives {
      texts = List.copyOf(texts);
    }
  }

  public record Capture(int index) implements MatchPart {
    public Capture {
      if (index < 0 || index >= 8) throw new IllegalArgumentException("Chat capture outside 0..7");
    }
  }

  public record MatchRule(
      int context, List<MatchPart> parts, int type, int subtype, SourceLocation location) {
    public MatchRule {
      parts = List.copyOf(parts);
    }
  }

  public enum Constraint {
    ANY,
    REQUIRED,
    FORBIDDEN
  }

  public sealed interface ReplyKey permits WordKey, PatternKey, SpecialKey {}

  public record WordKey(String text) implements ReplyKey {}

  public record PatternKey(List<MatchPart> parts) implements ReplyKey {
    public PatternKey {
      parts = List.copyOf(parts);
    }
  }

  public record SpecialKey(String name) implements ReplyKey {}

  public record ReplyCondition(Constraint constraint, ReplyKey key) {}

  public record ReplyRule(
      List<ReplyCondition> conditions,
      float priority,
      List<Template> messages,
      SourceLocation location) {
    public ReplyRule {
      conditions = List.copyOf(conditions);
      messages = List.copyOf(messages);
    }
  }

  public record Definition(String name, Map<String, List<Template>> types) {
    public Definition {
      var copy = new LinkedHashMap<String, List<Template>>();
      types.forEach((type, templates) -> copy.put(type, List.copyOf(templates)));
      types = Collections.unmodifiableMap(copy);
    }
  }

  public static ChatLibrary load(ScriptSources sources) throws IOException {
    return ChatParser.library(sources);
  }

  public static List<Definition> loadChats(ScriptSources sources, String path) throws IOException {
    return ChatParser.chats(sources, path);
  }
}
