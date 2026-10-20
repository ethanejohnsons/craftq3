package dev.bluevista.craftq3.botlib.aas;

import dev.bluevista.craftq3.assets.aas.AasMap.Reachability;

/** Published botlib TFL bit assignments; these differ from stored TRAVEL type numbers. */
public final class TravelFlags {
  public static final int INVALID = 0x00000001;
  public static final int WALK = 0x00000002;
  public static final int CROUCH = 0x00000004;
  public static final int BARRIER_JUMP = 0x00000008;
  public static final int JUMP = 0x00000010;
  public static final int LADDER = 0x00000020;
  public static final int WALK_OFF_LEDGE = 0x00000080;
  public static final int SWIM = 0x00000100;
  public static final int WATER_JUMP = 0x00000200;
  public static final int TELEPORT = 0x00000400;
  public static final int ELEVATOR = 0x00000800;
  public static final int ROCKET_JUMP = 0x00001000;
  public static final int BFG_JUMP = 0x00002000;
  public static final int GRAPPLE_HOOK = 0x00004000;
  public static final int DOUBLE_JUMP = 0x00008000;
  public static final int RAMP_JUMP = 0x00010000;
  public static final int STRAFE_JUMP = 0x00020000;
  public static final int JUMP_PAD = 0x00040000;
  public static final int AIR = 0x00080000;
  public static final int WATER = 0x00100000;
  public static final int SLIME = 0x00200000;
  public static final int LAVA = 0x00400000;
  public static final int DO_NOT_ENTER = 0x00800000;
  public static final int FUNC_BOB = 0x01000000;
  public static final int FLIGHT = 0x02000000;
  public static final int BRIDGE = 0x04000000;
  public static final int NOT_TEAM_1 = 0x08000000;
  public static final int NOT_TEAM_2 = 0x10000000;
  public static final int DEFAULT =
      WALK
          | CROUCH
          | BARRIER_JUMP
          | JUMP
          | LADDER
          | WALK_OFF_LEDGE
          | SWIM
          | WATER_JUMP
          | TELEPORT
          | ELEVATOR
          | AIR
          | WATER
          | JUMP_PAD
          | FUNC_BOB;
  public static final int ALL = 0x1fffffbf;

  private TravelFlags() {}

  /** Zero means an unknown stored type. INVALID remains a distinct, forbidden travel kind. */
  public static int forTravelType(int type) {
    return switch (type) {
      case 1 -> INVALID;
      case 2 -> WALK;
      case 3 -> CROUCH;
      case 4 -> BARRIER_JUMP;
      case 5 -> JUMP;
      case 6 -> LADDER;
      case 7 -> WALK_OFF_LEDGE;
      case 8 -> SWIM;
      case 9 -> WATER_JUMP;
      case 10 -> TELEPORT;
      case 11 -> ELEVATOR;
      case 12 -> ROCKET_JUMP;
      case 13 -> BFG_JUMP;
      case 14 -> GRAPPLE_HOOK;
      case 15 -> DOUBLE_JUMP;
      case 16 -> RAMP_JUMP;
      case 17 -> STRAFE_JUMP;
      case 18 -> JUMP_PAD;
      case 19 -> FUNC_BOB;
      default -> 0;
    };
  }

  public static int forReachability(Reachability reachability) {
    int flags = forTravelType(reachability.baseTravelType());
    if ((reachability.travelFlags() & 0x01000000) != 0) flags |= NOT_TEAM_1;
    if ((reachability.travelFlags() & 0x02000000) != 0) flags |= NOT_TEAM_2;
    return flags;
  }
}
