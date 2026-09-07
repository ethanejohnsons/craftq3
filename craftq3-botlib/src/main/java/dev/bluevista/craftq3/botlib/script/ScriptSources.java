package dev.bluevista.craftq3.botlib.script;

import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded PC_* source handles over a borrowed virtual filesystem. Sources preprocess
 * transactionally at load; global define updates affect later loads. Freed handle numbers never
 * become valid again.
 */
public final class ScriptSources implements AutoCloseable {
  private static final class Source {
    final ScriptPreprocessor.Result result;
    int index;
    SourceLocation location;

    Source(ScriptPreprocessor.Result result) {
      this.result = result;
      location = result.tokens().isEmpty() ? result.end() : result.tokens().getFirst().location();
    }
  }

  private final VirtualFileSystem fs;
  private final ScriptLimits limits;
  private final List<String> includeRoots;
  private final Map<Integer, Source> sources = new HashMap<>();
  private final Map<String, ScriptPreprocessor.Macro> globals = new HashMap<>();
  private final Map<String, Integer> globalSizes = new HashMap<>();
  private int nextHandle = 1, retainedTokens, globalBytes;
  private boolean closed;

  public ScriptSources(VirtualFileSystem fs) {
    this(fs, ScriptLimits.DEFAULT);
  }

  public ScriptSources(VirtualFileSystem fs, ScriptLimits limits) {
    this(fs, limits, List.of("botfiles"));
  }

  public ScriptSources(VirtualFileSystem fs, ScriptLimits limits, List<String> includeRoots) {
    this.fs = java.util.Objects.requireNonNull(fs);
    this.limits = java.util.Objects.requireNonNull(limits);
    this.includeRoots = includeRoots.stream().map(root -> new VirtualPath(root).value()).toList();
  }

  public int load(String virtualPath) throws IOException {
    open();
    if (sources.size() >= limits.maxHandles() || nextHandle <= 0)
      throw new IllegalStateException("Script source handle budget exhausted");
    String path = new VirtualPath(virtualPath).value();
    var result = new ScriptPreprocessor(fs, limits, includeRoots, globals).process(path);
    if ((long) retainedTokens + result.tokens().size() > limits.maxTokens())
      throw new ScriptException(
          new SourceLocation(path, 1, 1), "Retained source token budget exceeded");
    int handle = nextHandle++;
    sources.put(handle, new Source(result));
    retainedTokens += result.tokens().size();
    return handle;
  }

  public Optional<ScriptToken> read(int handle) {
    Source source = source(handle);
    if (source.index == source.result.tokens().size()) {
      source.location = source.result.end();
      return Optional.empty();
    }
    ScriptToken token = source.result.tokens().get(source.index++);
    source.location = token.location();
    return Optional.of(token);
  }

  public SourceLocation location(int handle) {
    return source(handle).location;
  }

  public boolean free(int handle) {
    if (closed) return false;
    Source source = sources.remove(handle);
    if (source == null) return false;
    retainedTokens -= source.result.tokens().size();
    return true;
  }

  public int openCount() {
    open();
    return sources.size();
  }

  public void addGlobalDefine(String text) throws ScriptException {
    open();
    var location = new SourceLocation("<global>", 1, 1);
    if (text == null || text.length() > limits.maxFileBytes())
      throw new ScriptException(location, "Global define byte budget exceeded");
    var tokens = ScriptLexer.lex(location.path(), text, limits);
    if (tokens.size() >= 2
        && tokens.getFirst().text().equals("#")
        && tokens.get(1).text().equals("define")) tokens = tokens.subList(2, tokens.size());
    if (!tokens.isEmpty() && tokens.getFirst().logicalLine() != tokens.getLast().logicalLine())
      throw new ScriptException(location, "Global define must occupy one logical line");
    var macro = ScriptPreprocessor.definition(tokens, location, limits);
    long bytes = (long) globalBytes - globalSizes.getOrDefault(macro.name(), 0) + text.length();
    if (bytes > limits.maxFileBytes()
        || !globals.containsKey(macro.name()) && globals.size() >= limits.maxMacros())
      throw new ScriptException(location, "Global define storage budget exceeded");
    globals.put(macro.name(), macro);
    globalSizes.put(macro.name(), text.length());
    globalBytes = (int) bytes;
  }

  public boolean removeGlobalDefine(String name) {
    open();
    if (globals.remove(name) == null) return false;
    globalBytes -= globalSizes.remove(name);
    return true;
  }

  public void clearGlobalDefines() {
    open();
    globals.clear();
    globalSizes.clear();
    globalBytes = 0;
  }

  private Source source(int handle) {
    open();
    Source source = sources.get(handle);
    if (source == null)
      throw new IllegalArgumentException("Invalid or freed script handle " + handle);
    return source;
  }

  private void open() {
    if (closed) throw new IllegalStateException("Script source service is closed");
  }

  @Override
  public void close() {
    sources.clear();
    globals.clear();
    globalSizes.clear();
    retainedTokens = 0;
    globalBytes = 0;
    closed = true;
  }
}
