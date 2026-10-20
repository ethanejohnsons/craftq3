package dev.bluevista.craftq3.engine.vmabi.layout;

import dev.bluevista.craftq3.engine.vm.QvmMemory;
import java.time.ZonedDateTime;

/**
 * {@code qtime_t}, the C {@code struct tm} fields: seconds, minutes, hours, day of month, month
 * (0-11), years since 1900, weekday (0 = Sunday), day of year (0-365) and a DST flag.
 */
public final class QTimeLayout {
  public static final int TM_SEC = 0;
  public static final int TM_MIN = 4;
  public static final int TM_HOUR = 8;
  public static final int TM_MDAY = 12;
  public static final int TM_MON = 16;
  public static final int TM_YEAR = 20;
  public static final int TM_WDAY = 24;
  public static final int TM_YDAY = 28;
  public static final int TM_ISDST = 32;
  public static final int BYTES = 36;

  /** {@code tm_year} counts from this year. */
  public static final int YEAR_BASE = 1900;

  private QTimeLayout() {}

  /** Writes a local time like {@code localtime()}. */
  public static void write(QvmMemory memory, int address, ZonedDateTime time) {
    memory.resolve(address, BYTES);
    memory.writeInt(address + TM_SEC, time.getSecond());
    memory.writeInt(address + TM_MIN, time.getMinute());
    memory.writeInt(address + TM_HOUR, time.getHour());
    memory.writeInt(address + TM_MDAY, time.getDayOfMonth());
    memory.writeInt(address + TM_MON, time.getMonthValue() - 1);
    memory.writeInt(address + TM_YEAR, time.getYear() - YEAR_BASE);
    memory.writeInt(address + TM_WDAY, time.getDayOfWeek().getValue() % 7);
    memory.writeInt(address + TM_YDAY, time.getDayOfYear() - 1);
    boolean dst = time.getZone().getRules().isDaylightSavings(time.toInstant());
    memory.writeInt(address + TM_ISDST, dst ? 1 : 0);
  }
}
