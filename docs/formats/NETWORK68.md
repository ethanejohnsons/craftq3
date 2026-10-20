# Protocol 68 netchan, bit stream and sessions

The Quake III 1.32 network protocol (68): datagram transports, netchan sequencing and
fragmentation, the fixed-Huffman bit stream and wire strings, payload XOR, the challenge/connect
handshake, client and server sessions and UDP file downloads. Message bodies are in
[NETWORK68_MESSAGES.md](NETWORK68_MESSAGES.md), state deltas in
[NETWORK68_DELTA.md](NETWORK68_DELTA.md), out-of-band packets in
[CONNECTIONLESS68.md](CONNECTIONLESS68.md) and demo files in [DEMOS.md](DEMOS.md).
Independent implementation; see [LICENSE-NOTES.md](../../LICENSE-NOTES.md).

Code:
[`Protocol68`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/Protocol68.java) (every wire constant),
[`HuffmanCodebook`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/huffman/HuffmanCodebook.java),
[`MessageReader`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/msg/MessageReader.java),
[`MessageWriter`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/msg/MessageWriter.java),
[`MessageStrings`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/msg/MessageStrings.java),
[`Protocol68Channel`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/chan/Protocol68Channel.java),
[`LegacyPayloadXor`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/chan/LegacyPayloadXor.java),
[`Protocol68Handshake`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/chan/Protocol68Handshake.java),
[`Protocol68ClientSession`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/chan/Protocol68ClientSession.java),
[`Protocol68ServerSession`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/chan/Protocol68ServerSession.java),
[`DownloadSender`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/chan/DownloadSender.java),
[`DownloadReceiver`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/chan/DownloadReceiver.java),
[`PacketTransport`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/transport/PacketTransport.java),
[`UdpTransport`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/transport/UdpTransport.java),
[`LoopbackTransport`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/transport/LoopbackTransport.java).
Consumers: [`ClientConnection`](../../engine/client/src/main/java/dev/bluevista/craftq3/engine/client/net/ClientConnection.java),
[`DownloadManager`](../../engine/client/src/main/java/dev/bluevista/craftq3/engine/client/net/DownloadManager.java)
and [`Protocol68Host`](../../engine/game/src/main/java/dev/bluevista/craftq3/engine/game/net/Protocol68Host.java).

## Layering

Send: message codec (Huffman bit stream) -> `LegacyPayloadXor` -> `Protocol68Channel` fragments
-> `PacketTransport`. Receive is the reverse; `Protocol68Datagram` first routes connectionless
packets (`ff ff ff ff`) away from the channel. Codecs and sessions own no sockets, threads or
clocks; the caller drives them.

## Limits

| Constant | Value |
| --- | --- |
| Assembled message, connectionless datagram, demo record payload (`MAX_MSGLEN`) | 16384 bytes |
| Sequenced datagram, headers included (`MAX_PACKETLEN`) | 1400 bytes |
| Fragment payload (`FRAGMENT_SIZE`) | 1300 bytes |
| Default server port | 27960 |
| Unacknowledged reliable commands per direction | 64 |
| Snapshot history (`PACKET_BACKUP`) | 32 |
| User commands per movement operation | 32 |

## Netchan header

All words are little-endian.

| Field | Size | Present |
| --- | --- | --- |
| sequence, bit 31 = fragment flag | 4 | always |
| qport | 2 | client-to-server only |
| fragment start offset | 2 | fragmented |
| fragment length | 2 | fragmented |
| payload | rest | always |

A message of 1300 bytes or more is sent as fragments of 1300 bytes; a shorter fragment ends it,
so an exact multiple of 1300 needs a final empty fragment. The sender holds one queued message
and emits one datagram per `pollPacket()`; its sequence starts at 1, advances after the last
fragment and refuses to pass `0x7ffffffe` (reconnect instead of wrapping). Reliability is a
session concern; the channel does not retransmit.

The receiver checks bounds before mutating anything. It reports `COMPLETE` (with the sequence,
the number of skipped sequences and an owned payload), `PARTIAL`, `STALE` (at or below the last
completed sequence, or older than the current assembly), `OUT_OF_ORDER` (a fragment that does
not continue the assembly), `MALFORMED` (outside 4..1400 bytes, sequence 0, short header, length
not matching the datagram, over 1300 or past 16384), `WRONG_QPORT` or `CONNECTIONLESS`.
Fragments must arrive consecutively; an older incomplete message cannot discard progress on a
newer assembly.

