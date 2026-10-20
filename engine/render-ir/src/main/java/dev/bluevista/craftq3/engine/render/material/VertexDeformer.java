package dev.bluevista.craftq3.engine.render.material;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.List;

/**
 * The reference CPU implementation of {@code deformVertexes}, applied in declared order to a range
 * of vertices in place. Sprite deforms work on quads: each group of six indices whose vertices have
 * four distinct positions (shared or duplicated) is turned toward the viewer; other trailing
 * geometry is left unchanged. Text and projection-shadow deforms need entity state the renderer
 * does not have and leave vertices unchanged.
 */
public final class VertexDeformer {
  private static final double DEFAULT_SPREAD = 100;
  private static final double NORMAL_POSITION_SCALE = 0.98;

  private VertexDeformer() {}

  /** Reusable scratch state for sprite deforms; one per calling thread. */
  public static final class Scratch {
    final int[] slotCorner = new int[6];
    final int[] cornerVertex = new int[4];
    final double[] corners = new double[12];
    final double[] deformed = new double[12];
    final int[][] edges = new int[4][4];
  }

  /**
   * Deforms vertices {@code [firstVertex, firstVertex + vertexCount)} of {@code mesh}. Sprite
   * deforms read the triangles {@code indices[firstIndex, firstIndex + indexCount)}, which must
   * reference that vertex range. {@code right} and {@code up} are the view's screen axes, so rolled
   * and mirrored portal views keep their orientation.
   */
  public static void deform(
      MaterialProgram material,
      double seconds,
      Vec3f viewOrigin,
      Vec3f right,
      Vec3f up,
      VertexArrays mesh,
      int firstVertex,
      int vertexCount,
      int[] indices,
      int firstIndex,
      int indexCount,
      Scratch scratch) {
    List<DeformProgram> deforms = material.deforms();
    if (deforms.isEmpty()) return;
    double time = material.shaderTime(seconds);
    for (DeformProgram deform : deforms) {
      switch (deform.op()) {
        case AUTOSPRITE, AUTOSPRITE2 -> {
          for (int group = firstIndex; group + 6 <= firstIndex + indexCount; group += 6)
            sprite(
                deform.op() == DeformProgram.Op.AUTOSPRITE2,
                viewOrigin,
                right,
                up,
                mesh,
                indices,
                group,
                scratch);
        }
        case PROJECTION_SHADOW, TEXT -> {}
        default -> {
          for (int v = firstVertex; v < firstVertex + vertexCount; v++)
            vertex(deform, time, mesh, v);
        }
      }
    }
  }

  /** True when any deform changes the geometry over time or with the view. */
  public static boolean isDynamic(MaterialProgram material) {
    for (DeformProgram deform : material.deforms()) if (deform.dynamic()) return true;
    return false;
  }

  private static void vertex(DeformProgram deform, double time, VertexArrays mesh, int v) {
    float[] p = mesh.positions(), n = mesh.normals();
    int at = v * 3;
    double x = p[at], y = p[at + 1], z = p[at + 2];
    double nx = n[at], ny = n[at + 1], nz = n[at + 2];
    switch (deform.op()) {
      case WAVE -> {
        double spread = deform.p0() == 0 ? DEFAULT_SPREAD : 1.0 / deform.p0();
        double amount = Waveforms.evaluate(deform.wave(), time, (x + y + z) * spread);
        x += nx * amount;
        y += ny * amount;
        z += nz * amount;
      }
      case MOVE -> {
        double amount = Waveforms.evaluate(deform.wave(), time);
        x += deform.p0() * amount;
        y += deform.p1() * amount;
        z += deform.p2() * amount;
      }
      case BULGE -> {
        double amount =
            Math.sin(mesh.texCoords()[v * 2] * deform.p0() + time * deform.p2()) * deform.p1();
        x += nx * amount;
        y += ny * amount;
        z += nz * amount;
      }
      case NORMAL -> {
        double sx = x * NORMAL_POSITION_SCALE, sy = y * NORMAL_POSITION_SCALE;
        double sz = z * NORMAL_POSITION_SCALE;
        double t = time * deform.p1();
        double amplitude = deform.p0();
        nx += Waveforms.noise(sx, sy, sz, t) * amplitude;
        ny += Waveforms.noise(sx + 100, sy, sz, t) * amplitude;
        nz += Waveforms.noise(sx + 200, sy, sz, t) * amplitude;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        double inverse = length > 1e-12 ? 1 / length : 0;
        nx *= inverse;
        ny *= inverse;
        nz *= inverse;
      }
      default -> throw new IllegalStateException("Not a per-vertex deform: " + deform.op());
    }
    p[at] = (float) x;
    p[at + 1] = (float) y;
    p[at + 2] = (float) z;
    n[at] = (float) nx;
    n[at + 1] = (float) ny;
    n[at + 2] = (float) nz;
  }

