package dev.bluevista.craftq3.engine.vmabi.service;

import dev.bluevista.craftq3.engine.common.time.Clock;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vmabi.layout.QTimeLayout;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * {@code trap_Milliseconds} and {@code trap_RealTime}. Milliseconds count from the creation of this
 * service on an injected monotonic {@link Clock}, as {@code Sys_Milliseconds} counts from engine
 * start; real time comes from an injected {@link java.time.Clock} whose zone is the local time zone
 * of {@code localtime()}.
 */
public final class TimeServices {
  private final Clock clock;
  private final java.time.Clock wallClock;
  private final long origin;

  public TimeServices(Clock clock, java.time.Clock wallClock) {
    this.clock = Objects.requireNonNull(clock);
    this.wallClock = Objects.requireNonNull(wallClock);
    this.origin = clock.millis();
  }

  /** The system clocks in the host's default zone. */
  public static TimeServices system() {
    return new TimeServices(Clock.system(), java.time.Clock.systemDefaultZone());
  }

  /** Milliseconds since this service was created, wrapping like a C int. */
  public int milliseconds() {
    return (int) (clock.millis() - origin);
  }

  public int milliseconds(QvmInstance vm, int[] args) {
    return milliseconds();
  }

  /**
   * {@code trap_RealTime(qtime)}: fills the {@code qtime_t} (a null pointer is allowed) and returns
   * seconds since the epoch.
   */
  public int realTime(QvmInstance vm, int[] args) {
    ZonedDateTime now = ZonedDateTime.now(wallClock);
    if (args[0] != 0) QTimeLayout.write(vm.memory(), args[0], now);
    return (int) now.toEpochSecond();
  }
}
