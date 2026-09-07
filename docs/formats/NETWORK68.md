# Protocol 68 framing and message codec

The Java-only `core.net` package implements connectionless framing, netchan sequencing/fragmentation, compressed bit fields, state deltas, bounded client/server payload codecs and legacy payload XOR. It has no sockets, remote connection state or Minecraft dependencies. This is groundwork for demos/networking; connecting to a server is still unavailable. Protocol 71 checksums and retail protocol 43 are not claimed.

## Datagram contract

`Protocol68Channel` is explicitly a client or server endpoint. Every datagram starts with a little-endian 32-bit sequence; the high bit denotes fragmentation. Client-to-server traffic also carries a 16-bit qport. Fragmented packets add 16-bit byte offset and length. Each fragment carries at most 1300 bytes; a shorter fragment terminates the message. An exact multiple requires an empty terminating fragment. Messages are capped at 16384 bytes.

The sender owns one queued message, copies caller input, and emits one packet per `pollPacket()` so a transport can pace delivery. Its sequence begins at one and advances after the final packet. The receiver retains a bounded assembly, rejects stale completed messages and only accepts consecutive fragments. A complete result reports the message sequence, skipped sequence count and owned payload. Reliability/retransmission is a higher-layer responsibility.

`ConnectionlessPacket` handles the four `ff` bytes and arbitrary bounded payload. Its text helper emits Latin-1 without an implicit NUL or newline. Route these packets before netchan; connect-payload adaptive compression is not implemented.

Deliberate stricter boundaries: datagrams above 1400 bytes are rejected by netchan; fragment lengths must exactly match the datagram; wrong qports cannot mutate a server channel; malformed offsets/lengths cannot mutate an assembly; older incomplete fragments cannot discard newer assembly progress. Sequence exhaustion requires reconnecting before the reserved/overflow range. Endpoint address validation and connectionless rate limits belong to the future transport/session owner.

## Compressed bit fields

`MessageWriter`/`MessageReader` encode residual low bits directly, followed by the protocol's fixed-Huffman byte codes. They preserve the native trailing byte when a field ends exactly on a byte boundary. Widths 1–32 and float bit patterns are supported. Every operation is bounded; capacity/truncation failure leaves its cursor unchanged. Buffers are owned copies and the codebook is immutable.

The 256 wire codes were observed by sending each byte through unchanged native `MSG_WriteBits`. They are protocol data, stored as a length marker plus the low-bit-first code; no native tree construction or adaptive-Huffman implementation was ported. The independent Java decoder builds a small prefix lookup. The 256-line native observation has SHA-256 `85078f1f9a537bed3a1d39eef1c9e28e46fce76dba499f58e9a434cafbb5d424`.

Native signed-field behavior needs care: `field(width)` supports the MSG convention of a negative width. In the observed implementation, an unaligned negative width extends the sign of its whole-byte portion: width −9 with value 128 becomes −128, while value 256 remains 256; widths −1…−7 do not sign-extend. Mathematical `signedBits(width)` is separately available. The native API rejects width −32; positive 32 preserves the complete int representation.

## Validation and provenance

The wire layout/constants come from the [published netchan header commentary](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/qcommon/net_chan.c) and [public message declarations](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/qcommon/qcommon.h). Authored `scripts/NetchanOracle.c` links unchanged `net_chan.c`, `msg.c` and `huffman.c` from that exact official commit. Its `compat=true` channel selects legacy protocol-68 framing. The ignored native executable is only an audit process; neither native engine functions nor upstream source are bundled or called by CraftQ3.

- Eight authored netchan tests cover header bytes, boundary sizes in both directions, loss/reordering/duplicates, qports, truncation, oversized data, ownership and 5000 random malformed datagrams.
- Eight message-codec tests cover captured wire examples, all byte symbols at every bit offset, all signed widths, native sign behavior, float bits, capacity, truncation and ownership.
- `AuditNetchan.java`: 2000 messages, **13813 byte-exact packets**, 16193 reception attempts and 1820 completed messages; zero native mismatches. The cases include whole-message loss, premature fragments and duplicates.
- `AuditMessages.java`: all 256 byte codes plus **131072 mixed signed/unsigned fields** in 512 messages, 297752 wire bytes; zero native bit/value mismatches.

Reproduce with Java 25 after building core and preparing the unchanged native source under ignored `.tools/ioquake3-source`:

```sh
mkdir -p .tools/netchan-oracle
for unit in net_chan msg huffman; do
  clang -std=c11 -O2 -ffunction-sections -fdata-sections \
    -I.tools/ioquake3-source/code -I.tools/ioquake3-source/code/qcommon \
    -c .tools/ioquake3-source/code/qcommon/$unit.c -o .tools/netchan-oracle/$unit.o
done
clang -std=c11 -O2 -Wl,-dead_strip \
  -I.tools/ioquake3-source/code -I.tools/ioquake3-source/code/qcommon \
  scripts/NetchanOracle.c .tools/netchan-oracle/net_chan.o \
  .tools/netchan-oracle/msg.o .tools/netchan-oracle/huffman.o \
  -o .tools/netchan-oracle/netchan-oracle
java -cp craftq3-core/build/classes/java/main scripts/AuditNetchan.java
java -cp craftq3-core/build/classes/java/main scripts/AuditMessages.java
```

The linker option above is macOS-specific. These tests establish framing/field encoding, not protocol completeness, UDP interoperability, demo playback, or hostile-network readiness.

Canonical entity/player delta codecs and their transactional message helpers are documented in
[NETWORK68_DELTA.md](NETWORK68_DELTA.md), including the native differential audit and API ownership.
Complete snapshot bodies and supported server payloads are documented in
[NETWORK68_MESSAGES.md](NETWORK68_MESSAGES.md), including their native parser acceptance audits.

## Wire strings

`MessageWriter.stringValue` and `bigStringValue` encode NUL-terminated byte strings with native
capacities of 1,024 and 8,192 bytes including the terminator. Null values encode as empty. Embedded
NUL ends the supplied value; an overlong value encodes an empty string, matching native output.
Percent signs and bytes 128–255 become dots; byte 127 and other control bytes are retained.
Unicode characters outside the byte range are rejected. Encoding is transactional, including
capacity failures after a partial write.

`MessageReader.stringValue`, `bigStringValue` and `stringLine` apply the same filtering to decoded
symbols. The line form consumes and stops at newline; carriage return remains data. At the native
string limit, the reader consumes one additional discarded symbol and returns the bounded prefix.
Truncated compressed input throws and restores the starting cursor, consistent with the other Java
message APIs; native out-of-buffer cursor behavior is not reproduced. These operations do not
constitute a complete server-message or demo parser.

Five focused tests check captured wire bytes, raw-symbol filtering, newline continuation, null and
embedded-NUL values, size boundaries and transactional failure. `MessageStringOracle.c` calls
unchanged public native MSG functions. `AuditMessageStrings.java` compares all byte values, every
bit alignment, generated strings and capacity boundaries: **13,376 writes / 1,653,264 wire bytes**
and **20,064 reads** match exactly, including read cursors.

```sh
clang -std=c11 -O2 -Wl,-dead_strip -I.tools/ioquake3-source/code \
  scripts/MessageStringOracle.c .tools/netchan-oracle/net_chan.o \
  .tools/netchan-oracle/msg.o .tools/netchan-oracle/huffman.o \
  -o .tools/netchan-oracle/string-oracle
java -cp craftq3-core/build/classes/java/main scripts/AuditMessageStrings.java
```
