# Protocol 68 connectionless packets

Connectionless (out-of-band) datagrams start with the four bytes `ff ff ff ff` and carry a text
command line, optionally followed by binary data. They are used for the connection handshake,
server queries, rcon and master-server lists. Sequenced netchan traffic is in
[NETWORK68.md](NETWORK68.md).

Code (`engine/net`, package `net.oob`):
[`Protocol68Datagram`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/oob/Protocol68Datagram.java),
[`ConnectionlessPacket`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/oob/ConnectionlessPacket.java),
[`ConnectionlessMessage`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/oob/ConnectionlessMessage.java),
[`ConnectPacketCodec`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/oob/ConnectPacketCodec.java)
(with [`AdaptiveHuffmanTree`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/huffman/AdaptiveHuffmanTree.java)),
[`MasterServerResponse`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/oob/MasterServerResponse.java).
Handlers: server
[`Protocol68Host`](../../engine/game/src/main/java/dev/bluevista/craftq3/engine/game/net/Protocol68Host.java),
[`RconAuthenticator`](../../engine/game/src/main/java/dev/bluevista/craftq3/engine/game/net/RconAuthenticator.java),
[`MasterHeartbeat`](../../engine/game/src/main/java/dev/bluevista/craftq3/engine/game/net/MasterHeartbeat.java);
client
[`Protocol68Handshake`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/chan/Protocol68Handshake.java),
[`ClientConnection`](../../engine/client/src/main/java/dev/bluevista/craftq3/engine/client/net/ClientConnection.java),
[`ServerBrowser`](../../engine/client/src/main/java/dev/bluevista/craftq3/engine/client/net/ServerBrowser.java).

## Framing

`Protocol68Datagram.classify` looks only at the marker and size: 4 to 16,384 bytes with the
marker is `CONNECTIONLESS`; any other prefix up to 1,400 bytes is a `SEQUENCED` candidate for the
channel; everything else is `MALFORMED`. A sequenced packet's own checks happen in the channel.

`ConnectionlessPacket.text` sends Latin-1 text without an implicit terminator or newline (callers
add protocol newlines), up to 16,380 payload bytes, and refuses NUL or characters above 255.

`ConnectionlessMessage.parse` reads the command line the way the native raw line reader does:

- The line ends at newline, NUL or the end of the packet (the terminator is consumed).
- `%` and bytes 128-255 become `.` in the line; byte 127 and carriage return are kept.
- At 1,023 characters the reader consumes and drops one more byte; the remaining bytes stay in
  the body.
- `body()` is every byte after the line, untouched (binary data, further newlines, NULs).
- `readCount()` and `bitPosition()` reproduce the native byte and bit cursors (an unsuccessful
  read at the end advances the byte cursor one past the packet but not the bit cursor).

## Compressed connect

A `connect` datagram is `ff ff ff ff connect ` (12 bytes) followed by a big-endian 16-bit byte
count and the rest of the command (`"<userinfo>"`) coded with a fresh adaptive Huffman tree: each
byte is the tree path of its node, or of the not-yet-transmitted node followed by the 8-bit
literal; the tree updates after every byte. `ConnectPacketCodec.decompress` rejects a count past
the message limit and truncated bit streams; `compress` refuses inputs the native encoder would
truncate. The server decompresses before parsing the line.

## Server commands

`Protocol68Host` handles, while the server is running:

| Request | Reply |
| --- | --- |
| `getchallenge [<nonce>]` | `challengeResponse <challenge> [<nonce> 68]`; at most 1,024 challenges kept, oldest replaced |
| `connect "<userinfo>"` | `connectResponse`, or `print` with the reason (userinfo of 1,024 bytes or more, protocol not 68, no or bad challenge, admission refused) |
| `getinfo <challenge>` | `infoResponse\n` and an info string: `challenge`, `protocol`, `hostname`, `mapname`, `clients`, `sv_maxclients` (minus private slots), `gametype`, `pure`, optional `minPing`, `maxPing`, `game` |
| `getstatus <challenge>` | `statusResponse\n`, the serverinfo string with `challenge`, a newline, then `<score> <ping> "<name>"` per connected client |
| `rcon <password> <command>` | `print` with the command's output in chunks of 1,008 bytes |

Notes:

- Single-player servers (`g_gametype 0`) ignore `getchallenge`, `getinfo` and `getstatus` from
  remote addresses.
- A retransmitted `connect` from an existing connection with the same challenge, within
  `sv_reconnectlimit`, just gets `connectResponse` again.
- A sequenced packet from an unknown address and qport is answered with `disconnect`.
- Rate limits: 10 connectionless packets per address and 100 in total per second; rcon requests
  at most one per 500 ms. An empty `rconPassword` refuses rcon ("No rconpassword set on the
  server."), and passwords are compared in constant time.
- With `dedicated 2` and `sv_master1`..`sv_master5` set, the server sends
  `heartbeat QuakeArena-1` to each master (port 27950 by default) every 300 seconds; this is off
  by default.

## Client side

The handshake (`getchallenge`, `challengeResponse`, `connect`, `connectResponse`) is described in
[NETWORK68.md](NETWORK68.md#handshake). An established `ClientConnection` acts on `print` and
`disconnect` from its server. The server browser sends `getinfo xxx` and `getstatus` and parses
`infoResponse` and `statusResponse`. It asks masters (`sv_master1` defaults to
`master.quake3arena.com`, port 27950) through the original `globalservers <master> <protocol>
[filters]` command with `getservers <protocol> [filters]`, or
`getserversExt Quake3Arena <protocol> [filters]` over IPv6, and scans the LAN by IPv4 broadcast.

`MasterServerResponse.parse` reads `getserversResponse` (records `\` + 4-byte IPv4 address +
big-endian port) and `getserversExtResponse` (also `/` + 16-byte IPv6 address + port). A record
counts only when another `\` or `/` follows it, so a malformed final record is dropped; at most
256 records per packet, duplicates kept. Addresses are never resolved.

Independent implementation; see [LICENSE-NOTES.md](../../LICENSE-NOTES.md). Tests:
`ConnectionlessMessageTest`, `ConnectPacketCodecTest`, `MasterServerResponseTest`,
`Protocol68HandshakeTest`.
