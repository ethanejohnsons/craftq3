package dev.bluevista.craftq3.server;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;

/**
 * Local snapshot interest selection. BSP box membership is cached separately from mutable area
 * connectivity; no renderer, QVM memory, or host-coordinate assumptions enter this calculation.
 */
final class SnapshotVisibility {
  static final int MAX_ENTITIES = 256;
  static final int NOCLIENT = 1, CLIENTMASK = 2, BROADCAST = 32, PORTAL = 64;
  static final int SINGLECLIENT = 256, NOTSINGLECLIENT = 2048;
  private static final int MAX_ENTITY_NUMBER = 1024, AREA_BYTES = 32;

  record Candidate(
      int number,
      BspMap.Bounds bounds,
      int flags,
      int singleClient,
      Vec3 origin,
      Vec3 portalOrigin,
      int portalRange) {
    Candidate {
      if (number < 0 || number >= MAX_ENTITY_NUMBER)
        throw new IllegalArgumentException("Invalid snapshot entity " + number);
    }
  }

  record Selection(List<Integer> entities, BspMap.Bytes areaMask, int visibleEntities) {
    Selection {
      entities = List.copyOf(entities);
    }

    int omittedEntities() {
      return visibleEntities - entities.size();
    }
  }

  private record Membership(int[] clusters, int[] areas, boolean unknown) {}

  private record Cached(BspMap.Bounds bounds, Membership membership) {}

  private record View(Vec3 point, int cluster, BitSet areas, boolean unknown) {}

  private final BspMap map;
  private final AreaConnectivity connectivity;
  private final Cached[] membershipCache = new Cached[MAX_ENTITY_NUMBER];
  private final boolean hasPvs;

  SnapshotVisibility(BspMap map, AreaConnectivity connectivity) {
    this.map = map;
    this.connectivity = connectivity;
    var visibility = map.visibility();
    hasPvs =
        visibility != null
            && visibility.clusters() > 0
            && visibility.bytesPerCluster() >= (visibility.clusters() + 7L) / 8
            && (long) visibility.bytesPerCluster() * visibility.clusters()
                <= visibility.bits().size();
  }

  Selection select(Vec3 eye, int playerEntity, List<Candidate> submitted) {
    if (playerEntity < 0 || playerEntity >= MAX_ENTITY_NUMBER)
      throw new IllegalArgumentException("Invalid snapshot player " + playerEntity);
    var candidates = new ArrayList<>(submitted);
    candidates.sort(Comparator.comparingInt(Candidate::number));
    BitSet candidateIds = new BitSet(), accepted = new BitSet(), visibleAreas = new BitSet();
    for (var candidate : candidates) {
      if (candidateIds.get(candidate.number()))
        throw new IllegalArgumentException("Duplicate snapshot entity " + candidate.number());
      candidateIds.set(candidate.number());
    }
    // A followed player's state also reconstructs that player's entity in cgame.
    accepted.set(playerEntity);
    var pending = new ArrayDeque<View>();
    pending.add(view(eye));
    boolean unknownAreas = false;
    while (!pending.isEmpty()) {
      View view = pending.removeFirst();
      unknownAreas |= view.unknown();
      visibleAreas.or(view.areas());
      for (var candidate : candidates) {
        if (accepted.get(candidate.number()) || !audience(candidate, playerEntity)) continue;
        boolean broadcast = (candidate.flags() & BROADCAST) != 0;
        if (!broadcast && !visible(view, membership(candidate))) continue;
        accepted.set(candidate.number());
        // Broadcast only bypasses visibility; it does not open a remote portal viewpoint.
        if (!broadcast && (candidate.flags() & PORTAL) != 0 && inRange(view.point(), candidate))
          pending.addLast(view(candidate.portalOrigin()));
      }
    }
    accepted.clear(playerEntity);
    List<Integer> selected = accepted.stream().limit(MAX_ENTITIES).boxed().toList();
    byte[] mask = new byte[AREA_BYTES];
    if (!unknownAreas) {
      Arrays.fill(mask, (byte) 255);
      for (int area = visibleAreas.nextSetBit(0);
          area >= 0 && area < AREA_BYTES * 8;
          area = visibleAreas.nextSetBit(area + 1)) mask[area / 8] &= (byte) ~(1 << (area & 7));
    }
    return new Selection(selected, new BspMap.Bytes(mask), accepted.cardinality());
  }

