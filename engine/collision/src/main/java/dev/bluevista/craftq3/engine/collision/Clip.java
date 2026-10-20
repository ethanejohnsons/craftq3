package dev.bluevista.craftq3.engine.collision;

/**
 * Clipping of the running trace in a {@link TraceWork} against single solids: brushes (box and
 * capsule sweeps and position tests), patch facets, and capsule models (spheres and vertical
 * cylinders). Every routine merges into the work's result in place: a solid start sets {@code
 * startSolid} (and {@code allSolid} with fraction 0 when the trace never leaves), and a strictly
 * nearer entry replaces the fraction, plane, surface flags and contents.
 */
final class Clip {
  /** Traces stop this far in front of the surface they hit. */
  static final float SURFACE_CLIP_EPSILON = 0.125f;

  /** Capsule surfaces are swollen by this much so a contact leaves a gap. */
  static final float RADIUS_EPSILON = 1.0f;

  /** The crossing time recorded for planes a point trace never crosses forwards. */
  private static final float NO_CROSSING = 99999;

  private Clip() {}

  static boolean boundsIntersect(TraceWork w, float[] bounds, int base) {
    return !(w.bounds1[0] < bounds[base] - SURFACE_CLIP_EPSILON
        || w.bounds1[1] < bounds[base + 1] - SURFACE_CLIP_EPSILON
        || w.bounds1[2] < bounds[base + 2] - SURFACE_CLIP_EPSILON
        || w.bounds0[0] > bounds[base + 3] + SURFACE_CLIP_EPSILON
        || w.bounds0[1] > bounds[base + 4] + SURFACE_CLIP_EPSILON
        || w.bounds0[2] > bounds[base + 5] + SURFACE_CLIP_EPSILON);
  }

  /** Sweeps or position-tests against one brush; the caller has checked contents. */
  static void brush(TraceWork w, BrushTable t, int b) {
    if (w.positionTest) testBrush(w, t, b);
    else if (boundsIntersect(w, t.bounds, b * 6)) traceBrush(w, t, b);
  }

  /** Distance of the sweep's start from a plane at distance {@code expanded}. */
  private static float startDistance(TraceWork w, float nx, float ny, float nz, float expanded) {
    return w.start[0] * nx + w.start[1] * ny + w.start[2] * nz - expanded;
  }

  private static float endDistance(TraceWork w, float nx, float ny, float nz, float expanded) {
    return w.end[0] * nx + w.end[1] * ny + w.end[2] * nz - expanded;
  }

  /** The plane distance pushed out by the box: minus the deepest corner's projection. */
  private static float boxExpanded(TraceWork w, int signs, float nx, float ny, float nz, float d) {
    int o = signs * 3;
    return d - (w.offsets[o] * nx + w.offsets[o + 1] * ny + w.offsets[o + 2] * nz);
  }

  /**
   * The sign that picks the capsule's sphere centre nearest a plane: -1 (the lower centre) when the
   * offset points along the normal, else +1.
   */
  private static float sphereSide(TraceWork w, float nx, float ny, float nz) {
    float t = nx * w.sphereOffset[0] + ny * w.sphereOffset[1] + nz * w.sphereOffset[2];
    return t > 0 ? -1 : 1;
  }

