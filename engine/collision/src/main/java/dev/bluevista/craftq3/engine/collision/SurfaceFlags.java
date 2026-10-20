package dev.bluevista.craftq3.engine.collision;

/** Quake III surface flags ({@code SURF_*}) with the original {@code surfaceflags.h} values. */
public final class SurfaceFlags {
  private SurfaceFlags() {}

  /** Never give falling damage. */
  public static final int NODAMAGE = 0x1;

  /** Effects game physics. */
  public static final int SLICK = 0x2;

  /** Lighting from the environment map. */
  public static final int SKY = 0x4;

  public static final int LADDER = 0x8;

  /** Do not make missile explosions. */
  public static final int NOIMPACT = 0x10;

  /** Do not leave missile marks. */
  public static final int NOMARKS = 0x20;

  /** Make flesh sounds and effects. */
  public static final int FLESH = 0x40;

  /** Do not generate a drawsurface at all. */
  public static final int NODRAW = 0x80;

  /** Make a primary BSP splitter. */
  public static final int HINT = 0x100;

  /** Completely ignore, allowing non-closed brushes. */
  public static final int SKIP = 0x200;

  public static final int NOLIGHTMAP = 0x400;
  public static final int POINTLIGHT = 0x800;
  public static final int METALSTEPS = 0x1000;
  public static final int NOSTEPS = 0x2000;

  /** Do not collide against curves with this set. */
  public static final int NONSOLID = 0x4000;

  public static final int LIGHTFILTER = 0x8000;
  public static final int ALPHASHADOW = 0x10000;
  public static final int NODLIGHT = 0x20000;
  public static final int DUST = 0x40000;
}
