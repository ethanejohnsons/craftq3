package dev.bluevista.craftq3.botlib.chat;

import static dev.bluevista.craftq3.botlib.chat.ChatLibrary.*;

import dev.bluevista.craftq3.botlib.script.ScriptSources;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.DoubleSupplier;
import java.util.random.RandomGenerator;

/** Bounded original-script initial chat and console queues. The caller owns delivery to clients. */
public final class BotChat implements AutoCloseable {
  public record Limits(
      int maxStates,
      int maxQueuedMessages,
      int maxRetainedCharacters,
      int maxExpansionDepth,
      int maxExpansionParts) {
    public static final Limits DEFAULT = new Limits(64, 1024, 8 * 1024 * 1024, 32, 4096);

    public Limits {
      if (maxStates < 1
          || maxStates > 1024
          || maxQueuedMessages < 1
          || maxRetainedCharacters < 1
          || maxExpansionDepth < 1
          || maxExpansionDepth > 128
          || maxExpansionParts < 1) throw new IllegalArgumentException("Invalid chat limits");
    }
  }

  public record ConsoleMessage(int id, float time, int type, String text) {}

  private static final class State {
    Definition definition;
    String name = "", current = "";
    int client, gender, retained;
    final Map<Template, Double> recent = new HashMap<>();
    final LinkedHashMap<Integer, ConsoleMessage> queue = new LinkedHashMap<>();
  }

  private final ScriptSources sources;
  private final boolean ownsSources;
  private final DoubleSupplier clock;
  private final RandomGenerator random;
  private final Limits limits;
  private final Map<Integer, State> states = new LinkedHashMap<>();
  private final Map<Template, Float> replyRecent = new HashMap<>();
  private ChatLibrary library;
  private ChatMatcher matcher;
  private int nextHandle = 1, nextMessage = 1, queued, retained;
  private boolean closed;

  public BotChat(VirtualFileSystem fs, DoubleSupplier clock, RandomGenerator random) {
    this(new ScriptSources(fs), clock, random, Limits.DEFAULT, true);
  }

  public BotChat(ScriptSources sources, DoubleSupplier clock, RandomGenerator random) {
    this(sources, clock, random, Limits.DEFAULT, false);
  }

  public BotChat(
      ScriptSources sources, DoubleSupplier clock, RandomGenerator random, Limits limits) {
    this(sources, clock, random, limits, false);
  }

  private BotChat(
      ScriptSources sources,
      DoubleSupplier clock,
      RandomGenerator random,
      Limits limits,
      boolean ownsSources) {
    this.sources = Objects.requireNonNull(sources);
    this.clock = Objects.requireNonNull(clock);
    this.random = Objects.requireNonNull(random);
    this.limits = Objects.requireNonNull(limits);
    this.ownsSources = ownsSources;
  }

  public synchronized void setup() throws IOException {
    open();
    if (library == null) {
      var loaded = ChatLibrary.load(sources);
      matcher = new ChatMatcher(loaded.matches());
      library = loaded;
    }
  }

  public synchronized ChatLibrary library() {
    open();
    if (library == null) throw new IllegalStateException("Chat library is not set up");
    return library;
  }

  public synchronized boolean findMatch(String text, int context, ByteBuffer output, int offset) {
    open();
    if (matcher == null) throw new IllegalStateException("Chat library is not set up");
    return matcher.find(text, context, output, offset);
  }

  public synchronized int allocate() {
    open();
    if (states.size() >= limits.maxStates() || nextHandle <= 0) return 0;
    int handle = nextHandle++;
    states.put(handle, new State());
    return handle;
  }

  public synchronized boolean free(int handle) {
    open();
    State state = states.remove(handle);
    if (state == null) return false;
    queued -= state.queue.size();
    retained -= state.retained;
    return true;
  }

  public synchronized boolean load(int handle, String path, String chatName) throws IOException {
    State state = state(handle);
    Definition found =
        ChatLibrary.loadChats(sources, path).stream()
            .filter(d -> d.name().equals(chatName))
            .findFirst()
            .orElse(null);
    if (found == null) return false;
    int size = size(found);
    if ((long) retained - state.retained + size > limits.maxRetainedCharacters())
      throw new IllegalStateException("Retained chat data budget exceeded");
    retained += size - state.retained;
    state.retained = size;
    state.definition = found;
    state.recent.clear();
    return true;
  }

