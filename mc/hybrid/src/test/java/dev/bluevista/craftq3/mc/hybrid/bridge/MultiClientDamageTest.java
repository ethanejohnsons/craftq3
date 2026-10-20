package dev.bluevista.craftq3.mc.hybrid.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.game.GameServer;
import dev.bluevista.craftq3.engine.game.ServerClient;
import dev.bluevista.craftq3.engine.game.world.GameEntities;
import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout.UserCmd;
import dev.bluevista.craftq3.mc.hybrid.CoordinateTransform;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * One {@link BridgeHost} serving three clients of the same game: each takes a different amount of
 * Minecraft damage, all queued in the same tick. The shared host triggers reach each client only in
 * its own think, each client receives exactly its own amount, and no trigger is touched twice
 * within its re-arm window (the game would ignore the second touch).
 */
@RealData
class MultiClientDamageTest {
  @Test
  void threeClientsTakeTheirOwnDamageInTheSameTick() throws Exception {
    CoordinateTransform t = CoordinateTransform.at(0, 64, 0);
    try (BridgeSimulation sim =
        new BridgeSimulation(RealDataRoot.require(), BridgeSimulationTest.TERRAIN, t)) {
      assertTrue(sim.start(new Vec3f(0, 0, 30), List.of()), sim::tail);
      assertTrue(sim.host.triggersReady());
      sim.idle(10);
      int a = sim.player;
      // Well inside one another's trigger reach, apart enough not to telefrag.
      int b = sim.join("Second", new Vec3f(96, 0, 30));
      int c = sim.join("Third", new Vec3f(0, 96, 30));
      sim.idle(10);
      int[] players = {a, b, c};
      for (int p : players)
        assertEquals(ServerClient.State.ACTIVE, sim.server.client(p).state(), sim::tail);

      // Every think: which hurt triggers are linked, and for whom.
      Map<Integer, Integer> delivered = new HashMap<>();
      List<String> rearm = new ArrayList<>();
      long[] firedAt = new long[ExternalDamage.TRIGGERS];
      java.util.Arrays.fill(firedAt, Long.MIN_VALUE / 2);
      sim.server.addListener(
          new GameServer.Listener() {
            @Override
            public void beforeClientThink(GameServer s, int client, UserCmd command) {
              int mask = linkedHurtMask(sim.entities());
              if (mask == 0) return;
              delivered.merge(client, mask, Integer::sum);
              for (int bit = 0; bit < ExternalDamage.TRIGGERS; bit++) {
                if ((mask & 1 << bit) == 0) continue;
                if (s.levelTime() - firedAt[bit] < ExternalDamage.REARM_MILLIS)
                  rearm.add("trigger " + bit + " again at " + s.levelTime() + " for " + client);
                firedAt[bit] = s.levelTime();
              }
            }
          });

      int[] before = new int[3];
      for (int i = 0; i < 3; i++) before[i] = sim.stat(players[i], Loadout.STAT_HEALTH);
      // One tick's Minecraft hits: 3, 7.4 and 12 points (15, 37 and 60 Quake points).
      float[] minecraft = {3, 7.4f, 12};
      int[] expected = {15, 37, 60};
      for (int i = 0; i < 3; i++) sim.host.client(players[i]).minecraftDamage(minecraft[i]);
      sim.idle(10);

      for (int i = 0; i < 3; i++) {
        int p = players[i];
        assertEquals(0, sim.host.client(p).queuedDamage(), "client " + p + " still has damage");
        assertEquals(expected[i], delivered.getOrDefault(p, 0), "delivered to client " + p);
        int lost = before[i] - sim.stat(p, Loadout.STAT_HEALTH);
        // Plus at most the point a second that over-maximum spawn health counts down.
        assertTrue(
            lost >= expected[i] && lost <= expected[i] + 1,
            "client " + p + " lost " + lost + " instead of " + expected[i] + "\n" + sim.tail(30));
      }
      assertEquals(3, delivered.size(), "only the three players were touched: " + delivered);
      assertTrue(rearm.isEmpty(), () -> "triggers reused before re-arming: " + rearm);
    }
  }

  /** The hurt triggers linked now, as damage bits. */
  private static int linkedHurtMask(GameEntities g) {
    int first = SyntheticMapSource.FIRST_HOST_MODEL;
    int mask = 0;
    for (int e = GameAccess.MAX_CLIENTS; e < g.count(); e++) {
      if (!g.bmodel(e) || !g.linked(e)) continue;
      int bit = g.modelIndex(e) - first;
      if (bit >= 0 && bit < ExternalDamage.TRIGGERS) mask |= 1 << bit;
    }
    return mask;
  }
}
