package dev.bluevista.craftq3.render;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/** BSP leaf traversal, PVS face deduplication, and conservative AABB frustum rejection. */
public final class BspVisibility {
  private final BspMap map;
  private final List<RenderScene.Surface> surfaces;
  private final BitSet unreferenced = new BitSet();
  private final int[] surfaceClusters;

  public BspVisibility(RenderScene scene) {
    map = scene.bsp();
    surfaces = scene.surfaces();
    surfaceClusters = new int[map == null ? 0 : map.faces().size()];
    Arrays.fill(surfaceClusters, -1);
    if (map != null) {
      for (RenderScene.Surface surface : surfaces) unreferenced.set(surface.id());
      for (int face : map.leafFaces()) unreferenced.clear(face);
      for (BspMap.Leaf leaf : map.leaves())
        for (int j = 0; j < leaf.faceCount(); j++) {
          int face = map.leafFaces().get(leaf.firstFace() + j);
          if (surfaceClusters[face] < 0) surfaceClusters[face] = leaf.cluster();
        }
    }
  }

  public int clusterForSurface(int id) {
    return id < 0 || id >= surfaceClusters.length ? -1 : surfaceClusters[id];
  }

  public record Selection(
      int cameraLeaf,
      int cameraCluster,
      List<RenderScene.Surface> surfaces,
      int pvsSurfaceCount,
      int visibleLeaves,
      boolean pvsApplied) {
    public Selection {
      surfaces = List.copyOf(surfaces);
    }

    public int triangleCount() {
      int total = 0;
      for (RenderScene.Surface surface : surfaces) total += surface.triangleCount();
      return total;
    }
  }

  /** Missing PVS, solid camera leaves and positions outside the world retain every surface. */
  public Selection select(
      RenderScene.Camera camera,
      double aspect,
      double near,
      double far,
      boolean pvsEnabled,
      boolean frustumEnabled) {
    Frustum frustum = new Frustum(camera, aspect, near, far);
    int leaf = findLeaf(camera.origin());
    int cluster = leaf < 0 ? -1 : map.leaves().get(leaf).cluster();
    boolean usePvs =
        pvsEnabled
            && map != null
            && cluster >= 0
            && cluster < map.visibility().clusters()
            && !map.leafFaces().isEmpty()
            && insideWorld(camera.origin());
    BitSet candidates = (BitSet) unreferenced.clone();
    int visibleLeaves = 0;
    if (map != null) {
      for (BspMap.Leaf candidate : map.leaves()) {
        if (usePvs && !map.visibility().visible(cluster, candidate.cluster())) continue;
        visibleLeaves++;
        if (usePvs)
          for (int j = 0; j < candidate.faceCount(); j++)
            candidates.set(map.leafFaces().get(candidate.firstFace() + j));
      }
    }
    int pvsCount = 0;
    List<RenderScene.Surface> selected = new ArrayList<>();
    for (RenderScene.Surface surface : surfaces) {
      if (usePvs && !candidates.get(surface.id())) continue;
      pvsCount++;
      if (!frustumEnabled || frustum.intersects(surface.bounds())) selected.add(surface);
    }
    return new Selection(leaf, cluster, selected, pvsCount, visibleLeaves, usePvs);
  }

  public int findLeaf(Vec3 point) {
    if (map == null || map.leaves().isEmpty()) return -1;
    if (map.nodes().isEmpty()) return map.leaves().size() == 1 ? 0 : -1;
    int index = 0;
    for (int visited = 0; index >= 0 && visited <= map.nodes().size(); visited++) {
      if (index >= map.nodes().size()) return -1;
      BspMap.Node node = map.nodes().get(index);
      BspMap.Plane plane = map.planes().get(node.plane());
      index = dot(point, plane.normal()) - plane.distance() >= 0 ? node.front() : node.back();
    }
    if (index >= 0) return -1;
    long decoded = -(long) index - 1;
    return decoded < map.leaves().size() ? (int) decoded : -1;
  }

  private boolean insideWorld(Vec3 point) {
    if (!map.models().isEmpty()) return contains(map.models().getFirst().bounds(), point);
    if (!map.nodes().isEmpty()) return contains(map.nodes().getFirst().bounds(), point);
    return true;
  }

  private static boolean contains(BspMap.Bounds bounds, Vec3 point) {
    return point.x() >= bounds.min().x()
        && point.y() >= bounds.min().y()
        && point.z() >= bounds.min().z()
        && point.x() <= bounds.max().x()
        && point.y() <= bounds.max().y()
        && point.z() <= bounds.max().z();
  }

  private static double dot(Vec3 a, Vec3 b) {
    return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
  }

  /** The camera uses Q3's Z-up convention, positive pitch looking down, horizontal FOV. */
  public static final class Frustum {
    private final Vec3 origin;
    private final Vec3[] normals;
    private final double[] offsets;

    public Frustum(RenderScene.Camera camera, double aspect, double near, double far) {
      if (!Double.isFinite(aspect)
          || aspect <= 0
          || !Double.isFinite(near)
          || near < 0
          || !Double.isFinite(far)
          || far <= near) throw new IllegalArgumentException("Invalid frustum");
      origin = camera.origin();
      double yaw = Math.toRadians(camera.yaw());
      double pitch = Math.toRadians(camera.pitch());
      double cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
      Vec3 forward = new Vec3(cy * cp, sy * cp, -sp);
      Vec3 right = new Vec3(sy, -cy, 0);
      Vec3 up = new Vec3(cy * sp, sy * sp, cp);
      double tanH = Math.tan(Math.toRadians(camera.horizontalFov()) / 2);
      double tanV = tanH / aspect;
      normals =
          new Vec3[] {
            forward,
            forward.scale(-1),
            forward.scale(tanH).add(right),
            forward.scale(tanH).add(right.scale(-1)),
            forward.scale(tanV).add(up),
            forward.scale(tanV).add(up.scale(-1))
          };
      offsets = new double[] {-near, far, 0, 0, 0, 0};
    }

    public boolean intersects(BspMap.Bounds bounds) {
      for (int i = 0; i < normals.length; i++) {
        Vec3 n = normals[i];
        double x = (n.x() >= 0 ? bounds.max().x() : bounds.min().x()) - origin.x();
        double y = (n.y() >= 0 ? bounds.max().y() : bounds.min().y()) - origin.y();
        double z = (n.z() >= 0 ? bounds.max().z() : bounds.min().z()) - origin.z();
        if (n.x() * x + n.y() * y + n.z() * z + offsets[i] < -1e-7) return false;
      }
      return true;
    }
  }
}