  public synchronized void setName(int handle, String name, int client) {
    State state = state(handle);
    state.name = bounded(name);
    state.client = client;
  }

  public synchronized void setGender(int handle, int gender) {
    if (gender < 0 || gender > 2) throw new IllegalArgumentException("Invalid chat gender");
    state(handle).gender = gender;
  }

  public synchronized String name(int handle) {
    return state(handle).name;
  }

  public synchronized int client(int handle) {
    return state(handle).client;
  }

  public synchronized int gender(int handle) {
    return state(handle).gender;
  }

  public synchronized int initialCount(int handle, String type) {
    return templates(state(handle), type).size();
  }

  /**
   * Unknown/empty types preserve the current message. Variables beyond the supplied list are empty.
   */
  public synchronized boolean initial(
      int handle, String type, int context, List<String> variables) {
    State state = state(handle);
    var choices = templates(state, type);
    if (choices.isEmpty()) return false;
    if (variables.size() > 8) throw new IllegalArgumentException("At most eight chat variables");
    library();
    double now = time();
    var eligible = new ArrayList<Template>();
    for (int i = choices.size() - 1; i >= 0; i--)
      if (state.recent.getOrDefault(choices.get(i), Double.NEGATIVE_INFINITY) + 20 <= now)
        eligible.add(choices.get(i));
    Template selected;
    if (eligible.isEmpty()) {
      selected = choices.getLast();
      for (int i = choices.size() - 2; i >= 0; i--)
        if (state.recent.getOrDefault(choices.get(i), Double.NEGATIVE_INFINITY)
            < state.recent.getOrDefault(selected, Double.NEGATIVE_INFINITY))
          selected = choices.get(i);
    } else selected = eligible.get(index(eligible.size()));
    String expanded = expand(selected, variables, context);
    if (expanded.length() >= 256)
      throw new IllegalStateException("Expanded chat exceeds 255 bytes");
    state.current = expanded;
    state.recent.put(selected, now);
    return true;
  }

  /** Length includes protection markers until the message is retrieved, as in the original API. */
  public synchronized int length(int handle) {
    return state(handle).current.length();
  }

  public synchronized String message(int handle) {
    return stripProtection(state(handle).current);
  }

  public synchronized String takeMessage(int handle) {
    State state = state(handle);
    String result = stripProtection(state.current);
    state.current = "";
    return result;
  }

  public synchronized boolean queue(int handle, int type, String text) {
    State state = state(handle);
    if (queued >= limits.maxQueuedMessages() || nextMessage <= 0) return false;
    int id = nextMessage++;
    state.queue.put(id, new ConsoleMessage(id, (float) time(), type, bounded(text)));
    queued++;
    return true;
  }

  public synchronized Optional<ConsoleMessage> next(int handle) {
    return state(handle).queue.values().stream().findFirst();
  }

  public synchronized boolean remove(int handle, int id) {
    if (state(handle).queue.remove(id) == null) return false;
    queued--;
    return true;
  }

  public synchronized int queued(int handle) {
    return state(handle).queue.size();
  }

  public synchronized String replaceSynonyms(String text, int context) {
    library();
    return synonyms(bounded(text), context, false);
  }

  /** ASCII case folding matches the engine's byte-string search, including an empty needle. */
  public static int stringContains(String text, String needle, boolean caseSensitive) {
    Objects.requireNonNull(text);
    Objects.requireNonNull(needle);
    if (text.length() > 1_048_576 || needle.length() > 1_048_576)
      throw new IllegalArgumentException("Chat search text budget exceeded");
    if (caseSensitive) return text.indexOf(needle);
    for (int at = 0; at <= text.length() - needle.length(); at++) {
      int i = 0;
      while (i < needle.length() && asciiLower(text.charAt(at + i)) == asciiLower(needle.charAt(i)))
        i++;
      if (i == needle.length()) return at;
    }
    return -1;
  }

