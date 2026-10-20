package dev.bluevista.craftq3.engine.collision;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.bsp.BspBoxLeaves;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.bsp.BspReader;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Builds the collision model of every map in the installation and checks trace invariants on seeded
 * random player-hull and point traces. The traces are also written to {@code
 * build/realdata/collision} (never committed) so an out-of-build program can replay them through
 * the legacy implementation.
 */
@RealData
class RealMapCollisionTest {
  private static final int TRACES_PER_KIND = 2000;
  private static final float[] HULL_MINS = {-15, -15, -24};
  private static final float[] HULL_MAXS = {15, 15, 32};
  private static final float PULL_BACK = 0.01f;
  private static final float E = Clip.SURFACE_CLIP_EPSILON;
  private static final float[] DEEP_HULL_MINS = {-15 + E, -15 + E, -24 + E};
  private static final float[] DEEP_HULL_MAXS = {15 - E, 15 - E, 32 - E};
  // An inverted box shrinks every brush by the epsilon in a position test.
  private static final float[] DEEP_POINT = {E, E, E};
  private static final float[] DEEP_POINT_MAXS = {-E, -E, -E};

  /** Distances are compared with this much slack for float rounding along long traces. */
  private static final float DISTANCE_SLACK = 0.05f;

  private static final Set<String> PATCH_HEAVY = Set.of("q3dm7", "q3dm17", "q3tourney2", "q3dm12");

  /** Totals over every map. */
  private static final class Totals {
    int maps;
    long traces;
    long hullNanos;
    long pointNanos;
    long buildNanos;
    int endInSolid;
    int endInPatch;
    int endWithinEpsilon;
    int shortenedEarlier;
    int shortenedHit;
    int reverseNear;
    long patchHits;
    long hits;
    long startSolid;
    final Map<String, Long> patchHitsByMap = new LinkedHashMap<>();
    final List<String> failures = new ArrayList<>();
    final List<String> examples = new ArrayList<>();
  }

  @Test
  void everyMapKeepsTheTraceInvariants() throws IOException {
    Path root = RealDataRoot.require();
    Path out = Path.of("build", "realdata", "collision");
    Files.createDirectories(out);
    Totals totals = new Totals();
    List<String> index = new ArrayList<>();
    Map<String, String> seen = new LinkedHashMap<>();
    for (String game : games(root)) {
      try (SearchPath search = SearchPath.mount(root, game)) {
        for (String name : search.list("maps", ".bsp")) {
          String map = name.substring(0, name.length() - 4).toLowerCase(Locale.ROOT);
          if (seen.putIfAbsent(map, game) != null) continue;
          byte[] bytes = search.read(VirtualPath.of("maps/" + name));
          BspMap bsp;
          try {
            bsp = BspReader.read(bytes, game + "/maps/" + name);
          } catch (RuntimeException e) {
            totals.failures.add(game + "/" + map + ": " + e.getMessage());
            continue;
          }
          Path dump = out.resolve(game + "_" + map + ".traces");
          sweep(game, map, bsp, totals, dump);
          index.add(game + "\t" + map + "\t" + dump.getFileName());
        }
      }
    }
    Files.write(out.resolve("index.tsv"), index);
    double hullRate = TRACES_PER_KIND * (double) totals.maps / (totals.hullNanos / 1e9);
    double pointRate = TRACES_PER_KIND * (double) totals.maps / (totals.pointNanos / 1e9);
    String summary =
        String.format(
            Locale.ROOT,
            "maps=%d traces=%d hits=%d patchHits=%d startSolid=%d build=%.1fs%n"
                + "hull traces/s=%.0f point traces/s=%.0f%n"
                + "endInSolid=%d endWithinEpsilon=%d endInPatchFromBehind=%d shortenedEarlier=%d shortenedHitMismatch=%d"
                + " reverseNear=%d%n"
                + "patch-heavy hits=%s%nfailures=%s%nexamples:%n  %s%n",
            totals.maps,
            totals.traces,
            totals.hits,
            totals.patchHits,
            totals.startSolid,
            totals.buildNanos / 1e9,
            hullRate,
            pointRate,
            totals.endInSolid,
            totals.endWithinEpsilon,
            totals.endInPatch,
            totals.shortenedEarlier,
            totals.shortenedHit,
            totals.reverseNear,
            totals.patchHitsByMap,
            totals.failures,
            String.join("\n  ", totals.examples));
    System.out.print(summary);
    Files.writeString(out.resolve("summary.txt"), summary);
    assertTrue(totals.maps > 200, "maps swept: " + totals.maps);
    assertTrue(totals.failures.isEmpty(), "unreadable maps: " + totals.failures);
    for (String map : PATCH_HEAVY)
      assertTrue(totals.patchHitsByMap.getOrDefault(map, 0L) > 0, "no patch hits on " + map);
    assertEquals(0, totals.endInSolid, "end positions deeper than the epsilon inside a brush");
    // Rare float-precision cases are tolerated, as in the original: a point ray through the exact
    // seam of two facets can slip between them, and a sweep may end within the epsilon of a face it
    // was leaving, which the reverse sweep then touches at once.
    long checks = totals.traces;
    assertTrue(
        totals.shortenedEarlier <= checks / 20000, "shortened earlier: " + totals.shortenedEarlier);
    assertTrue(totals.shortenedHit <= checks / 20000, "shortened mismatch: " + totals.shortenedHit);
    assertTrue(totals.reverseNear <= checks / 20000, "reverse near hits: " + totals.reverseNear);
  }

