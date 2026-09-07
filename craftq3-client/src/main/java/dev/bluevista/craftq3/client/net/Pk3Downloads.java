package dev.bluevista.craftq3.client.net;

import dev.bluevista.craftq3.assets.fs.DownloadCache;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.client.RemoteSystemInfo;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.net.DownloadReceiver;
import dev.bluevista.craftq3.core.net.ServerMessageCodec;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** A connection's requested downloads; only verified cache entries become mounted game content. */
public final class Pk3Downloads implements AutoCloseable {
  private final Pk3FileSystem fs;
  private final DownloadCache cache;
  private final CvarSystem cvars;
  private final ArrayDeque<DownloadCache.Request> queue = new ArrayDeque<>();
  private DownloadCache.Pending pending;
  private DownloadReceiver receiver;
  private CompletableFuture<DownloadCache.Entry> verification;
  private Thread worker;
  private int generation = -1;
  private boolean waiting, stopped;

  public Pk3Downloads(Pk3FileSystem fs, DownloadCache cache, CvarSystem cvars) {
    this.fs = fs;
    this.cache = cache;
    this.cvars = cvars;
    cvars.register("cl_allowDownload", "0", CvarSystem.ARCHIVE);
    for (String name :
        List.of("cl_downloadName", "cl_downloadSize", "cl_downloadCount", "cl_downloadTime"))
      cvars.register(name, name.equals("cl_downloadName") ? "" : "0", CvarSystem.ROM);
  }

  /** False keeps the original UI on its connection screen until a fresh gamestate arrives. */
  public boolean prepare(
      int sequence,
      RemoteSystemInfo.Settings settings,
      Consumer<String> command,
      Runnable begin,
      Runnable stopRecording,
      int uiTime)
      throws IOException {
    if (sequence == generation) return !busy();
    close();
    generation = sequence;
    stopped = false;
    settings.requireGame(fs.gameDirectory());
    String sums = settings.values().getOrDefault("sv_referencedPaks", "").trim();
    String names = settings.values().getOrDefault("sv_referencedPakNames", "").trim();
    String[] checksums = sums.isEmpty() ? new String[0] : sums.split("\\s+");
    String[] labels = names.isEmpty() ? new String[0] : names.split("\\s+");
    if (checksums.length > 1024) throw new IOException("Too many referenced server PK3s");
    var installed = new HashSet<Integer>();
    for (var pack : fs.packs()) installed.add(pack.checksum());
    List<DownloadCache.Entry> cached = cache == null ? List.of() : cache.entries();
    var requested = new HashSet<Integer>();
    for (int index = 0; index < checksums.length; index++) {
      int checksum = Integer.parseInt(checksums[index]);
      if (installed.contains(checksum)) continue;
      var available =
          cached.stream()
              .filter(
                  e ->
                      e.request().checksum() == checksum
                          && (e.request().game().equals(fs.gameDirectory())
                              || e.request().game().equals("baseq3")))
              .findFirst();
      if (available.isPresent()) {
        var entry = available.get();
        fs.attachArchive(entry.path(), entry.request().game(), entry.request().name(), checksum);
        installed.add(checksum);
        continue;
      }
      if (labels.length != checksums.length)
        throw new IOException(
            "Missing server PK3 checksum " + checksum + "; server omitted matching names");
      if (requested.add(checksum))
        queue.add(new DownloadCache.Request(labels[index] + ".pk3", checksum));
    }
    if (queue.isEmpty()) return true;
    if (queue.size() > 128) throw new IOException("Too many missing server PK3s");
    int policy = cvars.integer("cl_allowDownload");
    if (cache == null || (policy & 1) == 0 || (policy & 4) != 0)
      throw new IOException(
          "Missing server PK3s: "
              + queue.stream().map(DownloadCache.Request::remoteName).toList()
              + (cache == null
                  ? "; download cache unavailable"
                  : "; enable cl_allowDownload 1 to download"));
    for (var request : queue)
      if (!request.game().equals(fs.gameDirectory()) && !request.game().equals("baseq3"))
        throw new IOException("Server requested a PK3 from another game directory");
    stopRecording.run();
    next(command, begin, uiTime);
    return false;
  }

  public boolean busy() {
    return pending != null || waiting || !queue.isEmpty();
  }

  public boolean verifying() {
    return verification != null;
  }

  private void next(Consumer<String> command, Runnable begin, int uiTime) throws IOException {
    if (queue.isEmpty()) {
      waiting = true;
      clearDisplay();
      command.accept("donedl");
      return;
    }
    pending = cache.begin(queue.removeFirst());
    receiver = new DownloadReceiver(pending);
    begin.run();
    set("cl_downloadName", pending.request().remoteName());
    set("cl_downloadSize", "0");
    set("cl_downloadCount", "0");
    set("cl_downloadTime", Integer.toString(uiTime));
    command.accept("download \"" + pending.request().remoteName() + "\"");
  }

  public void accept(ServerMessageCodec.Download packet, Consumer<String> command)
      throws IOException {
    if (receiver == null) {
      if (pending == null && !stopped) {
        command.accept("stopdl");
        stopped = true;
      }
      return;
    }
    var progress = receiver.accept(packet);
    if (!progress.accepted()) return;
    command.accept("nextdl " + progress.acknowledge());
    set("cl_downloadSize", Integer.toString(progress.size()));
    set("cl_downloadCount", Integer.toString(progress.received()));
    if (progress.complete()) {
      receiver = null;
      var staging = pending;
      var result = new CompletableFuture<DownloadCache.Entry>();
      verification = result;
      worker =
          Thread.ofVirtual()
              .name("craftq3-pk3-verify")
              .start(
                  () -> {
                    try {
                      result.complete(staging.verifyAndCommit());
                    } catch (IOException | RuntimeException failure) {
                      result.completeExceptionally(failure);
                    }
                  });
    }
  }

  public void pump(Consumer<String> command, Runnable begin, int uiTime) throws IOException {
    if (verification == null || !verification.isDone()) return;
    DownloadCache.Entry entry;
    try {
      entry = verification.join();
    } catch (java.util.concurrent.CompletionException failure) {
      throw new IOException("PK3 verification failed", failure.getCause());
    }
    fs.attachArchive(
        entry.path(), entry.request().game(), entry.request().name(), entry.request().checksum());
    pending = null;
    verification = null;
    worker = null;
    next(command, begin, uiTime);
  }

  private void set(String name, String value) {
    cvars.set(name, value, CvarSystem.Source.ENGINE);
  }

  private void clearDisplay() {
    set("cl_downloadName", "");
    for (String name : List.of("cl_downloadSize", "cl_downloadCount", "cl_downloadTime"))
      set(name, "0");
  }

  @Override
  public void close() throws IOException {
    if (worker != null) worker.interrupt();
    if (verification != null) verification.cancel(true);
    try {
      if (pending != null) pending.close();
    } finally {
      pending = null;
      receiver = null;
      verification = null;
      worker = null;
      queue.clear();
      waiting = false;
      generation = -1;
      clearDisplay();
    }
  }
}
