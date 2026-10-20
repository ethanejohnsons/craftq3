package dev.bluevista.craftq3.engine.client.pipeline;

import dev.bluevista.craftq3.engine.net.delta.SnapshotDeltaCodec.Snapshot;
import java.util.Objects;

/**
 * One parsed snapshot as the cgame reads it ({@code clSnapshot_t}): the server snapshot, its
 * message number, the server command sequence at its arrival and the ping measured for it.
 *
 * @param deltaBase the message number its delta referenced, or 0 for a full snapshot
 */
public record ClientSnapshot(
    Snapshot snapshot, int serverCommandSequence, int ping, int deltaBase) {
  public ClientSnapshot {
    Objects.requireNonNull(snapshot);
  }

  /** The message number ({@code snap.messageNum}). */
  public int messageNum() {
    return snapshot.sequence();
  }

  public int serverTime() {
    return snapshot.time();
  }

  public int flags() {
    return snapshot.flags();
  }
}
