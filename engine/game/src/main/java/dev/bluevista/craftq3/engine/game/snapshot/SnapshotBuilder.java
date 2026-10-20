package dev.bluevista.craftq3.engine.game.snapshot;

import dev.bluevista.craftq3.engine.game.world.Contents;
import dev.bluevista.craftq3.engine.game.world.EntityWorld;
import dev.bluevista.craftq3.engine.game.world.GameEntities;
import dev.bluevista.craftq3.engine.game.world.MapVisibility;
import dev.bluevista.craftq3.engine.vmabi.layout.PlayerStateLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Builds per-client snapshots as {@code SV_BuildClientSnapshot} does.
 *
 * <ul>
 *   <li>The view point is the player's origin raised by its view height; its leaf gives the view
 *       cluster and area.
 *   <li>An entity is a candidate when it is linked and passes the audience flags: {@code
 *       SVF_NOCLIENT} never, {@code SVF_SINGLECLIENT} only for {@code r.singleClient}, {@code
 *       SVF_NOTSINGLECLIENT} for everyone else, {@code SVF_CLIENTMASK} for the clients whose bit is
 *       set in {@code r.singleClient}.
 *   <li>{@code SVF_BROADCAST} entities are always sent. Others need an area connected to the view
 *       area (either of their two areas) and a cluster in the view's PVS (with the overflow range
 *       up to the entity's last cluster).
 *   <li>A visible {@code SVF_PORTAL} entity within its {@code s.generic1} range adds everything
 *       visible from its {@code s.origin2}, merging that view's areas.
 *   <li>The client's own entity is never sent; the list is sorted by number and cut at {@link
 *       #MAX_SNAPSHOT_ENTITIES}. The area bits of every view are ORed and then inverted.
 * </ul>
 *
 * Owned by the simulation thread; scratch state is reused between calls.
 */
public final class SnapshotBuilder {
  /** {@code MAX_ENTITIES_IN_SNAPSHOT}. */
  public static final int MAX_SNAPSHOT_ENTITIES = 256;

  /** Portal views nested deeper than this are ignored (the original recursion has no bound). */
  public static final int MAX_PORTAL_DEPTH = 16;

  private final EntityWorld world;
  private final int[] added = new int[GameEntities.MAX_GENTITIES];
  private int counter;
  private final int[] numbers = new int[GameEntities.MAX_GENTITIES];
  private int count;
  private final byte[] areaBits = new byte[MapVisibility.MAX_AREA_BYTES];
  private final float[] point = new float[3];
  private final float[] scratch = new float[3];

  public SnapshotBuilder(EntityWorld world) {
    this.world = Objects.requireNonNull(world);
  }

  /** Builds client {@code client}'s snapshot at {@code serverTime}. */
  public ClientSnapshot build(int client, int serverTime) {
    GameEntities g = world.entities();
    if (++counter == Integer.MAX_VALUE) {
      Arrays.fill(added, 0);
      counter = 1;
    }
    count = 0;
    Arrays.fill(areaBits, (byte) 0);
    byte[] player = g.canonicalPlayerState(client);
    int clientNum = g.playerInt(client, PlayerStateLayout.CLIENT_NUM);
    // Never send the client's own entity; the player state regenerates it.
    if (clientNum >= 0 && clientNum < added.length) added[clientNum] = counter;
    g.playerVec(client, PlayerStateLayout.ORIGIN, point);
    point[2] += g.playerInt(client, PlayerStateLayout.VIEWHEIGHT);
    int areaBytes = addVisibleFrom(g, point.clone(), clientNum, 0);
    int[] sorted = Arrays.copyOf(numbers, count);
    Arrays.sort(sorted);
    int visible = sorted.length;
    if (sorted.length > MAX_SNAPSHOT_ENTITIES)
      sorted = Arrays.copyOf(sorted, MAX_SNAPSHOT_ENTITIES);
    for (int i = 0; i < MapVisibility.MAX_AREA_BYTES; i++) areaBits[i] = (byte) ~areaBits[i];
    List<byte[]> states = new ArrayList<>(sorted.length);
    for (int number : sorted) states.add(g.canonicalState(number));
    return new ClientSnapshot(
        serverTime,
        Arrays.copyOf(areaBits, Math.min(areaBytes, MapVisibility.MAX_AREA_BYTES)),
        player,
        sorted,
        states,
        visible);
  }

  private int addVisibleFrom(GameEntities g, float[] origin, int clientNum, int depth) {
    MapVisibility vis = world.visibility();
    int leaf = vis.pointLeaf(origin);
    int clientArea = vis.leafArea(leaf);
    int clientCluster = vis.leafCluster(leaf);
    int areaBytes = vis.writeAreaBits(areaBits, clientArea);
    int total = g.count();
    for (int e = 0; e < total; e++) {
      if (!g.linked(e)) continue;
      if (g.stateNumber(e) != e) g.setStateNumber(e, e);
      int flags = g.svFlags(e);
      if ((flags & Contents.SVF_NOCLIENT) != 0) continue;
      if ((flags & Contents.SVF_SINGLECLIENT) != 0 && g.singleClient(e) != clientNum) continue;
      if ((flags & Contents.SVF_NOTSINGLECLIENT) != 0 && g.singleClient(e) == clientNum) continue;
      if ((flags & Contents.SVF_CLIENTMASK) != 0) {
        if (clientNum < 0 || clientNum >= 32) continue;
        if ((~g.singleClient(e) & (1 << clientNum)) != 0) continue;
      }
      if (added[e] == counter) continue;
      if ((flags & Contents.SVF_BROADCAST) != 0) {
        add(e);
        continue;
      }
      if (!vis.areasConnected(clientArea, world.areaNum(e))
          && !vis.areasConnected(clientArea, world.areaNum2(e))) continue;
      if (!clusterVisible(vis, clientCluster, e)) continue;
      add(e);
      if ((flags & Contents.SVF_PORTAL) != 0 && depth < MAX_PORTAL_DEPTH) {
        int range = g.generic1(e);
        if (range != 0) {
          g.stateOrigin(e, scratch);
          float dx = scratch[0] - origin[0], dy = scratch[1] - origin[1];
          float dz = scratch[2] - origin[2];
          if (dx * dx + dy * dy + dz * dz > (float) range * range) continue;
        }
        float[] portal = new float[3];
        g.stateOrigin2(e, portal);
        addVisibleFrom(g, portal, clientNum, depth + 1);
      }
    }
    return areaBytes;
  }

  private boolean clusterVisible(MapVisibility vis, int clientCluster, int e) {
    int clusters = world.clusterCount(e);
    if (clusters == 0) return false;
    int cluster = 0;
    for (int i = 0; i < clusters; i++) {
      cluster = world.cluster(e, i);
      if (vis.clusterVisible(clientCluster, cluster)) return true;
    }
    int last = world.lastCluster(e);
    if (last == 0) return false;
    // Clusters past the stored ones up to the last leaf's cluster. As in the original, the entity
    // counts as hidden only when the scan stops exactly at the last cluster: a visible earlier
    // cluster and an exhausted scan both send it.
    for (; cluster <= last; cluster++) if (vis.clusterVisible(clientCluster, cluster)) break;
    return cluster != last;
  }

  private void add(int e) {
    added[e] = counter;
    numbers[count++] = e;
  }
}
