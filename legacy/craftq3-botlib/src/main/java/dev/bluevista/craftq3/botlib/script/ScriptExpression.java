package dev.bluevista.craftq3.botlib.script;

import java.util.List;
import java.util.Map;

/** Original precedence parser with lazy logical/conditional branches and explicit 32-bit math. */
final class ScriptExpression {
  private static final Map<String, Integer> PRECEDENCE =
      Map.ofEntries(
          Map.entry("||", 1),
          Map.entry("&&", 2),
          Map.entry("|", 3),
          Map.entry("^", 4),
          Map.entry("&", 5),
          Map.entry("==", 6),
          Map.entry("!=", 6),
          Map.entry("<", 7),
          Map.entry(">", 7),
          Map.entry("<=", 7),
          Map.entry(">=", 7),
          Map.entry("<<", 8),
          Map.entry(">>", 8),
          Map.entry("+", 9),
          Map.entry("-", 9),
          Map.entry("*", 10),
          Map.entry("/", 10),
          Map.entry("%", 10));
  private final List<ScriptLexer.Lexeme> tokens;
  private final boolean floating, undefinedZero;
  private final int maxDepth;
  private final SourceLocation source;
  private int index;

  private ScriptExpression(
      List<ScriptLexer.Lexeme> tokens,
      boolean floating,
      boolean undefinedZero,
      int maxDepth,
      SourceLocation source) {
    this.tokens = tokens;
    this.floating = floating;
    this.undefinedZero = undefinedZero;
    this.maxDepth = maxDepth;
    this.source = source;
  }

  static double evaluate(
      List<ScriptLexer.Lexeme> tokens,
      boolean floating,
      boolean undefinedZero,
      int maxDepth,
      SourceLocation source)
      throws ScriptException {
    var parser = new ScriptExpression(tokens, floating, undefinedZero, maxDepth, source);
    double value = parser.expression(0, true, 0);
    if (parser.index != tokens.size())
      throw parser.error("Unexpected expression token " + parser.peek());
    if (!Double.isFinite(value) || (floating && !Float.isFinite((float) value)))
      throw parser.error("Non-finite expression result");
    return value;
  }

  private double expression(int minimum, boolean evaluate, int depth) throws ScriptException {
    if (depth >= maxDepth) throw error("Expression nesting budget exceeded");
    double left = unary(evaluate, depth + 1);
    while (index < tokens.size()) {
      String operator = peek();
      int precedence = PRECEDENCE.getOrDefault(operator, -1);
      if (precedence < minimum || precedence < 0) break;
      index++;
      boolean rightActive =
          evaluate
              && !(operator.equals("&&") && left == 0)
              && !(operator.equals("||") && left != 0);
      double right = expression(precedence + 1, rightActive, depth + 1);
      if (evaluate) left = binary(operator, left, right);
    }
    if (minimum == 0 && peek().equals("?")) {
      index++;
      double yes = expression(0, evaluate && left != 0, depth + 1);
      require(":");
      double no = expression(0, evaluate && left == 0, depth + 1);
      if (evaluate) left = left != 0 ? yes : no;
    }
    return evaluate ? left : 0;
  }

  private double unary(boolean evaluate, int depth) throws ScriptException {
    if (depth >= maxDepth) throw error("Expression nesting budget exceeded");
    String token = peek();
    if (List.of("+", "-", "!", "~").contains(token)) {
      index++;
      double operand = unary(evaluate, depth + 1);
      if (!evaluate) return 0;
      return switch (token) {
        case "+" -> operand;
        case "-" -> floating ? -operand : -(int) operand;
        case "!" -> operand == 0 ? 1 : 0;
        default -> {
          if (floating) throw error("Bitwise operator in floating expression");
          yield ~(int) operand;
        }
      };
    }
    if (token.equals("(")) {
      index++;
      double result = expression(0, evaluate, depth + 1);
      require(")");
      return result;
    }
    if (index >= tokens.size()) throw error("Missing expression operand");
    var value = tokens.get(index++).token();
    if (value.type() == ScriptToken.NAME && undefinedZero) return 0;
    if (value.type() == ScriptToken.LITERAL) return evaluate ? value.subtype() : 0;
    if (value.type() != ScriptToken.NUMBER)
      throw error("Expected numeric expression operand: " + value.text());
    // Q3 integer evaluation uses each token's integer field, including truncated float operands.
    return !evaluate ? 0 : floating ? (double) value.floatValue() : (double) value.intValue();
  }

  private double binary(String operator, double a, double b) throws ScriptException {
    return switch (operator) {
      case "||" -> a != 0 || b != 0 ? 1 : 0;
      case "&&" -> a != 0 && b != 0 ? 1 : 0;
      case "==" -> a == b ? 1 : 0;
      case "!=" -> a != b ? 1 : 0;
      case "<" -> a < b ? 1 : 0;
      case ">" -> a > b ? 1 : 0;
      case "<=" -> a <= b ? 1 : 0;
      case ">=" -> a >= b ? 1 : 0;
      case "+" -> floating ? (double) (float) (a + b) : (double) ((int) a + (int) b);
      case "-" -> floating ? (double) (float) (a - b) : (double) ((int) a - (int) b);
      case "*" -> floating ? (double) (float) (a * b) : (double) ((int) a * (int) b);
      case "/" -> {
        if (b == 0) throw error("Division by zero in script expression");
        yield floating ? (double) (float) (a / b) : (double) ((int) a / (int) b);
      }
      default -> {
        if (floating) throw error("Integer-only operator in floating expression: " + operator);
        int x = (int) a, y = (int) b;
        yield switch (operator) {
          case "|" -> x | y;
          case "&" -> x & y;
          case "^" -> x ^ y;
          case "<<", ">>" -> {
            if (y < 0 || y > 31) throw error("Shift count outside 0..31");
            yield operator.equals("<<") ? x << y : x >> y;
          }
          case "%" -> {
            if (y == 0) throw error("Remainder by zero in script expression");
            yield x % y;
          }
          default -> throw error("Unsupported expression operator " + operator);
        };
      }
    };
  }

  private String peek() {
    return index < tokens.size() ? tokens.get(index).text() : "";
  }

  private void require(String expected) throws ScriptException {
    if (!peek().equals(expected)) throw error("Expected '" + expected + "' in expression");
    index++;
  }

  private ScriptException error(String message) {
    return new ScriptException(
        index < tokens.size() ? tokens.get(index).location() : source, message);
  }
}
