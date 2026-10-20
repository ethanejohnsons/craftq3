package dev.bluevista.craftq3.engine.client.net;

import dev.bluevista.craftq3.engine.common.info.InfoString;
import dev.bluevista.craftq3.engine.console.CvarTable;
import dev.bluevista.craftq3.engine.fs.DownloadCache;
import dev.bluevista.craftq3.engine.fs.DownloadPlanner;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.chan.DownloadReceiver;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The PK3 downloads one connection needs ({@code CL_InitDownloads}): from a gamestate's systeminfo
 * ({@code sv_referencedPaks}/{@code sv_referencedPakNames}) the {@link DownloadPlanner} finds the
 * packs that are neither installed nor in the {@link DownloadCache}. With {@code cl_allowDownload}
 * bit 1 set (and bit 4 clear, which forbids downloading from the game server) each missing pack is
 * requested in turn ({@code download}, {@code nextdl} per block), verified against its checksum and
 * committed to the cache; when all are in, the {@link Content} remounts the search path with them
 * and the server is asked for a fresh gamestate ({@code donedl}). Packs already in the cache are
 * mounted whatever the policy. Without permission the original engine's behaviour applies: a
 * warning lists the missing packs and the level loads anyway (a pure server then refuses the
 * client, or the map fails to load). Official packs ({@code baseq3/pak0..8}, {@code
 * missionpack/pak0..3}) are never downloaded: a server referencing one the client lacks ends the
 * connection when downloads are enabled. Progress is shown in the {@code cl_download*} cvars.
 */
public final class DownloadManager implements AutoCloseable {
  /** {@code cl_allowDownload} bit: downloads allowed. */
  public static final int ALLOW = 1;

  /** {@code cl_allowDownload} bit: never download from the game server itself. */
  public static final int NO_UDP = 4;

  /** The game content the downloads extend. */
  public interface Content {
    SearchPath searchPath();

    /** Rebuilds the search path with these cached archives added. */
    void mount(List<DownloadCache.Entry> archives) throws IOException;
  }

  /** What happened after a gamestate was planned. */
  public enum Outcome {
    /** Everything is present; the cgame can load. */
    READY,
    /** Downloads were started; the server will send a new gamestate once they finish. */
    DOWNLOADING
  }

  private final Content content;
  private final DownloadCache cache;
  private final CvarTable cvars;
  private final Consumer<String> reliable;
  private final Consumer<String> print;
  private final Runnable begin;
  private final ArrayDeque<DownloadCache.Request> queue = new ArrayDeque<>();
  private final List<DownloadCache.Entry> completed = new ArrayList<>();
  private DownloadCache.Pending pending;
  private DownloadReceiver receiver;
  private int startTime;

  /**
   * @param cache the download cache, or null when downloads are impossible
   * @param reliable sends a reliable client command
   * @param begin resets the session's download block numbering ({@code beginDownload})
   * @param print the console, for the missing-packs warning
   */
  public DownloadManager(
      Content content,
      DownloadCache cache,
      CvarTable cvars,
      Consumer<String> reliable,
      Runnable begin,
      Consumer<String> print) {
    this.content = Objects.requireNonNull(content);
    this.cache = cache;
    this.cvars = Objects.requireNonNull(cvars);
    this.reliable = Objects.requireNonNull(reliable);
    this.begin = Objects.requireNonNull(begin);
    this.print = Objects.requireNonNull(print);
  }

  /** True when {@code cl_allowDownload} permits downloading from the game server. */
  public boolean allowed() {
    int policy = cvars.integer("cl_allowDownload");
    return cache != null && (policy & ALLOW) != 0 && (policy & NO_UDP) == 0;
  }

  public boolean active() {
    return pending != null || !queue.isEmpty();
  }

  /**
   * Plans a gamestate's downloads.
   *
   * @throws IOException when downloads are enabled and the server's lists are malformed or name a
   *     pack that may not be downloaded (an official one, another game's), or mounting failed
   */
  public Outcome plan(ServerMessageCodec.GameState gameState, int time) throws IOException {
    close();
    boolean allowed = allowed();
    String systemInfo = gameState.configstrings().getOrDefault(Protocol68.CS_SYSTEMINFO, "");
    DownloadPlanner.Plan plan;
    try {
      var info = InfoString.parse(systemInfo, Protocol68.BIG_INFO_STRING);
      plan =
          DownloadPlanner.plan(
              content.searchPath(),
              info.getOrDefault("sv_referencedPaks", ""),
              info.getOrDefault("sv_referencedPakNames", ""),
              cache == null ? List.of() : cache.entries());
    } catch (IOException | IllegalArgumentException refused) {
      if (allowed) throw refused instanceof IOException io ? io : new IOException(refused);
      warnMissing(refused.getMessage());
      return Outcome.READY;
    }
    if (!plan.fromCache().isEmpty()) content.mount(plan.fromCache());
    if (plan.complete()) return Outcome.READY;
    if (!allowed) {
      warnMissing(
          String.join(
              " ", plan.downloads().stream().map(DownloadCache.Request::remoteName).toList()));
      return Outcome.READY;
    }
    queue.addAll(plan.downloads());
    startTime = time;
    next();
    return Outcome.DOWNLOADING;
  }

  private void next() throws IOException {
    if (queue.isEmpty()) {
      clearDisplay();
      if (!completed.isEmpty()) content.mount(List.copyOf(completed));
      completed.clear();
      reliable.accept("donedl");
      return;
    }
    pending = cache.begin(queue.removeFirst());
    receiver = new DownloadReceiver(pending);
    begin.run();
    cvars.set("cl_downloadName", pending.request().remoteName());
    cvars.set("cl_downloadSize", "0");
    cvars.set("cl_downloadCount", "0");
    cvars.set("cl_downloadTime", Integer.toString(startTime));
    reliable.accept("download " + pending.request().remoteName());
  }

  /** One {@code svc_download} block. */
  public void accept(ServerMessageCodec.Download block) throws IOException {
    if (receiver == null) return;
    DownloadReceiver.Progress progress = receiver.accept(block);
    if (!progress.accepted()) return;
    reliable.accept("nextdl " + progress.acknowledge());
    cvars.set("cl_downloadSize", Integer.toString(Math.max(0, progress.size())));
    cvars.set("cl_downloadCount", Integer.toString(progress.received()));
    if (!progress.complete()) return;
    receiver = null;
    DownloadCache.Pending finished = pending;
    pending = null;
    completed.add(finished.verifyAndCommit());
    next();
  }

  private void warnMissing(String what) {
    print.accept("WARNING: You are missing some files referenced by the server: " + what);
    print.accept(
        cache == null
            ? "You might not be able to join the game (no download cache)"
            : "You might not be able to join the game; set cl_allowDownload 1 to download them");
  }

  private void clearDisplay() {
    cvars.set("cl_downloadName", "");
    cvars.set("cl_downloadSize", "0");
    cvars.set("cl_downloadCount", "0");
    cvars.set("cl_downloadTime", "0");
  }

  @Override
  public void close() throws IOException {
    queue.clear();
    completed.clear();
    receiver = null;
    try {
      if (pending != null) pending.close();
    } finally {
      pending = null;
      clearDisplay();
    }
  }
}
