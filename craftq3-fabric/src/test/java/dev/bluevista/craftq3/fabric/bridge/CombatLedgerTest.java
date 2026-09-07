package dev.bluevista.craftq3.fabric.bridge;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CombatLedgerTest {
  @Test
  void staleSnapshotsRetainOnlyUnacknowledgedDamage() {
    var ledger = new CombatLedger();
    var target = UUID.randomUUID();
    ledger.add(target, 7);
    var first = ledger.send();
    ledger.add(target, 7);
    assertEquals(14, ledger.outstanding(target));
    ledger.acknowledge(first.through());
    assertEquals(7, ledger.outstanding(target));
    var second = ledger.send();
    assertEquals(1, second.hits().size());
    assertEquals(2, second.hits().getFirst().sequence());
    ledger.acknowledge(second.through());
    assertEquals(0, ledger.outstanding(target));
  }

  @Test
  void sendsHitsOnceAndSeparatesTargets() {
    var ledger = new CombatLedger();
    var a = UUID.randomUUID();
    var b = UUID.randomUUID();
    ledger.add(a, 5);
    ledger.add(b, 9);
    assertEquals(2, ledger.send().hits().size());
    assertTrue(ledger.send().hits().isEmpty());
    assertEquals(5, ledger.outstanding(a));
    assertEquals(9, ledger.outstanding(b));
  }

  @Test
  void rejectsFutureOrRegressingAcknowledgementsAndInvalidDamage() {
    var ledger = new CombatLedger();
    var target = UUID.randomUUID();
    assertThrows(IllegalArgumentException.class, () -> ledger.add(target, Float.NaN));
    assertThrows(IllegalArgumentException.class, () -> ledger.add(target, -1));
    ledger.add(target, 1);
    assertThrows(IllegalArgumentException.class, () -> ledger.acknowledge(1));
    ledger.send();
    ledger.acknowledge(1);
    assertThrows(IllegalArgumentException.class, () -> ledger.acknowledge(0));
  }

  @Test
  void impulsesStayWithTheirHitsAcrossStaleAcknowledgementsAndAreSentOnce() {
    var ledger = new CombatLedger();
    var target = UUID.randomUUID();
    var firstImpulse = new Vec3(35, -20, 100);
    var secondImpulse = new Vec3(-300, 0, 150);
    ledger.add(target, 7, firstImpulse);
    var first = ledger.send();
    ledger.add(target, 20, secondImpulse);
    ledger.acknowledge(first.through());
    var second = ledger.send();
    assertEquals(firstImpulse, first.hits().getFirst().impulse());
    assertEquals(secondImpulse, second.hits().getFirst().impulse());
    assertEquals(20, ledger.outstanding(target));
    assertTrue(ledger.send().hits().isEmpty());
    ledger.acknowledge(second.through());
    assertEquals(0, ledger.outstanding(target));
  }

  @Test
  void invalidImpulsesDoNotIssuePartialHits() {
    var ledger = new CombatLedger();
    var target = UUID.randomUUID();
    assertThrows(IllegalArgumentException.class, () -> ledger.add(target, 7, null));
    assertThrows(
        IllegalArgumentException.class, () -> ledger.add(target, 7, new Vec3(0, -65537, 0)));
    assertEquals(0, ledger.outstanding(target));
    ledger.add(target, 7, new Vec3(35, 0, 0));
    assertEquals(1, ledger.send().hits().getFirst().sequence());
  }
}
