package dev.bluevista.craftq3.mc.hybrid.bridge;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.common.time.ManualClock;
import dev.bluevista.craftq3.engine.formats.FileViewAssets;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.game.GameServer;
import dev.bluevista.craftq3.engine.game.ServerClient;
import dev.bluevista.craftq3.engine.game.map.BspMapSource;
import dev.bluevista.craftq3.engine.game.world.GameEntities;
import dev.bluevista.craftq3.engine.vmabi.layout.PlayerStateLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout.UserCmd;
import dev.bluevista.craftq3.engine.vmabi.service.FsVmFiles;
import dev.bluevista.craftq3.mc.hybrid.CoordinateTransform;
import dev.bluevista.craftq3.mc.hybrid.terrain.GridCollisionSource;
import dev.bluevista.craftq3.mc.hybrid.terrain.TerrainCells;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A headless bridge: the installation's baseq3 qagame on the {@link SyntheticMapSource} over
 * synthetic Minecraft terrain, with one local player driven by user commands. No network: the
 * player's reliable commands are acknowledged and dropped.
 */
final class BridgeSimulation implements AutoCloseable {
  static final int TICK = 50;

  final SearchPath search;
  final ManualClock clock = new ManualClock(0);
  final SyntheticMapSource maps = new SyntheticMapSource();
  final GameServer server;
  final BridgeHost host;
  final CoordinateTransform transform;
  final GridCollisionSource terrain;
  final List<String> console = Collections.synchronizedList(new ArrayList<>());
  final List<MobDamage> mobDamage = new ArrayList<>();
  List<ExternalActors.Target> targets = List.of();
  long now;
  int player = -1;
  private int commandTime;

  BridgeSimulation(Path root, TerrainCells cells, CoordinateTransform transform)
      throws IOException {
    this(root, cells, transform, false);
  }

  /** With {@code realMaps}, maps come from the game's files with host triggers added. */
  BridgeSimulation(Path root, TerrainCells cells, CoordinateTransform transform, boolean realMaps)
      throws IOException {
    this.transform = transform;
    search = SearchPath.mount(root, SearchPath.BASE_GAME);
    FileViewAssets files = new FileViewAssets(search.view());
    server =
        GameServer.builder()
            .files(files)
            .vmFiles(FsVmFiles.of(search, null, root))
            .maps(realMaps ? new HostTriggerMapSource(new BspMapSource(files)) : maps)
            .clocks(clock, java.time.Clock.systemDefaultZone())
            .seed(7)
            .output(
                line -> {
                  console.add(line);
                  if (console.size() > 5000) console.removeFirst();
                })
            .build();
    server.cvars().set("sv_pure", "0");
    server.cvars().set("bot_enable", "0");
    server.cvars().set("sv_maxclients", "16");
    server.cvars().set("g_gametype", "0");
    host = new BridgeHost(server, LoadoutPolicy.BASE_GAME, 8);
    server.addListener(host);
    terrain = new GridCollisionSource(cells, transform);
  }

  /** Spawns the bridge level with the player at {@code spawn} and the given items. */
  boolean start(Vec3f spawn, List<SyntheticMapSource.ItemSpawn> items) {
    maps.prepare(new SyntheticMapSource.World(terrain, spawn, 0, items, "test bridge"));
    return startMap(SyntheticMapSource.MAP_NAME);
  }

  /** Spawns {@code map} and (re)admits the player. */
  boolean startMap(String map) {
    if (!server.spawn(map, true)) return false;
    if (player < 0) {
      GameServer.Admission admission =
          server.connect(
              "\\name\\Tester\\model\\sarge\\handicap\\100\\snaps\\20", "localhost", true, -1);
      if (!admission.accepted()) throw new IllegalStateException(admission.denied());
      player = admission.client();
    }
    server.gamestateSent(player);
    host.setPlayer(player);
    commandTime = server.levelTime();
    return true;
  }

  /** One Minecraft tick with the given input. */
  void tick(int pitch, int yaw, int buttons, int weapon, int forward, int right, int up) {
    terrain.invalidate();
    host.syncActors(targets);
    commandTime = Math.max(commandTime + TICK, server.levelTime());
    host.userMove(
        player,
        List.of(new UserCmd(commandTime, pitch, yaw, 0, buttons, weapon, forward, right, up)));
    now += TICK;
    clock.advance(TICK);
    server.frame(now);
    mobDamage.addAll(host.afterFrame());
    if (server.state() == GameServer.State.RUNNING
        && server.client(player).state() != ServerClient.State.FREE) {
      ServerClient slot = server.client(player);
      slot.acknowledgeReliable(slot.reliableSequence());
    }
  }

  void idle(int ticks) {
    for (int i = 0; i < ticks; i++) tick(0, 0, 0, 0, 0, 0, 0);
  }

  GameEntities entities() {
    return server.entities().orElseThrow();
  }

  float[] origin() {
    float[] out = new float[3];
    entities().playerVec(player, PlayerStateLayout.ORIGIN, out);
    return out;
  }

  int stat(int index) {
    return entities().playerInt(player, PlayerStateLayout.STATS + index * 4);
  }

  int ammo(int weapon) {
    return entities().playerInt(player, PlayerStateLayout.AMMO + weapon * 4);
  }

  int playerInt(int offset) {
    return entities().playerInt(player, offset);
  }

  String tail() {
    return tail(60);
  }

  String tail(int lines) {
    synchronized (console) {
      return String.join(
          "\n", console.subList(Math.max(0, console.size() - lines), console.size()));
    }
  }

  @Override
  public void close() throws IOException {
    host.close();
    server.close();
    search.close();
  }
}
