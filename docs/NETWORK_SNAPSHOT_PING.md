# Snapshot ping metadata

`RemoteConnection.snapshotPing(messageNumber)` supplies the ping field to `RemoteCgameSource`. The wire snapshot does not contain this value. It is computed when an accepted snapshot arrives, then retained by snapshot message number in a 32-slot lookup. An absent, future, or expired lookup returns 999.

## Native contract

An authored metadata observer called unchanged `CL_ParseSnapshot` with controlled `cl.outPackets`, outgoing channel sequence, `cls.realtime`, and player-state command time. The operation searches exactly 32 out-packet records, newest first, beginning at `(outgoingSequence - 1) & 31`. The first record whose `p_serverTime` is less than or equal to the snapshot's player-state `commandTime` is selected, using signed integer comparison.

The result is `cls.realtime - p_realtime`, with native 32-bit signed arithmetic. The initial value 999 survives only when no slot qualifies. There is no lower bound or 999 upper clamp. Packet command-number metadata and snapshot server time do not choose the record. In particular:

- Equality qualifies, and the newest eligible record wins even when an older record has a closer command time.
- Zero-initialized slots participate. With no recorded sends, command time 0 returns current realtime; negative command time returns 999.
- Controlled future send metadata can produce negative ping. Timer wraparound follows signed 32-bit subtraction.
- Records older than the 32-slot ring are unavailable.

The observer also called unchanged `CL_WritePacket`, recording its public transmit callback. The writer publishes the current packet slot at `outgoingSequence & 31`, its current command number, newest transmitted user-command time, and current realtime. A payload with no movement records server time 0, even when the most recent retained input has a different time. These fields are already written at the callback boundary.

## Pump integration and limits

The pump captures a message's newest command timestamp when it calls `session.queue`. Later inputs cannot alter that pending message's timestamp. The first successful UDP datagram send supplies its send time. For fragmented messages, one record is published after the final datagram succeeds, using the original message sequence and retained first-send time. A would-block attempt contributes no timestamp. The pump already defers inbound processing while output is pending, so an acknowledgement cannot select incomplete or unsent output.

The native selection and integer rules are retained by `SnapshotPingHistory`. The pump's scheduling is independently documented in [NETWORK_REMOTE_CONNECTION.md](NETWORK_REMOTE_CONNECTION.md); no packet-byte or cadence parity is implied by matching ping selection. Real elapsed RTT for a selected sent record is measured in the host's monotonic time domain.

For a stable initial epoch, the adapter subtracts its constructor's `startMillis` before projecting monotonic times to native 32-bit realtime. This epoch differs from a native process that has already been running before connection. It affects fallback values from zero-initialized slots, while RTTs between populated records are epoch-independent. A new gamestate clears packet metadata and snapshot ping results, retaining the connection epoch and channel sequence. Ordered snapshot/gamestate operations in the same payload are respected.

## Evidence and reproduction

The reference is unchanged ioquake3 commit `588393618dbc82e7207c21c6ddecca229944a03a`. Only declared metadata and exported operation signatures were inspected; native function bodies were not read or translated.

[SnapshotPingOracle.c](../scripts/SnapshotPingOracle.c) reuses the authored `SnapshotOracle.c` host and creates complete synthetic snapshot bodies through unchanged native message writers. [BuildSnapshotPingOracle.py](../scripts/BuildSnapshotPingOracle.py) compiles unchanged parser, input writer, message, Huffman, and channel units. There are no real assets, network connections, engine command execution, or client windows in this fixture.

[AuditSnapshotPings.java](../scripts/AuditSnapshotPings.java) compares the production selector against **20,000 native snapshot parses**, varying ring wrap, zero through 70 preceding packet records, signed command times, signed realtime wraparound, and unrelated command-number/server-time fields. All 20,000 comparisons are exact. Four additional writer captures check the slot and metadata publication contract.

Four focused selector tests cover newest/equal eligibility, the zero-filled and no-match cases, non-clamped signed arithmetic, exact ring bounds, message lookup expiry, and gamestate clearing. Two pump regressions verify a command queued before would-block retains its own timestamp and a fragmented message measures from its first successful datagram. The complete pump suite contains 12 tests.

```sh
python3 scripts/BuildSnapshotPingOracle.py
javac -cp craftq3-client/build/classes/java/main -d .tools/snapshot-ping-oracle/classes scripts/AuditSnapshotPings.java
java -cp craftq3-client/build/classes/java/main:.tools/snapshot-ping-oracle/classes dev.bluevista.craftq3.client.net.AuditSnapshotPings .tools/snapshot-ping-oracle/probe
./gradlew :craftq3-client:test --tests dev.bluevista.craftq3.client.net.RemoteConnectionTest --tests dev.bluevista.craftq3.client.net.SnapshotPingHistoryTest
```

The observer and native objects remain development-only inputs and are not bundled.
