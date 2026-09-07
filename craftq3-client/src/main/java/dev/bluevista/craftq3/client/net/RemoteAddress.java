package dev.bluevista.craftq3.client.net;

/**
 * Parsed engine connect address, without DNS or network access. Hosts retain their spelling;
 * brackets delimit an IPv6 literal in the input and are not part of the returned host. This is an
 * explicit address syntax, not a claim of native parser parity.
 */
public record RemoteAddress(String host, int port) {
  /** Public protocol metadata: qcommon/qcommon.h PORT_SERVER. */
  public static final int DEFAULT_PORT = 27960;

  private static final int MAX_LENGTH = 1024;

  public RemoteAddress {
    checkText(host);
    if (port < 1 || port > 65535) throw invalid();
    if (host.indexOf(':') >= 0) checkIpv6(host);
    else checkHostname(host);
  }

  /**
   * Accepts an ASCII hostname or dotted IPv4 with optional :port, [IPv6] with optional :port, or
   * unbracketed IPv6 at the default port. IPv6 may carry a numeric or interface-name %scope, which
   * is preserved for the resolver to interpret. Input is never trimmed or silently truncated.
   */
  public static RemoteAddress parse(String text) {
    checkText(text);
    if (text.charAt(0) == '[') {
      int close = text.indexOf(']');
      if (close < 0 || text.indexOf('[', 1) >= 0 || text.indexOf(']', close + 1) >= 0)
        throw invalid();
      String host = text.substring(1, close);
      if (host.indexOf(':') < 0) throw invalid();
      if (close == text.length() - 1) return new RemoteAddress(host, DEFAULT_PORT);
      if (text.charAt(close + 1) != ':') throw invalid();
      return new RemoteAddress(host, parsePort(text.substring(close + 2)));
    }
    if (text.indexOf('[') >= 0 || text.indexOf(']') >= 0) throw invalid();
    int colon = text.indexOf(':');
    if (colon < 0 || colon != text.lastIndexOf(':')) return new RemoteAddress(text, DEFAULT_PORT);
    return new RemoteAddress(text.substring(0, colon), parsePort(text.substring(colon + 1)));
  }

  private static void checkText(String text) {
    if (text == null || text.isEmpty() || text.length() > MAX_LENGTH) throw invalid();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c))
        throw invalid();
    }
  }

  private static int parsePort(String text) {
    if (text.isEmpty()) throw invalid();
    int port = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (!digit(c)) throw invalid();
      port = port * 10 + c - '0';
      if (port > 65535) throw invalid();
    }
    if (port == 0) throw invalid();
    return port;
  }

  private static void checkHostname(String host) {
    if (host.chars().allMatch(c -> digit((char) c) || c == '.') && host.indexOf('.') >= 0) {
      checkIpv4(host);
      return;
    }
    String name = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    if (name.isEmpty() || name.length() > 253) throw invalid();
    for (String label : name.split("\\.", -1)) {
      if (label.isEmpty()
          || label.length() > 63
          || !alphanumeric(label.charAt(0))
          || !alphanumeric(label.charAt(label.length() - 1))) throw invalid();
      for (int i = 0; i < label.length(); i++) {
        char c = label.charAt(i);
        if (!alphanumeric(c) && c != '-') throw invalid();
      }
    }
  }

  private static void checkIpv4(String address) {
    String[] parts = address.split("\\.", -1);
    if (parts.length != 4) throw invalid();
    for (String part : parts) {
      if (part.isEmpty() || part.length() > 3) throw invalid();
      int value = 0;
      for (int i = 0; i < part.length(); i++) {
        if (!digit(part.charAt(i))) throw invalid();
        value = value * 10 + part.charAt(i) - '0';
      }
      if (value > 255) throw invalid();
    }
  }

  private static void checkIpv6(String host) {
    int scope = host.indexOf('%');
    String address = host;
    if (scope >= 0) {
      if (host.indexOf('%', scope + 1) >= 0) throw invalid();
      checkScope(host.substring(scope + 1));
      address = host.substring(0, scope);
    }
    int compression = address.indexOf("::");
    if (compression != address.lastIndexOf("::")) throw invalid();
    int groups;
    if (compression < 0) {
      groups = countGroups(address, true);
      if (groups != 8) throw invalid();
    } else {
      String left = address.substring(0, compression);
      String right = address.substring(compression + 2);
      groups =
          (left.isEmpty() ? 0 : countGroups(left, false))
              + (right.isEmpty() ? 0 : countGroups(right, true));
      if (groups >= 8) throw invalid();
    }
  }

  private static int countGroups(String part, boolean allowsIpv4Tail) {
    int groups = 0;
    String[] pieces = part.split(":", -1);
    for (int i = 0; i < pieces.length; i++) {
      String piece = pieces[i];
      if (piece.indexOf('.') >= 0) {
        if (!allowsIpv4Tail || i != pieces.length - 1) throw invalid();
        checkIpv4(piece);
        groups += 2;
      } else {
        if (piece.isEmpty() || piece.length() > 4) throw invalid();
        for (int j = 0; j < piece.length(); j++) {
          char c = piece.charAt(j);
          if (!digit(c) && !(c >= 'a' && c <= 'f') && !(c >= 'A' && c <= 'F')) throw invalid();
        }
        groups++;
      }
    }
    return groups;
  }

  private static void checkScope(String scope) {
    if (scope.isEmpty()) throw invalid();
    boolean numeric = true;
    for (int i = 0; i < scope.length(); i++) {
      char c = scope.charAt(i);
      if (!alphanumeric(c) && c != '_' && c != '-' && c != '.') throw invalid();
      numeric &= digit(c);
    }
    if (numeric) {
      // Java's numeric IPv6 scope is a nonnegative signed interface index.
      try {
        Integer.parseInt(scope);
      } catch (NumberFormatException invalidScope) {
        throw invalid();
      }
    }
  }

  private static boolean digit(char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean alphanumeric(char c) {
    return digit(c) || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid remote address");
  }
}
