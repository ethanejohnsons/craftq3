package dev.bluevista.craftq3.botlib.script;

import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Original token-queue expansion and file-local conditional processing, without native code. */
final class ScriptPreprocessor {
  record Macro(String name, List<String> parameters, List<ScriptLexer.Lexeme> body) {
    Macro {
      parameters = parameters == null ? null : List.copyOf(parameters);
      body = List.copyOf(body);
    }
  }

  record Result(List<ScriptToken> tokens, SourceLocation end) {}

  private record Expanded(ScriptLexer.Lexeme value, Set<String> hidden) {}

  private record Conditional(boolean parent, boolean taken, boolean active, boolean sawElse) {}

  private final VirtualFileSystem fs;
  private final ScriptLimits limits;
  private final List<String> includeRoots;
  private final Map<String, Macro> macros;
  private final List<ScriptLexer.Lexeme> output = new ArrayList<>();
  private long totalBytes, rawTokens, steps;
  private int includes;
  private SourceLocation end;

  ScriptPreprocessor(
      VirtualFileSystem fs,
      ScriptLimits limits,
      List<String> includeRoots,
      Map<String, Macro> globals) {
    this.fs = fs;
    this.limits = limits;
    this.includeRoots = includeRoots;
    macros = new HashMap<>(globals);
  }

  Result process(String name) throws IOException {
    SourceLocation requested = new SourceLocation(name, 1, 1);
    VirtualPath path = resolve(name, null, false, requested);
    file(path, 0);
    var tokens = new ArrayList<ScriptToken>();
    for (var value : output) {
      ScriptToken token = value.token();
      if (!tokens.isEmpty()
          && token.type() == ScriptToken.STRING
          && tokens.getLast().type() == ScriptToken.STRING) {
        var prior = tokens.removeLast();
        String text = prior.text() + token.text();
        if (text.length() + 2 > limits.maxTokenChars())
          throw new ScriptException(prior.location(), "Concatenated string exceeds token budget");
        tokens.add(ScriptLexer.string(text, prior.location()).token());
      } else tokens.add(token);
    }
    return new Result(List.copyOf(tokens), end);
  }

