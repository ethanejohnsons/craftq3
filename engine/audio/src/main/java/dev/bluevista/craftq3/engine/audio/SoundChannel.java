package dev.bluevista.craftq3.engine.audio;

/**
 * The entity channel numbers the game modules pass to the sound imports. Any other value is a valid
 * channel too; only {@link #AUTO} has special meaning.
 */
public final class SoundChannel {
  /** Never replaces another sound: every start takes a free voice. */
  public static final int AUTO = 0;

  /** Menu and listener-local sounds. */
  public static final int LOCAL = 1;

  public static final int WEAPON = 2;
  public static final int VOICE = 3;
  public static final int ITEM = 4;
  public static final int BODY = 5;

  /** Chat beeps and other sounds the cgame plays for the local client only. */
  public static final int LOCAL_SOUND = 6;

  /** Announcer voices; they are never stolen for another sound. */
  public static final int ANNOUNCER = 7;

  private SoundChannel() {}
}