  static void traceBrush(TraceWork w, BrushTable t, int b) {
    int count = t.sideCount[b];
    if (count == 0) return;
    int first = t.firstSide[b];
    float enter = -1;
    float leave = 1;
    int clipSide = -1;
    boolean getOut = false;
    boolean startOut = false;
    float[] planes = t.planes;
    for (int s = first; s < first + count; s++) {
      int plane = t.sidePlanes[s];
      int base = plane * 4;
      float nx = planes[base];
      float ny = planes[base + 1];
      float nz = planes[base + 2];
      float d1;
      float d2;
      if (w.sphere) {
        float dist = planes[base + 3] + w.radius;
        float k = sphereSide(w, nx, ny, nz);
        float ox = k * w.sphereOffset[0];
        float oy = k * w.sphereOffset[1];
        float oz = k * w.sphereOffset[2];
        d1 = (w.start[0] + ox) * nx + (w.start[1] + oy) * ny + (w.start[2] + oz) * nz - dist;
        d2 = (w.end[0] + ox) * nx + (w.end[1] + oy) * ny + (w.end[2] + oz) * nz - dist;
      } else {
        float dist = boxExpanded(w, t.planeSigns[plane], nx, ny, nz, planes[base + 3]);
        d1 = startDistance(w, nx, ny, nz, dist);
        d2 = endDistance(w, nx, ny, nz, dist);
      }
      if (d2 > 0) getOut = true;
      if (d1 > 0) startOut = true;
      // Entirely in front of this face: the whole brush is missed.
      if (d1 > 0 && (d2 >= SURFACE_CLIP_EPSILON || d2 >= d1)) return;
      if (d1 <= 0 && d2 <= 0) continue;
      if (d1 > d2) {
        float f = (d1 - SURFACE_CLIP_EPSILON) / (d1 - d2);
        if (f < 0) f = 0;
        if (f > enter) {
          enter = f;
          clipSide = s;
        }
      } else {
        float f = (d1 + SURFACE_CLIP_EPSILON) / (d1 - d2);
        if (f > 1) f = 1;
        if (f < leave) leave = f;
      }
    }
    if (!startOut) {
      w.startSolid = true;
      if (!getOut) {
        w.allSolid = true;
        w.fraction = 0;
        w.contents = t.contents[b];
      }
      return;
    }
    if (enter < leave && enter > -1 && enter < w.fraction) {
      if (enter < 0) enter = 0;
      w.fraction = enter;
      int plane = t.sidePlanes[clipSide];
      setPlane(w, planes, plane * 4, t.planeTypes[plane], t.planeSigns[plane]);
      w.surfaceFlags = t.sideFlags[clipSide];
      w.contents = t.contents[b];
    }
  }

  static void testBrush(TraceWork w, BrushTable t, int b) {
    int count = t.sideCount[b];
    if (count == 0) return;
    int base6 = b * 6;
    float[] bb = t.bounds;
    if (w.bounds0[0] > bb[base6 + 3]
        || w.bounds0[1] > bb[base6 + 4]
        || w.bounds0[2] > bb[base6 + 5]
        || w.bounds1[0] < bb[base6]
        || w.bounds1[1] < bb[base6 + 1]
        || w.bounds1[2] < bb[base6 + 2]) return;
    int first = t.firstSide[b];
    // The bounds test above is exactly the test of the six axial sides.
    int from = t.axialFirst[b] ? 6 : 0;
    float[] planes = t.planes;
    for (int s = first + from; s < first + count; s++) {
      int plane = t.sidePlanes[s];
      int base = plane * 4;
      float nx = planes[base];
      float ny = planes[base + 1];
      float nz = planes[base + 2];
      float d1;
      if (w.sphere) {
        float dist = planes[base + 3] + w.radius;
        float k = sphereSide(w, nx, ny, nz);
        d1 =
            (w.start[0] + k * w.sphereOffset[0]) * nx
                + (w.start[1] + k * w.sphereOffset[1]) * ny
                + (w.start[2] + k * w.sphereOffset[2]) * nz
                - dist;
      } else {
        d1 =
            startDistance(
                w, nx, ny, nz, boxExpanded(w, t.planeSigns[plane], nx, ny, nz, planes[base + 3]));
      }
      if (d1 > 0) return;
    }
    w.startSolid = true;
    w.allSolid = true;
    w.fraction = 0;
    w.contents = t.contents[b];
  }

  private static void setPlane(TraceWork w, float[] planes, int base, int type, int signs) {
    w.normal[0] = planes[base];
    w.normal[1] = planes[base + 1];
    w.normal[2] = planes[base + 2];
    w.dist = planes[base + 3];
    w.planeType = type;
    w.planeSigns = signs;
  }

  private static void setPlane(TraceWork w, float nx, float ny, float nz, float dist) {
    w.normal[0] = nx;
    w.normal[1] = ny;
    w.normal[2] = nz;
    w.dist = dist;
    w.planeType = BrushTable.planeType(nx, ny, nz);
    w.planeSigns = BrushTable.signbits(nx, ny, nz);
  }

  // ---- patches --------------------------------------------------------------------------------