  /** Byte-text normalization, including the observed skipped spans after whitespace removal. */
  public static String unifyWhiteSpaces(String text) {
    Objects.requireNonNull(text);
    if (text.length() > 1_048_576)
      throw new IllegalArgumentException("Chat normalization text budget exceeded");
    int zero = text.indexOf(0);
    if (zero >= 0) text = text.substring(0, zero);
    for (int i = 0; i < text.length(); i++)
      if (text.charAt(i) > 255)
        throw new IllegalArgumentException("Chat normalization requires byte text");
    int at = 0;
    while (at < text.length() && chatWhitespace(text.charAt(at))) at++;
    int unchanged = at;
    var result = new StringBuilder(text.length() - at);
    while (at < text.length()) {
      if (unchanged > 0) {
        int end = Math.min(text.length(), at + unchanged);
        result.append(text, at, end);
        at = end;
        unchanged = 0;
      } else if (chatWhitespace(text.charAt(at))) {
        int end = at + 1;
        while (end < text.length() && chatWhitespace(text.charAt(end))) end++;
        if (end < text.length()) result.append(' ');
        // Removing a run leaves this many following bytes unnormalized in the native API.
        unchanged = end - at - 1;
        at = end;
      } else result.append(text.charAt(at++));
    }
    return result.toString();
  }

  private static char asciiLower(char c) {
    return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
  }

  private static boolean chatWhitespace(char c) {
    return c <= 38 || c == 42 || c == 59 || c == 60 || c == 62 || c == 64 || c == 92 || c == 94
        || c == 96 || c >= 123;
  }

  /** Original reply priorities and predicates; unmatched input preserves the current message. */
  public synchronized boolean reply(
      int handle, String text, int messageContext, int variableContext, List<String> variables) {
    State state = state(handle);
    library();
    if (variables.size() > 8) throw new IllegalArgumentException("At most eight chat variables");
    float now = (float) time();
    var selected =
        ChatReplies.select(
            library.replies(),
            bounded(text),
            state.name,
            state.gender,
            choices -> {
              int eligible = 0;
              for (var choice : choices)
                if (replyRecent.getOrDefault(choice, Float.NEGATIVE_INFINITY) <= now) eligible++;
              // Native selection uses the eligible count but indexes the unfiltered reversed list.
              return (int) (unit() * eligible);
            });
    if (selected == null) return false;
    var values = new ArrayList<>(selected.variables());
    for (int i = 0; i < 8; i++) {
      String value = i < variables.size() ? variables.get(i) : null;
      if (value != null) values.set(i, bounded(value));
      values.set(
          i, ChatReplyVariables.normalize(values.get(i), library.synonyms(), variableContext));
    }
    state.current = expand(selected.template(), values, messageContext);
    replyRecent.put(selected.template(), now + 20);
    return true;
  }