  private static boolean audience(Candidate candidate, int player) {
    int flags = candidate.flags(), single = candidate.singleClient();
    return (flags & NOCLIENT) == 0
        && ((flags & SINGLECLIENT) == 0 || single == player)
        && ((flags & NOTSINGLECLIENT) == 0 || single != player)
        && ((flags & CLIENTMASK) == 0 || (player < 32 && (single & (1 << player)) != 0));
  }

  private static boolean inRange(Vec3 point, Candidate candidate) {
    if (candidate.portalRange() == 0) return true;
    Vec3 delta = candidate.origin().add(point.scale(-1));
    double radius = candidate.portalRange();
    return delta.x() * delta.x() + delta.y() * delta.y() + delta.z() * delta.z() <= radius * radius;
  }

  private View view(Vec3 eye) {
    var leaf = connectivity.leaf(eye);
    boolean unknown =
        leaf == null
            || leaf.cluster() < 0
            || !connectivity.valid(leaf.area())
            || (hasPvs && leaf.cluster() >= map.visibility().clusters())
            || (!map.models().isEmpty() && !contains(map.models().getFirst().bounds(), eye));
    return new View(
        eye,
        leaf == null ? -1 : leaf.cluster(),
        connectivity.reachable(unknown ? -1 : leaf.area()),
        unknown);
  }

  private boolean visible(View view, Membership member) {
    if (view.unknown() || member.unknown()) return true;
    boolean areaVisible = false;
    for (int area : member.areas()) if (view.areas().get(area)) areaVisible = true;
    if (!areaVisible) return false;
    if (!hasPvs) return true;
    for (int cluster : member.clusters())
      if (map.visibility().visible(view.cluster(), cluster)) return true;
    return false;
  }

  private Membership membership(Candidate candidate) {
    Cached cached = membershipCache[candidate.number()];
    if (cached != null && cached.bounds().equals(candidate.bounds())) return cached.membership();
    var result = classify(candidate.bounds());
    membershipCache[candidate.number()] = new Cached(candidate.bounds(), result);
    return result;
  }

  private Membership classify(BspMap.Bounds bounds) {
    BitSet clusters = new BitSet(), areas = new BitSet(), visited = new BitSet();
    var pending = new ArrayDeque<Integer>();
    boolean unknown = map.leaves().isEmpty();
    if (map.nodes().isEmpty()) {
      if (map.leaves().size() == 1) pending.add(-1);
      else unknown = true;
    } else pending.add(0);
    while (!pending.isEmpty()) {
      int index = pending.removeLast();
      if (index < 0) {
        int leafIndex = -index - 1;
        if (leafIndex < 0 || leafIndex >= map.leaves().size()) {
          unknown = true;
          continue;
        }
        var leaf = map.leaves().get(leafIndex);
        if (leaf.cluster() < 0) continue; // Solid leaves never contribute cluster membership.
        if (hasPvs && leaf.cluster() >= map.visibility().clusters()) unknown = true;
        else if (hasPvs) clusters.set(leaf.cluster());
        if (connectivity.valid(leaf.area())) areas.set(leaf.area());
        else unknown = true;
        continue;
      }
      if (index >= map.nodes().size()) {
        unknown = true;
        continue;
      }
      if (visited.get(index)) continue;
      visited.set(index);
      var node = map.nodes().get(index);
      if (node.plane() < 0 || node.plane() >= map.planes().size()) {
        unknown = true;
        continue;
      }
      var plane = map.planes().get(node.plane());
      Vec3 n = plane.normal(), min = bounds.min(), max = bounds.max();
      double low =
          n.x() * (n.x() < 0 ? max.x() : min.x())
              + n.y() * (n.y() < 0 ? max.y() : min.y())
              + n.z() * (n.z() < 0 ? max.z() : min.z());
      double high =
          n.x() * (n.x() < 0 ? min.x() : max.x())
              + n.y() * (n.y() < 0 ? min.y() : max.y())
              + n.z() * (n.z() < 0 ? min.z() : max.z());
      if (high >= plane.distance()) pending.add(node.front());
      if (low <= plane.distance()) pending.add(node.back());
    }
    return new Membership(clusters.stream().toArray(), areas.stream().toArray(), unknown);
  }

  private static boolean contains(BspMap.Bounds bounds, Vec3 point) {
    return point.x() >= bounds.min().x()
        && point.x() <= bounds.max().x()
        && point.y() >= bounds.min().y()
        && point.y() <= bounds.max().y()
        && point.z() >= bounds.min().z()
        && point.z() <= bounds.max().z();
  }
}
