package dev.bluevista.craftq3.engine.session;

import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import dev.bluevista.craftq3.engine.console.CommandInbox;
import dev.bluevista.craftq3.engine.console.ConfigSink;
import dev.bluevista.craftq3.engine.console.CvarTable;
import dev.bluevista.craftq3.engine.formats.FileViewAssets;
import dev.bluevista.craftq3.engine.fs.PureManifest;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import dev.bluevista.craftq3.engine.game.GameServer;
import dev.bluevista.craftq3.engine.game.ServerClient;
import dev.bluevista.craftq3.engine.game.net.Protocol68Host;
import dev.bluevista.craftq3.engine.net.transport.PacketTransport;
import dev.bluevista.craftq3.engine.vmabi.service.FsVmFiles;
import dev.bluevista.craftq3.engine.vmabi.service.ModList;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * The {@link LocalServer} of {@code engine:game}: a {@link GameServer} running the original qagame
 * with botlib, served by a {@link Protocol68Host} on every transport the session adds (the loopback
 * link of the local player, the hosting UDP socket). The server reads through its own {@link
 * PureManifest} over the session's search path, so {@code sv_paks} and {@code sv_referencedPaks}
 * describe what it loaded; {@code exec} reads the home store first; nothing else is written but
 * what the qagame writes.
 */
public final class GameLocalServer implements LocalServer {
  /** Builds {@code engine:game} servers. */
  public static final Factory FACTORY = GameLocalServer::new;

  private final Context context;
  private final PureManifest manifest;
  private final GameServer server;
  private final Protocol68Host host;
  private final List<PacketTransport> transports = new ArrayList<>();
  private int spawns, drops, sync;
  private boolean closed;
  private Status status = Status.INITIAL;
  private int cvarCount = -1;
  private Set<String> cvarNames = Set.of();
  private Key lastKey;

  /** What a published status depends on, besides the frame count. */
  private record Key(
      Phase phase,
      String map,
      int spawns,
      int drops,
      String dropReason,
      int humans,
      int bots,
      long degradations,
      int sync,
      int cvars) {}

  public GameLocalServer(Context context) throws IOException {
    this.context = context;
    this.manifest = new PureManifest(context.searchPath());
    var view = manifest.view();
    List<Path> roots = new ArrayList<>();
    if (context.home() != null) roots.add(context.home().root());
    if (context.installRoot() != null) roots.add(context.installRoot());
    GameServer.Builder builder =
        GameServer.builder()
            .owner(context.owner())
            .output(context.output())
            .files(new FileViewAssets(view))
            .vmFiles(
                new FsVmFiles(view, context.home(), roots.isEmpty() ? null : new ModList(roots)))
            .clocks(context.clock(), context.wallClock())
            .pure(manifest)
            .seed(context.seed())
            .config(this::readConfig, ConfigSink.NONE);
    server = builder.build();
    host = new Protocol68Host(server, name -> Optional.empty(), new Random(context.seed()));
    CvarTable cvars = server.cvars();
    cvars.set("dedicated", context.dedicated() ? "1" : "0");
    if (!context.game().equalsIgnoreCase("baseq3")) cvars.set("fs_game", context.game());
    server
        .console()
        .commands()
        .register(
            SYNC_COMMAND,
            args -> {
              try {
                sync = Integer.parseInt(args.argc() > 1 ? args.argv(1) : "0");
              } catch (NumberFormatException invalid) {
                sync = 0;
              }
            });
    server.addListener(
        new GameServer.Listener() {
          @Override
          public void levelSpawned(GameServer source) {
            spawns++;
          }

          @Override
          public void levelEnded(GameServer source, String reason) {
            if (source.state() == GameServer.State.DROPPED) drops++;
          }
        });
  }

  private Optional<String> readConfig(String path) {
    Optional<VirtualPath> parsed = VirtualPath.parse(path);
    if (parsed.isEmpty()) return Optional.empty();
    try {
      if (context.home() != null) {
        Optional<byte[]> saved = context.home().readIfPresent(parsed.get());
        if (saved.isPresent())
          return Optional.of(new String(saved.get(), StandardCharsets.ISO_8859_1));
      }
      var view = manifest.view();
      if (!view.exists(parsed.get())) return Optional.empty();
      return Optional.of(new String(view.read(parsed.get()), StandardCharsets.ISO_8859_1));
    } catch (IOException unreadable) {
      return Optional.empty();
    }
  }

  /** The game server (owner thread only). */
  public GameServer server() {
    return server;
  }

  /** The network endpoint (owner thread only). */
  public Protocol68Host host() {
    return host;
  }

  @Override
  public ThreadOwner owner() {
    return server.owner();
  }

  @Override
  public CommandInbox inbox() {
    return server.console().inbox();
  }

  @Override
  public void addTransport(PacketTransport transport, boolean local) {
    owner().check();
    transports.add(transport);
    host.addTransport(transport, local);
  }

  @Override
  public void frame(long nowMillis) {
    if (closed) return;
    host.frame(nowMillis);
    publish();
  }

  private void publish() {
    GameServer.State state = server.state();
    Phase phase =
        switch (state) {
          case RUNNING -> Phase.RUNNING;
          case DROPPED -> Phase.DROPPED;
          case CLOSED -> Phase.CLOSED;
          case IDLE, LOADING -> Phase.IDLE;
        };
    int humans = 0, bots = 0;
    if (state == GameServer.State.RUNNING) {
      for (int i = 0; i < server.maxClients(); i++) {
        ServerClient client = server.client(i);
        if (client.state() == ServerClient.State.FREE
            || client.state() == ServerClient.State.ZOMBIE) continue;
        if (client.isBot()) bots++;
        else humans++;
      }
    }
    CvarTable cvars = server.cvars();
    if (cvars.size() != cvarCount) {
      cvarCount = cvars.size();
      Set<String> names = new HashSet<>();
      for (int i = 0; i < cvarCount; i++) names.add(cvars.get(i).name().toLowerCase(Locale.ROOT));
      cvarNames = Set.copyOf(names);
    }
    String map = server.currentMap().map(m -> m.name()).orElse("");
    Key key =
        new Key(
            phase,
            map,
            spawns,
            drops,
            server.dropReason(),
            humans,
            bots,
            server.degradationTotal(),
            sync,
            cvarCount);
    if (key.equals(lastKey) && (server.framesRun() - status.frames()) < 20) return;
    lastKey = key;
    status =
        new Status(
            phase,
            map,
            spawns,
            drops,
            server.dropReason(),
            humans,
            bots,
            server.degradationTotal(),
            server.framesRun(),
            sync,
            cvarNames);
  }

  @Override
  public Status status() {
    return status;
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    try {
      host.close();
    } finally {
      server.close();
      for (PacketTransport transport : transports) {
        try {
          transport.close();
        } catch (IOException ignored) {
          // Closing.
        }
      }
      status =
          new Status(
              Phase.CLOSED,
              status.map(),
              spawns,
              drops,
              server.dropReason(),
              0,
              0,
              server.degradationTotal(),
              server.framesRun(),
              sync,
              Set.of());
    }
  }
}
