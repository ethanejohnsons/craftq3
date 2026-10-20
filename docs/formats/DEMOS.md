# Demo files

A demo is the stream of server messages a client received, framed as records. CraftQ3 plays the
demo protocols found in Quake III installations, from the 1999 releases to 1.32, and records
protocol 68.

Code:
[`DemoProtocol`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/demo/DemoProtocol.java),
[`DemoRecordReader`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/demo/DemoRecordReader.java),
[`DemoReader`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/demo/DemoReader.java),
[`DemoWriter`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/demo/DemoWriter.java),
[`DemoRecord`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/demo/DemoRecord.java),
[`UnsupportedDemoProtocolException`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/demo/UnsupportedDemoProtocolException.java);
client side
[`DemoPlayback`](../../engine/client/src/main/java/dev/bluevista/craftq3/engine/client/demo/DemoPlayback.java),
[`DemoRecorder`](../../engine/client/src/main/java/dev/bluevista/craftq3/engine/client/demo/DemoRecorder.java),
[`Protocol68DemoRecorder`](../../engine/client/src/main/java/dev/bluevista/craftq3/engine/client/demo/Protocol68DemoRecorder.java),
[`DemoServerClock`](../../engine/client/src/main/java/dev/bluevista/craftq3/engine/client/pipeline/DemoServerClock.java);
console commands in
[`EngineSession`](../../engine/session/src/main/java/dev/bluevista/craftq3/engine/session/EngineSession.java).

## Record framing

Identical for every protocol: a little-endian 32-bit message sequence, a little-endian 32-bit
payload length, then that many bytes of server message (the clear payload, after netchan
reassembly and payload XOR). There are no netchan headers. A record with length -1 (and sequence
-1) ends the demo.

- Payloads are limited to 16,384 bytes (`MAX_MSGLEN`); a negative length other than the end
  marker or a longer one is an error.
- `DemoRecordReader` reports why a demo ended: `MARKER`, `PHYSICAL_EOF` at a record boundary,
  `TRUNCATED_HEADER` or `TRUNCATED_PAYLOAD`. The `STRICT` policy treats truncation as an error;
  the `PLAYBACK` policy ends the demo there, as the original client does, and also accepts a
  length of -1 with any sequence as the end. A failed reader cannot be retried.
- `DemoWriter` writes records and one end marker on `finish()`; after a failed write it does not
  add a misleading end marker.

## Protocols

The protocol is taken from the file name (`DemoProtocol.fromFileName`, case-insensitive); a name
that matches no supported protocol raises `UnsupportedDemoProtocolException`.
`DemoProtocol.sniff` can instead infer the message coding from the first record (which always
starts with a gamestate).

| Protocol | Extension | Releases | Messages | Play | Record |
| ---: | --- | --- | --- | --- | --- |
| 43 | `.dm3` | 1999 releases (1.09 era) | raw bits, legacy layout | yes | no |
| 48 | `.dm_48` | 1.27, and 1.25 servers announcing 46 | raw bits, legacy layout | yes | no |
| 66 | `.dm_66` | 1.29/1.30 | protocol 68 layout | yes | no |
| 67 | `.dm_67` | 1.31 | protocol 68 layout | yes | no |
| 68 | `.dm_68` | 1.32 | protocol 68 layout | yes | yes |

Protocols 66, 67 and 68 share one message stream (the protocol-number changes concern the
connection handshake); they are decoded with the protocol-68 codecs of
[NETWORK68_MESSAGES.md](NETWORK68_MESSAGES.md). There is no separate `.dm_46` extension: 1.25
servers wrote `.dm_48` files and announce `protocol 46` in their server info, which selects the
46 player-state variant.

Protocols 43 and 48 are an extension (the original 1.32 engine plays neither). Their layouts were
measured from the demos shipped in retail PK3s and are documented in detail in `DemoProtocol`'s
Javadoc; in short:

- Messages are not Huffman coded (every bit raw, least significant first) and have no `svc_EOF`:
  a message ends when fewer than 8 bits, all zero, remain. Opcodes, configstrings and the snapshot
  header are as in 68.
- A gamestate ends with a zero byte and carries no client number or checksum feed; `DemoReader`
  decodes ahead to the level's first snapshot and reports its player state's `clientNum`.
- Protocol 43 messages have no leading reliable acknowledge; a 32-bit word follows the snapshot
  opcode instead.
- Entity and player deltas use the mask-table layout of `LegacyDeltaCodec`
  ([NETWORK68_DELTA.md](NETWORK68_DELTA.md#older-protocols-43-46-48)), decoded onto the canonical
  1.32 states, so playback uses the same snapshot path as protocol 68.
- Configstring indices, event numbers and entity types are the 1.32 ones as far as the demos show;
  nothing is renumbered.

`.dm3` demos need the retail cgame (see [COMPATIBILITY.md](../../COMPATIBILITY.md)); the 1.32
cgame rejects their gamestate, as it would on the original engine.

## Playback

`demo <name>` looks in `demos/`. A name with a `.dm_NN` or `.dm3` extension is used as given;
without one, `.dm_68`, `.dm_67` and `.dm_66` are tried in that order (so `.dm_48` and `.dm3` demos
must be named with their extension). `DemoReader` decodes each record with the `PLAYBACK` policy:
a snapshot whose delta base is missing is skipped while the rest of the message is kept, as the
original client does. Commands are returned as data and executed by the client like live ones.
`DemoServerClock` decides the cgame time and how far to read ahead each frame; `timedemo 1` plays
every frame 50 ms apart regardless of real time and reports timing statistics.

## Recording

`record [<name>]` records the current level (local or remote) as `.dm_68` (default name
`demoNNNN.dm_68`, the first free of 0000 to 9999) into the home store's `demos/`; `stoprecord`
ends it. A recording
starts with a synthesized gamestate of the current level (configstrings with every received update
applied, the baselines, the newest reliable command sequence), then every accepted message's clear
payload from the first full snapshot on; while waiting for it the connection asks the server for
full snapshots. A recording is limited to 1 GiB. Demo playback cannot be recorded.

Independent implementation; see [LICENSE-NOTES.md](../../LICENSE-NOTES.md). Tests:
`DemoFramingTest`, `DemoProtocolTest`, `DemoReaderTest`, `DemoRecordReaderPlaybackTest`,
`LegacyDemoTest`, and the `@RealData` `DemoCorpusTest`.
