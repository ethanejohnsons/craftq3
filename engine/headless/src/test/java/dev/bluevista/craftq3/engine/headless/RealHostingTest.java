package dev.bluevista.craftq3.engine.headless;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.client.pipeline.ClientSnapshot;
import dev.bluevista.craftq3.engine.client.pipeline.SnapshotPipeline;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec;
import dev.bluevista.craftq3.engine.session.CommandLine;
import dev.bluevista.craftq3.engine.session.SessionState;
import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * HOSTING with the original baseq3 VMs: one headless session hosts q3dm17 on UDP localhost, a
 * second one connects over real UDP; each sees the other player in its snapshots and both players'
 * configstrings.
 */
@RealData
final class RealHostingTest {
  /** baseq3 {@code CS_PLAYERS} (bg_public.h). */
  private static final int CS_PLAYERS = 544;

  @Test
  void aSecondClientJoinsOverUdp() throws Exception {
    HeadlessClient.Options base =
        HeadlessClient.options(RealDataRoot.require()).withManualClock(false).withFrameMillis(10);
    try (HeadlessClient host =
            HeadlessClient.start(
                base.withNetEnabled(true)
                    .withCommandLine(
                        CommandLine.parse(
                            "+set vm_strictSyscalls 1 +set sv_pure 0 +set net_ip 127.0.0.1 +set net_port 0 +set name Hoster")));
        HeadlessClient guest =
            HeadlessClient.start(
                base.withSeed(43)
                    .withCommandLine(
                        CommandLine.parse("+set vm_strictSyscalls 1 +set name Guest")))) {
      host.command("map q3dm17");
      assertTrue(host.runUntil(SessionState.HOSTING, 120_000), host.tail(30));
      int port = host.session().hostedAddress().orElseThrow().getPort();
      guest.command("connect 127.0.0.1:" + port);
      long end = System.nanoTime() + 120_000_000_000L;
      while (System.nanoTime() < end && guest.state() != SessionState.REMOTE) {
        host.frame();
        guest.frame();
      }
      assertEquals(SessionState.REMOTE, guest.state(), guest.tail(30));
      int hostNumber = clientNumber(host), guestNumber = clientNumber(guest);
      assertNotEquals(hostNumber, guestNumber);
      boolean hostSeesGuest = false, guestSeesHost = false;
      end = System.nanoTime() + 30_000_000_000L;
      while (System.nanoTime() < end && !(hostSeesGuest && guestSeesHost)) {
        host.frame();
        guest.frame();
        hostSeesGuest |= sees(host, guestNumber);
        guestSeesHost |= sees(guest, hostNumber);
      }
      assertEquals(SessionState.HOSTING, host.state(), host.tail(30));
      assertEquals(SessionState.REMOTE, guest.state(), guest.tail(30));
      assertTrue(hostSeesGuest, "the host's snapshots carry the guest");
      assertTrue(guestSeesHost, "the guest's snapshots carry the host");
      String guestInfo =
          guest.session().pipeline().orElseThrow().configString(CS_PLAYERS + hostNumber);
      assertTrue(guestInfo.contains("Hoster"), guestInfo);
      assertEquals(2, host.session().serverStatus().orElseThrow().humans());
      assertEquals(0, host.session().degradations(), host.tail(30));
      assertEquals(0, guest.session().degradations(), guest.tail(30));
      guest.command("disconnect");
      guest.run(200);
      host.run(1_000);
      assertEquals(SessionState.MENU, guest.state());
      assertEquals(1, host.session().serverStatus().orElseThrow().humans(), host.tail(10));
    }
  }

  private static int clientNumber(HeadlessClient client) {
    return client
        .session()
        .pipeline()
        .flatMap(SnapshotPipeline::gameState)
        .map(ServerMessageCodec.GameState::clientNumber)
        .orElseThrow();
  }

  private static boolean sees(HeadlessClient client, int other) {
    Optional<ClientSnapshot> latest =
        client.session().pipeline().flatMap(SnapshotPipeline::latestSnapshot);
    if (latest.isEmpty()) return false;
    for (byte[] entity : latest.get().snapshot().entities())
      if (ByteBuffer.wrap(entity).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == other) return true;
    return false;
  }
}
