package dev.bluevista.craftq3.engine.common.log;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The engine's logging facade. Engine modules log through named {@link Log} instances; the host
 * (Fabric, headless, tests) installs a {@link Sink}. Defaults to {@link System.Logger}.
 */
public final class Log {
  public enum Level {
    DEBUG,
    INFO,
    WARN,
    ERROR
  }

  /** Receives every log record. Implementations must be thread-safe. */
  @FunctionalInterface
  public interface Sink {
    void log(String name, Level level, String message, Throwable error);
  }

  private static volatile Sink sink = Log::systemLogger;
  private static final Set<String> ONCE = ConcurrentHashMap.newKeySet();

  private final String name;

  private Log(String name) {
    this.name = name;
  }

  public static Log get(Class<?> owner) {
    return new Log(owner.getName());
  }

  public static Log get(String name) {
    return new Log(Objects.requireNonNull(name));
  }

  /** Replaces the process-wide sink; returns the previous one so tests can restore it. */
  public static Sink install(Sink newSink) {
    Sink previous = sink;
    sink = Objects.requireNonNull(newSink);
    return previous;
  }

  /** Forgets every {@link #warnOnce} key, for a new session or test. */
  public static void resetOnce() {
    ONCE.clear();
  }

  public void debug(String message) {
    sink.log(name, Level.DEBUG, message, null);
  }

  public void info(String message) {
    sink.log(name, Level.INFO, message, null);
  }

  public void warn(String message) {
    sink.log(name, Level.WARN, message, null);
  }

  public void warn(String message, Throwable error) {
    sink.log(name, Level.WARN, message, error);
  }

  public void error(String message, Throwable error) {
    sink.log(name, Level.ERROR, message, error);
  }

  /** Logs a warning the first time {@code key} is seen in this process (or since reset). */
  public boolean warnOnce(String key, String message) {
    if (!ONCE.add(name + '\u0000' + key)) return false;
    sink.log(name, Level.WARN, message, null);
    return true;
  }

  private static void systemLogger(String name, Level level, String message, Throwable error) {
    System.Logger.Level mapped =
        switch (level) {
          case DEBUG -> System.Logger.Level.DEBUG;
          case INFO -> System.Logger.Level.INFO;
          case WARN -> System.Logger.Level.WARNING;
          case ERROR -> System.Logger.Level.ERROR;
        };
    System.Logger logger = System.getLogger(name);
    if (error == null) logger.log(mapped, message);
    else logger.log(mapped, message, error);
  }
}
