# Protocol 68 messages

The payloads inside netchan messages and demo records: server messages (gamestate, reliable
commands, snapshots, downloads) and client messages (reliable commands, user commands). They use
the bit stream of [NETWORK68.md](NETWORK68.md) and the deltas of
[NETWORK68_DELTA.md](NETWORK68_DELTA.md). The codecs produce data; executing commands, applying
configstrings and retaining history belong to the sessions (`Protocol68ClientSession`,
`Protocol68ServerSession`) and to demo playback.

Code (`engine/net`):
[`ServerMessageCodec`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/msg/ServerMessageCodec.java),
[`SnapshotDeltaCodec`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/delta/SnapshotDeltaCodec.java),
[`DownloadMessageCodec`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/msg/DownloadMessageCodec.java),
[`ClientMessageCodec`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/msg/ClientMessageCodec.java),
[`MessageHash`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/msg/MessageHash.java),
[`ConfigstringCommands`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/msg/ConfigstringCommands.java),
[`CommandTokens`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/msg/CommandTokens.java),
[`LegacyServerMessageCodec`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/msg/LegacyServerMessageCodec.java),
[`Protocol68`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/Protocol68.java)
(opcodes and limits).

## Server messages

A server message is a 32-bit reliable acknowledge, then operations, each an 8-bit opcode, ending
with `svc_EOF` (8). At most 4,096 operations are read.

| Opcode | Operation | Body |
| ---: | --- | --- |
| 1 | `svc_nop` | none |
| 2 | `svc_gamestate` | command sequence (32), records, `svc_EOF`, client number (32), checksum feed (32) |
| 3 | `svc_configstring` | inside a gamestate only: index (16), big string |
| 4 | `svc_baseline` | inside a gamestate only: an entity delta from the zero state |
| 5 | `svc_serverCommand` | sequence (32), string (under 1024 bytes) |
| 6 | `svc_download` | see below |
| 7 | `svc_snapshot` | snapshot body |

Gamestate limits: configstring indices 0 to 1023; each string under 8,192 bytes; the stored text
(one initial byte plus each string and its terminator) at most 16,000 bytes, charged for every
received string including duplicates (the last duplicate wins); at most 4,096 records. Empty
values are omitted. A baseline must be a live entity. A gamestate replaces the level baselines and
invalidates snapshot history for the rest of the message. Any other opcode fails the whole read.

Outgoing text (commands, configstrings) is filtered like every wire string (`%` and bytes above
127 become `.`); constructors reject NUL, characters above 255 and overlong values instead of
truncating.

## Snapshots

A snapshot body is: server time (32), delta distance (8), snapshot flags (8), area-mask length
(8, at most 32) and bytes, a player delta, then entity records in strictly increasing number
order ending with 1023. The message sequence comes from netchan or demo framing, not the body.

- Distance 0 is a full snapshot against the zero player state and the level baselines.
- Distance 1 to 255 names the exact earlier message `sequence - distance`; it must be supplied
  through a history lookup. A missing base is an error for live play. For demo playback
  (`readForPlayback`) the snapshot is consumed and dropped while the rest of the message is kept,
  as the original client does.
- Merging: entities absent from the stream are carried over from the base, removals drop them,
  existing entities are deltas against their previous state and new ones against the level
  baseline (and are always written, even when equal to it).
- At most 256 entities (`MAX_ENTITIES_IN_SNAPSHOT`); area-mask bytes beyond the sent length are
  zero.

The client keeps 32 snapshots (`PACKET_BACKUP`); the server deltas only from an acknowledged
snapshot that is still retained and fewer than 32 messages old.

## Downloads

`svc_download`: block number (16). At the start of a transfer (block 0 while block 0 is
expected) a file size (32) follows; a negative size is followed by an error string and no data.
Otherwise a byte count (16, at most 16384) and the bytes. An empty block ends the file. The
transfer itself is described in [NETWORK68.md](NETWORK68.md#downloads).

## Client messages

A client message is server id (32), message acknowledge (32) and reliable command acknowledge
(32), then operations ending with `clc_EOF` (5), at most 128:

| Opcode | Operation | Body |
| ---: | --- | --- |
| 1 | `clc_nop` | none (accepted only before movement) |
| 2 | `clc_move` | count (8, 1 to 32), keyed user-command deltas; asks for a delta snapshot |
| 3 | `clc_moveNoDelta` | the same; asks for a full snapshot |
| 4 | `clc_clientCommand` | sequence (32), string (under 1024 bytes); at most 64 |

Movement is the last operation. Its first command is a delta from the zero command, each next one
from the previous. The 12-byte header stays in clear under the payload XOR.

The user-command key is `checksumFeed ^ messageAcknowledge ^ hash`, where `hash` is
`MessageHash.key` of the first 32 bytes of the acknowledged reliable server command: the sum of
`(i + 119) * c` over the filtered bytes up to a NUL, folded as `sum ^ (sum >> 10) ^ (sum >> 20)`
with 32-bit wrap. Each command then XORs its own time into the key. This is obfuscation, not
authentication. Reading movement requires the exact acknowledged server command from the session.

## Configstring commands

Configstring changes after the gamestate arrive as reliable server commands. Values shorter than
1,000 characters are sent as `cs <index> "<value>"`; longer ones as `bcs0`, `bcs1`...`bcs2`
fragments of 999 characters, reassembled by `ConfigstringCommands`. `CommandTokens` tokenizes
protocol text like `Cmd_TokenizeString` (whitespace separates, quotes group, `//` and `/* */`
comments; semicolons and newlines do not split), with at most 1,024 arguments and 8,192
characters.

## Older protocols

`LegacyServerMessageCodec` reads the server messages of protocols 43, 46 and 48 (demos only; see
[DEMOS.md](DEMOS.md)) into the same operations. These messages use the raw (non-Huffman) bit
coding, have no `svc_EOF` (a message ends when fewer than 8 bits, all zero, remain), and their
gamestate ends with a zero byte without client number or checksum feed. Protocols 46 and 48 start
with the reliable acknowledge; protocol 43 instead sends a 32-bit word after the snapshot opcode.

All reads and writes are transactional; failures raise `NetFormatException` or
`IllegalArgumentException` and leave the cursor where it was.

Independent implementation; see [LICENSE-NOTES.md](../../LICENSE-NOTES.md). Tests:
`ServerMessageCodecTest`, `SnapshotDeltaCodecTest`, `ClientMessageCodecTest`, `MessageHashTest`,
`ConfigstringCommandsTest`, `LegacyDemoTest`.
