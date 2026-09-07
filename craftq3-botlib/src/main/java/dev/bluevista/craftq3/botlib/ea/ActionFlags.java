package dev.bluevista.craftq3.botlib.ea;

/** Published bot_input_t ACTION bits, including the jump history marker. */
public final class ActionFlags {
  public static final int ATTACK = 0x00000001;
  public static final int USE = 0x00000002;
  public static final int RESPAWN = 0x00000008;
  public static final int JUMP = 0x00000010;
  public static final int MOVE_UP = 0x00000020;
  public static final int CROUCH = 0x00000080;
  public static final int MOVE_DOWN = 0x00000100;
  public static final int MOVE_FORWARD = 0x00000200;
  public static final int MOVE_BACK = 0x00000800;
  public static final int MOVE_LEFT = 0x00001000;
  public static final int MOVE_RIGHT = 0x00002000;
  public static final int DELAYED_JUMP = 0x00008000;
  public static final int TALK = 0x00010000;
  public static final int GESTURE = 0x00020000;
  public static final int WALK = 0x00080000;
  public static final int AFFIRMATIVE = 0x00100000;
  public static final int NEGATIVE = 0x00200000;
  public static final int GET_FLAG = 0x00800000;
  public static final int GUARD_BASE = 0x01000000;
  public static final int PATROL = 0x02000000;
  public static final int FOLLOW_ME = 0x08000000;
  public static final int JUMPED_LAST_FRAME = 0x10000000;

  private ActionFlags() {}
}
