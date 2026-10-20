package dev.bluevista.craftq3.engine.collision;

import java.util.Arrays;

/** A convex polygon used while building patch facets (load time only). */
final class Winding {
  /** Half the side of a plane's initial winding; also the largest valid facet extent. */
  static final float MAX_MAP_BOUNDS = 65535;

  private static final int FRONT = 0;
  private static final int BACK = 1;
  private static final int ON = 2;

  float[] points;
  int count;

  private Winding(int capacity) {
    points = new float[capacity * 3];
  }

  Winding copy() {
    Winding copy = new Winding(Math.max(count, 4));
    System.arraycopy(points, 0, copy.points, 0, count * 3);
    copy.count = count;
    return copy;
  }

  /** A square of side {@code 2 * MAX_MAP_BOUNDS} on the plane, centred on its closest point. */
  static Winding forPlane(float nx, float ny, float nz, float dist) {
    float ax = Math.abs(nx);
    float ay = Math.abs(ny);
    float az = Math.abs(nz);
    int major = 0;
    float max = ax;
    if (ay > max) {
      major = 1;
      max = ay;
    }
    if (az > max) major = 2;
    float ux = 0;
    float uy = 0;
    float uz = 0;
    if (major == 2) ux = 1;
    else uz = 1;
    float along = ux * nx + uy * ny + uz * nz;
    ux -= along * nx;
    uy -= along * ny;
    uz -= along * nz;
    float length = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
    if (length != 0) {
      ux /= length;
      uy /= length;
      uz /= length;
    }
    float rx = uy * nz - uz * ny;
    float ry = uz * nx - ux * nz;
    float rz = ux * ny - uy * nx;
    ux *= MAX_MAP_BOUNDS;
    uy *= MAX_MAP_BOUNDS;
    uz *= MAX_MAP_BOUNDS;
    rx *= MAX_MAP_BOUNDS;
    ry *= MAX_MAP_BOUNDS;
    rz *= MAX_MAP_BOUNDS;
    float ox = nx * dist;
    float oy = ny * dist;
    float oz = nz * dist;
    Winding w = new Winding(8);
    w.add(ox - rx + ux, oy - ry + uy, oz - rz + uz);
    w.add(ox + rx + ux, oy + ry + uy, oz + rz + uz);
    w.add(ox + rx - ux, oy + ry - uy, oz + rz - uz);
    w.add(ox - rx - ux, oy - ry - uy, oz - rz - uz);
    return w;
  }

  private void add(float x, float y, float z) {
    if ((count + 1) * 3 > points.length) points = Arrays.copyOf(points, points.length * 2);
    points[count * 3] = x;
    points[count * 3 + 1] = y;
    points[count * 3 + 2] = z;
    count++;
  }

  /**
   * Keeps the part of this winding in front of the plane, treating points within {@code epsilon} as
   * on it. Returns null when nothing is in front, and this winding unchanged when nothing is
   * behind.
   */
  Winding chop(float nx, float ny, float nz, float dist, float epsilon) {
    float[] dists = new float[count + 1];
    int[] sides = new int[count + 1];
    int front = 0;
    int back = 0;
    for (int i = 0; i < count; i++) {
      float d = points[i * 3] * nx + points[i * 3 + 1] * ny + points[i * 3 + 2] * nz - dist;
      dists[i] = d;
      if (d > epsilon) {
        sides[i] = FRONT;
        front++;
      } else if (d < -epsilon) {
        sides[i] = BACK;
        back++;
      } else sides[i] = ON;
    }
    dists[count] = dists[0];
    sides[count] = sides[0];
    if (front == 0) return null;
    if (back == 0) return this;
    Winding out = new Winding(count + 4);
    float[] normal = {nx, ny, nz};
    for (int i = 0; i < count; i++) {
      int p = i * 3;
      if (sides[i] == ON) {
        out.add(points[p], points[p + 1], points[p + 2]);
        continue;
      }
      if (sides[i] == FRONT) out.add(points[p], points[p + 1], points[p + 2]);
      if (sides[i + 1] == ON || sides[i + 1] == sides[i]) continue;
      int q = ((i + 1) % count) * 3;
      float t = dists[i] / (dists[i] - dists[i + 1]);
      float[] mid = new float[3];
      for (int axis = 0; axis < 3; axis++) {
        // Exact coordinates on axial planes avoid drifting off them.
        if (normal[axis] == 1) mid[axis] = dist;
        else if (normal[axis] == -1) mid[axis] = -dist;
        else mid[axis] = points[p + axis] + t * (points[q + axis] - points[p + axis]);
      }
      out.add(mid[0], mid[1], mid[2]);
    }
    return out;
  }

  /** {@code [minX, minY, minZ, maxX, maxY, maxZ]}. */
  float[] bounds() {
    float[] b = {
      Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
      -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE
    };
    for (int i = 0; i < count; i++)
      for (int axis = 0; axis < 3; axis++) {
        float v = points[i * 3 + axis];
        if (v < b[axis]) b[axis] = v;
        if (v > b[3 + axis]) b[3 + axis] = v;
      }
    return b;
  }
}
