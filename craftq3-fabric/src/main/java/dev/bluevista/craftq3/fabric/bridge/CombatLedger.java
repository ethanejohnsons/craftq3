package dev.bluevista.craftq3.fabric.bridge;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.*;

/** Unacknowledged damage prevents an older Minecraft snapshot from healing a Quake proxy. */
final class CombatLedger {
  record Hit(long sequence, UUID target, float amount, Vec3 impulse) {}

  record Batch(long through, List<Hit> hits) {
    Batch {
      hits = List.copyOf(hits);
    }
  }

  private final ArrayDeque<Hit> pending = new ArrayDeque<>();
  private long issued, sent, acknowledged;

  void add(UUID target, float amount) {
    add(target, amount, new Vec3(0, 0, 0));
  }

  void add(UUID target, float amount, Vec3 impulse) {
    if (target == null
        || impulse == null
        || Math.abs(impulse.x()) > 65536
        || Math.abs(impulse.y()) > 65536
        || Math.abs(impulse.z()) > 65536
        || !Float.isFinite(amount)
        || amount <= 0
        || amount > 1_000_000
        || pending.size() >= 4096)
      throw new IllegalArgumentException("Invalid or excessive bridge damage");
    pending.add(new Hit(++issued, target, amount, impulse));
  }

  Batch send() {
    var hits = pending.stream().filter(hit -> hit.sequence() > sent).toList();
    sent = issued;
    return new Batch(sent, hits);
  }

  void acknowledge(long through) {
    if (through < acknowledged || through > sent)
      throw new IllegalArgumentException("Invalid bridge damage acknowledgement");
    acknowledged = through;
    while (!pending.isEmpty() && pending.getFirst().sequence() <= through) pending.removeFirst();
  }

  float outstanding(UUID target) {
    double amount = 0;
    for (var hit : pending) if (hit.target().equals(target)) amount += hit.amount();
    return (float) Math.min(amount, Float.MAX_VALUE);
  }
}
