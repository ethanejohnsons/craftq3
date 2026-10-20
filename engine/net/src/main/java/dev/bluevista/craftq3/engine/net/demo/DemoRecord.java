package dev.bluevista.craftq3.engine.net.demo;

import dev.bluevista.craftq3.engine.net.Protocol68;

/** One demo's message sequence and opaque server-message bytes, without netchan headers. */
public record DemoRecord(int sequence, byte[] payload) {
  public DemoRecord {
    if (payload.length > Protocol68.MAX_MSGLEN)
      throw new IllegalArgumentException("Demo message exceeds 16 KiB");
    payload = payload.clone();
  }

  @Override
  public byte[] payload() {
    return payload.clone();
  }
}
