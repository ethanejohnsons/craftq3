package dev.bluevista.craftq3.botlib.aas;

import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.PriorityQueue;

/** Native-observed cluster/portal travel-time semantics over immutable AAS data. */
public final class AasRouteTimes {
  public record Limits(int maxCacheEntries, long maxCacheBytes, int maxWork) {
    public static final Limits DEFAULT = new Limits(256, 64L * 1024 * 1024, 20_000_000);

    public Limits {
      if (maxCacheEntries < 1
          || maxCacheEntries > 4096
          || maxCacheBytes < 1024
          || maxCacheBytes > 1L << 31
          || maxWork < 1
          || maxWork > 100_000_000)
        throw new IllegalArgumentException("Invalid AAS routing limits");
    }
  }

  public record CacheStats(int entries, long bytes, long clusterBuilds, long portalBuilds) {}

  /** Reachability is the global AAS index; zero means no first edge (including same-area). */
  public record Route(int travelTime, int reachability) {
    public Route {
      if (travelTime < 0 || reachability < 0 || (travelTime == 0 && reachability != 0))
        throw new IllegalArgumentException("Invalid AAS route result");
    }
  }

  private static final Route UNREACHABLE = new Route(0, 0);
  private static final Route SAME_AREA = new Route(1, 0);

  private sealed interface Key permits ClusterKey, PortalKey {}

  private record ClusterKey(int cluster, int goal, TravelPolicy policy) implements Key {}

  private record PortalKey(int cluster, int goal, TravelPolicy policy) implements Key {}

  private sealed interface Cached permits ClusterCache, PortalCache {
    long bytes();
  }

  private record ClusterCache(int[] times, int[] exits) implements Cached {
    @Override
    public long bytes() {
      return 8L * times.length;
    }
  }

  private record PortalCache(int[] times) implements Cached {
    @Override
    public long bytes() {
      return 4L * times.length;
    }
  }

  private record Candidate(int portal, int time) {}

  private static final class Work {
    int remaining;

    Work(int remaining) {
      this.remaining = remaining;
    }

    void take() {
      if (--remaining < 0) throw new IllegalStateException("AAS routing work budget exceeded");
    }
  }

  private final AasMap map;
  private final Limits limits;
  private final int[][] incoming, clusterPortals;
  private final int[] reachSources, portalMaximum;
  private final LinkedHashMap<Key, Cached> caches = new LinkedHashMap<>(16, .75f, true);
  private long cacheBytes, clusterBuilds, portalBuilds;

  public AasRouteTimes(AasMap map) {
    this(map, Limits.DEFAULT);
  }

  public AasRouteTimes(AasMap map, Limits limits) {
    this.map = Objects.requireNonNull(map);
    this.limits = Objects.requireNonNull(limits);
    reachSources = new int[map.reachabilities().size()];
    Arrays.fill(reachSources, -1);
    int[] counts = new int[map.areas().size()];
    for (int a = 1; a < map.areaSettings().size(); a++) {
      var settings = map.areaSettings().get(a);
      for (int r = settings.firstReachability();
          r < settings.firstReachability() + settings.reachabilityCount();
          r++) {
        if (reachSources[r] != -1)
          throw new IllegalArgumentException("Reachability belongs to multiple source areas");
        reachSources[r] = a;
        counts[map.reachabilities().get(r).area()]++;
      }
    }
    incoming = new int[counts.length][];
    for (int a = 0; a < counts.length; a++) incoming[a] = new int[counts[a]];
    Arrays.fill(counts, 0);
    // Preserve area/source order before reversing each destination list during relaxation.
    for (int a = 1; a < map.areaSettings().size(); a++) {
      var settings = map.areaSettings().get(a);
      for (int r = settings.firstReachability();
          r < settings.firstReachability() + settings.reachabilityCount();
          r++) {
        int destination = map.reachabilities().get(r).area();
        incoming[destination][counts[destination]++] = r;
      }
    }
    portalMaximum = new int[map.portals().size()];
    Work work = new Work(limits.maxWork());
    for (int p = 1; p < map.portals().size(); p++) {
      var portal = map.portals().get(p);
      var settings = map.areaSettings().get(portal.area());
      for (int r : incoming[portal.area()]) {
        for (int out = settings.firstReachability();
            out < settings.firstReachability() + settings.reachabilityCount();
            out++) {
          work.take();
          portalMaximum[p] =
              Math.max(
                  portalMaximum[p],
                  areaTravelTime(
                      portal.area(),
                      map.reachabilities().get(r).end(),
                      map.reachabilities().get(out).start()));
        }
      }
    }
    // The file's portal order breaks equal-time ties; numeric portal order is different.
    clusterPortals = new int[map.clusters().size()][];
    for (int c = 0; c < clusterPortals.length; c++) {
      var cluster = map.clusters().get(c);
      clusterPortals[c] = new int[cluster.portalCount()];
      for (int p = 0; p < cluster.portalCount(); p++)
        clusterPortals[c][p] = map.portalIndices().get(cluster.firstPortal() + p);
    }
  }