  /** Sweeps against one patch; the caller has checked contents. */
  static void tracePatch(TraceWork w, PatchTable p, int patch) {
    float before = w.fraction;
    if (!boundsIntersect(w, p.bounds, patch * 6)) return;
    if (w.isPoint) tracePointThroughPatch(w, p, patch);
    else traceFacets(w, p, patch);
    if (w.fraction < before) {
      w.surfaceFlags = p.surfaceFlags[patch];
      w.contents = p.contents[patch];
    }
  }

  // Facet clipping state, kept in the work's scratch: [0] enter, [1] leave, [2] hit flag,
  // [4..7] the candidate plane, [8..11] the best plane.
  private static final int ENTER = 0;
  private static final int LEAVE = 1;
  private static final int HIT = 2;
  private static final int PLANE = 4;
  private static final int BEST = 8;

  /**
   * Adds one expanded facet plane to the facet's entry/exit interval. Returns false when the sweep
   * is entirely in front of it, so the facet is missed.
   */
  private static boolean facetPlane(
      float[] s, float sx, float sy, float sz, float ex, float ey, float ez) {
    s[HIT] = 0;
    float nx = s[PLANE];
    float ny = s[PLANE + 1];
    float nz = s[PLANE + 2];
    float d = s[PLANE + 3];
    float d1 = sx * nx + sy * ny + sz * nz - d;
    float d2 = ex * nx + ey * ny + ez * nz - d;
    if (d1 > 0 && (d2 >= SURFACE_CLIP_EPSILON || d2 >= d1)) return false;
    if (d1 <= 0 && d2 <= 0) return true;
    if (d1 > d2) {
      float f = (d1 - SURFACE_CLIP_EPSILON) / (d1 - d2);
      if (f < 0) f = 0;
      // Earlier planes win ties, so the surface plane is favoured.
      if (f > s[ENTER]) {
        s[ENTER] = f;
        s[HIT] = 1;
      }
    } else {
      float f = (d1 + SURFACE_CLIP_EPSILON) / (d1 - d2);
      if (f > 1) f = 1;
      if (f < s[LEAVE]) s[LEAVE] = f;
    }
    return true;
  }

  /**
   * Loads facet plane {@code local} of the patch into the candidate slot, flipped if requested and
   * expanded for the swept shape. Returns the sign of the sphere centre offset to use (or 0 for a
   * box).
   */
  private static float loadFacetPlane(
      TraceWork w, PatchTable p, int global, boolean flip, boolean surface) {
    float[] s = w.scratch;
    int base = global * 4;
    float sign = flip ? -1 : 1;
    float nx = sign * p.planes[base];
    float ny = sign * p.planes[base + 1];
    float nz = sign * p.planes[base + 2];
    float d = sign * p.planes[base + 3];
    float k = 0;
    if (w.sphere) {
      d += w.radius;
      k = sphereSide(w, nx, ny, nz);
    } else {
      int o = p.planeSigns[global] * 3;
      float offset = w.offsets[o] * nx + w.offsets[o + 1] * ny + w.offsets[o + 2] * nz;
      // The surface is pushed out like a brush side; borders by the box's full half-extent, which
      // is right for either orientation because the box is centred.
      d = surface ? d - offset : d + Math.abs(offset);
    }
    s[PLANE] = nx;
    s[PLANE + 1] = ny;
    s[PLANE + 2] = nz;
    s[PLANE + 3] = d;
    return k;
  }

  private static boolean facetPlaneFor(TraceWork w, float k) {
    float ox = k * w.sphereOffset[0];
    float oy = k * w.sphereOffset[1];
    float oz = k * w.sphereOffset[2];
    return facetPlane(
        w.scratch,
        w.start[0] + ox,
        w.start[1] + oy,
        w.start[2] + oz,
        w.end[0] + ox,
        w.end[1] + oy,
        w.end[2] + oz);
  }

  private static void keepBest(float[] s) {
    s[BEST] = s[PLANE];
    s[BEST + 1] = s[PLANE + 1];
    s[BEST + 2] = s[PLANE + 2];
    s[BEST + 3] = s[PLANE + 3];
  }

