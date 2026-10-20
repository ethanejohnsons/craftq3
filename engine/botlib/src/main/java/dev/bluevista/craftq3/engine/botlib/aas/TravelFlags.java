package dev.bluevista.craftq3.engine.botlib.aas;

import dev.bluevista.craftq3.engine.botlib.Contents;
import dev.bluevista.craftq3.engine.botlib.aas.AasWorld.AreaSettings;
import dev.bluevista.craftq3.engine.botlib.aas.AasWorld.Reachability;
import dev.bluevista.craftq3.engine.formats.aas.TravelType;

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
    return TravelType.fromId(type).map(TravelFlags::forTravel).orElse(0);
  }

  public static int forTravel(TravelType type) {
    return switch (type) {
      case INVALID -> INVALID;
      case WALK -> WALK;
      case CROUCH -> CROUCH;
      case BARRIER_JUMP -> BARRIER_JUMP;
      case JUMP -> JUMP;
      case LADDER -> LADDER;
      case WALK_OFF_LEDGE -> WALK_OFF_LEDGE;
      case SWIM -> SWIM;
      case WATER_JUMP -> WATER_JUMP;
      case TELEPORT -> TELEPORT;
      case ELEVATOR -> ELEVATOR;
      case ROCKET_JUMP -> ROCKET_JUMP;
      case BFG_JUMP -> BFG_JUMP;
      case GRAPPLE_HOOK -> GRAPPLE_HOOK;
      case DOUBLE_JUMP -> DOUBLE_JUMP;
      case RAMP_JUMP -> RAMP_JUMP;
      case STRAFE_JUMP -> STRAFE_JUMP;
      case JUMP_PAD -> JUMP_PAD;
      case FUNC_BOB -> FUNC_BOB;
    };
  }

  /**
   * The flags a route needs to take this reachability: its travel type plus {@link #NOT_TEAM_1} and
   * {@link #NOT_TEAM_2} for the {@code TRAVELFLAG_NOTTEAM} bits of its travel field.
   */
  public static int forReachability(Reachability reachability) {
    int flags = forTravelType(reachability.baseTravelType());
    if ((reachability.travelFlags() & Reachability.NOT_TEAM_1) != 0) flags |= NOT_TEAM_1;
    if ((reachability.travelFlags() & Reachability.NOT_TEAM_2) != 0) flags |= NOT_TEAM_2;
    return flags;
  }

  /** The flags needed to enter an area: its liquid or air, do-not-enter, team and bridge bits. */
  public static int forArea(AreaSettings settings) {
    int contents = settings.contents(), flags = 0;
    if ((contents & Contents.AREA_WATER) != 0) flags |= WATER;
    if ((contents & Contents.AREA_LAVA) != 0) flags |= LAVA;
    if ((contents & Contents.AREA_SLIME) != 0) flags |= SLIME;
    if ((contents & Contents.AREA_LIQUID) == 0) flags |= AIR;
    if ((contents & Contents.AREA_DONOTENTER) != 0) flags |= DO_NOT_ENTER;
    if ((settings.flags() & Contents.AREAFLAG_BRIDGE) != 0) flags |= BRIDGE;
    if ((contents & Contents.AREA_NOTTEAM1) != 0) flags |= NOT_TEAM_1;
    if ((contents & Contents.AREA_NOTTEAM2) != 0) flags |= NOT_TEAM_2;
    return flags;
  }
}
