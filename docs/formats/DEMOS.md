# Demo records and protocol-68 decoding

`core.demo` reads and writes one record at a time: little-endian 32-bit server sequence, little-endian 32-bit payload length, then that many opaque server-message bytes. A canonical −1/−1 header ends the stream. These records contain no netchan fragment headers.

This framing applies to the user's original `.dm3` files and modern `.dm_68` records, but their payload protocols differ. `Protocol68DemoReader` adds bounded protocol-68 server-message decoding; original retail demo payload decoding remains separate work. There is no demo playback or recording command yet. `DemoWriter` supplies record framing, not the required initial gamestate or a live recording lifecycle.

Each payload is capped at 16384 bytes before allocation. Truncated headers/payloads fail explicitly and poison the reader to prevent accidental retries. EOF exactly at a record boundary is distinguished from the clean end marker, allowing a future UI to report an interrupted recording. Record payloads are owned copies; reader/writer own their streams. A failed write does not produce a misleading clean end marker on close.

Five authored tests cover exact bytes, zero/maximal payloads, sequence gaps, end markers, boundary EOF, truncated data, invalid lengths, buffer ownership and sink failure. The optional `scripts/AuditDemos.java` reads original demos directly through the PK3 VFS and rewrites them only in memory. Both original files matched byte-for-byte:

| Original file | Records | First / last sequence | Largest payload | File bytes |
| --- | ---: | --- | ---: | ---: |
| demos/demo001.dm3 | 1348 | 8380 / 9728 | 3551 | 124801 |
| demos/demo002.dm3 | 1401 | 9534 / 10936 | 1521 | 126266 |

These are framing checks, not visual/game-state validation. Sequence gaps are retained as recorded. Both files ended with the canonical marker.

```sh
java -cp craftq3-core/build/classes/java/main:craftq3-assets/build/classes/java/main \
  scripts/AuditDemos.java run/craftq3/games
```

## Protocol-68 record decoding

`Protocol68DemoReader(InputStream)` owns the stream and returns one
`DecodedRecord(sequence, ServerMessageCodec.Message)` at a time. The caller must identify the file
as protocol 68. There is no content-based protocol guessing or conversion of retail demo messages.
The reader retains level baselines and a bounded snapshot history, drops history on gamestate
reset, and exposes the latest decoded snapshot and last transmitted gamestate. Reliable commands
remain data: configstring commands, game commands and system-info effects are not applied here.

Each complete packet is parsed before its state is published. Malformed/unsupported payloads or
missing delta history fail the reader with an `IOException` identifying the record, retaining the
previous completed state. Retrying a failed or closed reader is rejected. Clean markers and physical
EOF retain the distinction from the framing layer. History keeps at most 32 snapshots, dropping
entries outside the newest snapshot's 32-message window after decoding that frame. No renderer,
QVM startup, playback clock, seeking or audio is provided by this data reader.

Four focused tests cover level/full/delta sequences, deferred commands, reset and state ownership,
missing/expired history, failure without partial publication, stream ownership and end conditions.
`AuditServerMessages.java` additionally frames its **1,500 native-checked payloads** into an authored
**566,407-byte** in-memory demo and decodes the stream. Every level reset, player/entity/area state
and snapshot metadata result is retained. This validates the decoder against the independently
checked message corpus; it is not an original `.dm3` playback or visual comparison.

```sh
java -cp craftq3-core/build/classes/java/main scripts/AuditServerMessages.java
```

The server payload API, native parser observers and build/reproduction steps are documented in
[NETWORK68_MESSAGES.md](NETWORK68_MESSAGES.md).