  private void file(VirtualPath path, int depth) throws IOException {
    SourceLocation source = new SourceLocation(path.value(), 1, 1);
    if (depth >= limits.maxNesting() || ++includes > limits.maxIncludes())
      throw new ScriptException(source, "Include nesting/count budget exceeded");
    byte[] bytes;
    try {
      bytes = fs.read(path);
    } catch (IOException failure) {
      throw new ScriptException(source, "Could not read virtual source");
    }
    totalBytes += bytes.length;
    if (bytes.length > limits.maxFileBytes() || totalBytes > limits.maxTotalBytes())
      throw new ScriptException(source, "Source byte budget exceeded");
    String text = new String(bytes, StandardCharsets.ISO_8859_1);
    var tokens = ScriptLexer.lex(path.value(), text, limits);
    rawTokens += tokens.size();
    if (rawTokens > limits.maxTokens())
      throw new ScriptException(source, "Source token budget exceeded");
    var conditionals = new ArrayDeque<Conditional>();
    var code = new ArrayList<ScriptLexer.Lexeme>();
    int index = 0;
    while (index < tokens.size()) {
      int next = index + 1;
      while (next < tokens.size()
          && tokens.get(next).logicalLine() == tokens.get(index).logicalLine()) next++;
      var line = tokens.subList(index, next);
      if (line.getFirst().text().equals("#")) {
        emit(expand(code, 0));
        code.clear();
        directive(path, line, conditionals, depth);
      } else if (active(conditionals)) code.addAll(line);
      index = next;
    }
    emit(expand(code, 0));
    if (!conditionals.isEmpty())
      throw new ScriptException(source, "Unterminated conditional directive");
    if (depth == 0) {
      int line = 1, column = 1;
      for (int i = 0; i < text.length(); i++) {
        char c = text.charAt(i);
        if (c == '\r') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
          line++;
          column = 1;
        } else if (c == '\n') {
          line++;
          column = 1;
        } else column++;
      }
      end = new SourceLocation(path.value(), line, column);
    }
  }

  private void directive(
      VirtualPath path,
      List<ScriptLexer.Lexeme> line,
      ArrayDeque<Conditional> conditions,
      int depth)
      throws IOException {
    if (line.size() == 1) return;
    String name = line.get(1).text();
    SourceLocation source = line.get(1).location();
    var tail = line.subList(2, line.size());
    boolean active = active(conditions);
    switch (name) {
      case "if", "ifdef", "ifndef" -> {
        if (conditions.size() >= limits.maxNesting())
          throw new ScriptException(source, "Conditional nesting budget exceeded");
        boolean enabled;
        if (name.equals("if")) enabled = active && condition(tail, source);
        else {
          requireName(tail, source);
          enabled = active && (defined(tail.getFirst().text()) != name.equals("ifndef"));
        }
        conditions.addLast(new Conditional(active, enabled, enabled, false));
      }
      case "elif" -> {
        if (conditions.isEmpty() || conditions.getLast().sawElse())
          throw new ScriptException(source, "Unexpected #elif");
        var previous = conditions.removeLast();
        boolean enabled = previous.parent() && !previous.taken() && condition(tail, source);
        conditions.addLast(
            new Conditional(previous.parent(), previous.taken() || enabled, enabled, false));
      }
      case "else" -> {
        if (!tail.isEmpty() || conditions.isEmpty() || conditions.getLast().sawElse())
          throw new ScriptException(source, "Unexpected #else");
        var previous = conditions.removeLast();
        conditions.addLast(
            new Conditional(previous.parent(), true, previous.parent() && !previous.taken(), true));
      }
      case "endif" -> {
        if (!tail.isEmpty() || conditions.isEmpty())
          throw new ScriptException(source, "Unexpected #endif");
        conditions.removeLast();
      }
      default -> {
        if (!active) return;
        switch (name) {
          case "define" -> {
            Macro macro = definition(tail, source, limits);
            if (!macros.containsKey(macro.name()) && macros.size() >= limits.maxMacros())
              throw new ScriptException(source, "Macro count budget exceeded");
            macros.put(macro.name(), macro);
          }
          case "undef" -> {
            requireName(tail, source);
            macros.remove(tail.getFirst().text());
          }
          case "include" -> {
            var expanded = expand(tail, 0);
            String included;
            boolean angled = false;
            if (expanded.size() == 1 && expanded.getFirst().token().type() == ScriptToken.STRING)
              included = expanded.getFirst().text();
            else if (expanded.size() >= 3
                && expanded.getFirst().text().equals("<")
                && expanded.getLast().text().equals(">")) {
              angled = true;
              var includeName = new StringBuilder();
              for (var part : expanded.subList(1, expanded.size() - 1)) {
                includeName.append(part.spelling());
                if (includeName.length() > 255)
                  throw new ScriptException(source, "Include path length budget exceeded");
              }
              included = includeName.toString();
            } else throw new ScriptException(source, "Expected quoted or angled include path");
            file(resolve(included, path, angled, source), depth + 1);
          }
          case "eval", "evalfloat" -> emit(evaluated(tail, name.equals("evalfloat"), source, 0));
          case "error" -> throw new ScriptException(source, "#error " + stringify(tail));
          default -> throw new ScriptException(source, "Unsupported directive #" + name);
        }
      }
    }
  }

  static Macro definition(
      List<ScriptLexer.Lexeme> tokens, SourceLocation source, ScriptLimits limits)
      throws ScriptException {
    if (tokens.isEmpty() || tokens.getFirst().token().type() != ScriptToken.NAME)
      throw new ScriptException(source, "Expected macro name");
    String name = tokens.getFirst().text();
    if (name.equals("defined") || name.equals("__LINE__") || name.equals("__FILE__"))
      throw new ScriptException(source, "Cannot redefine reserved preprocessor name " + name);
    int index = 1;
    List<String> parameters = null;
    if (index < tokens.size()
        && tokens.get(index).text().equals("(")
        && !tokens.get(index).space()) {
      parameters = new ArrayList<>();
      index++;
      if (index < tokens.size() && !tokens.get(index).text().equals(")")) {
        while (true) {
          if (index >= tokens.size() || tokens.get(index).token().type() != ScriptToken.NAME)
            throw new ScriptException(
                source, "Expected macro parameter; variadic macros are unsupported");
          String parameter = tokens.get(index++).text();
          if (parameters.contains(parameter) || parameters.size() >= 128)
            throw new ScriptException(source, "Duplicate or excessive macro parameters");
          parameters.add(parameter);
          if (index >= tokens.size() || !tokens.get(index).text().equals(",")) break;
          index++;
        }
      }
      if (index >= tokens.size() || !tokens.get(index++).text().equals(")"))
        throw new ScriptException(source, "Unterminated macro parameter list");
    }
    var body = tokens.subList(index, tokens.size());
    if (!body.isEmpty()
        && (body.getFirst().text().equals("##") || body.getLast().text().equals("##")))
      throw new ScriptException(source, "Token paste requires both operands");
    if (body.size() > limits.maxTokens())
      throw new ScriptException(source, "Macro token budget exceeded");
    return new Macro(name, parameters, body);
  }

  private List<ScriptLexer.Lexeme> expand(List<ScriptLexer.Lexeme> input, int depth)
      throws ScriptException {
    return expandItems(input.stream().map(value -> new Expanded(value, Set.of())).toList(), depth)
        .stream()
        .map(Expanded::value)
        .toList();
  }

  private List<Expanded> expandItems(List<Expanded> input, int depth) throws ScriptException {
    if (input.isEmpty()) return List.of();
    if (depth >= limits.maxNesting())
      throw new ScriptException(
          input.getFirst().value().location(), "Macro nesting budget exceeded");
    var queue = new ArrayDeque<>(input);
    var result = new ArrayList<Expanded>();
    while (!queue.isEmpty()) {
      var current = queue.removeFirst();
      var value = current.value();
      tick(value.location());
      if (value.text().equals("$") && !queue.isEmpty()) {
        String directive = queue.removeFirst().value().text();
        if (!directive.equals("evalint") && !directive.equals("evalfloat"))
          throw new ScriptException(value.location(), "Unsupported dollar directive $" + directive);
        if (queue.isEmpty() || !queue.removeFirst().value().text().equals("("))
          throw new ScriptException(value.location(), "Expected '(' after dollar directive");
        var expression = argumentBody(queue, value.location());
        for (var token :
            evaluated(
                expression.stream().map(Expanded::value).toList(),
                directive.equals("evalfloat"),
                value.location(),
                depth + 1)) result.add(new Expanded(token, current.hidden()));
        continue;
      }
      if (value.token().type() != ScriptToken.NAME || current.hidden().contains(value.text())) {
        result.add(current);
        continue;
      }
      if (value.text().equals("__LINE__") || value.text().equals("__FILE__")) {
        result.add(
            new Expanded(
                value.text().equals("__LINE__")
                    ? ScriptLexer.number(value.location().line(), value.location())
                    : ScriptLexer.string(value.location().path(), value.location()),
                current.hidden()));
        continue;
      }
      Macro macro = macros.get(value.text());
      if (macro == null
          || (macro.parameters() != null
              && (queue.isEmpty() || !queue.getFirst().value().text().equals("(")))) {
        result.add(current);
        continue;
      }
      Map<String, List<Expanded>> arguments = new HashMap<>();
      if (macro.parameters() != null) {
        queue.removeFirst();
        var body = argumentBody(queue, value.location());
        var parts = splitArguments(body);
        if (body.isEmpty() && macro.parameters().isEmpty()) parts = List.of();
        if (parts.size() != macro.parameters().size())
          throw new ScriptException(
              value.location(), "Wrong argument count for macro " + macro.name());
        for (int i = 0; i < parts.size(); i++)
          arguments.put(macro.parameters().get(i), parts.get(i));
      }
      Set<String> hidden = new HashSet<>(current.hidden());
      hidden.add(macro.name());
      if (hidden.size() > limits.maxNesting())
        throw new ScriptException(value.location(), "Macro expansion depth budget exceeded");
      var replacement = substitute(macro, arguments, value.location(), depth);
      if ((long) replacement.size() + queue.size() + result.size() > limits.maxTokens())
        throw new ScriptException(value.location(), "Expanded token budget exceeded");
      Set<String> suppress = Set.copyOf(hidden);
      for (int i = replacement.size() - 1; i >= 0; i--) {
        var item = replacement.get(i);
        Set<String> combined = new HashSet<>(item.hidden());
        combined.addAll(suppress);
        queue.addFirst(new Expanded(item.value(), Set.copyOf(combined)));
      }
    }
    return result;
  }

  private List<Expanded> substitute(
      Macro macro, Map<String, List<Expanded>> arguments, SourceLocation call, int depth)
      throws ScriptException {
    var result = new ArrayList<Expanded>();
    Map<String, List<Expanded>> expandedArguments = new HashMap<>();
    boolean paste = false, lastPartEmpty = true;
    for (int i = 0; i < macro.body().size(); i++) {
      var part = macro.body().get(i);
      if (part.text().equals("##")) {
        paste = true;
        continue;
      }
      List<Expanded> values;
      if (part.text().equals("#")) {
        if (++i >= macro.body().size() || !arguments.containsKey(macro.body().get(i).text()))
          throw new ScriptException(call, "Stringification requires a macro parameter");
        String text =
            stringify(
                arguments.get(macro.body().get(i).text()).stream().map(Expanded::value).toList());
        if (text.length() + 2 > limits.maxTokenChars())
          throw new ScriptException(call, "Stringification exceeds token budget");
        values = List.of(new Expanded(ScriptLexer.string(text, call), Set.of()));
      } else if (arguments.containsKey(part.text())) {
        if (paste || i + 1 < macro.body().size() && macro.body().get(i + 1).text().equals("##"))
          values = arguments.get(part.text());
        else {
          values = expandedArguments.get(part.text());
          if (values == null) {
            values = expandItems(arguments.get(part.text()), depth + 1);
            expandedArguments.put(part.text(), values);
          }
        }
      } else values = List.of(new Expanded(part.at(call), Set.of()));
      if (paste && !lastPartEmpty && !values.isEmpty()) {
        var left = result.removeLast();
        String spelling = left.value().spelling() + values.getFirst().value().spelling();
        var lexed = ScriptLexer.lex(call.path(), spelling, limits);
        if (lexed.size() != 1)
          throw new ScriptException(call, "Token paste does not produce one token");
        Set<String> hidden = new HashSet<>(left.hidden());
        hidden.addAll(values.getFirst().hidden());
        result.add(new Expanded(lexed.getFirst().at(call), Set.copyOf(hidden)));
        result.addAll(values.subList(1, values.size()));
      } else result.addAll(values);
      lastPartEmpty = paste ? lastPartEmpty && values.isEmpty() : values.isEmpty();
      paste = false;
      if (result.size() > limits.maxTokens())
        throw new ScriptException(call, "Macro substitution token budget exceeded");
    }
    return result;
  }

  private List<Expanded> argumentBody(ArrayDeque<Expanded> queue, SourceLocation source)
      throws ScriptException {
    var result = new ArrayList<Expanded>();
    int nesting = 1;
    while (!queue.isEmpty()) {
      var item = queue.removeFirst();
      tick(source);
      String text = item.value().text();
      if (text.equals("(")) nesting++;
      if (text.equals(")") && --nesting == 0) return result;
      // Closing depth changes only on closing punctuation.
      if (!text.equals(")") && nesting > limits.maxNesting())
        throw new ScriptException(source, "Macro argument nesting budget exceeded");
      result.add(item);
    }
    throw new ScriptException(source, "Unterminated macro/evaluation arguments");
  }

  private static List<List<Expanded>> splitArguments(List<Expanded> tokens) {
    var result = new ArrayList<List<Expanded>>();
    int nesting = 0, start = 0;
    for (int i = 0; i < tokens.size(); i++) {
      String text = tokens.get(i).value().text();
      if (text.equals("(")) nesting++;
      else if (text.equals(")")) nesting--;
      else if (text.equals(",") && nesting == 0) {
        result.add(List.copyOf(tokens.subList(start, i)));
        start = i + 1;
      }
    }
    result.add(List.copyOf(tokens.subList(start, tokens.size())));
    return result;
  }

  private List<ScriptLexer.Lexeme> evaluated(
      List<ScriptLexer.Lexeme> tokens, boolean floating, SourceLocation source, int depth)
      throws ScriptException {
    double value =
        ScriptExpression.evaluate(
            expand(definedTokens(tokens), depth), floating, false, limits.maxNesting(), source);
    double magnitude = Math.abs(value);
    String text =
        floating ? String.format(Locale.ROOT, "%.2f", magnitude) : Long.toString((long) magnitude);
    if (text.length() > limits.maxTokenChars())
      throw new ScriptException(source, "Evaluation token exceeds length budget");
    int subtype =
        ScriptToken.DECIMAL
            | ScriptToken.LONG
            | (floating ? ScriptToken.FLOAT : ScriptToken.INTEGER);
    var token =
        new ScriptLexer.Lexeme(
            new ScriptToken(
                ScriptToken.NUMBER,
                subtype,
                (int) (long) magnitude,
                (float) magnitude,
                text,
                source),
            text,
            false,
            1);
    if (value >= 0) return List.of(token);
    return List.of(ScriptLexer.lex(source.path(), "-", limits).getFirst().at(source), token);
  }

  private boolean condition(List<ScriptLexer.Lexeme> tokens, SourceLocation source)
      throws ScriptException {
    return ScriptExpression.evaluate(
            expand(definedTokens(tokens), 0), false, false, limits.maxNesting(), source)
        != 0;
  }

  private List<ScriptLexer.Lexeme> definedTokens(List<ScriptLexer.Lexeme> tokens)
      throws ScriptException {
    var result = new ArrayList<ScriptLexer.Lexeme>();
    for (int i = 0; i < tokens.size(); i++) {
      var token = tokens.get(i);
      if (!token.text().equals("defined")) {
        result.add(token);
        continue;
      }
      int next = i + 1;
      boolean parentheses = next < tokens.size() && tokens.get(next).text().equals("(");
      if (parentheses) next++;
      if (next >= tokens.size() || tokens.get(next).token().type() != ScriptToken.NAME)
        throw new ScriptException(token.location(), "Expected name after defined");
      result.add(ScriptLexer.number(defined(tokens.get(next).text()) ? 1 : 0, token.location()));
      i = next;
      if (parentheses && (++i >= tokens.size() || !tokens.get(i).text().equals(")")))
        throw new ScriptException(token.location(), "Expected ')' after defined name");
    }
    return result;
  }

  private boolean defined(String name) {
    return macros.containsKey(name) || name.equals("__LINE__") || name.equals("__FILE__");
  }

  private static boolean active(ArrayDeque<Conditional> conditions) {
    return conditions.isEmpty() || conditions.getLast().active();
  }

  private static void requireName(List<ScriptLexer.Lexeme> tokens, SourceLocation source)
      throws ScriptException {
    if (tokens.size() != 1 || tokens.getFirst().token().type() != ScriptToken.NAME)
      throw new ScriptException(source, "Expected one preprocessor name");
  }

  private String stringify(List<ScriptLexer.Lexeme> tokens) throws ScriptException {
    var text = new StringBuilder();
    for (var token : tokens) {
      if (!text.isEmpty() && token.space()) text.append(' ');
      text.append(token.spelling());
      if (text.length() + 2 > limits.maxTokenChars())
        throw new ScriptException(
            token.location(), "Stringification/diagnostic text budget exceeded");
    }
    return text.toString();
  }

  private void emit(List<ScriptLexer.Lexeme> tokens) throws ScriptException {
    if ((long) output.size() + tokens.size() > limits.maxTokens())
      throw new ScriptException(tokens.getFirst().location(), "Output token budget exceeded");
    output.addAll(tokens);
  }

  private void tick(SourceLocation source) throws ScriptException {
    if (++steps > limits.maxExpansionSteps())
      throw new ScriptException(source, "Expansion work budget exceeded");
  }

  private VirtualPath resolve(
      String name, VirtualPath including, boolean angled, SourceLocation source)
      throws ScriptException {
    VirtualPath requested;
    try {
      requested = new VirtualPath(name);
    } catch (IllegalArgumentException invalid) {
      throw new ScriptException(source, "Invalid virtual include/source path");
    }
    Set<String> candidates = new LinkedHashSet<>();
    if (including != null && !angled) {
      int slash = including.value().lastIndexOf('/');
      if (slash >= 0) candidates.add(including.value().substring(0, slash + 1) + requested.value());
    }
    candidates.add(requested.value());
    for (String root : includeRoots) candidates.add(root + "/" + requested.value());
    for (String candidate : candidates) {
      if (candidate.length() > 255) continue;
      var path = new VirtualPath(candidate);
      if (fs.which(path).isPresent()) return path;
    }
    throw new ScriptException(source, "Virtual source not found: " + requested.value());
  }
}