  private static List<String> games(Path root) throws IOException {
    List<String> games = new ArrayList<>();
    games.add(SearchPath.BASE_GAME);
    try (Stream<Path> entries = Files.list(root)) {
      entries
          .filter(Files::isDirectory)
          .map(p -> p.getFileName().toString())
          .filter(name -> !name.equalsIgnoreCase(SearchPath.BASE_GAME))
          .sorted()
          .forEach(games::add);
    }
    List<String> result = new ArrayList<>();
    for (String game : games) {
      Path dir = root.resolve(game);
      try (Stream<Path> files = Files.list(dir)) {
        if (files.anyMatch(
            p ->
                p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pk3")
                    || p.getFileName().toString().equalsIgnoreCase("maps"))) result.add(game);
      }
    }
    return result;
  }

  private static void sweep(String game, String name, BspMap bsp, Totals totals, Path dump)
      throws IOException {
    long built = System.nanoTime();
    CollisionModel model = CollisionModel.build(bsp);
    totals.buildNanos += System.nanoTime() - built;
    CollisionModel brushesOnly = CollisionModel.build(bsp, false);
    totals.maps++;
    float[] lo = bsp.models().getFirst().mins().toArray();
    float[] hi = bsp.models().getFirst().maxs().toArray();
    var random = new Random(name.hashCode() * 31L + 46);
    TraceWork work = new TraceWork();
    TraceWork other = new TraceWork();
    float[][] starts = new float[TRACES_PER_KIND * 2][];
    float[][] ends = new float[TRACES_PER_KIND * 2][];
    for (int i = 0; i < starts.length; i++) {
      boolean hull = i >= TRACES_PER_KIND;
      starts[i] = openPoint(model, work, random, lo, hi, hull);
      ends[i] = randomPoint(random, lo, hi);
    }
    Trace[] results = new Trace[starts.length];
    for (int i = 0; i < results.length; i++) results[i] = new Trace();
    long t0 = System.nanoTime();
    for (int i = 0; i < TRACES_PER_KIND; i++)
      model.boxTrace(
          work, results[i], starts[i], ends[i], null, null, 0, Contents.MASK_SHOT, false);
    long t1 = System.nanoTime();
    for (int i = TRACES_PER_KIND; i < results.length; i++)
      model.boxTrace(
          work,
          results[i],
          starts[i],
          ends[i],
          HULL_MINS,
          HULL_MAXS,
          0,
          Contents.MASK_PLAYERSOLID,
          false);
    long t2 = System.nanoTime();
    totals.pointNanos += t1 - t0;
    totals.hullNanos += t2 - t1;

    Trace check = new Trace();
    Trace plain = new Trace();
    long patchHits = 0;
    try (OutputStream file = Files.newOutputStream(dump);
        DataOutputStream data = new DataOutputStream(new BufferedOutputStream(file))) {
      data.writeInt(results.length);
      for (int i = 0; i < results.length; i++) {
        boolean hull = i >= TRACES_PER_KIND;
        float[] mins = hull ? HULL_MINS : null;
        float[] maxs = hull ? HULL_MAXS : null;
        int mask = hull ? Contents.MASK_PLAYERSOLID : Contents.MASK_SHOT;
        Trace r = results[i];
        float[] s = starts[i];
        float[] e = ends[i];
        float length = distance(s, e);
        totals.traces++;
        if (r.startSolid()) totals.startSolid++;
        if (r.fraction() < 1) totals.hits++;
        brushesOnly.boxTrace(other, plain, s, e, mins, maxs, 0, mask, false);
        boolean patchHit = !r.startSolid() && r.fraction() < plain.fraction();
        if (patchHit) patchHits++;
        if (!r.startSolid()) {
          // 1. The end position, pulled back a hair, is not inside a brush by more than the clip
          // epsilon: a sweep within SURFACE_CLIP_EPSILON of leaving a brush counts as having left
          // it, so it may skim a corner that shallowly (tested with the shape shrunk by the
          // epsilon). Patch facets are one-sided for sweeps but two-sided slabs for position tests
          // (as in the original), so a sweep that passed through a patch from behind may end
          // inside one; those are counted separately.
          float[] p = new float[3];
          float back = length > PULL_BACK ? PULL_BACK / length : 0;
          for (int axis = 0; axis < 3; axis++)
            p[axis] = r.endPos(axis) - back * (e[axis] - s[axis]);
          float[] deepMins = hull ? DEEP_HULL_MINS : DEEP_POINT;
          float[] deepMaxs = hull ? DEEP_HULL_MAXS : DEEP_POINT_MAXS;
          brushesOnly.boxTrace(other, check, p, p, deepMins, deepMaxs, 0, mask, false);
          if (check.startSolid()) {
            totals.endInSolid++;
            example(totals, name + " end in solid " + describe(s, e, hull) + " " + r);
          } else {
            brushesOnly.boxTrace(other, check, p, p, mins, maxs, 0, mask, false);
            if (check.startSolid()) totals.endWithinEpsilon++;
            else {
              model.boxTrace(work, check, p, p, mins, maxs, 0, mask, false);
              if (check.startSolid()) totals.endInPatch++;
            }
          }
          // 2. Shortening keeps the stopping distance: a shorter trace stops at the same place,
          // or runs clear when it ends before that place.
          float stop = r.fraction() * length;
          float t = 0.2f + 0.6f * random.nextFloat();
          float[] mid = new float[3];
          for (int axis = 0; axis < 3; axis++) mid[axis] = s[axis] + t * (e[axis] - s[axis]);
          model.boxTrace(work, check, s, mid, mins, maxs, 0, mask, false);
          float shortLength = distance(s, mid);
          float shortStop = check.fraction() * shortLength;
          if (shortStop < Math.min(stop, shortLength) - DISTANCE_SLACK - 1e-4f * length) {
            totals.shortenedEarlier++;
            example(
                totals,
                name
                    + " shortened stops earlier "
                    + describe(s, mid, hull)
                    + " "
                    + check
                    + " full end "
                    + Arrays.toString(e)
                    + " "
                    + r);
          } else if (r.fraction() < 1
              && shortLength > stop + 1
              && Math.abs(shortStop - stop) > DISTANCE_SLACK + 1e-4f * length) {
            totals.shortenedHit++;
            example(totals, name + " shortened stop differs " + stop + " vs " + shortStop);
          }
          // 3. Tracing back from the end never hits within the clip epsilon of it.
          float[] end = new float[3];
          r.endPos(end);
          model.boxTrace(work, check, end, s, mins, maxs, 0, mask, false);
          float backLength = distance(end, s);
          if (!check.startSolid()
              && check.fraction() < 1
              && check.fraction() * backLength < Clip.SURFACE_CLIP_EPSILON - 1e-3f) {
            totals.reverseNear++;
            example(totals, name + " reverse hit near " + describe(end, s, hull) + " " + check);
          }
        }
        data.writeByte(hull ? 1 : 0);
        for (float v : s) data.writeFloat(v);
        for (float v : e) data.writeFloat(v);
        data.writeFloat(r.fraction());
        data.writeByte((r.startSolid() ? 1 : 0) | (r.allSolid() ? 2 : 0) | (patchHit ? 4 : 0));
      }
    }
    totals.patchHits += patchHits;
    if (PATCH_HEAVY.contains(name)) totals.patchHitsByMap.put(name, patchHits);
    checkLeaves(bsp, model, random, lo, hi);
    var stats = model.statistics();
    System.out.printf(
        Locale.ROOT,
        "%s/%s brushes=%d patches=%d facets=%d skipped=%d patchHits=%d%n",
        game,
        name,
        stats.brushes(),
        stats.patches(),
        stats.patchFacets(),
        stats.skippedPatches(),
        patchHits);
  }

