package dev.bluevista.craftq3.collision;

/** Published Quake III content bits and common masks; values cross the VM ABI unchanged. */
public final class Contents {
  private Contents() {}

  public static final int SOLID = 1;
  public static final int LAVA = 8;
  public static final int SLIME = 16;
  public static final int WATER = 32;
  public static final int FOG = 64;
  public static final int AREAPORTAL = 0x8000;
  public static final int PLAYERCLIP = 0x10000;
  public static final int MONSTERCLIP = 0x20000;
  public static final int TELEPORTER = 0x40000;
  public static final int JUMPPAD = 0x80000;
  public static final int CLUSTERPORTAL = 0x100000;
  public static final int DONOTENTER = 0x200000;
  public static final int BOTCLIP = 0x400000;
  public static final int MOVER = 0x800000;
  public static final int ORIGIN = 0x1000000;
  public static final int BODY = 0x2000000;
  public static final int CORPSE = 0x4000000;
  public static final int DETAIL = 0x8000000;
  public static final int STRUCTURAL = 0x10000000;
  public static final int TRANSLUCENT = 0x20000000;
  public static final int TRIGGER = 0x40000000;
  public static final int NODROP = 0x80000000;
  public static final int MASK_SOLID = SOLID;
  public static final int MASK_PLAYERSOLID = SOLID | PLAYERCLIP | BODY;
  public static final int MASK_DEADSOLID = SOLID | PLAYERCLIP;
  public static final int MASK_WATER = WATER | LAVA | SLIME;
  public static final int MASK_SHOT = SOLID | BODY | CORPSE;
  public static final int WORLD_ENTITY = 1022;
}
