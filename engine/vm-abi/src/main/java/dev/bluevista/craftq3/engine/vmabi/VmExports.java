package dev.bluevista.craftq3.engine.vmabi;

/**
 * The {@code vmMain} command numbers each module exports. A command's number is its ordinal, the
 * same in both ABI profiles.
 */
public final class VmExports {
  /** ui {@code UI_GETAPIVERSION} results accepted by hosts (retail 3/4, 1.32 6). */
  public static final int[] UI_API_VERSIONS = {3, 4, 6};

  private VmExports() {}

  /** {@code gameExport_t}. */
  public enum Qagame {
    GAME_INIT,
    GAME_SHUTDOWN,
    GAME_CLIENT_CONNECT,
    GAME_CLIENT_BEGIN,
    GAME_CLIENT_USERINFO_CHANGED,
    GAME_CLIENT_DISCONNECT,
    GAME_CLIENT_COMMAND,
    GAME_CLIENT_THINK,
    GAME_RUN_FRAME,
    GAME_CONSOLE_COMMAND,
    BOTAI_START_FRAME;

    public int command() {
      return ordinal();
    }
  }

  /** {@code cgameExport_t}. */
  public enum Cgame {
    CG_INIT,
    CG_SHUTDOWN,
    CG_CONSOLE_COMMAND,
    CG_DRAW_ACTIVE_FRAME,
    CG_CROSSHAIR_PLAYER,
    CG_LAST_ATTACKER,
    CG_KEY_EVENT,
    CG_MOUSE_EVENT,
    CG_EVENT_HANDLING;

    public int command() {
      return ordinal();
    }
  }

  /** {@code uiExport_t}. */
  public enum Ui {
    UI_GETAPIVERSION,
    UI_INIT,
    UI_SHUTDOWN,
    UI_KEY_EVENT,
    UI_MOUSE_EVENT,
    UI_REFRESH,
    UI_IS_FULLSCREEN,
    UI_SET_ACTIVE_MENU,
    UI_CONSOLE_COMMAND,
    UI_DRAW_CONNECT_SCREEN,
    UI_HASUNIQUECDKEY;

    public int command() {
      return ordinal();
    }
  }
}
