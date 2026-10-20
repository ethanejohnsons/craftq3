package dev.bluevista.craftq3.engine.formats.bsp;

/** The {@code surfaceType} field of a BSP face. */
public enum SurfaceType {
  /** Zero or any unknown value; renderers skip such faces. */
  BAD(0),
  PLANAR(1),
  PATCH(2),
  TRIANGLE_SOUP(3),
  FLARE(4);

  private static final SurfaceType[] BY_ID = {BAD, PLANAR, PATCH, TRIANGLE_SOUP, FLARE};

  private final int id;

  SurfaceType(int id) {
    this.id = id;
  }

  public int id() {
    return id;
  }

  /** The type for a raw field value; unknown values map to {@link #BAD}. */
  public static SurfaceType of(int id) {
    return id >= 0 && id < BY_ID.length ? BY_ID[id] : BAD;
  }

  /** Planar faces and triangle soups draw indexed triangles from the mesh-vertex lump. */
  public boolean indexed() {
    return this == PLANAR || this == TRIANGLE_SOUP;
  }
}
