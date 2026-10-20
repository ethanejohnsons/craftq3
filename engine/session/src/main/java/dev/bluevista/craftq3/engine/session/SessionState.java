package dev.bluevista.craftq3.engine.session;

/** What an {@link EngineSession} is doing. */
public enum SessionState {
  /** Only the ui runs (main menu, optionally an attract loop). */
  MENU,
  /** A map is loading or a connection is being established; the connect or loading screen shows. */
  LOADING,
  /** A local server and a local client over loopback; nobody else can join. */
  LOCAL,
  /** A local server reachable over UDP, plus the local client over loopback. */
  HOSTING,
  /** A client of a remote server over UDP. */
  REMOTE,
  /** A demo plays. */
  DEMO,
  /** A full-screen RoQ cinematic plays ({@code cinematic}). */
  CINEMATIC;

  /** True while a cgame draws a level (local, hosted, remote or demo). */
  public boolean inGame() {
    return this == LOCAL || this == HOSTING || this == REMOTE || this == DEMO;
  }
}
