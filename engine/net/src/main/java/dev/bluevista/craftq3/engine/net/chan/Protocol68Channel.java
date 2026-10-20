package dev.bluevista.craftq3.engine.net.chan;

import dev.bluevista.craftq3.engine.net.Protocol68;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Protocol 68 sequencing and fragmentation of opaque messages. The caller validates and routes the
 * remote address, schedules packets, and encodes the message payload. This class owns no socket.
 */
public final class Protocol68Channel {
  /** Highest usable sequence; the channel refuses to wrap into the fragment bit. */
  private static final int LAST_SEQUENCE = 0x7ffffffe;

  private static final int SEQUENCE_BYTES = 4, QPORT_BYTES = 2, FRAGMENT_HEADER_BYTES = 4;

  public enum Endpoint {
    CLIENT,
    SERVER
  }

  public enum Status {
    COMPLETE,
    PARTIAL,
    /** A sequence at or below the last completed one, or an abandoned older fragment. */
    STALE,
    /** A fragment that does not continue the current assembly. */
    OUT_OF_ORDER,
    MALFORMED,
    WRONG_QPORT,
    CONNECTIONLESS;

    /** Ordinary UDP loss or reordering, as opposed to hostile or misrouted input. */
    public boolean isDrop() {
      return this == STALE || this == OUT_OF_ORDER;
    }
  }

  public record Received(Status status, int sequence, int dropped, byte[] payload) {
    public Received {
      Objects.requireNonNull(status);
      payload = payload.clone();
    }

    @Override
    public byte[] payload() {
      return payload.clone();
    }
  }

  private static final byte[] EMPTY = new byte[0];

  private final Endpoint endpoint;
  private final int qport;
  private final byte[] assembly = new byte[Protocol68.MAX_MSGLEN];
  private int incomingSequence;
  private int outgoingSequence = 1;
  private int assemblySequence;
  private int assemblyLength;
  private byte[] outgoing;
  private int outgoingOffset;

  public Protocol68Channel(Endpoint endpoint, int qport) {
    this.endpoint = Objects.requireNonNull(endpoint);
    if (qport < 0 || qport > 65535) throw new IllegalArgumentException("qport is unsigned 16-bit");
    this.qport = qport;
  }

  public int incomingSequence() {
    return incomingSequence;
  }

  public int outgoingSequence() {
    return outgoingSequence;
  }

  public boolean hasPendingPacket() {
    return outgoing != null;
  }

  /** Takes a copy; the caller must finish sending a message before queuing the next one. */
  public void queue(byte[] message) {
    Objects.requireNonNull(message);
    if (message.length > Protocol68.MAX_MSGLEN)
      throw new IllegalArgumentException("Message exceeds 16 KiB");
    if (outgoing != null)
      throw new IllegalStateException("Previous message still has queued packets");
    if (outgoingSequence > LAST_SEQUENCE)
      throw new IllegalStateException("Channel sequence exhausted; reconnect before wrapping");
    outgoing = message.clone();
    outgoingOffset = 0;
  }

  /** Produces one datagram. Exact multiples of 1300 require a final empty fragment. */
  public Optional<byte[]> pollPacket() {
    if (outgoing == null) return Optional.empty();
    boolean fragmented = outgoing.length >= Protocol68.FRAGMENT_SIZE;
    int size =
        fragmented
            ? Math.min(Protocol68.FRAGMENT_SIZE, outgoing.length - outgoingOffset)
            : outgoing.length;
    int header =
        SEQUENCE_BYTES
            + (endpoint == Endpoint.CLIENT ? QPORT_BYTES : 0)
            + (fragmented ? FRAGMENT_HEADER_BYTES : 0);
    ByteBuffer packet = ByteBuffer.allocate(header + size).order(ByteOrder.LITTLE_ENDIAN);
    packet.putInt(outgoingSequence | (fragmented ? Protocol68.FRAGMENT_BIT : 0));
    if (endpoint == Endpoint.CLIENT) packet.putShort((short) qport);
    if (fragmented) packet.putShort((short) outgoingOffset).putShort((short) size);
    packet.put(outgoing, outgoingOffset, size);
    outgoingOffset += size;
    if (!fragmented || size < Protocol68.FRAGMENT_SIZE) {
      outgoing = null;
      outgoingSequence++;
    }
    return Optional.of(packet.array());
  }

  /**
   * Bounds/identity checks precede mutation. Invalid UDP input is an explicit result, not an
   * exception. Fragments are consecutive; retransmission/reliability belongs to higher layers.
   */
  public Received receive(byte[] datagram) {
    Objects.requireNonNull(datagram);
    if (datagram.length < SEQUENCE_BYTES || datagram.length > Protocol68.MAX_PACKETLEN)
      return result(Status.MALFORMED, 0);
    ByteBuffer packet = ByteBuffer.wrap(datagram).order(ByteOrder.LITTLE_ENDIAN);
    int word = packet.getInt();
    if (word == -1) return result(Status.CONNECTIONLESS, 0);
    boolean fragmented = (word & Protocol68.FRAGMENT_BIT) != 0;
    int sequence = word & ~Protocol68.FRAGMENT_BIT;
    if (sequence == 0 || sequence > LAST_SEQUENCE) return result(Status.MALFORMED, sequence);
    int remainingHeader =
        (endpoint == Endpoint.SERVER ? QPORT_BYTES : 0) + (fragmented ? FRAGMENT_HEADER_BYTES : 0);
    if (packet.remaining() < remainingHeader) return result(Status.MALFORMED, sequence);
    if (endpoint == Endpoint.SERVER && Short.toUnsignedInt(packet.getShort()) != qport)
      return result(Status.WRONG_QPORT, sequence);
    if (!fragmented) {
      if (sequence <= incomingSequence) return result(Status.STALE, sequence);
      byte[] message = new byte[packet.remaining()];
      packet.get(message);
      return complete(sequence, message);
    }
    return receiveFragment(packet, sequence);
  }

  private Received receiveFragment(ByteBuffer packet, int sequence) {
    int start = Short.toUnsignedInt(packet.getShort());
    int length = Short.toUnsignedInt(packet.getShort());
    if (length > Protocol68.FRAGMENT_SIZE
        || length != packet.remaining()
        || start > Protocol68.MAX_MSGLEN - length) return result(Status.MALFORMED, sequence);
    if (sequence <= incomingSequence) return result(Status.STALE, sequence);
    // An older incomplete message must not discard progress on a newer assembly.
    if (sequence < assemblySequence) return result(Status.STALE, sequence);
    if (sequence != assemblySequence) {
      assemblySequence = sequence;
      assemblyLength = 0;
    }
    if (start != assemblyLength) return result(Status.OUT_OF_ORDER, sequence);
    packet.get(assembly, start, length);
    assemblyLength += length;
    if (length == Protocol68.FRAGMENT_SIZE) return result(Status.PARTIAL, sequence);
    return complete(sequence, Arrays.copyOf(assembly, assemblyLength));
  }

  private Received complete(int sequence, byte[] message) {
    int dropped = sequence - incomingSequence - 1;
    incomingSequence = sequence;
    assemblySequence = 0;
    assemblyLength = 0;
    return new Received(Status.COMPLETE, sequence, dropped, message);
  }

  private Received result(Status status, int sequence) {
    return new Received(status, sequence, 0, EMPTY);
  }
}
