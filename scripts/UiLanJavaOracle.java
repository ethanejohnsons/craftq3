import dev.bluevista.craftq3.client.net.LanServerList;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** Authored line adapter around the production list, with numeric-only fixture address parsing. */
public final class UiLanJavaOracle {
  private static String decode(String hex) {
    return hex.equals("-")
        ? ""
        : new String(HexFormat.of().parseHex(hex), StandardCharsets.ISO_8859_1);
  }

  private static InetSocketAddress address(String value) throws Exception {
    if (value.equals("!fixture-failure!")) return null;
    String host;
    int port = 27960;
    if (value.startsWith("[")) {
      int close = value.indexOf(']');
      host = value.substring(1, close);
      if (close + 1 < value.length()) port = Integer.parseInt(value.substring(close + 2));
    } else {
      int colon = value.indexOf(':');
      host = colon >= 0 ? value.substring(0, colon) : value;
      if (colon >= 0) port = Integer.parseInt(value.substring(colon + 1));
    }
    if (!host.matches("[0-9a-fA-F:.%]+"))
      throw new IllegalArgumentException("Fixture requires numeric addresses");
    InetAddress parsed = InetAddress.getByName(host);
    if (host.contains(":") && parsed.getAddress().length == 4) {
      byte[] bytes = new byte[16];
      bytes[10] = bytes[11] = -1;
      System.arraycopy(parsed.getAddress(), 0, bytes, 12, 4);
      parsed = Inet6Address.getByAddress(null, bytes, 0);
    }
    return new InetSocketAddress(parsed, port);
  }

  private static void counts(LanServerList list) {
    System.out.println("COUNTS " + list.count(0) + " " + list.count(2) + " " + list.count(3));
  }

  public static void main(String[] args) throws Exception {
    LanServerList list = new LanServerList();
    try (var input =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.ISO_8859_1))) {
      for (String line; (line = input.readLine()) != null; ) {
        String[] a = line.split(" ");
        int source = a.length > 1 ? Integer.parseInt(a[1]) : 0;
        switch (a[0]) {
          case "reset" -> {
            list = new LanServerList();
            System.out.println("RESET");
          }
          case "counts" -> {
            list.setCount(0, source);
            list.setCount(2, Integer.parseInt(a[2]));
            list.setCount(3, Integer.parseInt(a[3]));
            counts(list);
          }
          case "seed" -> {
            list.updateEntry(
                source,
                Integer.parseInt(a[2]),
                new LanServerList.Entry(
                    address(decode(a[3])),
                    decode(a[4]),
                    decode(a[5]),
                    decode(a[6]),
                    Integer.parseInt(a[7]),
                    Integer.parseInt(a[8]),
                    Integer.parseInt(a[9]),
                    Integer.parseInt(a[10]),
                    Integer.parseInt(a[11]),
                    Integer.parseInt(a[12]),
                    Integer.parseInt(a[13]),
                    Integer.parseInt(a[14]),
                    Integer.parseInt(a[15]),
                    Integer.parseInt(a[16]),
                    Integer.parseInt(a[17])));
            System.out.println("SEEDED");
          }
          case "add" -> {
            System.out.println("ADDED " + list.add(source, decode(a[2]), address(decode(a[3]))));
            counts(list);
          }
          case "remove" -> {
            list.remove(source, address(decode(a[2])));
            counts(list);
          }
          case "count" -> System.out.println("COUNT " + list.count(source));
          case "kind" -> {
            var entry = list.entry(source, Integer.parseInt(a[2])).orElse(null);
            System.out.println(
                "KIND "
                    + (entry == null
                        ? -1
                        : entry.address() == null
                            ? 0
                            : entry.address().getAddress() instanceof Inet6Address ? 5 : 4));
          }
          case "ping" -> System.out.println("VALUE " + list.ping(source, Integer.parseInt(a[2])));
          case "visible" ->
              System.out.println("VALUE " + list.visible(source, Integer.parseInt(a[2])));
          case "mark" -> {
            list.markVisible(source, Integer.parseInt(a[2]), Integer.parseInt(a[3]));
            System.out.println("MARKED");
          }
          case "resetpings" -> {
            list.resetPings(source);
            System.out.println("RESET_PINGS");
          }
          case "compare" ->
              System.out.println(
                  "COMPARE "
                      + list.compare(
                          source,
                          Integer.parseInt(a[2]),
                          Integer.parseInt(a[3]),
                          Integer.parseInt(a[4]),
                          Integer.parseInt(a[5])));
          case "info", "address" -> {
            int index = Integer.parseInt(a[2]), capacity = Integer.parseInt(a[3]);
            boolean valid = list.entry(source, index).isPresent();
            if (valid && capacity < 1) {
              System.out.println(
                  "ERROR 0 "
                      + HexFormat.of()
                          .formatHex(
                              "Q_strncpyz: destsize < 1".getBytes(StandardCharsets.ISO_8859_1)));
            } else {
              // This adapter records the separately observed native buffer-write convention.
              byte[] buffer = new byte[Math.max(0, capacity) + 8];
              java.util.Arrays.fill(buffer, (byte) 0x7f);
              buffer[0] = 0;
              if (valid) {
                java.util.Arrays.fill(buffer, 0, capacity, (byte) 0);
                String text =
                    a[0].equals("info") ? list.info(source, index) : list.address(source, index);
                byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
                System.arraycopy(bytes, 0, buffer, 0, Math.min(bytes.length, capacity - 1));
              }
              System.out.println("BUFFER " + HexFormat.of().formatHex(buffer));
            }
          }
          default -> throw new IllegalArgumentException("Unknown fixture operation " + a[0]);
        }
        System.out.println("END");
        System.out.flush();
      }
    }
  }
}
