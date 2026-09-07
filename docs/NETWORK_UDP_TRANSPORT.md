# Pinned nonblocking UDP transport

`dev.bluevista.craftq3.platform.net.UdpTransport` owns one nonblocking
`DatagramChannel`. `open(InetSocketAddress)` requires an already resolved unicast
address and nonzero port, selects IPv4 or IPv6 from that address, and connects
the channel to that exact peer. It performs no DNS, discovery, challenge exchange
or protocol negotiation. The OS assigns an ephemeral local port.

The connected socket filters datagrams from other remote addresses or ports.
`peer()` and `localAddress()` expose the pinned and local endpoints for the
owning session. The channel cannot be retargeted through this API.

`send(byte[])` accepts 1–16,384 bytes and retains no caller buffer. It returns
`true` when the entire datagram is accepted by the local socket, and `false` when
a nonblocking write returns zero. The owner decides whether and when to retry.
Local acceptance is not an acknowledgement of remote delivery. A partial write
is an explicit I/O failure; it never sends a remainder as a second datagram.

`poll()` performs at most one nonblocking receive and distinguishes:

| Status | Meaning | Payload |
| --- | --- | --- |
| `EMPTY` | No packet was available | Empty |
| `PACKET` | One packet of 1–16,384 bytes | Owned copy |
| `OVERSIZE` | One larger datagram was consumed and discarded | Empty |
| `EMPTY_DATAGRAM` | One actual zero-byte UDP datagram was consumed | Empty |

The fixed receive buffer has 16,385 bytes. Its extra byte detects an oversized
datagram even when the OS truncates a much larger datagram to that buffer. A
valid 16,384-byte message is therefore distinguishable from truncation. The
transport does not parse, decompress or mutate any connection/channel state.

Returned `Poll` values defensively own their byte arrays, and `payload()` returns
a copy. Subsequent reads do not change an earlier result. The receive buffer is
reused internally, so the owner must confine this object to its caller thread.
There is no selector, worker or background draining loop. `close()` is idempotent;
later I/O reports a closed channel. A failed open closes the partially created
channel and preserves the original failure.

## Loopback validation

Five focused `UdpTransportTest` tests pass under Java 25 with the project's lint
policy. They use only explicitly constructed IPv4 `127.0.0.1` and IPv6 `::1`
addresses and ephemeral ports; no DNS or external server is involved.

The tests exercise both directions of actual IPv4/IPv6 traffic, inject eight
packets from a different source port and verify only the pinned peer is received,
distinguish zero-byte packets from no packet, discard both 16,385-byte and
20,480-byte packets, and round-trip a full 16,384-byte message. They also verify
owned arrays across caller mutations and subsequent receives, input bounds,
unresolved/wildcard/zero-port rejection, repeated close and closed-channel I/O.

The send helper handles a `false` return with a bounded retry. The loopback test
does not claim to force an operating-system send-buffer saturation event; the
would-block branch directly represents the NIO write return contract.

The focused result is recorded in `/tmp/craftq3-udp-transport-tests.log`:

```sh
./gradlew :craftq3-platform:test --tests '*UdpTransportTest'
```

Protocol-68 handshake and real native-server interoperability are separate
session-level work. This transport introduces no production server discovery,
socket worker, user configuration or original asset changes.
