package dev.bluevista.craftq3.engine.session;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.common.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * Runs a {@link LocalServer} on its own {@value #THREAD_NAME} thread (or, in synchronous mode, on
 * the caller's thread through {@link #step}). The server's owner is claimed by that thread, so
 * every use of its console, cvars, qagame and botlib from anywhere else fails the thread check.
 *
 * <p>Other threads reach the server only through lock-free queues: console text through the
 * server's {@link LocalServer#inbox()}, ownership hand-offs (a transport to serve) through {@link
 * #submit}. The server's state comes back as an immutable {@link LocalServer.Status} published
 * after every frame.
 *
 * <p>The loop calls {@link LocalServer#frame} continuously, parking at most {@value #PARK_MILLIS}
 * ms in between (the server runs its game frames at {@code sv_fps} itself); a client that just sent
 * packets may {@link #wake} it. A runtime failure escaping the server ends the loop with a {@link
 * LocalServer.Phase#DROPPED} status; an {@link Error} (resource exhaustion) is kept in {@link
 * #fatal()} for the session to rethrow on its thread.
 */
public final class ServerRunner implements AutoCloseable {
  public static final String THREAD_NAME = "cq3-sim";

  /** Longest the loop sleeps between server frames. */
  public static final int PARK_MILLIS = 2;

  /** How long {@link #close()} waits for the server thread. */
  public static final int JOIN_MILLIS = 10_000;

  private static final Log LOG = Log.get(ServerRunner.class);

  private final LocalServer server;
  private final Clock clock;
  private final boolean threaded;
  private final ConcurrentLinkedQueue<Consumer<LocalServer>> tasks = new ConcurrentLinkedQueue<>();
  private volatile LocalServer.Status status = LocalServer.Status.INITIAL;
  private volatile boolean stopping;
  private volatile Throwable fatal;
  private Thread thread;
  private boolean started, closed;

  public ServerRunner(LocalServer server, Clock clock, boolean threaded) {
    this.server = Objects.requireNonNull(server);
    this.clock = Objects.requireNonNull(clock);
    this.threaded = threaded;
  }

  public boolean threaded() {
    return threaded;
  }

  /** The server thread, while threaded and running. */
  public Optional<Thread> thread() {
    return Optional.ofNullable(thread);
  }

  /** Starts the server thread, or claims the server for the caller in synchronous mode. */
  public void start() {
    if (started) throw new IllegalStateException("Server runner already started");
    started = true;
    if (!threaded) {
      server.owner().claim();
      return;
    }
    thread = Thread.ofPlatform().name(THREAD_NAME).daemon(true).unstarted(this::loop);
    thread.start();
  }

  private void loop() {
    server.owner().claim();
    try {
      while (!stopping) {
        runTasks();
        server.frame(clock.millis());
        status = server.status();
        if (!stopping) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(PARK_MILLIS));
      }
    } catch (RuntimeException failure) {
      LOG.warn("Server thread failed: " + failure);
      publishFailure(failure);
    } catch (Error exhausted) {
      fatal = exhausted;
      publishFailure(exhausted);
    } finally {
      shutdownOnOwner();
    }
  }

  private void publishFailure(Throwable failure) {
    LocalServer.Status last = status;
    String reason = "Server failed: " + failure;
    status =
        new LocalServer.Status(
            LocalServer.Phase.DROPPED,
            last.map(),
            last.spawns(),
            last.drops() + 1,
            reason,
            0,
            0,
            last.degradations(),
            last.frames(),
            last.sync(),
            Set.of());
  }

  private void runTasks() {
    for (Consumer<LocalServer> task; (task = tasks.poll()) != null; ) task.accept(server);
  }

  private void shutdownOnOwner() {
    try {
      runTasks();
    } catch (RuntimeException ignored) {
      LOG.debug("Server task failed during shutdown");
    }
    try {
      server.close();
      status = server.status();
    } catch (RuntimeException failure) {
      LOG.warn("Server shutdown failed: " + failure);
    } finally {
      server.owner().release();
    }
  }

  /** Synchronous mode: runs queued tasks and one server frame at {@code nowMillis}. */
  public void step(long nowMillis) {
    if (threaded) throw new IllegalStateException("The server runs on its own thread");
    if (closed) return;
    runTasks();
    try {
      server.frame(nowMillis);
      status = server.status();
    } catch (RuntimeException failure) {
      LOG.warn("Server failed: " + failure);
      publishFailure(failure);
    }
  }

  /** Posts console text to the server. Any thread. */
  public boolean post(String text) {
    boolean posted = server.inbox().post(text);
    wake();
    return posted;
  }

  /** Runs {@code task} on the server's thread before its next frame. Any thread. */
  public void submit(Consumer<LocalServer> task) {
    tasks.add(Objects.requireNonNull(task));
    wake();
  }

  /** Unparks the server thread (packets were just sent to it). */
  public void wake() {
    Thread current = thread;
    if (current != null) LockSupport.unpark(current);
  }

  /** The newest published status. Any thread. */
  public LocalServer.Status status() {
    return status;
  }

  /** The resource-exhaustion error that ended the server thread, if any. */
  public Optional<Throwable> fatal() {
    return Optional.ofNullable(fatal);
  }

  /** Stops the server: the thread runs remaining tasks, closes the server and ends. */
  @Override
  public void close() {
    if (closed) return;
    closed = true;
    stopping = true;
    if (!started) return;
    if (!threaded) {
      shutdownOnOwner();
      return;
    }
    LockSupport.unpark(thread);
    try {
      thread.join(JOIN_MILLIS);
      if (thread.isAlive()) LOG.warn("Server thread did not stop within " + JOIN_MILLIS + " ms");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    thread = null;
  }
}