  /** The leaf enumeration agrees with the format layer's reference query. */
  private static void checkLeaves(
      BspMap bsp, CollisionModel model, Random random, float[] lo, float[] hi) {
    int[] list = new int[64];
    int[] last = new int[1];
    for (int i = 0; i < 50; i++) {
      float[] a = randomPoint(random, lo, hi);
      float[] b = new float[3];
      for (int axis = 0; axis < 3; axis++) b[axis] = a[axis] + random.nextFloat() * 256;
      int count = model.boxLeafnums(a, b, list, list.length, last);
      var expected = BspBoxLeaves.query(bsp, Vec3f.of(a), Vec3f.of(b), list.length);
      assertArrayEquals(expected.toArray(), Arrays.copyOf(list, count));
      assertEquals(expected.lastLeaf(), last[0]);
    }
  }

  private static void example(Totals totals, String text) {
    if (totals.examples.size() < 20) totals.examples.add(text);
  }

  private static String describe(float[] s, float[] e, boolean hull) {
    return (hull ? "hull " : "point ") + Arrays.toString(s) + " -> " + Arrays.toString(e);
  }

  private static float distance(float[] a, float[] b) {
    float dx = b[0] - a[0];
    float dy = b[1] - a[1];
    float dz = b[2] - a[2];
    return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
  }

  private static float[] randomPoint(Random random, float[] lo, float[] hi) {
    float[] p = new float[3];
    for (int axis = 0; axis < 3; axis++)
      p[axis] = lo[axis] + random.nextFloat() * (hi[axis] - lo[axis]);
    return p;
  }

  /** A random point in a leaf with a cluster where the shape does not start solid, if found. */
  private static float[] openPoint(
      CollisionModel model, TraceWork work, Random random, float[] lo, float[] hi, boolean hull) {
    Trace probe = new Trace();
    float[] p = randomPoint(random, lo, hi);
    for (int attempt = 0; attempt < 200; attempt++) {
      if (model.leafCluster(model.pointLeafnum(p)) >= 0) {
        model.boxTrace(
            work,
            probe,
            p,
            p,
            hull ? HULL_MINS : null,
            hull ? HULL_MAXS : null,
            0,
            Contents.MASK_PLAYERSOLID,
            false);
        if (!probe.startSolid()) return p;
      }
      p = randomPoint(random, lo, hi);
    }
    return p;
  }
}