  private static void traceFacets(TraceWork w, PatchTable p, int patch) {
    float[] s = w.scratch;
    int planeBase = p.firstPlane[patch];
    int firstFacet = p.firstFacet[patch];
    int end = firstFacet + p.facetCount[patch];
    for (int f = firstFacet; f < end; f++) {
      s[ENTER] = -1;
      s[LEAVE] = 1;
      int hitBorder = -1;
      float k = loadFacetPlane(w, p, planeBase + p.facetSurface[f], false, true);
      if (!facetPlaneFor(w, k)) continue;
      if (s[HIT] != 0) keepBest(s);
      int borders = p.facetBorderCount[f];
      int firstBorder = p.facetFirstBorder[f];
      int j = 0;
      for (; j < borders; j++) {
        int border = firstBorder + j;
        k = loadFacetPlane(w, p, planeBase + p.borderPlane[border], p.borderInward[border], false);
        if (!facetPlaneFor(w, k)) break;
        if (s[HIT] != 0) {
          hitBorder = j;
          keepBest(s);
        }
      }
      if (j < borders) continue;
      // Never clip against the back side (the last border is the reversed surface).
      if (hitBorder == borders - 1) continue;
      float enter = s[ENTER];
      if (enter < s[LEAVE] && enter >= 0 && enter < w.fraction) {
        w.fraction = enter;
        setPlane(w, s[BEST], s[BEST + 1], s[BEST + 2], s[BEST + 3]);
      }
    }
  }

  private static void tracePointThroughPatch(TraceWork w, PatchTable p, int patch) {
    int planeBase = p.firstPlane[patch];
    int planeCount = p.planeCount[patch];
    boolean[] front = w.frontFacing;
    float[] crossing = w.crossing;
    float[] planes = p.planes;
    for (int i = 0; i < planeCount; i++) {
      int base = (planeBase + i) * 4;
      float nx = planes[base];
      float ny = planes[base + 1];
      float nz = planes[base + 2];
      float d1 = w.start[0] * nx + w.start[1] * ny + w.start[2] * nz - planes[base + 3];
      float d2 = w.end[0] * nx + w.end[1] * ny + w.end[2] * nz - planes[base + 3];
      front[i] = d1 > 0;
      if (d1 == d2) crossing[i] = NO_CROSSING;
      else {
        float c = d1 / (d1 - d2);
        crossing[i] = c <= 0 ? NO_CROSSING : c;
      }
    }
    int firstFacet = p.firstFacet[patch];
    int end = firstFacet + p.facetCount[patch];
    for (int f = firstFacet; f < end; f++) {
      int surface = p.facetSurface[f];
      if (!front[surface]) continue;
      float at = crossing[surface];
      if (at < 0 || at > w.fraction) continue;
      int borders = p.facetBorderCount[f];
      int firstBorder = p.facetFirstBorder[f];
      int j = 0;
      for (; j < borders; j++) {
        int border = firstBorder + j;
        int k = p.borderPlane[border];
        // The crossing must lie on the inner side of every border.
        if (front[k] ^ p.borderInward[border]) {
          if (crossing[k] > at) break;
        } else if (crossing[k] < at) break;
      }
      if (j < borders) continue;
      int base = (planeBase + surface) * 4;
      float nx = planes[base];
      float ny = planes[base + 1];
      float nz = planes[base + 2];
      float d1 = w.start[0] * nx + w.start[1] * ny + w.start[2] * nz - planes[base + 3];
      float d2 = w.end[0] * nx + w.end[1] * ny + w.end[2] * nz - planes[base + 3];
      float fraction = (d1 - SURFACE_CLIP_EPSILON) / (d1 - d2);
      w.fraction = fraction < 0 ? 0 : fraction;
      setPlane(w, nx, ny, nz, planes[base + 3]);
    }
  }

  /** Whether the (box or capsule) start position lies inside one of the patch's facets. */
  static boolean testPatch(TraceWork w, PatchTable p, int patch) {
    if (w.isPoint) return false;
    if (!boundsIntersect(w, p.bounds, patch * 6)) return false;
    float[] s = w.scratch;
    int planeBase = p.firstPlane[patch];
    int firstFacet = p.firstFacet[patch];
    int end = firstFacet + p.facetCount[patch];
    for (int f = firstFacet; f < end; f++) {
      float k = loadFacetPlane(w, p, planeBase + p.facetSurface[f], false, true);
      if (startOutside(w, s, k)) continue;
      int borders = p.facetBorderCount[f];
      int firstBorder = p.facetFirstBorder[f];
      int j = 0;
      for (; j < borders; j++) {
        int border = firstBorder + j;
        k = loadFacetPlane(w, p, planeBase + p.borderPlane[border], p.borderInward[border], false);
        if (startOutside(w, s, k)) break;
      }
      if (j == borders) return true;
    }
    return false;
  }

