package dev.bluevista.craftq3.mc.hybrid.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.game.GameServer;
import dev.bluevista.craftq3.engine.game.ServerClient;
import dev.bluevista.craftq3.engine.game.world.GameEntities;
import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import dev.bluevista.craftq3.engine.vmabi.layout.PlayerStateLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout.UserCmd;
import dev.bluevista.craftq3.mc.hybrid.CoordinateTransform;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Host triggers linked for the bridge player (client A) through the server's think hook reach only
 * A's think: another human (client B) standing well inside the triggers' 512-unit reach and
 * thinking before and after A in every tick never touches them, while A takes the damage. Both
 * clients are driven through {@code GameServer.userMove} directly, as network and loopback
 * connections are.
 */
@RealData
class HostTriggerIsolationTest {
  @Test
  void triggersLinkedForThePlayerNeverReachAnotherClientsThink() throws Exception {
    CoordinateTransform t = CoordinateTransform.at(0, 64, 0);
    try (BridgeSimulation sim =
        new BridgeSimulation(RealDataRoot.require(), BridgeSimulationTest.TERRAIN, t)) {
      assertTrue(sim.start(new Vec3f(0, 0, 30), List.of()), sim::tail);
      assertTrue(sim.host.triggersReady());
      sim.idle(10);
      int a = sim.player;

      // B spawns 64 units beside A (the spawn point moved so it does not telefrag A).
      sim.host.moveSpawn(64, 0, 30);
      GameServer server = sim.server;
      GameServer.Admission admission =
          server.connect("\\name\\Bystander\\model\\sarge\\snaps\\20", "localhost", true, -1);
      assertTrue(admission.accepted(), admission.denied());
      int b = admission.client();
      server.gamestateSent(b);
      int[] bTime = {server.levelTime()};
      Runnable thinkB =
          () -> {
            bTime[0] = Math.max(bTime[0] + 25, server.levelTime());
            server.userMove(b, List.of(UserCmd.idle(bTime[0])));
            // Nobody reads B's reliable commands (no connection): keep its window open.
            ServerClient slot = server.client(b);
            slot.acknowledgeReliable(slot.reliableSequence());
          };
      thinkB.run();
      sim.host.moveSpawn(0, 0, 30);
      for (int i = 0; i < 6; i++) {
        thinkB.run();
        sim.idle(1);
      }
      assertEquals(ServerClient.State.ACTIVE, server.client(b).state());
      float[] oa = sim.origin();
      float[] ob = new float[3];
      sim.entities().playerVec(b, PlayerStateLayout.ORIGIN, ob);
      float distance = (float) Math.hypot(Math.hypot(oa[0] - ob[0], oa[1] - ob[1]), oa[2] - ob[2]);
      assertTrue(
          distance > 40 && distance < HostTriggers.HALF_SIZE / 2, "A-B distance " + distance);

      // Watch every think: during B's, no host trigger may be linked.
      List<String> leaks = new ArrayList<>();
      int[] linkedForA = {0};
      server.addListener(
          new GameServer.Listener() {
            @Override
            public void beforeClientThink(GameServer s, int client, UserCmd command) {
              boolean linked = anyHostTriggerLinked(sim.entities());
              if (client == a && linked) linkedForA[0]++;
              if (client != a && linked) leaks.add("client " + client + " at " + s.levelTime());
            }
          });

      int healthA = sim.stat(Loadout.STAT_HEALTH);
      int healthB = health(sim, b);
      sim.host.damage(60);
      for (int i = 0; i < 10; i++) {
        thinkB.run();
        sim.idle(1);
        thinkB.run();
      }
      assertTrue(leaks.isEmpty(), () -> "triggers linked during another think: " + leaks);
      assertTrue(linkedForA[0] >= 1, "the triggers were linked for A");
      int lostA = healthA - sim.stat(Loadout.STAT_HEALTH);
      assertTrue(lostA >= 60 && lostA <= 61, "A lost " + lostA);
      // B loses at most the one point a second its over-maximum spawn health counts down.
      int lostB = healthB - health(sim, b);
      assertTrue(lostB <= 1, "B lost " + lostB + " health\n" + sim.tail(30));
    }
  }

  private static int health(BridgeSimulation sim, int client) {
    return sim.entities().playerInt(client, PlayerStateLayout.STATS + Loadout.STAT_HEALTH * 4);
  }

  private static boolean anyHostTriggerLinked(GameEntities g) {
    int first = SyntheticMapSource.FIRST_HOST_MODEL;
    for (int e = GameAccess.MAX_CLIENTS; e < g.count(); e++) {
      if (!g.bmodel(e)) continue;
      int model = g.modelIndex(e);
      if (model >= first && model < first + HostTriggers.MODELS && g.linked(e)) return true;
    }
    return false;
  }
}