## Huffman bit stream

Bits are packed least significant first. A field of `width` bits (1..32) writes its `width & 7`
low bits raw, then each remaining byte as a code from a fixed, prefix-free 256-entry codebook
(codes of 2 to 11 bits, LSB first). The codebook is stored as marked codes: the highest set bit
of each entry is a length marker. It was measured from an unmodified upstream build during the
earlier implementation (see LICENSE-NOTES); there is no tree construction. Decoding uses a
2048-entry lookup on an 11-bit window; the table is checked to be prefix-free at class load.

- `MessageWriter.byteLength()` is `bit / 8 + 1` when anything was written, so a message ending on a
  byte boundary keeps one trailing byte, as native messages do. The writer refuses a field that
  would not leave that byte inside its capacity (default 16384).
- Reads and writes are transactional: a failed or truncated operation restores the cursor
  (`transaction`, `mark`/`rewind`, `rollback`). Readers can be `reset` and reused without
  allocating.
- `MessageReader.Coding.RAW` reads every bit raw (no Huffman). It is only used for the pre-1.29
  demo protocols 43 and 48 ([DEMOS.md](DEMOS.md)).
- Floats travel as their raw 32-bit pattern; `shortValue` reads signed 16 bits.

Signed fields follow the MSG convention of a negative width: the writer sends the low `|width|`
bits; the reader sign-extends from the top bit of the whole-byte portion only. Width -9 with
value 128 decodes as -128, value 256 stays 256, and widths -1..-7 are not sign-extended. Width
-32 is rejected; +32 keeps the full int. `signedBits(width)` gives mathematical sign extension.

## Wire strings

`stringValue` (1024 bytes) and `bigStringValue` (8192 bytes) are NUL-terminated byte strings;
the capacities include the terminator.

- Writing: null encodes as empty; an embedded NUL ends the value; a value of capacity length or
  longer encodes as empty (native behaviour); characters above 255 are rejected.
- Both directions replace `%` and bytes 128-255 with `.`; byte 127 and other control bytes are
  kept.
- Reading stops at NUL. `stringLine` also stops at, and consumes, newline; carriage return is
  data. At the limit (1023 or 8191 characters) the reader consumes and discards one more symbol,
  as the native reader does.

## Payload XOR

`LegacyPayloadXor` applies the protocol-68 obfuscation to a whole compressed payload after
encoding and before fragmentation (and after reassembly on receive). The same call encodes and
decodes. It is obfuscation, not encryption or authentication.

| Direction | Clear prefix | Initial key | Key string |
| --- | --- | --- | --- |
| client to server | 12 bytes (serverId, messageAcknowledge, reliableAcknowledge) | challenge ^ serverId ^ messageAcknowledge | text of the acknowledged reliable server command |
| server to client | 4 bytes (reliableAcknowledge) | challenge ^ message sequence | text of the last reliable client command received |

For each byte `i` after the prefix: `key ^= text[index] << (i & 1)`, then `payload[i] ^= key`,
with `index` cycling through the key string (0 for an empty string). The key string stops at
NUL, uses the `%`/high-byte dot replacement and must be under 1024 bytes; payloads are limited
to 16384 bytes.

## Handshake

`Protocol68Handshake` runs one challenge/connect exchange against a peer the transport has
already pinned:

1. The client sends `getchallenge <nonce> Quake3Arena` (retried by `ClientConnection`).
2. The server replies `challengeResponse <challenge> [<nonce> <protocol>]`. When the nonce and
   protocol are present they must match; older replies without them are accepted.
3. The client sends `connect "<userinfo>"`, compressed by `ConnectPacketCodec`. The userinfo is
   the caller's keys in sorted order with `protocol 68`, `qport` and `challenge` replacing any
   caller values, at most 1023 bytes (checked up front with the longest possible challenge).
4. The server replies `connectResponse [<challenge>]`; a challenge, when present, must match.

A `print` reply is kept as text for display; other or malformed packets are ignored. Server-side
checks are described in [CONNECTIONLESS68.md](CONNECTIONLESS68.md).

