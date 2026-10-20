/**
 * The Quake III client, Minecraft-free: the platform renders its frames and feeds its input through
 * {@link dev.bluevista.craftq3.engine.client.ClientHostServices}. Never depends on the game server.
 *
 * <ul>
 *   <li>{@code host}: the cgame and ui VM hosts ({@code CgameHost}, {@code UiHost}) on the shared
 *       {@code HostServices} (console, files, renderer tables, sound and music, cinematics,
 *       scripts, CD key, VM loading and restart);
 *   <li>{@code pipeline}: the one {@code SnapshotPipeline} that local, remote and demo play feed,
 *       the server clocks and the user command history;
 *   <li>{@code net}: {@code ClientConnection} over a {@code PacketTransport} (UDP or loopback),
 *       downloads and the server browser;
 *   <li>{@code demo}: playback (protocols 66 to 68) and recording;
 *   <li>{@code render}: renderer registration and the recorder of {@code CgameFrame}s;
 *   <li>{@code input}, {@code console}, {@code script}, {@code video}: key catchers and user
 *       commands, the drop-down console, the menu-script precompiler, RoQ cinematics.
 * </ul>
 */
package dev.bluevista.craftq3.engine.client;
