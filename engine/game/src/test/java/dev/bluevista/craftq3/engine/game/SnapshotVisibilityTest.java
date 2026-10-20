package dev.bluevista.craftq3.engine.game;

import static dev.bluevista.craftq3.engine.game.world.Contents.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.game.snapshot.ClientSnapshot;
import dev.bluevista.craftq3.engine.vmabi.QagameImport;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class SnapshotVisibilityTest {
  private static final float[] MINS = {-8, -8, -8}, MAXS = {8, 8, 8};

  /** A spawned server with player 0 in the world at x = -200 (area 0, cluster 0). */
  private static Harness withPlayer() {
    Harness h = new Harness().spawn();
    h.server.connect("\\name\\P", "1.1.1.1:1", false, -1);
    h.server.gamestateSent(0);
    h.server.userMove(0, List.of(GameServerTest.cmd(500, 0)));
    return h;
  }

  private static List<Integer> visible(Harness h) {
    ClientSnapshot snap = h.server.snapshot(0);
    return Arrays.stream(snap.entityNumbers()).boxed().toList();
  }

  @Test
  void pvsAndAreaPortalsDecideWhatIsSent() {
    Harness h = withPlayer();
    h.game.spawn(8, new float[] {-400, 0, 24}, MINS, MAXS, 0, 0); // same area
    h.game.spawn(9, new float[] {300, 0, 24}, MINS, MAXS, 0, 0); // area 1, cluster 1 (in PVS)
    h.game.spawn(10, new float[] {1000, 0, 24}, MINS, MAXS, 0, 0); // area 2, cluster 2 (not in PVS)
    assertEquals(List.of(8, 14), visible(h), "the closed door hides area 1");
    h.game.sys(QagameImport.G_ADJUST_AREA_PORTAL_STATE, FakeGame.entity(14), 1);
    assertEquals(List.of(8, 9, 14), visible(h));
    // The area mask hides area 2 only; set bits are hidden areas.
    byte[] mask = h.server.snapshot(0).areaMask();
    assertEquals(1, mask.length);
    assertEquals(0b100, mask[0] & 0b111);
    h.game.sys(QagameImport.G_ADJUST_AREA_PORTAL_STATE, FakeGame.entity(14), 0);
    assertEquals(List.of(8, 14), visible(h));
    assertFalse(visible(h).contains(0), "the client's own entity is never sent");
  }

  @Test
  void audienceFlags() {
    Harness h = withPlayer();
    h.game.spawn(8, new float[] {-300, 0, 24}, MINS, MAXS, 0, SVF_NOCLIENT);
    h.game.spawn(9, new float[] {-300, 0, 24}, MINS, MAXS, 0, SVF_SINGLECLIENT);
    h.game.setSingleClient(9, 0);
    h.game.spawn(10, new float[] {-300, 0, 24}, MINS, MAXS, 0, SVF_SINGLECLIENT);
    h.game.setSingleClient(10, 3);
    h.game.spawn(11, new float[] {-300, 0, 24}, MINS, MAXS, 0, SVF_NOTSINGLECLIENT);
    h.game.setSingleClient(11, 0);
    h.game.spawn(12, new float[] {-300, 0, 24}, MINS, MAXS, 0, SVF_CLIENTMASK);
    h.game.setSingleClient(12, 1);
    h.game.spawn(13, new float[] {-300, 0, 24}, MINS, MAXS, 0, SVF_CLIENTMASK);
    h.game.setSingleClient(13, 2);
    h.game.spawn(15, new float[] {1000, 0, 24}, MINS, MAXS, 0, SVF_BROADCAST);
    assertEquals(List.of(9, 12, 14, 15), visible(h));
  }

  @Test
  void portalSurfacesAddTheViewAtTheirOtherOrigin() {
    Harness h = withPlayer();
    h.game.spawn(10, new float[] {1000, 0, 24}, MINS, MAXS, 0, 0);
    h.game.spawn(8, new float[] {-300, 0, 24}, MINS, MAXS, 0, SVF_PORTAL);
    assertEquals(List.of(8, 14), visible(h));
    h.game.setOrigin2(8, new float[] {900, 0, 24});
    assertEquals(List.of(8, 10, 14), visible(h));
    byte[] mask = h.server.snapshot(0).areaMask();
    assertEquals(0, mask[0] & 0b101, "both views' areas are visible");
  }

  @Test
  void unlinkedEntitiesAreNeverSentAndNumbersAreFixedUp() {
    Harness h = withPlayer();
    h.game.spawn(8, new float[] {-300, 0, 24}, MINS, MAXS, 0, 0);
    h.game.memory().writeInt(FakeGame.entity(8), 99);
    ClientSnapshot snap = h.server.snapshot(0);
    assertEquals(8, snap.entityNumbers()[0]);
    assertEquals(8, h.game.memory().readInt(FakeGame.entity(8)));
    h.game.unlink(8);
    assertEquals(List.of(14), visible(h));
  }
}
