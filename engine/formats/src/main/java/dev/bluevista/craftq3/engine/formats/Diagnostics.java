package dev.bluevista.craftq3.engine.formats;

import dev.bluevista.craftq3.engine.common.log.Log;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects the notes a lenient reader makes while accepting imperfect data. Each note is kept (up
 * to a bound) for the caller and logged once per process and category.
 */
public final class Diagnostics {
  public static final int MAX_NOTES = 256;

  private final Log log;
  private final String source;
  private final List<String> notes = new ArrayList<>();

  public Diagnostics(Log log, String source) {
    this.log = log;
    this.source = source;
  }

  /** Records a note; {@code category} groups notes for the once-per-process log line. */
  public void note(String category, String message) {
    if (notes.size() < MAX_NOTES) notes.add(message);
    log.warnOnce(category, source + ": " + message + " (further '" + category + "' notes muted)");
  }

  public List<String> notes() {
    return List.copyOf(notes);
  }
}
