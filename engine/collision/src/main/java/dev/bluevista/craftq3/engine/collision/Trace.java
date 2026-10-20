package dev.bluevista.craftq3.engine.collision;

import dev.bluevista.craftq3.engine.common.math.Vec3f;

/**
 * A mutable trace result laid out like the original {@code trace_t}: {@code allsolid}, {@code
 * startsolid}, {@code fraction}, {@code endpos}, the hit {@code cplane_t} (normal, dist, type,
 * signbits), {@code surfaceFlags}, {@code contents} and {@code entityNum}.
 *
 * <p>Callers own and reuse instances so hot paths do not allocate. When {@link #allSolid()} is set
 * or {@link #fraction()} is 1 the plane is not valid, as in the original engine. The collision
 * model leaves {@link #entityNum()} at 0 like the original {@code CM_BoxTrace}; the game host sets
 * it through {@link TraceMerge}.
 */
public final class Trace {
  /** {@code ENTITYNUM_NONE}: nothing was hit. */
  public static final int ENTITYNUM_NONE = 1023;

  /** {@code ENTITYNUM_WORLD}: the world model was hit. */
  public static final int ENTITYNUM_WORLD = 1022;

  /** {@code PLANE_NON_AXIAL}: a plane type that is not a positive unit axis. */
  public static final int PLANE_NON_AXIAL = 3;

  boolean allSolid;
  boolean startSolid;
  float fraction = 1;
  final float[] endPos = new float[3];
  final float[] normal = new float[3];
  float dist;
  int planeType;
  int planeSignbits;
  int surfaceFlags;
  int contents;
  int entityNum;

  public boolean allSolid() {
    return allSolid;
  }

  public boolean startSolid() {
    return startSolid;
  }

  /** Time completed along the requested segment: 1.0 means nothing was hit. */
  public float fraction() {
    return fraction;
  }

  /** True when something was hit or the trace never left solid. */
  public boolean blocked() {
    return fraction < 1 || allSolid;
  }

  public float endPos(int axis) {
    return endPos[axis];
  }

  public Vec3f endPos() {
    return Vec3f.of(endPos);
  }

  public void endPos(float[] out) {
    out[0] = endPos[0];
    out[1] = endPos[1];
    out[2] = endPos[2];
  }

  public float planeNormal(int axis) {
    return normal[axis];
  }

  public Vec3f planeNormal() {
    return Vec3f.of(normal);
  }

  public float planeDist() {
    return dist;
  }

  /** {@code cplane_t.type}: 0..2 for a positive unit axis, otherwise {@link #PLANE_NON_AXIAL}. */
  public int planeType() {
    return planeType;
  }

  /**
   * {@code cplane_t.signbits}: bit {@code i} is set when normal component {@code i} is negative.
   */
  public int planeSignbits() {
    return planeSignbits;
  }

  public int surfaceFlags() {
    return surfaceFlags;
  }

  /** Contents of the solid that was hit. */
  public int contents() {
    return contents;
  }

  public int entityNum() {
    return entityNum;
  }

  public Trace entityNum(int value) {
    entityNum = value;
    return this;
  }

  /**
   * Sets {@link #entityNum()} the way the original server's world trace does: {@link
   * #ENTITYNUM_WORLD} when something was hit, {@link #ENTITYNUM_NONE} otherwise.
   */
  public Trace markWorld() {
    entityNum = fraction != 1 ? ENTITYNUM_WORLD : ENTITYNUM_NONE;
    return this;
  }

  /** Resets to an unobstructed trace ending at {@code end}. */
  public Trace clear(float[] end) {
    allSolid = false;
    startSolid = false;
    fraction = 1;
    endPos[0] = end[0];
    endPos[1] = end[1];
    endPos[2] = end[2];
    clearPlane();
    surfaceFlags = 0;
    contents = 0;
    entityNum = 0;
    return this;
  }

  public Trace copyFrom(Trace other) {
    allSolid = other.allSolid;
    startSolid = other.startSolid;
    fraction = other.fraction;
    System.arraycopy(other.endPos, 0, endPos, 0, 3);
    System.arraycopy(other.normal, 0, normal, 0, 3);
    dist = other.dist;
    planeType = other.planeType;
    planeSignbits = other.planeSignbits;
    surfaceFlags = other.surfaceFlags;
    contents = other.contents;
    entityNum = other.entityNum;
    return this;
  }

  void clearPlane() {
    normal[0] = 0;
    normal[1] = 0;
    normal[2] = 0;
    dist = 0;
    planeType = 0;
    planeSignbits = 0;
  }

  @Override
  public String toString() {
    return "Trace[fraction="
        + fraction
        + ", startSolid="
        + startSolid
        + ", allSolid="
        + allSolid
        + ", endPos=("
        + endPos[0]
        + ", "
        + endPos[1]
        + ", "
        + endPos[2]
        + "), normal=("
        + normal[0]
        + ", "
        + normal[1]
        + ", "
        + normal[2]
        + "), dist="
        + dist
        + ", surfaceFlags=0x"
        + Integer.toHexString(surfaceFlags)
        + ", contents=0x"
        + Integer.toHexString(contents)
        + ", entityNum="
        + entityNum
        + "]";
  }
}
