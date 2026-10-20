package dev.bluevista.craftq3.engine.collision;

import java.util.Arrays;

/**
 * Collision facets of every patch surface, flattened into primitive arrays. Each patch has its own
 * plane list (indices in facets and borders are local to the patch), a bounding box expanded by one
 * unit, and facets: a surface plane plus border planes, each border flagged when its plane faces
 * into the facet and must be flipped. A facet's last border is the reversed surface plane.
 */
final class PatchTable {
  final int count;
  final int[] face;
  final int[] contents;
  final int[] surfaceFlags;
  final float[] bounds;
  final int[] firstPlane;
  final int[] planeCount;
  final float[] planes;
  final byte[] planeSigns;
  final int[] firstFacet;
  final int[] facetCount;
  final int[] facetSurface;
  final int[] facetFirstBorder;
  final int[] facetBorderCount;
  final int[] borderPlane;
  final boolean[] borderInward;
  final int maxPlanesPerPatch;

  private PatchTable(Builder b) {
    count = b.count;
    face = Arrays.copyOf(b.face, count);
    contents = Arrays.copyOf(b.contents, count);
    surfaceFlags = Arrays.copyOf(b.surfaceFlags, count);
    bounds = Arrays.copyOf(b.bounds, count * 6);
    firstPlane = Arrays.copyOf(b.firstPlane, count);
    planeCount = Arrays.copyOf(b.planeCount, count);
    planes = Arrays.copyOf(b.planes, b.planeTotal * 4);
    planeSigns = Arrays.copyOf(b.planeSigns, b.planeTotal);
    firstFacet = Arrays.copyOf(b.firstFacet, count);
    facetCount = Arrays.copyOf(b.facetCount, count);
    facetSurface = Arrays.copyOf(b.facetSurface, b.facetTotal);
    facetFirstBorder = Arrays.copyOf(b.facetFirstBorder, b.facetTotal);
    facetBorderCount = Arrays.copyOf(b.facetBorderCount, b.facetTotal);
    borderPlane = Arrays.copyOf(b.borderPlane, b.borderTotal);
    borderInward = Arrays.copyOf(b.borderInward, b.borderTotal);
    int max = 0;
    for (int i = 0; i < count; i++) max = Math.max(max, planeCount[i]);
    maxPlanesPerPatch = max;
  }

  int totalFacets() {
    return facetSurface.length;
  }

  int totalPlanes() {
    return planeSigns.length;
  }

  static final class Builder {
    int count;
    int[] face = new int[16];
    int[] contents = new int[16];
    int[] surfaceFlags = new int[16];
    float[] bounds = new float[96];
    int[] firstPlane = new int[16];
    int[] planeCount = new int[16];
    int[] firstFacet = new int[16];
    int[] facetCount = new int[16];
    int planeTotal;
    float[] planes = new float[256];
    byte[] planeSigns = new byte[64];
    int facetTotal;
    int[] facetSurface = new int[64];
    int[] facetFirstBorder = new int[64];
    int[] facetBorderCount = new int[64];
    int borderTotal;
    int[] borderPlane = new int[256];
    boolean[] borderInward = new boolean[256];

    /** Starts a patch; returns its index. */
    int begin(int sourceFace, int patchContents, int flags) {
      if (count == face.length) {
        int n = count * 2;
        face = Arrays.copyOf(face, n);
        contents = Arrays.copyOf(contents, n);
        surfaceFlags = Arrays.copyOf(surfaceFlags, n);
        bounds = Arrays.copyOf(bounds, n * 6);
        firstPlane = Arrays.copyOf(firstPlane, n);
        planeCount = Arrays.copyOf(planeCount, n);
        firstFacet = Arrays.copyOf(firstFacet, n);
        facetCount = Arrays.copyOf(facetCount, n);
      }
      face[count] = sourceFace;
      contents[count] = patchContents;
      surfaceFlags[count] = flags;
      firstPlane[count] = planeTotal;
      firstFacet[count] = facetTotal;
      return count;
    }

    void plane(float nx, float ny, float nz, float dist) {
      if ((planeTotal + 1) * 4 > planes.length) planes = Arrays.copyOf(planes, planes.length * 2);
      if (planeTotal == planeSigns.length) planeSigns = Arrays.copyOf(planeSigns, planeTotal * 2);
      planes[planeTotal * 4] = nx;
      planes[planeTotal * 4 + 1] = ny;
      planes[planeTotal * 4 + 2] = nz;
      planes[planeTotal * 4 + 3] = dist;
      planeSigns[planeTotal] = (byte) BrushTable.signbits(nx, ny, nz);
      planeTotal++;
    }

    void facet(int surface, int[] borders, boolean[] inward, int borderCount) {
      if (facetTotal == facetSurface.length) {
        int n = facetTotal * 2;
        facetSurface = Arrays.copyOf(facetSurface, n);
        facetFirstBorder = Arrays.copyOf(facetFirstBorder, n);
        facetBorderCount = Arrays.copyOf(facetBorderCount, n);
      }
      facetSurface[facetTotal] = surface;
      facetFirstBorder[facetTotal] = borderTotal;
      facetBorderCount[facetTotal] = borderCount;
      facetTotal++;
      while (borderTotal + borderCount > borderPlane.length) {
        borderPlane = Arrays.copyOf(borderPlane, borderPlane.length * 2);
        borderInward = Arrays.copyOf(borderInward, borderInward.length * 2);
      }
      System.arraycopy(borders, 0, borderPlane, borderTotal, borderCount);
      System.arraycopy(inward, 0, borderInward, borderTotal, borderCount);
      borderTotal += borderCount;
    }

    /** Completes the patch begun last with its bounds ({@code [min xyz, max xyz]}). */
    void end(float[] patchBounds) {
      planeCount[count] = planeTotal - firstPlane[count];
      facetCount[count] = facetTotal - firstFacet[count];
      System.arraycopy(patchBounds, 0, bounds, count * 6, 6);
      count++;
    }

    PatchTable build() {
      return new PatchTable(this);
    }
  }
}
