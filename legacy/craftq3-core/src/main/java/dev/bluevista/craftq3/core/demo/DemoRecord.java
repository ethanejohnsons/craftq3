package dev.bluevista.craftq3.core.demo;

import dev.bluevista.craftq3.core.net.Protocol68Channel;

/** One demo's message sequence and opaque server-message bytes, without netchan headers. */
public record DemoRecord(int sequence, byte[] payload) {
  public DemoRecord {
    if (payload.length > Protocol68Channel.MAX_MESSAGE)
      throw new IllegalArgumentException("Demo message exceeds 16 KiB");
    payload = payload.clone();
  }

  @Override
  public byte[] payload() {
    return payload.clone();
  }
}