  private static void sprite(
      boolean axial,
      Vec3f eye,
      Vec3f right,
      Vec3f up,
      VertexArrays mesh,
      int[] indices,
      int group,
      Scratch s) {
    float[] p = mesh.positions();
    int corners = 0;
    for (int slot = 0; slot < 6; slot++) {
      int vertex = indices[group + slot];
      int found = -1;
      for (int c = 0; c < corners; c++) {
        int other = s.cornerVertex[c];
        if (p[other * 3] == p[vertex * 3]
            && p[other * 3 + 1] == p[vertex * 3 + 1]
            && p[other * 3 + 2] == p[vertex * 3 + 2]) {
          found = c;
          break;
        }
      }
      if (found < 0) {
        if (corners == 4) return;
        found = corners++;
        s.cornerVertex[found] = vertex;
      }
      s.slotCorner[slot] = found;
    }
    if (corners != 4) return;
    double cx = 0, cy = 0, cz = 0;
    for (int c = 0; c < 4; c++) {
      int v = s.cornerVertex[c];
      s.corners[c * 3] = p[v * 3];
      s.corners[c * 3 + 1] = p[v * 3 + 1];
      s.corners[c * 3 + 2] = p[v * 3 + 2];
      cx += p[v * 3];
      cy += p[v * 3 + 1];
      cz += p[v * 3 + 2];
    }
    cx *= 0.25;
    cy *= 0.25;
    cz *= 0.25;
    boolean moved =
        axial ? axialCorners(s, eye, cx, cy, cz) : facingCorners(s, mesh, right, up, cx, cy, cz);
    if (!moved) System.arraycopy(s.corners, 0, s.deformed, 0, 12);
    double nx = eye.x() - cx, ny = eye.y() - cy, nz = eye.z() - cz;
    double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
    double inverse = length > 1e-12 ? 1 / length : 0;
    float[] n = mesh.normals();
    for (int slot = 0; slot < 6; slot++) {
      int v = indices[group + slot], c = s.slotCorner[slot];
      p[v * 3] = (float) s.deformed[c * 3];
      p[v * 3 + 1] = (float) s.deformed[c * 3 + 1];
      p[v * 3 + 2] = (float) s.deformed[c * 3 + 2];
      n[v * 3] = (float) (nx * inverse);
      n[v * 3 + 1] = (float) (ny * inverse);
      n[v * 3 + 2] = (float) (nz * inverse);
    }
  }

  /** Re-centres the quad on the view's right/up axes, spanning its texture-coordinate extent. */
  private static boolean facingCorners(
      Scratch s, VertexArrays mesh, Vec3f right, Vec3f up, double cx, double cy, double cz) {
    double dx = s.corners[0] - cx, dy = s.corners[1] - cy, dz = s.corners[2] - cz;
    double radius = Math.sqrt((dx * dx + dy * dy + dz * dz) / 2);
    float[] uv = mesh.texCoords();
    float minS = Float.POSITIVE_INFINITY, maxS = Float.NEGATIVE_INFINITY;
    float minT = Float.POSITIVE_INFINITY, maxT = Float.NEGATIVE_INFINITY;
    for (int c = 0; c < 4; c++) {
      int v = s.cornerVertex[c];
      minS = Math.min(minS, uv[v * 2]);
      maxS = Math.max(maxS, uv[v * 2]);
      minT = Math.min(minT, uv[v * 2 + 1]);
      maxT = Math.max(maxT, uv[v * 2 + 1]);
    }
    if (maxS - minS < 1e-6 || maxT - minT < 1e-6) return false;
    for (int c = 0; c < 4; c++) {
      int v = s.cornerVertex[c];
      double ss = 2 * (uv[v * 2] - minS) / (maxS - minS) - 1;
      double tt = 1 - 2 * (uv[v * 2 + 1] - minT) / (maxT - minT);
      s.deformed[c * 3] = cx + right.x() * (ss * radius) + up.x() * (tt * radius);
      s.deformed[c * 3 + 1] = cy + right.y() * (ss * radius) + up.y() * (tt * radius);
      s.deformed[c * 3 + 2] = cz + right.z() * (ss * radius) + up.z() * (tt * radius);
    }
    return true;
  }

