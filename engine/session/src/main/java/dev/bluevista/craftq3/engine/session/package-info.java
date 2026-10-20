/**
 * The engine session, free of Minecraft: {@link dev.bluevista.craftq3.engine.session.EngineSession}
 * is the state machine (menu, loading, local, hosting, remote, demo, cinematic) the platform drives
 * frame by frame through the {@link dev.bluevista.craftq3.engine.session.SessionHost} SPI.
 *
 * <ul>
 *   <li>{@code EngineSession}: console commands to transitions, the client game ({@code
 *       GameClient}: connection or demo, cgame, recording), the ui and content ({@code
 *       ClientContent}, rebuilt for a pure server's packs), cvar forwarding ({@code CvarMirror}),
 *       {@code ERR_DROP} handling.
 *   <li>{@code SessionPaks}: the mounted installation and the PK3s a remote server referenced,
 *       taken from the download cache or downloaded ({@code cl_allowDownload}) by the client's
 *       {@code DownloadManager}, mounted only while that game lasts.
 *   <li>{@link dev.bluevista.craftq3.engine.session.LocalServer}: the server half as the session
 *       sees it; {@link dev.bluevista.craftq3.engine.session.GameLocalServer} implements it with
 *       {@code engine:game}; {@link dev.bluevista.craftq3.engine.session.ServerRunner} runs it on
 *       the {@code cq3-sim} thread (or synchronously for reproducible runs).
 *   <li>{@link dev.bluevista.craftq3.engine.session.SessionOptions}, {@link
 *       dev.bluevista.craftq3.engine.session.CommandLine}, {@link
 *       dev.bluevista.craftq3.engine.session.TransportFactory}: configuration, the original {@code
 *       +command} syntax and sockets.
 * </ul>
 *
 * <p>Threading: the client thread owns the client console, VMs, input, sound and rendering; the
 * server thread owns the server console, qagame and botlib; they share only a loopback link,
 * lock-free queues and published immutable status.
 */
package dev.bluevista.craftq3.engine.session;
