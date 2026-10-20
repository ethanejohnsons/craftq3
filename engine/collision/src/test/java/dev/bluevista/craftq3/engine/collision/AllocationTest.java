package dev.bluevista.craftq3.engine.collision;

import static dev.bluevista.craftq3.engine.collision.TestMap.v;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** After warm-up, traces and point queries allocate nothing on the calling thread. */
class AllocationTest {
  @Test
  void hotPathDoesNotAllocate() {
    TestMap map = new TestMap();
    int solid = map.texture("textures/solid", 0, Contents.SOLID);
    int curve = map.texture("textures/curve", 0, Contents.SOLID);
    int split = map.plane(1, 0, 0, 0);
    int a = map.box(v(-200, -200, -16), v(-8, 200, 0), solid);
    int b = map.box(v(8, -200, -16), v(200, 200, 0), solid);
    float[][] dome = new float[9][];
    for (int i = 0; i < 9; i++) dome[i] = v((i % 3 - 1) * 128, (i / 3 - 1) * 128, i == 4 ? 100 : 0);
    int face = map.patch(curve, 3, 3, dome);
    map.node(split, -1, -2);
    map.leaf(0, 0, new int[] {face}, new int[] {b});
    map.leaf(1, 0, new int[] {face}, new int[] {a});
    map.model(TestMap.WORLD_MIN, TestMap.WORLD_MAX, 0, 1, 0, 2);
    var model = CollisionModel.build(map.build());
    TraceWork work = new TraceWork();
    Trace trace = new Trace();
    float[] mins = {-15, -15, -24};
    float[] maxs = {15, 15, 32};
    float[] origin = {0, 0, 0};
    float[] angles = {0, 30, 10};
    float[][] points = new float[256][];
    var random = new Random(3);
    for (int i = 0; i < points.length; i++)
      points[i] =
          v(
              random.nextFloat() * 400 - 200,
              random.nextFloat() * 400 - 200,
              random.nextFloat() * 200 - 50);
    Runnable batch =
        () -> {
          for (int i = 0; i < points.length; i++) {
            float[] start = points[i];
            float[] end = points[(i + 1) % points.length];
            model.boxTrace(work, trace, start, end, null, null, 0, -1, false);
            model.boxTrace(work, trace, start, end, mins, maxs, 0, -1, false);
            model.boxTrace(work, trace, start, end, mins, maxs, 0, -1, true);
            model.boxTrace(work, trace, start, start, mins, maxs, 0, -1, false);
            model.transformedBoxTrace(
                work, trace, start, end, mins, maxs, 0, -1, origin, angles, false);
            int box = model.tempBoxModel(work, mins, maxs, (i & 1) == 0);
            model.transformedBoxTrace(
                work, trace, start, end, mins, maxs, box, -1, start, angles, false);
            model.pointContents(work, start, 0);
          }
        };
    for (int i = 0; i < 200; i++) batch.run();
    var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    long thread = Thread.currentThread().threadId();
    long before = threads.getThreadAllocatedBytes(thread);
    for (int i = 0; i < 50; i++) batch.run();
    long allocated = threads.getThreadAllocatedBytes(thread) - before;
    assertTrue(allocated < 4096, "allocated " + allocated + " bytes over 102400 queries");
  }
}
