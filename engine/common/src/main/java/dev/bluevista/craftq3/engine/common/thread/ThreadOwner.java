package dev.bluevista.craftq3.engine.common.thread;

/**
 * Records which thread owns a single-threaded structure (cvar table, botlib, VM host) and checks it
 * at entry points. Checks are on when assertions are enabled (always in tests) and cost a field
 * read otherwise.
 */
public final class ThreadOwner {
  private static final boolean CHECKS = ThreadOwner.class.desiredAssertionStatus();

  private final String what;
  private volatile Thread owner;

  public ThreadOwner(String what) {
    this.what = what;
  }

  /** Binds ownership to the calling thread; called when a structure is handed to its thread. */
  public void claim() {
    owner = Thread.currentThread();
  }

  public void release() {
    owner = null;
  }

  /** Fails if a thread other than the owner calls; unowned structures accept any caller. */
  public void check() {
    if (!CHECKS) return;
    Thread current = owner;
    if (current != null && current != Thread.currentThread())
      throw new IllegalStateException(
          what
              + " is owned by "
              + current.getName()
              + " but was used from "
              + Thread.currentThread().getName());
  }
}