  /** Keeps the major axis fixed and rotates each short edge around it to face the viewer. */
  private static boolean axialCorners(Scratch s, Vec3f eye, double cx, double cy, double cz) {
    for (int[] row : s.edges) java.util.Arrays.fill(row, 0);
    for (int triangle = 0; triangle < 2; triangle++)
      for (int edge = 0; edge < 3; edge++) {
        int a = s.slotCorner[triangle * 3 + edge];
        int b = s.slotCorner[triangle * 3 + (edge + 1) % 3];
        s.edges[Math.min(a, b)][Math.max(a, b)]++;
      }
    int start = -1, end = -1;
    double shortest = Double.POSITIVE_INFINITY;
    double[] k = s.corners;
    for (int a = 0; a < 4; a++)
      for (int b = a + 1; b < 4; b++) {
        if (s.edges[a][b] != 1) continue;
        double dx = k[a * 3] - k[b * 3], dy = k[a * 3 + 1] - k[b * 3 + 1];
        double dz = k[a * 3 + 2] - k[b * 3 + 2];
        double distance = dx * dx + dy * dy + dz * dz;
        if (distance < shortest) {
          shortest = distance;
          start = a;
          end = b;
        }
      }
    if (start < 0) return false;
    int o0 = -1, o1 = -1;
    for (int i = 0; i < 4; i++)
      if (i != start && i != end) {
        if (o0 < 0) o0 = i;
        else o1 = i;
      }
    double[] first = {
      (k[start * 3] + k[end * 3]) * 0.5,
      (k[start * 3 + 1] + k[end * 3 + 1]) * 0.5,
      (k[start * 3 + 2] + k[end * 3 + 2]) * 0.5
    };
    double[] last = {
      (k[o0 * 3] + k[o1 * 3]) * 0.5,
      (k[o0 * 3 + 1] + k[o1 * 3 + 1]) * 0.5,
      (k[o0 * 3 + 2] + k[o1 * 3 + 2]) * 0.5
    };
    double[] axis = unit(last[0] - first[0], last[1] - first[1], last[2] - first[2]);
    double[] oldSide =
        unit(
            k[end * 3] - k[start * 3],
            k[end * 3 + 1] - k[start * 3 + 1],
            k[end * 3 + 2] - k[start * 3 + 2]);
    double[] view =
        unit(
            eye.x() - (first[0] + last[0]) * 0.5,
            eye.y() - (first[1] + last[1]) * 0.5,
            eye.z() - (first[2] + last[2]) * 0.5);
    double[] side =
        unit(
            view[1] * axis[2] - view[2] * axis[1],
            view[2] * axis[0] - view[0] * axis[2],
            view[0] * axis[1] - view[1] * axis[0]);
    if (side[0] * side[0] + side[1] * side[1] + side[2] * side[2] < 1e-8) side = oldSide;
    // Retain corner order and texture orientation as the view rotates around the locked axis.
    if (side[0] * oldSide[0] + side[1] * oldSide[1] + side[2] * oldSide[2] < 0)
      side = new double[] {-side[0], -side[1], -side[2]};
    for (int i = 0; i < 4; i++) {
      double[] center = i == start || i == end ? first : last;
      double width =
          (k[i * 3] - center[0]) * oldSide[0]
              + (k[i * 3 + 1] - center[1]) * oldSide[1]
              + (k[i * 3 + 2] - center[2]) * oldSide[2];
      s.deformed[i * 3] = center[0] + side[0] * width;
      s.deformed[i * 3 + 1] = center[1] + side[1] * width;
      s.deformed[i * 3 + 2] = center[2] + side[2] * width;
    }
    return true;
  }

  private static double[] unit(double x, double y, double z) {
    double length = Math.sqrt(x * x + y * y + z * z);
    double inverse = length > 1e-12 ? 1 / length : 0;
    return new double[] {x * inverse, y * inverse, z * inverse};
  }
}
