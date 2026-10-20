package dev.bluevista.craftq3.engine.collision;

/**
 * Quake III content bits ({@code CONTENTS_*}) and the game's standard masks ({@code MASK_*}). The
 * values cross the VM ABI unchanged and match the original {@code surfaceflags.h} and {@code
 * bg_public.h}.
 */
public final class Contents {
  private Contents() {}

  public static final int SOLID = 0x1;
  public static final int LAVA = 0x8;
  public static final int SLIME = 0x10;
  public static final int WATER = 0x20;
  public static final int FOG = 0x40;

  /** Team Arena: blocks only the first team. */
  public static final int NOTTEAM1 = 0x80;

  /** Team Arena: blocks only the second team. */
  public static final int NOTTEAM2 = 0x100;

  /** Team Arena: bots ignore this clip brush. */
  public static final int NOBOTCLIP = 0x200;

  public static final int AREAPORTAL = 0x8000;
  public static final int PLAYERCLIP = 0x10000;
  public static final int MONSTERCLIP = 0x20000;
  public static final int TELEPORTER = 0x40000;
  public static final int JUMPPAD = 0x80000;
  public static final int CLUSTERPORTAL = 0x100000;
  public static final int DONOTENTER = 0x200000;
  public static final int BOTCLIP = 0x400000;
  public static final int MOVER = 0x800000;

  /** Removed before bsp-ing an entity. */
  public static final int ORIGIN = 0x1000000;

  /** Never on a BSP brush; the contents of entity bounding boxes and temporary box models. */
  public static final int BODY = 0x2000000;

  public static final int CORPSE = 0x4000000;

  /** Brushes not used for the BSP. */
  public static final int DETAIL = 0x8000000;

  /** Brushes used for the BSP. */
  public static final int STRUCTURAL = 0x10000000;

  /** Does not eat light. */
  public static final int TRANSLUCENT = 0x20000000;

  public static final int TRIGGER = 0x40000000;

  /** Items are not dropped here. */
  public static final int NODROP = 0x80000000;

  public static final int MASK_ALL = -1;
  public static final int MASK_SOLID = SOLID;
  public static final int MASK_PLAYERSOLID = SOLID | PLAYERCLIP | BODY;
  public static final int MASK_DEADSOLID = SOLID | PLAYERCLIP;
  public static final int MASK_WATER = WATER | LAVA | SLIME;
  public static final int MASK_OPAQUE = SOLID | SLIME | LAVA;
  public static final int MASK_SHOT = SOLID | BODY | CORPSE;
}
