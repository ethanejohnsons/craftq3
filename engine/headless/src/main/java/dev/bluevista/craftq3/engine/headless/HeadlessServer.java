package dev.bluevista.craftq3.engine.headless;

import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import dev.bluevista.craftq3.engine.common.time.Clock;
import dev.bluevista.craftq3.engine.fs.HomeStore;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import dev.bluevista.craftq3.engine.net.transport.PacketTransport;
import dev.bluevista.craftq3.engine.session.CommandLine;
import dev.bluevista.craftq3.engine.session.GameLocalServer;
import dev.bluevista.craftq3.engine.session.LocalServer;
import dev.bluevista.craftq3.engine.session.ServerRunner;
import dev.bluevista.craftq3.engine.session.TransportFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/**
 * The command-line dedicated Quake III server: the original argument syntax ({@code +set dedicated
 * 1 +set net_port 27960 +map q3dm17}), the qagame with bots on the {@code cq3-sim} thread, UDP on
 * {@code net_ip}:{@code net_port} (default {@code 0.0.0.0:27960}, the next ports tried when busy)
 * with rcon, and the console on standard input ({@code quit} stops it).
 *
 * <p>The installation is {@code +set fs_basepath <dir>}, else {@code $CRAFTQ3_Q3ROOT}, else the
 * working directory; the game {@code +set fs_game <mod>}; the writable home {@code +set fs_homepath
 * <dir>} (default {@code ~/.craftq3}).
 */
public final class HeadlessServer implements AutoCloseable {
  private final SearchPath searchPath;
  private final HomeStore home;
  private final ServerRunner runner;
  private final InetSocketAddress address;
  private final CountDownLatch stopped = new CountDownLatch(1);

  /**
   * Starts a dedicated server for {@code commands} (parsed command line).
   *
   * @param output receives every console line (any thread)
   */
  public HeadlessServer(List<String> commands, Consumer<String> output, TransportFactory sockets)
      throws IOException {
    Objects.requireNonNull(output);
    Path install = installRoot(commands);
    String game = valueOr(commands, "fs_game", "baseq3");
    if (game.isBlank()) game = "baseq3";
    searchPath = SearchPath.mount(install, game);
    Path homeRoot = Path.of(valueOr(commands, "fs_homepath", defaultHome()));
    HomeStore store = null;
    try {
      store = HomeStore.open(homeRoot.resolve(VirtualPath.gameDirectory(game)));
    } catch (IOException | RuntimeException unusable) {
      output.accept("Home directory unusable, nothing will be saved: " + unusable.getMessage());
    }
    home = store;
    LocalServer server =
        GameLocalServer.FACTORY.create(
            new LocalServer.Context(
                searchPath,
                home,
                install,
                game,
                new ThreadOwner("dedicated server"),
                Clock.system(),
                java.time.Clock.systemDefaultZone(),
                System.nanoTime(),
                output::accept,
                true));
    runner = new ServerRunner(server, Clock.system(), true);
    address = listen(commands, sockets, output);
    StringBuilder startup = new StringBuilder();
    for (String command : commands)
      if (CommandLine.isSet(command)) startup.append(command).append('\n');
    startup.append("exec default.cfg\nexec q3config.cfg\nexec autoexec.cfg\n");
    for (String command : commands) startup.append(command).append('\n');
    runner.start();
    runner.post(startup.toString());
  }

  private InetSocketAddress listen(
      List<String> commands, TransportFactory sockets, Consumer<String> output) throws IOException {
    String ip = valueOr(commands, "net_ip", "0.0.0.0");
    int port = Integer.parseInt(valueOr(commands, "net_port", "27960"));
    IOException last = null;
    for (int attempt = 0; attempt < (port == 0 ? 1 : 10); attempt++) {
      try {
        PacketTransport socket =
            sockets.listen(new InetSocketAddress(InetAddress.getByName(ip), port + attempt));
        InetSocketAddress bound = (InetSocketAddress) socket.localAddress();
        runner.submit(server -> server.addTransport(socket, false));
        output.accept("Server listening on " + bound);
        return bound;
      } catch (IOException busy) {
        last = busy;
      }
    }
    throw new IOException("Couldn't open a server socket on " + ip + ":" + port, last);
  }

  private static String defaultHome() {
    String home = System.getenv("HOME");
    if (home == null || home.isBlank()) home = System.getenv("USERPROFILE");
    return (home == null || home.isBlank() ? "." : home) + "/.craftq3";
  }

  private static Path installRoot(List<String> commands) {
    String base = CommandLine.setValue(commands, "fs_basepath");
    if (base != null && !base.isBlank()) return Path.of(base);
    String env = System.getenv("CRAFTQ3_Q3ROOT");
    if (env != null && !env.isBlank()) return Path.of(env);
    return Path.of("").toAbsolutePath();
  }

  private static String valueOr(List<String> commands, String name, String fallback) {
    String value = CommandLine.setValue(commands, name);
    return value == null ? fallback : value;
  }

  /** The UDP address the server listens on. */
  public InetSocketAddress address() {
    return address;
  }

  /** Console text for the server (any thread). */
  public void command(String text) {
    runner.post(text);
  }

  public LocalServer.Status status() {
    return runner.status();
  }

  /** Waits until {@link #close()} ran. */
  public void await() throws InterruptedException {
    stopped.await();
  }

  @Override
  public void close() throws IOException {
    runner.close();
    if (home != null) home.close();
    searchPath.close();
    stopped.countDown();
  }

  public static void main(String[] args) throws Exception {
    List<String> commands = CommandLine.parse(args);
    HeadlessServer server = new HeadlessServer(commands, System.out::println, TransportFactory.UDP);
    Runtime.getRuntime()
        .addShutdownHook(
            Thread.ofPlatform()
                .unstarted(
                    () -> {
                      try {
                        server.close();
                      } catch (IOException ignored) {
                        // Exiting.
                      }
                    }));
    Thread.ofPlatform()
        .daemon(true)
        .name("console")
        .start(
            () -> {
              try (BufferedReader in =
                  new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null; ) {
                  if (line.strip().equalsIgnoreCase("quit")) {
                    server.close();
                    return;
                  }
                  if (!line.isBlank()) server.command(line);
                }
              } catch (IOException closed) {
                // Standard input closed: keep serving until killed.
              }
            });
    server.await();
  }
}