  public synchronized int travelTime(int startArea, Vec3 origin, int goalArea, int travelFlags) {
    return travelTime(startArea, origin, goalArea, TravelPolicy.ofFlags(travelFlags));
  }

  /** Zero means no route. Budget/unsupported time-range failures are explicit exceptions. */
  public synchronized int travelTime(
      int startArea, Vec3 origin, int goalArea, TravelPolicy policy) {
    return route(startArea, origin, goalArea, policy).travelTime();
  }

  public synchronized Route route(int startArea, Vec3 origin, int goalArea, int travelFlags) {
    return route(startArea, origin, goalArea, TravelPolicy.ofFlags(travelFlags));
  }

  /** Returns the native AAS cache-selected first edge, not the movement AI avoidance selector. */
  public synchronized Route route(int startArea, Vec3 origin, int goalArea, TravelPolicy policy) {
    Objects.requireNonNull(origin);
    Objects.requireNonNull(policy);
    finitePoint(origin);
    if (!valid(startArea) || !valid(goalArea)) return UNREACHABLE;
    if (startArea == goalArea) return SAME_AREA;
    if (map.areaSettings().get(startArea).reachabilityCount() == 0
        || map.areaSettings().get(goalArea).reachabilityCount() == 0) return UNREACHABLE;
    int startCluster = map.areaSettings().get(startArea).cluster();
    int goalCluster = map.areaSettings().get(goalArea).cluster();
    boolean portalGoal = goalCluster < 0;
    if (startCluster == 0 || goalCluster == 0) return UNREACHABLE;
    if (((map.areaSettings().get(startArea).contents()
                | map.areaSettings().get(goalArea).contents())
            & 256)
        != 0)
      policy =
          new TravelPolicy(
              policy.travelFlags() | TravelFlags.DO_NOT_ENTER,
              policy.presenceTypes(),
              policy.team(),
              policy.disabledAreas());
    if (goalCluster < 0) {
      var portal = map.portals().get(-goalCluster);
      goalCluster = portal.frontCluster();
    }
    Work work = new Work(limits.maxWork());
    int directCluster =
        portalGoal && startCluster > 0 && belongs(goalArea, startCluster)
            ? startCluster
            : goalCluster;
    if (belongs(startArea, directCluster) && (startCluster > 0 || !portalGoal)) {
      var local = cluster(directCluster, goalArea, policy, work);
      int direct = fromOrigin(local, startArea, origin);
      if (direct != 0) return new Route(direct, local.exits()[startArea]);
    }
    // An unreachable direct route falls back through portals. A portal goal's remote
    // cache uses its front cluster, independently of which side supplied the start.
    var remote = portals(goalCluster, goalArea, policy, work);
    if (startCluster < 0) {
      int portal = -startCluster;
      // Native AAS reports the portal area's first stored edge here, independently
      // of the remote cache's chosen path. Movement AI uses a separate selector.
      return remote.times()[portal] == 0
          ? UNREACHABLE
          : new Route(
              remote.times()[portal] - portalMaximum[portal],
              map.areaSettings().get(startArea).firstReachability());
    }
    int best = 0, bestReach = 0;
    for (int portal : clusterPortals[startCluster]) {
      work.take();
      if (remote.times()[portal] == 0) continue;
      var local = cluster(startCluster, map.portals().get(portal).area(), policy, work);
      int first = fromOrigin(local, startArea, origin);
      if (first != 0) {
        int candidate = add(first, remote.times()[portal]);
        if (best == 0 || candidate < best) {
          best = candidate;
          bestReach = local.exits()[startArea];
        }
      }
    }
    return best == 0 ? UNREACHABLE : new Route(best, bestReach);
  }

  private int fromOrigin(ClusterCache cache, int start, Vec3 origin) {
    int exit = cache.exits()[start];
    return exit < 0 || cache.times()[start] == 0
        ? 0
        : add(
            cache.times()[start],
            areaTravelTime(start, origin, map.reachabilities().get(exit).start()));
  }

  private ClusterCache cluster(int cluster, int goal, TravelPolicy policy, Work work) {
    var key = new ClusterKey(cluster, goal, policy);
    if (caches.get(key) instanceof ClusterCache result) return result;
    int[] times = new int[map.areas().size()], exits = new int[times.length];
    Arrays.fill(exits, -1);
    int[] queue = new int[times.length];
    boolean[] queued = new boolean[times.length];
    int head = 0, tail = 0, pending = 0;
    times[goal] = 1;
    queue[tail++] = goal;
    pending++;
    queued[goal] = true;
    while (pending != 0) {
      work.take();
      int destination = queue[head++];
      if (head == queue.length) head = 0;
      pending--;
      queued[destination] = false;
      // A forbidden area may be exited, but cannot be entered while searching a route.
      if (!policy.permitsArea(destination, map.areaSettings().get(destination))) continue;
      int[] links = incoming[destination];
      for (int i = links.length - 1; i >= 0; i--) {
        work.take();
        int r = links[i], source = reachSources[r];
        var reach = map.reachabilities().get(r);
        if (!belongs(source, cluster) || !policy.permitsReachability(reach)) continue;
        int local =
            exits[destination] < 0
                ? 0
                : areaTravelTime(
                    destination, reach.end(), map.reachabilities().get(exits[destination]).start());
        int candidate = add(times[destination], reach.travelTime(), local);
        if (times[source] != 0 && times[source] <= candidate) continue;
        times[source] = candidate;
        exits[source] = r;
        if (!queued[source]) {
          queue[tail++] = source;
          if (tail == queue.length) tail = 0;
          pending++;
          queued[source] = true;
        }
      }
    }
    var result = new ClusterCache(times, exits);
    clusterBuilds++;
    retain(key, result);
    return result;
  }