  private static boolean startOutside(TraceWork w, float[] s, float k) {
    return (w.start[0] + k * w.sphereOffset[0]) * s[PLANE]
            + (w.start[1] + k * w.sphereOffset[1]) * s[PLANE + 1]
            + (w.start[2] + k * w.sphereOffset[2]) * s[PLANE + 2]
            - s[PLANE + 3]
        > 0;
  }

  // ---- capsules -------------------------------------------------------------------------------

  /**
   * The squared distance from {@code p} to the segment {@code a..b} with unit direction {@code d}.
   */
  private static float distanceFromSegmentSquared(
      float px,
      float py,
      float pz,
      float ax,
      float ay,
      float az,
      float bx,
      float by,
      float bz,
      float dx,
      float dy,
      float dz) {
    float along = (px - ax) * dx + (py - ay) * dy + (pz - az) * dz;
    float qx = ax + dx * along;
    float qy = ay + dy * along;
    float qz = az + dz * along;
    // When the projection falls outside the segment on some axis, use the nearer end point.
    int axis = outside(qx, ax, bx) ? 0 : outside(qy, ay, by) ? 1 : outside(qz, az, bz) ? 2 : -1;
    if (axis >= 0) {
      float q = axis == 0 ? qx : axis == 1 ? qy : qz;
      float a = axis == 0 ? ax : axis == 1 ? ay : az;
      float b = axis == 0 ? bx : axis == 1 ? by : bz;
      float ex;
      float ey;
      float ez;
      if (Math.abs(q - a) < Math.abs(q - b)) {
        ex = px - ax;
        ey = py - ay;
        ez = pz - az;
      } else {
        ex = px - bx;
        ey = py - by;
        ez = pz - bz;
      }
      return ex * ex + ey * ey + ez * ez;
    }
    float ex = px - qx;
    float ey = py - qy;
    float ez = pz - qz;
    return ex * ex + ey * ey + ez * ez;
  }

  private static boolean outside(float q, float a, float b) {
    return (q > a && q > b) || (q < a && q < b);
  }

  /** Sweeps the point {@code s..e} against a sphere of {@code radius} around {@code o}. */
  static void traceSphere(
      TraceWork w,
      float ox,
      float oy,
      float oz,
      float radius,
      float sx,
      float sy,
      float sz,
      float ex,
      float ey,
      float ez) {
    float r2 = radius * radius;
    float vx = sx - ox;
    float vy = sy - oy;
    float vz = sz - oz;
    if (vx * vx + vy * vy + vz * vz < r2) {
      w.fraction = 0;
      w.startSolid = true;
      float qx = ex - ox;
      float qy = ey - oy;
      float qz = ez - oz;
      if (qx * qx + qy * qy + qz * qz < r2) w.allSolid = true;
      return;
    }
    float dx = ex - sx;
    float dy = ey - sy;
    float dz = ez - sz;
    float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    if (length != 0) {
      dx /= length;
      dy /= length;
      dz /= length;
    }
    float l1 = distanceFromSegmentSquared(ox, oy, oz, sx, sy, sz, ex, ey, ez, dx, dy, dz);
    float qx = ex - ox;
    float qy = ey - oy;
    float qz = ez - oz;
    float l2 = qx * qx + qy * qy + qz * qz;
    float grown = radius + SURFACE_CLIP_EPSILON;
    if (l1 >= r2 && l2 > grown * grown) return;
    // |s + t*d - o| = radius + RADIUS_EPSILON with |d| = 1.
    float b = 2.0f * (dx * vx + dy * vy + dz * vz);
    float swollen = radius + RADIUS_EPSILON;
    float c = vx * vx + vy * vy + vz * vz - swollen * swollen;
    float discriminant = b * b - 4.0f * c;
    if (discriminant <= 0) return;
    float fraction = (-b - (float) Math.sqrt(discriminant)) * 0.5f;
    fraction = fraction < 0 ? 0 : fraction / length;
    if (fraction >= w.fraction) return;
    w.fraction = fraction;
    float ix = sx + fraction * (ex - sx);
    float iy = sy + fraction * (ey - sy);
    float iz = sz + fraction * (ez - sz);
    float scale = 1 / swollen;
    float nx = (ix - ox) * scale;
    float ny = (iy - oy) * scale;
    float nz = (iz - oz) * scale;
    setPlane(w, nx, ny, nz, nx * ix + ny * iy + nz * iz);
    w.contents = Contents.BODY;
  }

