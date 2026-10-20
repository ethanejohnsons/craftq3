package dev.bluevista.craftq3.engine.client.script;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The C-like preprocessor that menu and HUD scripts are read through ({@code trap_PC_*}):
 *
 * <ul>
 *   <li>{@code #include "file"} and {@code #include <file>}, read through the game file system;
 *   <li>{@code #define} of object-like and function-like macros (with {@code #} stringizing and
 *       {@code ##} pasting), {@code #undef}, global defines added by {@code
 *       trap_PC_AddGlobalDefine};
 *   <li>{@code #if}, {@code #ifdef}, {@code #ifndef}, {@code #elif}, {@code #else}, {@code #endif}
 *       with integer and float expressions and {@code defined};
 *   <li>{@code #eval}/{@code #evalfloat} and {@code $evalint(..)}/{@code $evalfloat(..)}, which
 *       replace an expression by its value; {@code #line} and {@code #pragma} are ignored, {@code
 *       #error} ends the script;
 *   <li>the built-in macros {@code __LINE__}, {@code __FILE__}, {@code __DATE__} and {@code
 *       __TIME__};
 *   <li>adjacent string tokens are joined, as the lexer already does within a file.
 * </ul>
 *
 * Errors throw {@link ScriptException}; the caller reports them and treats the source as ended.
 */
public final class ScriptPrecompiler {
  /** Nested {@code #include} files at most. */
  public static final int MAX_INCLUDE_DEPTH = 32;

  /** Macro expansions one token read may perform before the script is considered recursive. */
  public static final int MAX_EXPANSIONS = 4096;

  /** Defines one source may hold. */
  public static final int MAX_DEFINES = 8192;

  /** A macro: parameters (null for object-like) and body tokens. */
  record Define(String name, List<String> parameters, List<ScriptToken> body) {
    boolean functionLike() {
      return parameters != null;
    }
  }

  private record Conditional(
      boolean parentActive, boolean active, boolean taken, boolean sawElse) {}

  private final Function<String, Optional<String>> files;
  private final Deque<ScriptLexer> sources = new ArrayDeque<>();
  private final Map<String, Define> defines = new HashMap<>();
  private final Deque<Conditional> conditions = new ArrayDeque<>();
  private final Deque<ScriptToken> pending = new ArrayDeque<>();
  private String lastFile;
  private int lastLine;

  /**
   * @param files reads an included file's text by its game path
   * @param globalDefines {@code trap_PC_AddGlobalDefine} strings, e.g. {@code "MISSIONPACK 1"}
   */
  public ScriptPrecompiler(
      String file,
      String text,
      Function<String, Optional<String>> files,
      List<String> globalDefines) {
    this.files = Objects.requireNonNull(files);
    for (String global : globalDefines) defineFromString(global);
    sources.push(new ScriptLexer(file, text));
    lastFile = file;
  }

  /** Parses {@code "NAME body"} or {@code "NAME(a,b) body"} as a {@code #define}. */
  public static void checkDefine(String text) {
    new ScriptPrecompiler("define", "", path -> Optional.empty(), List.of()).defineFromString(text);
  }

  private void defineFromString(String text) {
    ScriptLexer lexer = new ScriptLexer("define", text);
    sources.push(lexer);
    try {
      directiveDefine();
    } finally {
      sources.remove(lexer);
    }
  }

  /** The file of the innermost source being read. */
  public String file() {
    return sources.isEmpty() ? lastFile : sources.peek().file();
  }

  /** The current line of the innermost source. */
  public int line() {
    return sources.isEmpty() ? lastLine : sources.peek().line();
  }

  /** The next expanded token, or null at the end of the outermost file. */
  public ScriptToken next() {
    ScriptToken token = nextExpanded();
    if (token == null || token.type() != ScriptToken.TT_STRING) return token;
    // Strings behind each other are concatenated, still resolving defines.
    while (true) {
      ScriptToken following = nextExpanded();
      if (following == null) return token;
      if (following.type() != ScriptToken.TT_STRING) {
        pending.push(following);
        return token;
      }
      String joined = token.pcText() + following.pcText();
      if (joined.length() + 2 >= ScriptToken.MAX_LENGTH)
        throw error("string longer than MAX_TOKEN");
      token =
          new ScriptToken(
              ScriptToken.TT_STRING,
              joined.length(),
              '"' + joined + '"',
              0,
              0,
              token.line(),
              token.linesCrossed(),
              token.spaceBefore());
    }
  }

  private ScriptToken nextExpanded() {
    int expansions = 0;
    while (true) {
      boolean fromSource = pending.isEmpty();
      ScriptToken token = raw();
      if (token == null) {
        if (!conditions.isEmpty()) throw error("missing #endif");
        return null;
      }
      if (fromSource && token.isPunctuation("#")) {
        directive();
        continue;
      }
      if (!active()) continue;
      if (token.isPunctuation("$")) {
        ScriptToken name = raw();
        if (name != null && (name.isName("evalint") || name.isName("evalfloat"))) {
          pending.push(evaluateDollar(name.text().equals("evalfloat"), token.line()));
          continue;
        }
        if (name != null) pending.push(name);
        return token;
      }
      if (token.type() == ScriptToken.TT_NAME) {
        List<ScriptToken> expansion = expand(token);
        if (expansion != null) {
          if (++expansions > MAX_EXPANSIONS) throw error("recursive define " + token.text());
          for (int i = expansion.size() - 1; i >= 0; i--) pending.push(expansion.get(i));
          continue;
        }
      }
      return token;
    }
  }

  private boolean active() {
    return conditions.isEmpty() || conditions.peek().active();
  }

  /** The next unexpanded token: pending first, then the source stack. */
  private ScriptToken raw() {
    if (!pending.isEmpty()) return pending.pop();
    while (!sources.isEmpty()) {
      ScriptLexer lexer = sources.peek();
      ScriptToken token = lexer.next();
      if (token != null) {
        lastFile = lexer.file();
        lastLine = lexer.line();
        return token;
      }
      if (sources.size() == 1) {
        lastFile = lexer.file();
        lastLine = lexer.line();
        return null;
      }
      sources.pop();
    }
    return null;
  }

  // ---------------------------------------------------------------- directives

  private void directive() {
    ScriptToken name = lineToken();
    if (name == null) throw error("found # without name");
    if (name.type() != ScriptToken.TT_NAME) {
      skipLine();
      throw error("unknown precompiler directive " + name.text());
    }
    switch (name.text()) {
      case "if" -> directiveIf(false);
      case "ifdef" -> directiveIfdef(true);
      case "ifndef" -> directiveIfdef(false);
      case "elif" -> directiveIf(true);
      case "else" -> directiveElse();
      case "endif" -> directiveEndif();
      default -> {
        if (!active()) {
          skipLine();
          return;
        }
        switch (name.text()) {
          case "include" -> directiveInclude();
          case "define" -> directiveDefine();
          case "undef" -> directiveUndef();
          case "line", "pragma", "warning" -> skipLine();
          case "error" -> throw error("#error " + sources.peek().restOfLine());
          case "eval" -> pending.push(evaluateLine(false, name.line()));
          case "evalfloat" -> pending.push(evaluateLine(true, name.line()));
          default -> {
            skipLine();
            throw error("unknown precompiler directive " + name.text());
          }
        }
      }
    }
  }

  /** The next token of the directive's line from the current file, honouring {@code \} joins. */
  private ScriptToken lineToken() {
    ScriptLexer lexer = sources.peek();
    while (true) {
      if (lexer.lineBreakAhead()) return null;
      ScriptToken token = lexer.next();
      if (token == null) return null;
      if (token.isPunctuation("\\")) {
        // A line continuation: the directive goes on with the next line.
        ScriptToken next = lexer.next();
        if (next == null) return null;
        return next;
      }
      return token;
    }
  }

  private List<ScriptToken> lineTokens() {
    List<ScriptToken> tokens = new ArrayList<>();
    for (ScriptToken token = lineToken(); token != null; token = lineToken()) tokens.add(token);
    return tokens;
  }

  private void skipLine() {
    while (lineToken() != null) {
      // discard
    }
  }

  private void directiveInclude() {
    ScriptToken token = lineToken();
    if (token == null) throw error("#include without file name");
    String path;
    if (token.type() == ScriptToken.TT_STRING) {
      path = token.pcText();
      skipLine();
    } else if (token.isPunctuation("<")) {
      StringBuilder spelled = new StringBuilder();
      for (ScriptToken part = lineToken(); part != null; part = lineToken()) {
        if (part.isPunctuation(">")) break;
        spelled.append(part.text());
      }
      path = spelled.toString();
      skipLine();
    } else throw error("#include without file name");
    if (sources.size() >= MAX_INCLUDE_DEPTH) throw error("#include nested too deeply");
    String text = files.apply(path).orElseThrow(() -> error("file " + path + " not found"));
    sources.push(new ScriptLexer(path, text));
  }

  private void directiveDefine() {
    ScriptToken name = lineToken();
    if (name == null || name.type() != ScriptToken.TT_NAME)
      throw error("expected name after #define");
    List<String> parameters = null;
    ScriptToken token = lineToken();
    List<ScriptToken> body = new ArrayList<>();
    if (token != null && token.isPunctuation("(") && !token.spaceBefore()) {
      parameters = new ArrayList<>();
      ScriptToken parameter = lineToken();
      if (parameter != null && !parameter.isPunctuation(")")) {
        while (true) {
          if (parameter == null || parameter.type() != ScriptToken.TT_NAME)
            throw error("invalid define parameter");
          if (parameters.contains(parameter.text()))
            throw error("two of the same define parameters");
          parameters.add(parameter.text());
          ScriptToken separator = lineToken();
          if (separator == null) throw error("define parameters not terminated");
          if (separator.isPunctuation(")")) break;
          if (!separator.isPunctuation(",")) throw error("define not terminated");
          parameter = lineToken();
        }
      }
      token = lineToken();
    }
    for (; token != null; token = lineToken()) body.add(token);
    if (!defines.containsKey(name.text()) && defines.size() >= MAX_DEFINES)
      throw error("too many defines");
    defines.put(name.text(), new Define(name.text(), parameters, List.copyOf(body)));
  }

  private void directiveUndef() {
    ScriptToken name = lineToken();
    if (name == null || name.type() != ScriptToken.TT_NAME)
      throw error("expected name after #undef");
    skipLine();
    defines.remove(name.text());
  }

  private void directiveIfdef(boolean wanted) {
    ScriptToken name = lineToken();
    if (name == null || name.type() != ScriptToken.TT_NAME)
      throw error("expected name after #ifdef");
    skipLine();
    boolean parent = active();
    boolean value = parent && (defined(name.text()) == wanted);
    conditions.push(new Conditional(parent, value, value, false));
  }

  private void directiveIf(boolean elif) {
    List<ScriptToken> expression = lineTokens();
    if (!elif) {
      boolean parent = active();
      boolean value = parent && evaluate(expression, false).truth();
      conditions.push(new Conditional(parent, value, value, false));
      return;
    }
    Conditional current = conditions.poll();
    if (current == null) throw error("misplaced #elif");
    if (current.sawElse()) throw error("#elif after #else");
    boolean value =
        current.parentActive() && !current.taken() && evaluate(expression, false).truth();
    conditions.push(
        new Conditional(current.parentActive(), value, current.taken() || value, false));
  }

  private void directiveElse() {
    skipLine();
    Conditional current = conditions.poll();
    if (current == null) throw error("misplaced #else");
    if (current.sawElse()) throw error("#else after #else");
    boolean value = current.parentActive() && !current.taken();
    conditions.push(new Conditional(current.parentActive(), value, true, true));
  }

  private void directiveEndif() {
    skipLine();
    if (conditions.poll() == null) throw error("misplaced #endif");
  }

  private boolean defined(String name) {
    return defines.containsKey(name) || builtin(name);
  }

  private static boolean builtin(String name) {
    return switch (name) {
      case "__LINE__", "__FILE__", "__DATE__", "__TIME__" -> true;
      default -> false;
    };
  }

  // ---------------------------------------------------------------- macro expansion

  /** The expansion of {@code name}, or null when it is not a macro use. */
  private List<ScriptToken> expand(ScriptToken name) {
    int line = name.line();
    switch (name.text()) {
      case "__LINE__":
        return List.of(ScriptToken.integer(line, line));
      case "__FILE__":
        return List.of(ScriptToken.string(file(), line));
      case "__DATE__":
        return List.of(
            ScriptToken.string(
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM dd yyyy", Locale.ROOT)),
                line));
      case "__TIME__":
        return List.of(
            ScriptToken.string(
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)),
                line));
      default:
        break;
    }
    Define define = defines.get(name.text());
    if (define == null) return null;
    List<List<ScriptToken>> arguments = List.of();
    if (define.functionLike()) {
      ScriptToken open = raw();
      if (open == null || !open.isPunctuation("(")) {
        if (open != null) pending.push(open);
        return null;
      }
      arguments = readArguments(define);
    }
    List<ScriptToken> output = new ArrayList<>();
    List<ScriptToken> body = define.body();
    for (int i = 0; i < body.size(); i++) {
      ScriptToken token = body.get(i);
      if (define.functionLike()
          && token.isPunctuation("#")
          && i + 1 < body.size()
          && parameterIndex(define, body.get(i + 1)) >= 0) {
        output.add(stringize(arguments.get(parameterIndex(define, body.get(++i))), line));
        continue;
      }
      if (token.isPunctuation("##") && !output.isEmpty() && i + 1 < body.size()) {
        ScriptToken left = output.removeLast();
        ScriptToken right = body.get(++i);
        int index = parameterIndex(define, right);
        List<ScriptToken> rightTokens = index >= 0 ? arguments.get(index) : List.of(right);
        String pasted = left.text() + (rightTokens.isEmpty() ? "" : rightTokens.getFirst().text());
        output.addAll(relex(pasted, line));
        if (rightTokens.size() > 1) output.addAll(rightTokens.subList(1, rightTokens.size()));
        continue;
      }
      int index = parameterIndex(define, token);
      if (index >= 0) {
        for (ScriptToken argument : arguments.get(index))
          output.add(argument.at(line, 0, argument.spaceBefore()));
      } else output.add(token.at(line, 0, token.spaceBefore()));
    }
    return output;
  }

  private static int parameterIndex(Define define, ScriptToken token) {
    if (!define.functionLike() || token.type() != ScriptToken.TT_NAME) return -1;
    return define.parameters().indexOf(token.text());
  }

  private List<List<ScriptToken>> readArguments(Define define) {
    List<List<ScriptToken>> arguments = new ArrayList<>();
    List<ScriptToken> current = new ArrayList<>();
    int depth = 0;
    while (true) {
      ScriptToken token = raw();
      if (token == null) throw error("define " + define.name() + " missing closing parenthesis");
      if (token.isPunctuation("(")) depth++;
      else if (token.isPunctuation(")")) {
        if (depth == 0) {
          if (!current.isEmpty() || !arguments.isEmpty() || !define.parameters().isEmpty())
            arguments.add(current);
          break;
        }
        depth--;
      } else if (token.isPunctuation(",") && depth == 0) {
        arguments.add(current);
        current = new ArrayList<>();
        continue;
      }
      current.add(token);
    }
    if (arguments.size() != define.parameters().size())
      throw error(
          "define " + define.name() + " takes " + define.parameters().size() + " arguments");
    return arguments;
  }

  private static ScriptToken stringize(List<ScriptToken> tokens, int line) {
    StringBuilder text = new StringBuilder();
    for (ScriptToken token : tokens) {
      if (!text.isEmpty() && token.spaceBefore()) text.append(' ');
      text.append(token.text());
    }
    return ScriptToken.string(text.toString().replace("\"", "\\\""), line);
  }

  private List<ScriptToken> relex(String text, int line) {
    ScriptLexer lexer = new ScriptLexer(file(), text);
    List<ScriptToken> tokens = new ArrayList<>();
    for (ScriptToken token = lexer.next(); token != null; token = lexer.next())
      tokens.add(token.at(line, 0, tokens.isEmpty()));
    return tokens;
  }

  // ---------------------------------------------------------------- expressions

  private ScriptToken evaluateLine(boolean real, int line) {
    Value value = evaluate(lineTokens(), true);
    return real
        ? ScriptToken.real(value.asDouble(), line)
        : ScriptToken.integer(value.asLong(), line);
  }

  private ScriptToken evaluateDollar(boolean real, int line) {
    ScriptToken open = raw();
    if (open == null || !open.isPunctuation("(")) throw error("expected ( after $eval");
    List<ScriptToken> expression = new ArrayList<>();
    int depth = 0;
    while (true) {
      ScriptToken token = nextExpanded();
      if (token == null) throw error("$eval without closing parenthesis");
      if (token.isPunctuation("(")) depth++;
      else if (token.isPunctuation(")")) {
        if (depth == 0) break;
        depth--;
      }
      expression.add(token);
    }
    Value value = evaluate(expression, true);
    return real
        ? ScriptToken.real(value.asDouble(), line)
        : ScriptToken.integer(value.asLong(), line);
  }

  /** A number in an expression; integers stay integral until a float joins them. */
  record Value(boolean real, long integer, double floating) {
    static Value of(long value) {
      return new Value(false, value, value);
    }

    static Value of(double value) {
      return new Value(true, (long) value, value);
    }

    boolean truth() {
      return real ? floating != 0 : integer != 0;
    }

    long asLong() {
      return real ? (long) floating : integer;
    }

    double asDouble() {
      return real ? floating : integer;
    }
  }

  private Value evaluate(List<ScriptToken> raw, boolean expandFirst) {
    List<ScriptToken> tokens = new ArrayList<>();
    for (int i = 0; i < raw.size(); i++) {
      ScriptToken token = raw.get(i);
      if (token.isName("defined")) {
        boolean paren = i + 1 < raw.size() && raw.get(i + 1).isPunctuation("(");
        int nameIndex = paren ? i + 2 : i + 1;
        if (nameIndex >= raw.size() || raw.get(nameIndex).type() != ScriptToken.TT_NAME)
          throw error("defined without name");
        tokens.add(ScriptToken.integer(defined(raw.get(nameIndex).text()) ? 1 : 0, token.line()));
        i = nameIndex;
        if (paren) {
          if (i + 1 >= raw.size() || !raw.get(i + 1).isPunctuation(")"))
            throw error("defined without closing parenthesis");
          i++;
        }
      } else tokens.add(token);
    }
    if (!expandFirst) tokens = expandAll(tokens);
    Parser parser = new Parser(tokens);
    Value value = parser.ternary();
    if (parser.position != tokens.size())
      throw error("invalid expression near " + tokens.get(parser.position).text());
    return value;
  }

  /** Macro-expands a token list in isolation (for {@code #if}). */
  private List<ScriptToken> expandAll(List<ScriptToken> tokens) {
    Deque<ScriptToken> saved = new ArrayDeque<>(pending);
    pending.clear();
    List<ScriptToken> output = new ArrayList<>();
    Deque<ScriptToken> work = new ArrayDeque<>(tokens);
    int expansions = 0;
    try {
      while (!work.isEmpty()) {
        ScriptToken token = work.pop();
        if (token.type() == ScriptToken.TT_NAME) {
          // Function-like macros read their arguments from the remaining work list.
          pending.addAll(work);
          List<ScriptToken> expansion = expand(token);
          work.clear();
          work.addAll(pending);
          pending.clear();
          if (expansion != null) {
            if (++expansions > MAX_EXPANSIONS) throw error("recursive define " + token.text());
            for (int i = expansion.size() - 1; i >= 0; i--) work.push(expansion.get(i));
            continue;
          }
        }
        output.add(token);
      }
    } finally {
      pending.clear();
      pending.addAll(saved);
    }
    return output;
  }

  /** Recursive-descent evaluation of C operators over number tokens. */
  private final class Parser {
    private final List<ScriptToken> tokens;
    private int position;

    Parser(List<ScriptToken> tokens) {
      this.tokens = tokens;
    }

    private boolean accept(String punctuation) {
      if (position < tokens.size() && tokens.get(position).isPunctuation(punctuation)) {
        position++;
        return true;
      }
      return false;
    }

    Value ternary() {
      Value condition = logicalOr();
      if (!accept("?")) return condition;
      Value yes = ternary();
      if (!accept(":")) throw error("? without :");
      Value no = ternary();
      return condition.truth() ? yes : no;
    }

    private Value logicalOr() {
      Value left = logicalAnd();
      while (accept("||")) {
        Value right = logicalAnd();
        left = Value.of(left.truth() || right.truth() ? 1 : 0);
      }
      return left;
    }

    private Value logicalAnd() {
      Value left = bitOr();
      while (accept("&&")) {
        Value right = bitOr();
        left = Value.of(left.truth() && right.truth() ? 1 : 0);
      }
      return left;
    }

    private Value bitOr() {
      Value left = bitXor();
      while (accept("|")) left = Value.of(integral(left) | integral(bitXor()));
      return left;
    }

    private Value bitXor() {
      Value left = bitAnd();
      while (accept("^")) left = Value.of(integral(left) ^ integral(bitAnd()));
      return left;
    }

    private Value bitAnd() {
      Value left = equality();
      while (accept("&")) left = Value.of(integral(left) & integral(equality()));
      return left;
    }

    private Value equality() {
      Value left = relational();
      while (true) {
        if (accept("==")) left = Value.of(compare(left, relational()) == 0 ? 1 : 0);
        else if (accept("!=")) left = Value.of(compare(left, relational()) != 0 ? 1 : 0);
        else return left;
      }
    }

    private Value relational() {
      Value left = shift();
      while (true) {
        if (accept("<")) left = Value.of(compare(left, shift()) < 0 ? 1 : 0);
        else if (accept(">")) left = Value.of(compare(left, shift()) > 0 ? 1 : 0);
        else if (accept("<=")) left = Value.of(compare(left, shift()) <= 0 ? 1 : 0);
        else if (accept(">=")) left = Value.of(compare(left, shift()) >= 0 ? 1 : 0);
        else return left;
      }
    }

    private Value shift() {
      Value left = additive();
      while (true) {
        if (accept("<<")) left = Value.of(integral(left) << integral(additive()));
        else if (accept(">>")) left = Value.of(integral(left) >> integral(additive()));
        else return left;
      }
    }

    private Value additive() {
      Value left = multiplicative();
      while (true) {
        if (accept("+")) left = arithmetic(left, multiplicative(), '+');
        else if (accept("-")) left = arithmetic(left, multiplicative(), '-');
        else return left;
      }
    }

    private Value multiplicative() {
      Value left = unary();
      while (true) {
        if (accept("*")) left = arithmetic(left, unary(), '*');
        else if (accept("/")) left = arithmetic(left, unary(), '/');
        else if (accept("%")) {
          long divisor = integral(unary());
          if (divisor == 0) throw error("divide by zero in expression");
          left = Value.of(integral(left) % divisor);
        } else return left;
      }
    }

    private Value unary() {
      if (accept("-")) {
        Value value = unary();
        return value.real() ? Value.of(-value.floating()) : Value.of(-value.integer());
      }
      if (accept("+")) return unary();
      if (accept("!")) return Value.of(unary().truth() ? 0 : 1);
      if (accept("~")) return Value.of(~integral(unary()));
      if (accept("(")) {
        Value value = ternary();
        if (!accept(")")) throw error("missing ) in expression");
        return value;
      }
      if (position >= tokens.size()) throw error("expression ends early");
      ScriptToken token = tokens.get(position++);
      if (token.type() == ScriptToken.TT_NUMBER)
        return (token.subtype() & ScriptToken.TT_FLOAT) != 0
            ? Value.of(token.floatValue())
            : Value.of(token.intValue());
      if (token.type() == ScriptToken.TT_LITERAL) return Value.of(token.intValue());
      if (token.type() == ScriptToken.TT_NAME) return Value.of(0L); // undefined names are 0
      throw error("can't evaluate " + token.text());
    }

    private long integral(Value value) {
      if (value.real()) throw error("integer operator applied to a float");
      return value.integer();
    }

    private int compare(Value a, Value b) {
      if (a.real() || b.real()) return Double.compare(a.asDouble(), b.asDouble());
      return Long.compare(a.integer(), b.integer());
    }

    private Value arithmetic(Value a, Value b, char operator) {
      if (a.real() || b.real()) {
        double x = a.asDouble(), y = b.asDouble();
        return Value.of(
            switch (operator) {
              case '+' -> x + y;
              case '-' -> x - y;
              case '*' -> x * y;
              default -> {
                if (y == 0) throw error("divide by zero in expression");
                yield x / y;
              }
            });
      }
      long x = a.integer(), y = b.integer();
      return Value.of(
          switch (operator) {
            case '+' -> x + y;
            case '-' -> x - y;
            case '*' -> x * y;
            default -> {
              if (y == 0) throw error("divide by zero in expression");
              yield x / y;
            }
          });
    }
  }

  private ScriptException error(String message) {
    return new ScriptException(file(), line(), message);
  }
}
