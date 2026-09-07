package dev.bluevista.craftq3.client;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.collision.BoxTraceWorld;
import dev.bluevista.craftq3.collision.BspTraceWorld;
import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.collision.TraceWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.render.MarkFragments;
import dev.bluevista.craftq3.server.VmAbi;
import dev.bluevista.craftq3.vm.QvmMemory;
import java.util.ArrayList;

/** Collision services used by original cgame prediction, separate from server linked entities. */
final class ClientCollision {
  private static final int TEMP_BOX = 1_000_000;
  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private final BspTraceWorld world;
  private final BspMap map;
  private final TraceWorld terrain;
  private MarkFragments marks;
  private Vec3 temporaryMin = ZERO, temporaryMax = ZERO;

  ClientCollision(BspMap map) {
    this(map, null);
  }

  ClientCollision(BspMap map, TraceWorld terrain) {
    this.map = map;
    world = new BspTraceWorld(map);
    this.terrain = terrain == null ? world : terrain;
  }

  int modelCount() {
    return map.models().size();
  }

  int inline(int index) {
    if (index < 0 || index >= modelCount())
      throw new IllegalArgumentException("Invalid cgame collision model");
    return index;
  }

  int temporary(Vec3 min, Vec3 max) {
    new TraceRequest(ZERO, ZERO, min, max, -1, -1);
    temporaryMin = min;
    temporaryMax = max;
    return TEMP_BOX;
  }

  int contents(QvmMemory memory, int[] args, boolean transformed) {
    Vec3 origin = transformed ? VmAbi.vector(memory, args[2]) : ZERO,
        angles = transformed ? VmAbi.vector(memory, args[3]) : ZERO;
    return model(args[1], origin, angles).pointContents(VmAbi.vector(memory, args[0]), -1, -1);
  }

  void trace(QvmMemory memory, int[] args, boolean transformed) {
    Vec3 origin = transformed ? VmAbi.vector(memory, args[7]) : ZERO,
        angles = transformed ? VmAbi.vector(memory, args[8]) : ZERO;
    var request =
        new TraceRequest(
            VmAbi.vector(memory, args[1]),
            VmAbi.vector(memory, args[2]),
            args[3] == 0 ? ZERO : VmAbi.vector(memory, args[3]),
            args[4] == 0 ? ZERO : VmAbi.vector(memory, args[4]),
            args[6],
            -1);
    VmAbi.trace(memory, args[0], model(args[5], origin, angles).trace(request));
  }

  int marks(QvmMemory memory, int[] a) {
    if (a[0] < 3 || a[0] > 64 || a[3] < 0 || a[3] > 65536 || a[5] < 0 || a[5] > 8192)
      throw new IllegalArgumentException("Invalid cgame mark fragment capacity");
    memory.checkRange(a[1], a[0] * 12);
    memory.checkRange(a[2], 12);
    memory.checkRange(a[4], a[3] * 12);
    memory.checkRange(a[6], a[5] * 8);
    var projection = VmAbi.vector(memory, a[2]);
    // An all-solid impact has no surface normal. Original cgame can form NaN corners from
    // that zero direction; there is no projection to clip, regardless of those corners.
    // Validate every guest buffer above before returning the empty result.
    if (projection.x() == 0 && projection.y() == 0 && projection.z() == 0) return 0;
    var polygon = new ArrayList<Vec3>(a[0]);
    for (int i = 0; i < a[0]; i++) polygon.add(VmAbi.vector(memory, a[1] + i * 12));
    if (marks == null) marks = new MarkFragments(map);
    var fragments = marks.project(polygon, projection, a[3], a[5]);
    int point = 0;
    for (int i = 0; i < fragments.size(); i++) {
      var fragment = fragments.get(i);
      memory.writeInt(a[6] + i * 8, point);
      memory.writeInt(a[6] + i * 8 + 4, fragment.size());
      for (Vec3 position : fragment) VmAbi.vector(memory, a[4] + 12 * point++, position);
    }
    return fragments.size();
  }

  private TraceWorld model(int handle, Vec3 origin, Vec3 angles) {
    if (handle == TEMP_BOX)
      return new BoxTraceWorld(
          temporaryMin.add(origin), temporaryMax.add(origin), 0x2000000, 0, 1022);
    inline(handle);
    return handle == 0 && origin.equals(ZERO) && angles.equals(ZERO)
        ? terrain
        : world.model(handle, 1022, origin, angles);
  }
}
