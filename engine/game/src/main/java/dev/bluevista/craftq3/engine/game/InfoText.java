package dev.bluevista.craftq3.engine.game;

/**
 * Lenient info-string edits with the original {@code Info_ValueForKey} / {@code
 * Info_SetValueForKey} semantics: a string is scanned as {@code \key\value} pairs (a missing
 * leading backslash is tolerated), lookups are case-insensitive, a set removes every existing pair
 * with the key and appends the new one, and an empty value only removes. Values containing a
 * backslash, quote or semicolon are refused, as the original refuses them.
 */
final class InfoText {
  private InfoText() {}

  static String valueForKey(String info, String key) {
    int i = 0, n = info.length();
    if (i < n && info.charAt(i) == '\\') i++;
    while (i < n) {
      int keyEnd = info.indexOf('\\', i);
      if (keyEnd < 0) return "";
      String k = info.substring(i, keyEnd);
      int valueEnd = info.indexOf('\\', keyEnd + 1);
      if (valueEnd < 0) valueEnd = n;
      if (k.equalsIgnoreCase(key)) return info.substring(keyEnd + 1, valueEnd);
      i = valueEnd + 1;
    }
    return "";
  }

  static String removeKey(String info, String key) {
    StringBuilder out = new StringBuilder();
    int i = 0, n = info.length();
    if (i < n && info.charAt(i) == '\\') i++;
    while (i < n) {
      int keyEnd = info.indexOf('\\', i);
      if (keyEnd < 0) break;
      String k = info.substring(i, keyEnd);
      int valueEnd = info.indexOf('\\', keyEnd + 1);
      if (valueEnd < 0) valueEnd = n;
      if (!k.equalsIgnoreCase(key))
        out.append('\\').append(k).append('\\').append(info, keyEnd + 1, valueEnd);
      i = valueEnd + 1;
    }
    return out.toString();
  }

  /**
   * @param capacity the buffer size including the terminator; a result that would not fit leaves
   *     {@code info} unchanged
   */
  static String setValueForKey(String info, String key, String value, int capacity) {
    if (key.indexOf('\\') >= 0 || value.indexOf('\\') >= 0) return info;
    if (key.indexOf(';') >= 0 || value.indexOf(';') >= 0) return info;
    if (key.indexOf('"') >= 0 || value.indexOf('"') >= 0) return info;
    String removed = removeKey(info, key);
    if (value.isEmpty()) return removed;
    String result = removed + "\\" + key + "\\" + value;
    return result.length() >= capacity ? info : result;
  }
}
