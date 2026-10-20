package dev.bluevista.craftq3.engine.net.demo;

import dev.bluevista.craftq3.engine.net.NetFormatException;

/** A demo whose protocol is known but whose message stream this module cannot decode. */
public final class UnsupportedDemoProtocolException extends NetFormatException {
  private static final long serialVersionUID = 1L;

  private final int protocol;

  public UnsupportedDemoProtocolException(int protocol, String message) {
    super(message);
    this.protocol = protocol;
  }

  public int protocol() {
    return protocol;
  }
}
