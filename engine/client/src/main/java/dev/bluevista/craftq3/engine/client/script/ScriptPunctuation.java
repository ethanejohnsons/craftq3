package dev.bluevista.craftq3.engine.client.script;

import java.util.List;

/**
 * The default C-like punctuation set of menu scripts with the {@code P_*} ids the guest sees as the
 * token subtype. Matching is longest first.
 */
public final class ScriptPunctuation {
  /** Punctuation text by id; index 0 is unused. */
  private static final List<String> BY_ID =
      List.of(
          "", ">>=", "<<=", "...", "##", "&&", "||", ">=", "<=", "==", "!=", "*=", "/=", "%=", "+=",
          "-=", "++", "--", "&=", "|=", "^=", ">>", "<<", "->", "::", ".*", "*", "/", "%", "+", "-",
          "=", "&", "|", "^", "~", "!", ">", "<", ".", ",", ";", ":", "?", "(", ")", "{", "}", "[",
          "]", "\\", "#", "$");

  private ScriptPunctuation() {}

  /** The id of the longest punctuation starting at {@code offset}, or 0. */
  static int match(String text, int offset) {
    int best = 0, bestLength = 0;
    for (int id = 1; id < BY_ID.size(); id++) {
      String candidate = BY_ID.get(id);
      if (candidate.length() > bestLength && text.startsWith(candidate, offset)) {
        best = id;
        bestLength = candidate.length();
      }
    }
    return best;
  }

  /** The text of punctuation {@code id}. */
  public static String text(int id) {
    return BY_ID.get(id);
  }

  /** The id of {@code text}, or 0 when it is not punctuation. */
  public static int id(String text) {
    int id = BY_ID.indexOf(text);
    return Math.max(id, 0);
  }
}
