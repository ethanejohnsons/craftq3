package dev.bluevista.craftq3.mc.hybrid.building;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact coverage of a rectangle on a face plane by convex supports, by polygon subtraction rather
 * than sampling: the rectangle starts as one gap; subtracting a convex support (an intersection of
 * half-planes) keeps only the parts of each gap outside it. The rectangle is covered when no gap
 * with area remains. Adjoining supports can therefore cover a face jointly while a narrow hole
 * between them stays a hole.
 *
 * <p>Coordinates are 2D {@code (u, v)} on the plane. Not thread-safe; cheap to create.
 */
public final class FaceCoverage {
  private static final double EPSILON = 1e-7;
  private static final double MIN_AREA = 1e-10;

  /** Fragment budget; an exceeded budget reports the face as not covered. */
  public static final int MAX_FRAGMENTS = 4096;

  private List<double[]> gaps = new ArrayList<>();
  private boolean overflow;

  /** The rectangle {@code [u0, u1] x [v0, v1]} to cover. */
  public FaceCoverage(double u0, double v0, double u1, double v1) {
    if (!(u0 < u1 && v0 < v1)) throw new IllegalArgumentException("Empty coverage rectangle");
    gaps.add(new double[] {u0, v0, u1, v0, u1, v1, u0, v1});
  }

  public boolean covered() {
    return !overflow && gaps.isEmpty();
  }

  /** True when no part of the rectangle has been covered yet. */
  public boolean untouched(double area) {
    return !overflow && Math.abs(gapArea() - area) < 1e-9;
  }

  /** The total area still uncovered. */
  public double gapArea() {
    double total = 0;
    for (double[] gap : gaps) total += area(gap);
    return total;
  }

  /** Subtracts the rectangle {@code [u0, u1] x [v0, v1]}. */
  public void subtractRect(double u0, double v0, double u1, double v1) {
    subtract(new double[] {-1, 0, -u0, 1, 0, u1, 0, -1, -v0, 0, 1, v1}, 4);
  }

  /**
   * Subtracts the convex region {@code a*u + b*v <= c} for every triple {@code (a, b, c)} of {@code
   * planes[0 .. 3*count)}.
   */
  public void subtract(double[] planes, int count) {
    if (overflow || gaps.isEmpty()) return;
    List<double[]> remaining = new ArrayList<>();
    for (double[] gap : gaps) {
      double[] inside = gap;
      for (int p = 0; p < count; p++) {
        double a = planes[p * 3];
        double b = planes[p * 3 + 1];
        double c = planes[p * 3 + 2];
        double[] outside = clip(inside, a, b, c, false);
        if (area(outside) > MIN_AREA) remaining.add(outside);
        if (remaining.size() > MAX_FRAGMENTS) {
          overflow = true;
          gaps = List.of();
          return;
        }
        inside = clip(inside, a, b, c, true);
        if (area(inside) <= MIN_AREA) break;
      }
      // The part inside every half-plane is covered and dropped.
    }
    gaps = remaining;
  }

  /** The part of {@code polygon} inside ({@code a*u + b*v <= c}) or outside the half-plane. */
  static double[] clip(double[] polygon, double a, double b, double c, boolean inside) {
    int n = polygon.length / 2;
    if (n == 0) return polygon;
    double[] out = new double[(n + 2) * 2];
    int count = 0;
    double pu = polygon[(n - 1) * 2];
    double pv = polygon[(n - 1) * 2 + 1];
    double pd = a * pu + b * pv - c - EPSILON;
    boolean pKept = inside ? pd <= 0 : pd >= 0;
    for (int i = 0; i < n; i++) {
      double u = polygon[i * 2];
      double v = polygon[i * 2 + 1];
      double d = a * u + b * v - c - EPSILON;
      boolean kept = inside ? d <= 0 : d >= 0;
      if (kept != pKept) {
        double t = pd / (pd - d);
        if (count * 2 + 2 > out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
        out[count * 2] = pu + (u - pu) * t;
        out[count * 2 + 1] = pv + (v - pv) * t;
        count++;
      }
      if (kept) {
        if (count * 2 + 2 > out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
        out[count * 2] = u;
        out[count * 2 + 1] = v;
        count++;
      }
      pu = u;
      pv = v;
      pd = d;
      pKept = kept;
    }
    return java.util.Arrays.copyOf(out, count * 2);
  }

  static double area(double[] polygon) {
    int n = polygon.length / 2;
    if (n < 3) return 0;
    double twice = 0;
    for (int i = 0; i < n; i++) {
      int j = (i + 1) % n;
      twice += polygon[i * 2] * polygon[j * 2 + 1] - polygon[j * 2] * polygon[i * 2 + 1];
    }
    return Math.abs(twice) * 0.5;
  }
}
