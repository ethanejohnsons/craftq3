package dev.bluevista.craftq3.client.net;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class RemoteAddressTest {
  @Test
  void retainsHostnamesAndUsesPublicDefaultPort() {
    assertEquals(27960, RemoteAddress.DEFAULT_PORT);
    assertEquals(new RemoteAddress("localhost", 27960), RemoteAddress.parse("localhost"));
    assertEquals(
        new RemoteAddress("Quake-3.Example.COM", 27960),
        RemoteAddress.parse("Quake-3.Example.COM"));
    assertEquals(new RemoteAddress("server.example.", 1), RemoteAddress.parse("server.example.:1"));
    assertEquals(new RemoteAddress("example.com", 65535), RemoteAddress.parse("example.com:65535"));
    assertEquals(
        new RemoteAddress("example.com", 27960), RemoteAddress.parse("example.com:00027960"));
  }

  @Test
  void parsesDottedIpv4WithoutDnsOrAlternateNumericAbbreviations() {
    assertEquals(new RemoteAddress("127.0.0.1", 27960), RemoteAddress.parse("127.0.0.1"));
    assertEquals(new RemoteAddress("192.0.2.25", 30000), RemoteAddress.parse("192.0.2.25:30000"));
    assertEquals(
        new RemoteAddress("255.255.255.255", 27960), RemoteAddress.parse("255.255.255.255"));
    reject("256.0.0.1", "1.2.3", "1.2.3.4.5", "1..2.3", "1.2.3.-1", "1.2.3.1234");
  }

  @Test
  void bracketedIpv6SupportsPortAndUnbracketedIpv6AlwaysUsesDefault() {
    assertEquals(new RemoteAddress("::1", 27960), RemoteAddress.parse("[::1]"));
    assertEquals(
        new RemoteAddress("2001:db8::A", 30000), RemoteAddress.parse("[2001:db8::A]:30000"));
    assertEquals(new RemoteAddress("2001:db8::2796", 27960), RemoteAddress.parse("2001:db8::2796"));
    assertEquals(
        new RemoteAddress("1:2:3:4:5:6:7:8", 27960), RemoteAddress.parse("1:2:3:4:5:6:7:8"));
    assertEquals(new RemoteAddress("::", 27960), RemoteAddress.parse("::"));
    assertEquals(
        new RemoteAddress("::ffff:192.0.2.1", 27960), RemoteAddress.parse("[::ffff:192.0.2.1]"));
    assertEquals(
        new RemoteAddress("1:2:3:4:5:6:192.0.2.1", 27960),
        RemoteAddress.parse("1:2:3:4:5:6:192.0.2.1"));
  }

  @Test
  void preservesIpv6ScopesForLaterResolutionWithoutInspectingLocalInterfaces() {
    assertEquals(new RemoteAddress("fe80::1%en0", 27960), RemoteAddress.parse("[fe80::1%en0]"));
    assertEquals(
        new RemoteAddress("fe80::1%eth0.42", 27961),
        RemoteAddress.parse("[fe80::1%eth0.42]:27961"));
    assertEquals(new RemoteAddress("fe80::1%3", 27960), RemoteAddress.parse("fe80::1%3"));
    assertEquals(
        new RemoteAddress("fe80::1%2147483647", 27960), RemoteAddress.parse("fe80::1%2147483647"));
    reject("fe80::1%", "fe80::1%en0%1", "fe80::1%2147483648", "[fe80::1%en0:123]", "host%en0");
  }

  @Test
  void rejectsInvalidPortsAndBracketSuffixesRatherThanTruncating() {
    reject(
        "host:",
        "host:0",
        "host:65536",
        "host:-1",
        "host:+1",
        "host:1x",
        "host:999999999999999999999999999999",
        "host:１２３",
        "[::1]:",
        "[::1]:0",
        "[::1]:65536",
        "[::1]junk",
        "[::1]:1junk",
        "[::1]:1:2",
        "[::1",
        "::1]",
        "[[::1]]",
        "[127.0.0.1]:27960",
        "[example.com]",
        "[]",
        ":27960");
  }

  @Test
  void rejectsMalformedIpv6AndUriOrCommandSyntax() {
    reject(
        "1:2:3",
        "1:2:3:4:5:6:7",
        "1:2:3:4:5:6:7:8:9",
        "1:2:3:4:5:6:7:8::",
        ":::",
        "1:::2",
        "1::2::3",
        "12345::",
        "gggg::1",
        "::ffff:256.0.0.1",
        "192.0.2.1::",
        "192.0.2.1::192.0.2.1",
        "::192.0.2.1:2",
        "quake://example.com",
        "udp://[::1]:27960",
        "https://example.com",
        "example.com/path",
        "user@example.com",
        "host;quit",
        "host?x",
        "host#x",
        "a..b",
        ".host",
        "-host",
        "host-",
        "host_name");
  }

  @Test
  void enforcesLengthAndRejectsWhitespaceControlsAndInvalidConstructedRecords() {
    reject(
        null,
        "",
        " host",
        "host ",
        "host\t",
        "host\n",
        "ho\u0000st",
        "ho\u007fst",
        "ho\u00a0st",
        "ho\u2003st",
        "a".repeat(1025));
    String scoped = "[fe80::1%" + "a".repeat(1014) + "]";
    assertEquals(1024, scoped.length());
    assertEquals(scoped.substring(1, scoped.length() - 1), RemoteAddress.parse(scoped).host());
    reject(scoped + "a");
    assertThrows(IllegalArgumentException.class, () -> new RemoteAddress("host", 0));
    assertThrows(IllegalArgumentException.class, () -> new RemoteAddress("host", 65536));
    assertThrows(IllegalArgumentException.class, () -> new RemoteAddress("[::1]", 27960));
    assertThrows(IllegalArgumentException.class, () -> new RemoteAddress(null, 27960));
  }

  private static void reject(String... values) {
    for (String value : values)
      assertThrows(
          IllegalArgumentException.class, () -> RemoteAddress.parse(value), String.valueOf(value));
  }
}
