import dev.bluevista.craftq3.client.net.BrowserPings;
import dev.bluevista.craftq3.client.net.LanServerList;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Authored differential adapter; reflection seeds only owned Java queue state from public native
 * metadata.
 */
public class BrowserPingJavaOracle {
  private static LanServerList servers;
  private static BrowserPings pings;
  private static int now, maximum;
  private static final Field SLOTS, ADDRESS, START, TIME, INFO;

  static {
    try {
      SLOTS = BrowserPings.class.getDeclaredField("slots");
      SLOTS.setAccessible(true);
      Class<?> slot = Class.forName(BrowserPings.class.getName() + "$Slot");
      ADDRESS = slot.getDeclaredField("address");
      START = slot.getDeclaredField("start");
      TIME = slot.getDeclaredField("time");
      INFO = slot.getDeclaredField("info");
      for (Field field : List.of(ADDRESS, START, TIME, INFO)) field.setAccessible(true);
    } catch (Exception failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }

  private static void reset() {
    servers = new LanServerList();
    now = 0;
    maximum = 800;
    pings =
        new BrowserPings(
            servers,
            () -> now,
            () -> maximum,
            "Quake3Arena",
            71,
            68,
            request ->
                System.out.println(
                    "SEND 0 "
                        + LanServerList.formatAddress(request.address())
                        + " "
                        + hex(request.command())));
  }

  private static String hex(String text) {
    return HexFormat.of().formatHex(text.getBytes(StandardCharsets.ISO_8859_1));
  }

  private static byte[] decode(String text) {
    return text.equals("-") ? new byte[0] : HexFormat.of().parseHex(text);
  }

  private static InetSocketAddress address(String text) throws Exception {
    int split = text.lastIndexOf(':');
    int port = Integer.parseInt(text.substring(split + 1));
    String host = text.substring(0, split);
    if (host.startsWith("[")) {
      host = host.substring(1, host.length() - 1);
      int scope = 0;
      int percent = host.indexOf('%');
      if (percent >= 0) {
        scope = Integer.parseInt(host.substring(percent + 1));
        host = host.substring(0, percent);
      }
      byte[] bytes = InetAddress.getByName(host).getAddress();
      if (bytes.length == 4) {
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 255;
        mapped[11] = (byte) 255;
        System.arraycopy(bytes, 0, mapped, 12, 4);
        bytes = mapped;
      }
      return new InetSocketAddress(Inet6Address.getByAddress(null, bytes, scope), port);
    }
    String[] components = host.split("\\.");
    byte[] bytes = new byte[4];
    for (int i = 0; i < 4; i++) bytes[i] = (byte) Integer.parseInt(components[i]);
    return new InetSocketAddress(InetAddress.getByAddress(bytes), port);
  }

  private static void slots() throws Exception {
    System.out.println("COUNT " + pings.count());
    Object[] slots = (Object[]) SLOTS.get(pings);
    for (int i = 0; i < slots.length; i++) {
      Object slot = slots[i];
      var address = (InetSocketAddress) ADDRESS.get(slot);
      if (address != null)
        System.out.println(
            "SLOT "
                + i
                + " "
                + LanServerList.formatAddress(address)
                + " "
                + START.getInt(slot)
                + " "
                + TIME.getInt(slot)
                + " "
                + hex((String) INFO.get(slot)));
    }
  }

  private static void serverState(int source) {
    System.out.println("SERVERS " + source + " " + servers.count(source));
    for (int i = 0; i < servers.count(source); i++) {
      var e = servers.entry(source, i).orElseThrow();
      System.out.println(
          "SERVER "
              + i
              + " "
              + (e.address() == null ? "invalid" : LanServerList.formatAddress(e.address()))
              + " "
              + e.ping()
              + " "
              + e.visible()
              + " "
              + e.netType()
              + " "
              + hex(e.hostName())
              + " "
              + hex(e.mapName()));
      System.out.println(
          "DETAIL "
              + i
              + " "
              + e.gameType()
              + " "
              + e.clients()
              + " "
              + e.maxClients()
              + " "
              + e.minPing()
              + " "
              + e.maxPing()
              + " "
              + e.punkbuster()
              + " "
              + e.humanPlayers()
              + " "
              + e.needPass()
              + " "
              + hex(e.game()));
    }
  }

  public static void main(String[] args) throws Exception {
    reset();
    System.out.println("READY slots=32 local=128 global=4096");
    var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.ISO_8859_1));
    String line;
    while ((line = in.readLine()) != null) {
      String[] a = line.split(" +");
      switch (a[0]) {
        case "reset" -> {
          reset();
          System.out.println("RESET");
        }
        case "time" -> {
          now = Integer.parseInt(a[1]);
          System.out.println("TIME " + now);
        }
        case "max" -> {
          maximum = Integer.parseInt(a[1]);
          System.out.println("MAX " + maximum);
        }
        case "ping" -> {
          pings.request(address(a[1]));
          slots();
        }
        case "slots" -> slots();
        case "clear" -> {
          pings.clear(Integer.parseInt(a[1]));
          slots();
        }
        case "get" -> {
          int index = Integer.parseInt(a[1]), size = a.length > 2 ? Integer.parseInt(a[2]) : 1024;
          var got = pings.get(index);
          String value =
              got.map(BrowserPings.Ping::address).map(LanServerList::formatAddress).orElse("");
          System.out.println(
              "GET "
                  + index
                  + " "
                  + got.map(BrowserPings.Ping::milliseconds).orElse(0)
                  + " "
                  + hex(value.substring(0, Math.min(value.length(), size - 1))));
          slots();
        }
        case "info" -> {
          int index = Integer.parseInt(a[1]), size = a.length > 2 ? Integer.parseInt(a[2]) : 1024;
          String text = pings.info(index);
          System.out.println(
              "INFO " + index + " " + hex(text.substring(0, Math.min(text.length(), size - 1))));
        }
        case "reply" -> {
          pings.receiveInfo(address(a[1]), decode(a[2]));
          slots();
        }
        case "source" -> {
          int source = Integer.parseInt(a[1]);
          pings.discoverySource(source);
          System.out.println("SOURCE " + source);
        }
        case "update" -> {
          System.out.println("UPDATE " + (pings.updateVisible(Integer.parseInt(a[1])) ? 1 : 0));
          slots();
        }
        case "server" -> {
          int source = Integer.parseInt(a[1]), index = Integer.parseInt(a[2]);
          servers.updateEntry(
              source,
              index,
              new LanServerList.Entry(
                  address(a[3]),
                  "",
                  "",
                  "",
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  Integer.parseInt(a[4]),
                  Integer.parseInt(a[5]),
                  0,
                  0,
                  0));
          if (index >= servers.count(source)) servers.setCount(source, index + 1);
          serverState(source);
        }
        case "servers" -> serverState(Integer.parseInt(a[1]));
        case "count" -> {
          int count = Integer.parseInt(a[2]);
          servers.setCount(Integer.parseInt(a[1]), count);
          System.out.println("SETCOUNT " + count);
        }
        case "overflow" -> {
          int index = Integer.parseInt(a[1]);
          var list = new ArrayList<>(pings.globalOverflow());
          while (list.size() <= index) list.add(address(a[2]));
          list.set(index, address(a[2]));
          pings.setGlobalOverflow(list);
          System.out.println("OVERFLOW " + list.size());
        }
        case "slot" -> {
          Object slot = ((Object[]) SLOTS.get(pings))[Integer.parseInt(a[1])];
          ADDRESS.set(slot, address(a[2]));
          START.setInt(slot, Integer.parseInt(a[3]));
          TIME.setInt(slot, Integer.parseInt(a[4]));
          INFO.set(slot, new String(decode(a[5]), StandardCharsets.ISO_8859_1));
          slots();
        }
        case "raw" -> {
          int index = Integer.parseInt(a[1]);
          Object slot = ((Object[]) SLOTS.get(pings))[index];
          var address = (InetSocketAddress) ADDRESS.get(slot);
          System.out.println(
              "RAW "
                  + index
                  + " "
                  + (address == null ? 0 : address.getPort())
                  + " "
                  + START.getInt(slot)
                  + " "
                  + TIME.getInt(slot)
                  + " "
                  + hex((String) INFO.get(slot)));
        }
        default -> throw new IllegalArgumentException(line);
      }
      System.out.println("END");
      System.out.flush();
    }
  }
}
