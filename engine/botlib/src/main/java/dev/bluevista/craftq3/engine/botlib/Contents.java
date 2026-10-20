package dev.bluevista.craftq3.engine.botlib;

/**
 * Bit values botlib uses: BSP brush contents ({@code CONTENTS_*}), AAS area contents ({@code
 * AREACONTENTS_*}), AAS area flags ({@code AREA_*}) and AAS face flags ({@code FACE_*}). The
 * numbers are the original ones; the collision module defines the BSP set independently and a host
 * adapter passes them through unchanged.
 */
public final class Contents {
  private Contents() {}

  // BSP contents.
  public static final int SOLID = 1;
  public static final int LAVA = 8;
  public static final int SLIME = 16;
  public static final int WATER = 32;
  public static final int FOG = 64;

  /** Lava, slime and water. */
  public static final int LIQUID = LAVA | SLIME | WATER;

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

  /** Solid and player clip: what a walking player collides with. */
  public static final int PLAYER_SOLID = SOLID | PLAYERCLIP;

  /** Solid, player clip and bodies: entity obstruction tests. */
  public static final int PLAYER_SOLID_BODY = SOLID | PLAYERCLIP | BODY;

  // AAS area contents.
  public static final int AREA_WATER = 1;
  public static final int AREA_LAVA = 2;
  public static final int AREA_SLIME = 4;
  public static final int AREA_CLUSTERPORTAL = 8;
  public static final int AREA_TELEPORTAL = 16;
  public static final int AREA_ROUTEPORTAL = 32;
  public static final int AREA_TELEPORTER = 64;
  public static final int AREA_JUMPPAD = 128;
  public static final int AREA_DONOTENTER = 256;
  public static final int AREA_VIEWPORTAL = 512;
  public static final int AREA_MOVER = 1024;
  public static final int AREA_NOTTEAM1 = 2048;
  public static final int AREA_NOTTEAM2 = 4096;

  /** Area liquids. */
  public static final int AREA_LIQUID = AREA_WATER | AREA_LAVA | AREA_SLIME;

  // AAS area flags.
  public static final int AREAFLAG_GROUNDED = 1;
  public static final int AREAFLAG_LADDER = 2;
  public static final int AREAFLAG_LIQUID = 4;
  public static final int AREAFLAG_DISABLED = 8;
  public static final int AREAFLAG_BRIDGE = 16;

  // AAS face flags.
  public static final int FACE_SOLID = 1;
  public static final int FACE_LADDER = 2;
  public static final int FACE_GROUND = 4;
  public static final int FACE_GAP = 8;
  public static final int FACE_LIQUID = 16;
  public static final int FACE_LIQUIDSURFACE = 32;
  public static final int FACE_BRIDGE = 64;

  // Presence types.
  public static final int PRESENCE_NONE = 1;
  public static final int PRESENCE_NORMAL = 2;
  public static final int PRESENCE_CROUCH = 4;
}