  /**
   * Sweeps the point {@code s..e} against a vertical cylinder of {@code radius} around {@code o}
   * reaching {@code halfHeight} above and below it.
   */
  static void traceVerticalCylinder(
      TraceWork w,
      float ox,
      float oy,
      float oz,
      float radius,
      float halfHeight,
      float sx,
      float sy,
      float sz,
      float ex,
      float ey,
      float ez) {
    float r2 = radius * radius;
    float vx = sx - ox;
    float vy = sy - oy;
    if (sz <= oz + halfHeight && sz >= oz - halfHeight && vx * vx + vy * vy < r2) {
      w.fraction = 0;
      w.startSolid = true;
      float qx = ex - ox;
      float qy = ey - oy;
      if (qx * qx + qy * qy < r2) w.allSolid = true;
      return;
    }
    float dx = ex - sx;
    float dy = ey - sy;
    float length = (float) Math.sqrt(dx * dx + dy * dy);
    if (length != 0) {
      dx /= length;
      dy /= length;
    }
    float l1 = distanceFromSegmentSquared(ox, oy, 0, sx, sy, 0, ex, ey, 0, dx, dy, 0);
    float qx = ex - ox;
    float qy = ey - oy;
    float l2 = qx * qx + qy * qy;
    float grown = radius + SURFACE_CLIP_EPSILON;
    if (l1 >= r2 && l2 > grown * grown) return;
    float b = 2.0f * (vx * dx + vy * dy);
    float swollen = radius + RADIUS_EPSILON;
    float c = vx * vx + vy * vy - swollen * swollen;
    float discriminant = b * b - 4.0f * c;
    if (discriminant <= 0) return;
    float fraction = (-b - (float) Math.sqrt(discriminant)) * 0.5f;
    fraction = fraction < 0 ? 0 : fraction / length;
    if (fraction >= w.fraction) return;
    float ix = sx + fraction * (ex - sx);
    float iy = sy + fraction * (ey - sy);
    float iz = sz + fraction * (ez - sz);
    if (iz > oz + halfHeight || iz < oz - halfHeight) return;
    w.fraction = fraction;
    float scale = 1 / swollen;
    float nx = (ix - ox) * scale;
    float ny = (iy - oy) * scale;
    setPlane(w, nx, ny, 0, nx * ix + ny * iy);
    w.contents = Contents.BODY;
  }

  /** Capsule dimensions of a capsule model's bounds: centre xyz, radius, half height, offset. */
  private static void capsuleOf(float[] mins, float[] maxs, float[] out) {
    for (int axis = 0; axis < 3; axis++) out[axis] = (mins[axis] + maxs[axis]) * 0.5f;
    float halfWidth = maxs[0] - out[0];
    float halfHeight = maxs[2] - out[2];
    float radius = Math.min(halfWidth, halfHeight);
    out[3] = radius;
    out[4] = halfHeight;
    out[5] = halfHeight - radius;
  }

