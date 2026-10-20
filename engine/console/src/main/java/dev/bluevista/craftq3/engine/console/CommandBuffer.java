package dev.bluevista.craftq3.engine.console;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import java.util.Objects;

/**
 * The command buffer ({@code Cbuf_*}): a text buffer that is executed one command line at a time.
 * {@link #add} appends text verbatim (callers end commands with a newline, as in the original),
 * {@link #insert} puts text plus a newline in front, and {@link #execute()} runs lines until the
 * buffer is empty, a {@code wait} is pending, {@link #breakFrame()} is called or the per-frame
 * command bound is reached. The bound stops self-feeding {@code vstr} or {@code exec} loops from
 * hanging a frame; what remains runs next frame.
 *
 * <p>Single owner thread, shared with its registry. Other threads use {@link CommandInbox}.
 */
public final class CommandBuffer {
  /**
   * Buffer capacity in characters. Larger than 1.32's 16 KiB because shipped mod q3config.cfg files
   * exceed it; overflowing text is dropped with a message, as in the original.
   */
  public static final int CAPACITY = 128 * 1024;

  /** Default bound on command lines executed by one {@link #execute()}. */
  public static final int DEFAULT_FRAME_LIMIT = 4096;

  /** Upper bound for {@code wait N}. */
  public static final int MAX_WAIT = 10_000;

  /**
   * {@code cbufExec_t}; ordinals match {@code EXEC_NOW}, {@code EXEC_INSERT}, {@code EXEC_APPEND}.
   */
  public enum When {
    NOW,
    INSERT,
    APPEND
  }

  private static final Log LOG = Log.get(CommandBuffer.class);

  private final ThreadOwner owner;
  private final CommandRegistry registry;
  private final StringBuilder text = new StringBuilder();
  private int waitFrames;
  private boolean breakFrame;
  private boolean executing;
  private int remaining;

  public CommandBuffer(CommandRegistry registry) {
    this.registry = Objects.requireNonNull(registry);
    this.owner = registry.owner();
  }

  public CommandRegistry registry() {
    return registry;
  }

  /** {@code Cbuf_AddText}: appends verbatim. Returns false (and prints) when it would overflow. */
  public boolean add(String commandText) {
    owner.check();
    String clean = CommandParser.cutAtNul(commandText);
    if (text.length() + clean.length() > CAPACITY) {
      registry.output().print("Cbuf_AddText: overflow");
      return false;
    }
    text.append(clean);
    return true;
  }

  /** {@code Cbuf_InsertText}: puts the text and a newline before everything buffered. */
  public boolean insert(String commandText) {
    owner.check();
    String clean = CommandParser.cutAtNul(commandText);
    if (text.length() + clean.length() + 1 > CAPACITY) {
      registry.output().print("Cbuf_InsertText overflowed");
      return false;
    }
    text.insert(0, '\n').insert(0, clean);
    return true;
  }

  /**
   * {@code Cbuf_ExecuteText}. {@link When#NOW} runs {@code commandText} immediately as a single
   * command (no {@code ;} splitting, as in the original), or runs the buffer when it is empty.
   */
  public void execute(When when, String commandText) {
    owner.check();
    switch (when) {
      case NOW -> {
        if (commandText.isEmpty()) execute();
        else registry.execute(commandText);
      }
      case INSERT -> insert(commandText);
      case APPEND -> add(commandText);
    }
  }

  /** {@code Cbuf_Execute} with the default per-frame bound. */
  public int execute() {
    return execute(DEFAULT_FRAME_LIMIT);
  }

  /**
   * Runs buffered lines. A nested call from inside a command keeps draining the same buffer with
   * the outer call's remaining bound.
   *
   * @return the number of command lines executed, including those run by nested calls; a nested
   *     call returns only its own
   */
  public int execute(int limit) {
    owner.check();
    if (limit < 1) throw new IllegalArgumentException("Command limit must be positive");
    if (executing) return drain();
    executing = true;
    remaining = limit;
    breakFrame = false;
    try {
      drain();
      if (remaining == 0 && !text.isEmpty())
        LOG.warnOnce("frame-limit", "Command buffer frame limit reached; continuing next frame");
      return limit - remaining;
    } finally {
      executing = false;
      breakFrame = false;
    }
  }

  private int drain() {
    int ran = 0;
    while (!text.isEmpty()) {
      if (waitFrames > 0) {
        // The frame that sees a pending wait consumes one wait frame and stops.
        waitFrames--;
        break;
      }
      if (breakFrame || remaining <= 0) break;
      int end = CommandParser.lineEnd(text, 0);
      String line = CommandParser.line(text, 0, end);
      text.delete(0, Math.min(end + 1, text.length()));
      remaining--;
      ran++;
      registry.execute(line);
    }
    return ran;
  }

  /** {@code wait N}: stops the current execution; the rest runs {@code frames} frames later. */
  public void waitFrames(int frames) {
    owner.check();
    waitFrames = Math.clamp(frames, 0, MAX_WAIT);
  }

  public int waitFrames() {
    owner.check();
    return waitFrames;
  }

  /**
   * Stops the current execution after the running command without skipping a frame, so the host
   * can, for example, change maps outside any VM call. Outside buffer execution (an immediate
   * command) there is nothing to stop and it does nothing.
   */
  public void breakFrame() {
    owner.check();
    if (executing) breakFrame = true;
  }

  /** Buffered characters. */
  public int length() {
    owner.check();
    return text.length();
  }

  public boolean isEmpty() {
    owner.check();
    return text.isEmpty();
  }

  /** The unexecuted text, for diagnostics. */
  public String pendingText() {
    owner.check();
    return text.toString();
  }

  public void clear() {
    owner.check();
    text.setLength(0);
    waitFrames = 0;
  }
}
