package dev.bluevista.craftq3.engine.collision;

import java.util.Arrays;

/**
 * The open/closed state of a map's area portals: a reference count per pair of areas, adjusted as
 * doors open and close, and the flood-filled connectivity derived from it. Two areas are connected
 * when a chain of open portals joins them. All portals start closed.
 *
 * <p>Mutable and owned by one thread (the game simulation); create one per running game with {@link
 * CollisionModel#newAreaPortalState()}.
 */
public final class AreaPortalState {
  private final int areas;
  private final int[] portals;
  private final int[] flood;
  private final int[] pending;

  AreaPortalState(int areas) {
    this.areas = areas;
    portals = new int[areas * areas];
    flood = new int[areas];
    pending = new int[Math.max(1, areas)];
    reflood();
  }

  public int areaCount() {
    return areas;
  }

  /**
   * Opens ({@code open}) or closes one reference of the portal between two areas. As in the
   * original, negative areas are ignored.
   *
   * @throws IllegalArgumentException for an area beyond the map's areas
   * @throws IllegalStateException when closing a portal that is not open
   */
  public void adjust(int area1, int area2, boolean open) {
    if (area1 < 0 || area2 < 0) return;
    check(area1);
    check(area2);
    int forward = area1 * areas + area2;
    int backward = area2 * areas + area1;
    if (open) {
      portals[forward]++;
      portals[backward]++;
    } else {
      int needed = forward == backward ? 2 : 1;
      if (portals[forward] < needed)
        throw new IllegalStateException(
            "Area portal " + area1 + "-" + area2 + " closed more often than opened");
      portals[forward]--;
      portals[backward]--;
    }
    reflood();
  }

  /** The number of open references between two areas. */
  public int portalCount(int area1, int area2) {
    if (area1 < 0 || area2 < 0) return 0;
    check(area1);
    check(area2);
    return portals[area1 * areas + area2];
  }

  /**
   * Whether the two areas are joined by open portals. A negative area (a point in solid or outside
   * the map) is connected to nothing.
   *
   * @throws IllegalArgumentException for an area beyond the map's areas
   */
  public boolean connected(int area1, int area2) {
    if (area1 < 0 || area2 < 0) return false;
    check(area1);
    check(area2);
    return flood[area1] == flood[area2];
  }

  /**
   * ORs into {@code buffer} one bit per area connected to {@code area} and returns the number of
   * bytes covering all areas. A negative area sets every bit. These are "visible" bits; the server
   * inverts them into the snapshot's area mask.
   */
  public int writeAreaBits(byte[] buffer, int area) {
    int bytes = (areas + 7) >> 3;
    if (area < 0) {
      Arrays.fill(buffer, 0, bytes, (byte) 0xff);
      return bytes;
    }
    check(area);
    int group = flood[area];
    for (int i = 0; i < areas; i++) if (flood[i] == group) buffer[i >> 3] |= (byte) (1 << (i & 7));
    return bytes;
  }

  /** Closes every portal, as when a map is restarted. */
  public void reset() {
    Arrays.fill(portals, 0);
    reflood();
  }

  private void check(int area) {
    if (area >= areas)
      throw new IllegalArgumentException("Area " + area + " out of range 0.." + (areas - 1));
  }

  private void reflood() {
    Arrays.fill(flood, 0);
    int next = 0;
    for (int start = 0; start < areas; start++) {
      if (flood[start] != 0) continue;
      int group = ++next;
      int top = 0;
      flood[start] = group;
      pending[top++] = start;
      while (top > 0) {
        int area = pending[--top];
        int row = area * areas;
        for (int other = 0; other < areas; other++)
          if (portals[row + other] > 0 && flood[other] == 0) {
            flood[other] = group;
            pending[top++] = other;
          }
      }
    }
  }
}