  private PortalCache portals(int targetCluster, int goal, TravelPolicy policy, Work work) {
    var key = new PortalKey(targetCluster, goal, policy);
    if (caches.get(key) instanceof PortalCache result) return result;
    int[] times = new int[map.portals().size()];
    var queue =
        new PriorityQueue<Candidate>(
            Comparator.comparingInt(Candidate::time).thenComparingInt(Candidate::portal));
    var goalCache = cluster(targetCluster, goal, policy, work);
    for (int p : clusterPortals[targetCluster]) {
      work.take();
      int area = map.portals().get(p).area();
      if (area == goal || goalCache.times()[area] == 0) continue;
      times[p] = add(goalCache.times()[area], portalMaximum[p], 1);
      queue.add(new Candidate(p, times[p]));
    }
    while (!queue.isEmpty()) {
      work.take();
      var next = queue.remove();
      if (times[next.portal()] != next.time()) continue;
      var exitPortal = map.portals().get(next.portal());
      for (int c : new int[] {exitPortal.frontCluster(), exitPortal.backCluster()}) {
        var local = cluster(c, exitPortal.area(), policy, work);
        for (int p : clusterPortals[c]) {
          work.take();
          int area = map.portals().get(p).area();
          if (p == next.portal() || area == goal || local.times()[area] == 0) continue;
          int candidate = add(next.time(), local.times()[area], portalMaximum[p]);
          if (times[p] != 0 && times[p] <= candidate) continue;
          times[p] = candidate;
          queue.add(new Candidate(p, candidate));
        }
      }
    }
    var result = new PortalCache(times);
    portalBuilds++;
    retain(key, result);
    return result;
  }

  /** Observed float32 local metric: normal .33, liquid 1, crouch-only 1.3; unsigned16 result. */
  public int areaTravelTime(int area, Vec3 start, Vec3 end) {
    if (!valid(area)) throw new IllegalArgumentException("Invalid AAS area " + area);
    finitePoint(start);
    finitePoint(end);
    float x = (float) start.x() - (float) end.x(),
        y = (float) start.y() - (float) end.y(),
        z = (float) start.z() - (float) end.z();
    float length = (float) Math.sqrt(x * x + y * y + z * z);
    var settings = map.areaSettings().get(area);
    float scale =
        (settings.presenceType() & 2) == 0 ? 1.3f : (settings.contents() & 7) != 0 ? 1 : .33f;
    float time = length * scale;
    if (!Float.isFinite(time) || time > Integer.MAX_VALUE)
      throw new IllegalArgumentException("Intra-area travel distance exceeds supported range");
    return Math.max(1, (int) time & 0xffff);
  }

  private boolean belongs(int area, int cluster) {
    int stored = map.areaSettings().get(area).cluster();
    if (stored >= 0) return stored == cluster;
    var portal = map.portals().get(-stored);
    return portal.frontCluster() == cluster || portal.backCluster() == cluster;
  }

  private boolean valid(int area) {
    return area > 0 && area < map.areas().size();
  }

  private static void finitePoint(Vec3 point) {
    Objects.requireNonNull(point);
    if (!Float.isFinite((float) point.x())
        || !Float.isFinite((float) point.y())
        || !Float.isFinite((float) point.z()))
      throw new IllegalArgumentException("AAS routing point exceeds float range");
  }

  private static int add(int... terms) {
    long sum = 0;
    for (int term : terms) sum += term;
    if (sum > 65535)
      throw new IllegalStateException("AAS route exceeds the supported unsigned16 cache range");
    return (int) sum;
  }

  private void retain(Key key, Cached value) {
    if (value.bytes() > limits.maxCacheBytes()) return;
    Cached prior = caches.put(key, value);
    if (prior != null) cacheBytes -= prior.bytes();
    cacheBytes += value.bytes();
    while (caches.size() > limits.maxCacheEntries() || cacheBytes > limits.maxCacheBytes()) {
      var entry = caches.pollFirstEntry();
      cacheBytes -= Objects.requireNonNull(entry).getValue().bytes();
    }
  }

  public synchronized CacheStats cacheStats() {
    return new CacheStats(caches.size(), cacheBytes, clusterBuilds, portalBuilds);
  }

  public synchronized void clearCaches() {
    caches.clear();
    cacheBytes = 0;
  }

  AasMap map() {
    return map;
  }
}