  private String expand(Template template, List<String> variables, int context) {
    List<Part> current = template.parts();
    int parts = 0;
    for (int pass = 0; pass < limits.maxExpansionDepth(); pass++) {
      var out = new StringBuilder();
      var deferred = new ArrayList<Part>();
      boolean expandedReference = false;
      for (Part part : current) {
        if (++parts > limits.maxExpansionParts())
          throw new IllegalStateException("Chat expansion part budget exceeded");
        if (part instanceof Text text) appendExpansionText(out, text.value());
        else if (part instanceof Variable variable) {
          if (variable.index() < variables.size() && variables.get(variable.index()) != null)
            appendExpansionText(out, bounded(variables.get(variable.index())));
        } else {
          String name = ((Reference) part).name();
          var choices = library.randomLists().get(name);
          if (choices == null || choices.isEmpty())
            throw new IllegalStateException(
                "Unknown/empty chat random list " + name + " at " + template.location());
          expandedReference = true;
          var selected = choices.get(choices.size() - 1 - index(choices.size()));
          for (Part nested : selected.parts()) {
            if (++parts > limits.maxExpansionParts())
              throw new IllegalStateException("Chat expansion part budget exceeded");
            if (nested instanceof Text text) appendExpansionText(out, text.value());
            else {
              out.append('\uffff').append(deferred.size()).append('\uffff');
              deferred.add(nested);
            }
          }
        }
        if (out.length() >= 256)
          throw new IllegalStateException(
              "Expanded chat exceeds 255 bytes at " + template.location());
      }
      // A pass expands one level, then substitutes synonyms across the whole intermediate
      // message. Native probes include one final pass after the last random reference.
      String expanded = synonyms(out.toString(), context, true);
      if (!expandedReference) return expanded;
      if (pass == 9) {
        // Original botlib returns the unresolved encoded message after ten passes.
        for (int i = 0; i < deferred.size(); i++) {
          Part pending = deferred.get(i);
          String encoded =
              pending instanceof Reference reference
                  ? "\u0001r" + reference.name() + "\u0001"
                  : "\u0001v" + ((Variable) pending).index() + "\u0001";
          expanded = expanded.replace("\uffff" + i + "\uffff", encoded);
        }
        if (expanded.length() >= 256)
          throw new IllegalStateException("Expanded chat exceeds 255 bytes");
        return expanded;
      }
      var next = new ArrayList<Part>();
      int at = 0;
      while (at < expanded.length()) {
        int marker = expanded.indexOf('\uffff', at);
        if (marker < 0) {
          next.add(new Text(expanded.substring(at)));
          break;
        }
        if (marker > at) next.add(new Text(expanded.substring(at, marker)));
        int end = expanded.indexOf('\uffff', marker + 1);
        if (end < 0) throw new IllegalStateException("Corrupted deferred chat expansion");
        int index = Integer.parseInt(expanded.substring(marker + 1, end));
        next.add(deferred.get(index));
        at = end + 1;
      }
      current = next;
    }
    throw new IllegalStateException("Chat expansion depth exceeded at " + template.location());
  }

  private static void appendExpansionText(StringBuilder out, String text) {
    // This non-byte marker is reserved internally and cannot occur in original byte-valued text.
    if (text.indexOf('\uffff') >= 0)
      throw new IllegalStateException("Chat text contains the reserved expansion marker");
    out.append(text);
  }

  private String synonyms(String text, int context, boolean weighted) {
    return ChatSynonyms.apply(text, library.synonyms(), context, weighted, this::unit);
  }

  private static String stripProtection(String text) {
    var result = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '~') {
        if (++i == text.length()) break;
      }
      result.append(text.charAt(i));
    }
    return result.toString();
  }

  private int index(int size) {
    return Math.min(size - 1, (int) (unit() * size));
  }

  private float unit() {
    float value = random.nextFloat();
    if (!Float.isFinite(value) || value < 0 || value >= 1)
      throw new IllegalStateException("Chat RNG outside [0,1)");
    return value;
  }

  private double time() {
    double value = clock.getAsDouble();
    if (!Double.isFinite(value) || Math.abs(value) > Float.MAX_VALUE)
      throw new IllegalStateException("Invalid chat clock");
    return value;
  }

  private static List<Template> templates(State state, String type) {
    return state.definition == null
        ? List.of()
        : state.definition.types().getOrDefault(type, List.of());
  }

  private static int size(Definition definition) {
    long size = definition.name().length();
    for (var entry : definition.types().entrySet()) {
      size += entry.getKey().length();
      for (var template : entry.getValue())
        for (Part part : template.parts())
          size +=
              part instanceof Text text
                  ? text.value().length()
                  : part instanceof Reference reference ? reference.name().length() : 1;
    }
    return Math.toIntExact(size);
  }

  private static String bounded(String text) {
    Objects.requireNonNull(text);
    int nul = text.indexOf(0);
    if (nul >= 0) text = text.substring(0, nul);
    return text.substring(0, Math.min(255, text.length()));
  }

  private State state(int handle) {
    open();
    State state = states.get(handle);
    if (state == null) throw new IllegalArgumentException("Invalid chat handle " + handle);
    return state;
  }

  private void open() {
    if (closed) throw new IllegalStateException("Chat service is closed");
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    states.clear();
    replyRecent.clear();
    library = null;
    matcher = null;
    retained = queued = 0;
    closed = true;
    if (ownsSources) sources.close();
  }
}
