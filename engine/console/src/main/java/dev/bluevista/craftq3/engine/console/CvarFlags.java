package dev.bluevista.craftq3.engine.console;

/**
 * Cvar flag bits with the values of Quake III 1.32 ({@code CVAR_*}), which VMs pass through {@code
 * trap_Cvar_Register} unchanged. Bits above {@link #NORESTART} are stored but have no meaning to
 * the table.
 */
public final class CvarFlags {
  /** Written to the config file by {@code writeconfig}. */
  public static final int ARCHIVE = 1;

  /** Sent to the server as part of the userinfo string. */
  public static final int USERINFO = 2;

  /** Sent in response to front-end status requests (serverinfo). */
  public static final int SERVERINFO = 4;

  /** Duplicated on all clients through the systeminfo configstring. */
  public static final int SYSTEMINFO = 8;

  /** Can only be set from the command line; the console reports it as write protected. */
  public static final int INIT = 16;

  /** User changes are held until the next registration or {@link CvarTable#applyLatched()}. */
  public static final int LATCH = 32;

  /** Read only for the user; only engine (forced) writes change it. */
  public static final int ROM = 64;

  /** Created by a user {@code set} before any code registered it. */
  public static final int USER_CREATED = 128;

  /** Temporary: carried by the original engine for cvars that should not be archived. */
  public static final int TEMP = 256;

  /** User changes require {@code sv_cheats}; values reset when cheats are turned off. */
  public static final int CHEAT = 512;

  /** Not reset by {@code cvar_restart}. */
  public static final int NORESTART = 1024;

  /** Any of the info-string flags. */
  public static final int INFO_MASK = USERINFO | SERVERINFO | SYSTEMINFO;

  private CvarFlags() {}
}
