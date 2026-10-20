package dev.bluevista.craftq3.engine.console;

import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The only cross-thread way into a console: any thread may {@link #post} command text, and the
 * owner thread moves it into its command buffer with {@link #drainTo} at the start of a frame. This
 * is how, for example, the render thread changes a server-owned cvar ({@code set g_gravity 400}).
 * Lock-free and bounded.
 */
public final class CommandInbox {
  /** Pending posts allowed before further posts are refused. */
  public static final int CAPACITY = 1024;

  private final ThreadOwner owner;
  private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
  private final AtomicInteger size = new AtomicInteger();

  public CommandInbox(ThreadOwner owner) {
    this.owner = Objects.requireNonNull(owner);
  }

  /**
   * Queues command text from any thread. A trailing newline is added when missing so posts never
   * run together.
   *
   * @return false when the inbox is full
   */
  public boolean post(String text) {
    Objects.requireNonNull(text);
    if (size.incrementAndGet() > CAPACITY) {
      size.decrementAndGet();
      return false;
    }
    queue.add(text.endsWith("\n") ? text : text + "\n");
    return true;
  }

  /** Appends every queued post to {@code buffer}, in posting order. Owner thread only. */
  public int drainTo(CommandBuffer buffer) {
    owner.check();
    int moved = 0;
    for (String text; (text = queue.poll()) != null; ) {
      size.decrementAndGet();
      buffer.add(text);
      moved++;
    }
    return moved;
  }

  /** Approximate number of queued posts. */
  public int pending() {
    return size.get();
  }
}
