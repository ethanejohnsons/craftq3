package dev.bluevista.craftq3.engine.game.snapshot;

import java.util.Arrays;
import java.util.List;

/**
 * One client's view of a server frame: the inverted area mask (a set bit hides that area), the
 * canonical {@code playerState_t} and the visible entities' canonical states in ascending number
 * order. Immutable.
 *
 * @param serverTime {@code sv.time} of the frame
 * @param areaMask {@code areabytes} bytes of the mask the renderer uses
 * @param visibleEntities how many entities were visible before the {@link
 *     SnapshotBuilder#MAX_SNAPSHOT_ENTITIES} cut
 */
public record ClientSnapshot(
    int serverTime,
    byte[] areaMask,
    byte[] playerState,
    int[] entityNumbers,
    List<byte[]> entities,
    int visibleEntities) {
  public ClientSnapshot {
    areaMask = areaMask.clone();
    playerState = playerState.clone();
    entityNumbers = entityNumbers.clone();
    entities = List.copyOf(entities);
  }

  @Override
  public byte[] areaMask() {
    return areaMask.clone();
  }

  @Override
  public byte[] playerState() {
    return playerState.clone();
  }

  @Override
  public int[] entityNumbers() {
    return entityNumbers.clone();
  }

  /** Entity number at {@code index}, or -1 past the end (botlib's {@code GetSnapshotEntity}). */
  public int entityNumber(int index) {
    return index < 0 || index >= entityNumbers.length ? -1 : entityNumbers[index];
  }

  public int entityCount() {
    return entityNumbers.length;
  }

  public boolean contains(int number) {
    return Arrays.binarySearch(entityNumbers, number) >= 0;
  }
}