## Client session

`Protocol68ClientSession` owns an established connection:

- Reliable client commands are queued once and repeated in every message until acknowledged; at
  most 64 may be outstanding. If the backlog does not fit a message, a fitting prefix is sent.
- Movement asks for a delta snapshot (`clc_move`) only when the last received message carried
  the current snapshot; otherwise, after a rejected message, a gamestate or on request
  (`requestFullSnapshot`, `fullSnapshots` while a demo recorder waits) it sends
  `clc_moveNoDelta`.
- On receive, the clear acknowledge word must name a command actually sent and still retained;
  the payload is then decoded and every operation applied to a copy of the level state, which
  is committed only if all of them succeed. A gamestate must not rewind reliable commands and
  must name client 0..63; reliable server commands must be consecutive (a gap is rejected);
  the last 64 are kept; `cs`/`bcs` commands update configstrings; system info must contain
  `sv_serverid`. Snapshots are kept for 32 messages.
- Results are `ACCEPTED` (with the clear payload as a `DemoRecord`), `PARTIAL`, `DROPPED` (stale
  or out of order, counted), `IGNORED` or `REJECTED`.

## Server session

`Protocol68ServerSession` is the per-client counterpart. The host supplies gamestates (resetting
snapshot history), snapshots and download blocks; every message carries all unacknowledged
reliable server commands. A snapshot is delta-coded from the client's acknowledged message only
while that snapshot is retained and fewer than 32 messages old. A client message whose serverId
differs from the current one is `WRONG_GAME` (its acknowledgements are still applied, and the
host resends the gamestate once the client has acknowledged past it). Reliable client commands
already seen are skipped and a gap is rejected; user commands are kept only if their times
increase and do not exceed the final command's time.

## Downloads

| Constant | Value |
| --- | --- |
| Block size (`DownloadSender.BLOCK_BYTES`) | 1024 bytes |
| Window (`DownloadSender.WINDOW`) | 48 blocks |
| Largest file (`MAX_FILE_BYTES`) | 512 MiB |
| Blocks sent per server frame (`Protocol68Host.DOWNLOAD_BLOCKS_PER_FRAME`) | 8 (48 over loopback) |

Block 0 carries the file size; an empty block ends the file. Block numbers travel as their low 16
bits. The sender resends from the first unacknowledged block after 1000 ms without progress and
rejects an acknowledgement that is not the oldest outstanding block. The receiver accepts only
the expected block, requires the size on block 0, rejects data past the advertised size, completes
only when the size is reached, and fails on a server error string. The client requests a file
with `download <game/name.pk3>`, acknowledges each block with `nextdl <block>` and finishes with
`donedl`; `stopdl` cancels. The server only offers referenced, non-official PK3s and only when
`sv_allowDownload` bit 1 is set; a refusal is sent as block 0 with size -1 and the reason. The
`svc_download` encoding is in [NETWORK68_MESSAGES.md](NETWORK68_MESSAGES.md); planning, caching
and mounting downloaded packs are in [PK3.md](PK3.md) and
[DIVERGENCES.md](../DIVERGENCES.md).

## Transports

`PacketTransport` is a caller-driven, non-blocking datagram endpoint: `send` returns false when it
would block, `poll` returns a length, `NONE` or `OVERSIZE` (datagram discarded) and `lastSource()`
names the sender. Datagrams must be 1..16384 bytes.

- `UdpTransport` wraps one `DatagramChannel`, IPv4 or IPv6: `bind` (server listener), `ephemeral`
  (browser socket, optional IPv4 broadcast) and `connect` (client socket pinned to one server so
  the OS drops other sources). Peers must be resolved unicast addresses; no DNS is done here.
- `LoopbackTransport.pair()` links an in-process client and server (local play) with two bounded
  lock-free rings, 64 datagrams each by default; a send to a closed end is silently dropped.

Tests: `Protocol68ChannelTest`, `MessageCodecTest`, `MessageStringsTest`, `MessageReuseTest`,
`LegacyPayloadXorTest`, `Protocol68HandshakeTest`, `Protocol68ClientSessionTest`,
`Protocol68ServerSessionTest`, `SessionDropTest`, `DownloadTransferTest`, `UdpTransportTest`,
`LoopbackTransportTest`.
