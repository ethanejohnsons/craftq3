# Host-driven remote connection

`RemoteConnection` composes the verified protocol-68 handshake/session codecs and `UdpTransport`. It owns one nonblocking socket connected to an explicitly resolved peer. It performs no DNS lookup, discovery, background work, blocking receive, sleep, or game-command execution.

```java
var connection = RemoteConnection.open(peer, userInfo, nonce, qport, monotonicStartMillis);
var result = connection.pump(monotonicNowMillis);
connection.session().ifPresent(session -> {
  // Borrow for RemoteCgameSource, reliable commands, and wire-state inspection.
});
```

The host drives all updates on one thread. `pump` returns immutable, bounded events and datagram counts. A `MESSAGE` event contains the accepted `Protocol68ClientSession.Received` value and its `arrivalMillis`; its original ordered operations include any snapshots. The latest snapshot number and arrival time are also exposed directly, and reset at each new gamestate. A snapshot preceding a later gamestate in the same message does not survive that reset. `snapshotPing(messageNumber)` supplies a bounded lookup of the [native-observed ping estimate](NETWORK_SNAPSHOT_PING.md), using successful sends associated with that snapshot's acknowledged input time.

`session()` becomes available after `connectResponse`. Cgame must wait for a gamestate before initialization or input. The pump alone owns the session's `queue`, `pollPacket`, and `receive` methods. The host and cgame may inspect the borrowed session and queue reliable text with `command`. Presented server IDs remain under the session/source contract; the pump does not substitute wire configstrings for the consumed cgame view.

## Scheduling and ownership

The default policy retries a challenge or connect request after 3 seconds, imposes a 15-second total connection deadline, times out after 30 seconds without valid peer activity, and schedules established packets every 50 milliseconds. Settings expose bounded alternatives for all four times plus receive/send work per update. Defaults allow at most 32 received and 16 sent datagrams per pump. Deadlines are checked before pending output is sent. Host monotonic time may stay equal between pumps but cannot go backward.

Requests remain byte-identical across retries. An established packet retransmits unacknowledged reliable commands through the session. It contains no movement before a gamestate. The pump retains up to 32 newest canonical user-command records and repeats them in scheduled movement packets, providing bounded packet-loss redundancy. Angles/buttons are projected to their 16-bit wire fields, and signed movement -128 is canonicalized to -127. Source server times remain signed values passed to the existing codec.

Equal command timestamps replace the last redundant sample. A backward server-time correction clears this redundancy deque before accepting the new sample. Native clock observations permit such corrections within the same gamestate; they must not crash input handling. This policy leaves `Q3Client`'s separate command history untouched. Every new gamestate clears the pump's redundant input history as well.

This is an explicit initial adapter policy. It does **not** establish native packet cadence, native packet-dup selection, or whole-engine clock parity.

When a UDP send would block, the exact owned datagram remains pending. Later updates retry it before taking another datagram from the session. All pending fragments are sent in order before new messages are queued. Inbound processing waits until this pending output is accepted or the connection times out. This matters because returning the final session fragment advances its reliable acknowledgement ceiling: a peer acknowledgement cannot be processed before local socket acceptance. It also prevents a recognized new gamestate from being followed by previously staged movement for the old level.

## Events and lifecycle

The connected UDP socket pins the complete remote address and port. Before mutable session processing, datagrams pass the stateless envelope classifier. Only accepted messages, valid partial fragment assemblies, recognized handshake responses, and parsed `print` envelopes refresh activity. Malformed/oversized/empty datagrams, stale packets, and unrelated commands do not extend a timeout.

`PRINT` is inert text, including a denial message received while connecting. Reliable commands remain part of accepted message events for cgame's ordered consumption. An OOB `disconnect`, `exec`, or other unrecognized command has no execution effect. Deadline expiration yields `TIMED_OUT`; transport failure yields `FAILED` and a typed diagnostic. Both release the owned endpoint. No received command is passed to the engine command buffer by this layer.

`disconnect()` queues the reliable disconnect once and stops subsequent movement, without waiting. The host may continue pumping to retransmit it. `disconnectAndClose(now)` performs one bounded best-effort flush and then closes, even when the reliable window is full or the socket would block. A blocked or too-large backlog can prevent that attempt from reaching the server; this method does not promise delivery. `close()` immediately closes the endpoint and is safe to repeat.

## Verification

`RemoteConnectionTest` contains twelve focused tests. The real IPv4 loopback scenario exercises a deliberately dropped challenge, exact retry, compressed connect, pre-gamestate keepalive, fragmented gamestate, timestamped snapshot events, movement, reliable retransmission and acknowledgement, a new level with fresh input, and inactivity timeout.

An injected pinned endpoint verifies deterministic would-block retention, acknowledgement deferral, fragment/read/send limits, canonical 32-record redundancy, backward clock correction, inert connecting denial text, terminal I/O handling, strict pending-output deadlines, ordered snapshot/gamestate metadata, best-effort disconnect, and ping timing across would-block and fragmented sends. Four additional selector tests and 20,000 native comparisons verify the ping selection rule. No external game server or client window is used by these tests. The pump adds scheduling and ownership behavior; it reuses the native-verified codecs and transport rather than duplicating their algorithms.

```sh
./gradlew :craftq3-client:test --tests dev.bluevista.craftq3.client.net.RemoteConnectionTest
```

Related contracts: [protocol session](NETWORK68_CLIENT_SESSION.md), [UDP transport](NETWORK_UDP_TRANSPORT.md), [connect compression](NETWORK68_CONNECT_COMPRESSION.md), and [cgame command consumption](NETWORK68_CGAME_COMMANDS.md).