  /**
   * Sweeps the work's capsule against a capsule model with the given bounds. Two vertical capsules
   * touch when the moving centre comes within the sum of the radii of the segment that spans the
   * static centre plus and minus the sum of both axis half lengths: a vertical cylinder (swept only
   * for horizontal movement, as the original does) capped by two spheres. The original paired the
   * moving top sphere with the static top sphere (and bottom with bottom) instead, which let
   * vertically stacked capsules sink into each other by the axis lengths.
   */
  static void traceCapsuleThroughCapsule(TraceWork w, float[] mins, float[] maxs) {
    if (w.bounds0[0] > maxs[0] + RADIUS_EPSILON
        || w.bounds0[1] > maxs[1] + RADIUS_EPSILON
        || w.bounds0[2] > maxs[2] + RADIUS_EPSILON
        || w.bounds1[0] < mins[0] - RADIUS_EPSILON
        || w.bounds1[1] < mins[1] - RADIUS_EPSILON
        || w.bounds1[2] < mins[2] - RADIUS_EPSILON) return;
    float[] c = w.scratch;
    capsuleOf(mins, maxs, c);
    float cx = c[0];
    float cy = c[1];
    float cz = c[2];
    float radius = c[3] + w.radius;
    // Equal to the original's cylinder height: both half heights minus both radii.
    float reach = c[5] + Math.abs(w.sphereOffset[2]);
    if ((w.start[0] != w.end[0] || w.start[1] != w.end[1]) && reach > 0)
      traceVerticalCylinder(
          w,
          cx,
          cy,
          cz,
          radius,
          reach,
          w.start[0],
          w.start[1],
          w.start[2],
          w.end[0],
          w.end[1],
          w.end[2]);
    traceSphere(
        w,
        cx,
        cy,
        cz + reach,
        radius,
        w.start[0],
        w.start[1],
        w.start[2],
        w.end[0],
        w.end[1],
        w.end[2]);
    traceSphere(
        w,
        cx,
        cy,
        cz - reach,
        radius,
        w.start[0],
        w.start[1],
        w.start[2],
        w.end[0],
        w.end[1],
        w.end[2]);
  }

  /** Whether the work's capsule start position overlaps a capsule model (see above). */
  static void testCapsuleInCapsule(TraceWork w, float[] mins, float[] maxs) {
    float[] c = w.scratch;
    capsuleOf(mins, maxs, c);
    float r = w.radius + c[3];
    float reach = c[5] + Math.abs(w.sphereOffset[2]);
    float dz = w.start[2] - c[2];
    if (dz > reach) dz -= reach;
    else if (dz < -reach) dz += reach;
    else dz = 0;
    if (squared(w.start[0] - c[0], w.start[1] - c[1], dz) < r * r) {
      w.startSolid = true;
      w.allSolid = true;
      w.fraction = 0;
    }
  }

  private static float squared(float x, float y, float z) {
    return x * x + y * y + z * z;
  }

  /**
   * Box against capsule: the roles are swapped so the capsule moves (relative to the capsule
   * model's centre) through a box the size of the swept box, which is exact because both shapes are
   * centred and symmetric.
   */
  static void boxAgainstCapsule(TraceWork w, float[] mins, float[] maxs) {
    float[] c = w.scratch;
    capsuleOf(mins, maxs, c);
    for (int axis = 0; axis < 3; axis++) {
      w.start[axis] -= c[axis];
      w.end[axis] -= c[axis];
    }
    w.sphere = true;
    w.radius = c[3];
    w.halfHeight = c[4];
    w.sphereOffset[0] = 0;
    w.sphereOffset[1] = 0;
    w.sphereOffset[2] = c[5];
    w.swapBox.setBox(w.size0, w.size1, Contents.BODY, 0);
    sphereBounds(w);
    if ((Contents.BODY & w.mask) != 0) brush(w, w.swapBox, 0);
  }

  /** The swept bounds of the work's capsule. */
  static void sphereBounds(TraceWork w) {
    for (int axis = 0; axis < 3; axis++) {
      float reach = Math.abs(w.sphereOffset[axis]) + w.radius;
      float lo = Math.min(w.start[axis], w.end[axis]);
      float hi = Math.max(w.start[axis], w.end[axis]);
      w.bounds0[axis] = lo - reach;
      w.bounds1[axis] = hi + reach;
    }
  }

  /** Whether a point lies inside the capsule fitted to the given bounds. */
  static boolean pointInCapsule(float[] mins, float[] maxs, float[] p, float[] scratch) {
    capsuleOf(mins, maxs, scratch);
    float dz = p[2] - scratch[2];
    float offset = scratch[5];
    if (dz > offset) dz -= offset;
    else if (dz < -offset) dz += offset;
    else dz = 0;
    float dx = p[0] - scratch[0];
    float dy = p[1] - scratch[1];
    return dx * dx + dy * dy + dz * dz <= scratch[3] * scratch[3];
  }
}
