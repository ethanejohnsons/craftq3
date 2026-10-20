package dev.bluevista.craftq3.engine.benchmarks;

import dev.bluevista.craftq3.engine.collision.CollisionModel;
import dev.bluevista.craftq3.engine.collision.Contents;
import dev.bluevista.craftq3.engine.collision.Trace;
import dev.bluevista.craftq3.engine.collision.TraceWork;
import java.util.Random;

/**
 * Seeded trace segments through a collision model, shaped like game traffic: every start is a
 * position a player hull fits in, and every end lies up to 512 units away horizontally and 256
 * vertically, so the set mixes short movement traces with longer weapon and visibility traces.
 */
final class TraceSet {
  static final int SIZE = 512;
  static final float[] HULL_MINS = {-15, -15, -24};
  static final float[] HULL_MAXS = {15, 15, 32};
  static final int MASK = Contents.MASK_PLAYERSOLID;

  final float[][] starts = new float[SIZE][];
  final float[][] ends = new float[SIZE][];

  TraceSet(CollisionModel model, float[] mins, float[] maxs, long seed) {
    Random random = new Random(seed);
    TraceWork work = model.work();
    Trace trace = new Trace();
    int found = 0;
    for (int attempt = 0; found < SIZE; attempt++) {
      if (attempt > SIZE * 10_000)
        throw new IllegalStateException("Only " + found + " open positions in the map");
      float[] start = {
        lerp(mins[0], maxs[0], random.nextFloat()),
        lerp(mins[1], maxs[1], random.nextFloat()),
        lerp(mins[2], maxs[2], random.nextFloat())
      };
      // The void outside a compiled map has no cluster; skip it like the inside of brushes.
      if (model.leafCluster(model.pointLeafnum(start)) < 0) continue;
      model.boxTrace(work, trace, start, start, HULL_MINS, HULL_MAXS, 0, MASK, false);
      if (trace.startSolid()) continue;
      float[] end = {
        start[0] + (random.nextFloat() * 2 - 1) * 512,
        start[1] + (random.nextFloat() * 2 - 1) * 512,
        start[2] + (random.nextFloat() * 2 - 1) * 256
      };
      starts[found] = start;
      ends[found] = end;
      found++;
    }
  }

  private static float lerp(float a, float b, float t) {
    return a + (b - a) * t;
  }
}
