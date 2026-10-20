package dev.bluevista.craftq3.engine.game;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.game.net.Protocol68Host;
import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Boots the original baseq3 qagame without bots and plays a loopback client through it. */
@RealData
final class RealBootTest {
  @Test
  void baseq3BootsAndALoopbackClientPlays() throws Exception {
    try (RealGame game = new RealGame(RealDataRoot.require(), "baseq3", null)) {
      game.server.cvars().set("bot_enable", "0");
      assertTrue(game.server.spawn("q3dm17", false), game.tail(40));
      assertEquals(GameServer.State.RUNNING, game.server.state(), game.server.dropReason());
      Harness view = new Harness();
      Protocol68Host host =
          new Protocol68Host(game.server, name -> Optional.empty(), new Random(3));
      TestClient client = new TestClient(view, host, host.openLoopback());
      view.now = game.now;
      // Drive the real server's clock from the client's frames.
      assertTrue(
          client.connect(
              Map.of(
                  "name",
                  "Visitor",
                  "rate",
                  "25000",
                  "snaps",
                  "20",
                  "model",
                  "sarge/default",
                  "headmodel",
                  "sarge/default",
                  "handicap",
                  "100",
                  "sex",
                  "male"),
              777),
          String.valueOf(client.printed));
      for (int i = 0; i < 200; i++) client.step(i % 40 < 20 ? 127 : -127);
      assertEquals(
          GameServer.State.RUNNING,
          game.server.state(),
          game.server.dropReason() + "\n" + game.tail(40));
      assertEquals(ServerClient.State.ACTIVE, game.server.client(0).state(), game.tail(40));
      assertTrue(client.session.snapshot().isPresent());
      assertEquals(0, game.server.degradationTotal(), game.tail(40));
      System.out.println(
          "baseq3 boot: "
              + game.server.profile().orElseThrow()
              + ", frames "
              + game.server.framesRun());
    }
  }
}
