# Protocol-68 connectionless envelopes

`ConnectionlessPacket` already supplies the four `ff` marker bytes and bounded,
owned raw payloads. The new `Protocol68Datagram` and `ConnectionlessMessage`
provide the classification and command-line envelope needed before future
connectionless dispatch. They do not send packets or execute commands.

## API and bounds

`Protocol68Datagram.classify(byte[])` is stateless. It returns `CONNECTIONLESS`
for the complete `ff ff ff ff` marker within the 16,384-byte message bound;
`SEQUENCED` for another four-byte prefix within the existing channel's 1,400-byte
packet bound; otherwise `MALFORMED`. Sequenced means a framing candidate:
endpoint-specific qport/header/fragment/sequence checks remain with
`Protocol68Channel.receive`. Classification cannot mutate a channel's fragment
assembly. A truncated native `MSG_ReadLong` returns the same `-1` value as the
connectionless marker, so the explicit four-byte length check is necessary.

`ConnectionlessMessage.parse(byte[])` rejects a malformed or sequenced input and
returns an immutable envelope:

- `line()` is the first raw `MSG_ReadStringLine` result after the marker.
- `body()` is an owned copy of every remaining byte. It preserves binary bytes,
  additional newlines, percent signs and NULs for a later response-body parser.
- `readCount()` is the native byte cursor including the marker.
- `bitPosition()` is the native bit cursor including the marker.

The reader reuses `MessageStrings.sanitize`, already verified by the compressed
wire-string corpus in [NETWORK68.md](NETWORK68.md#wire-strings). Percent signs
and bytes 128–255 become dots in the line only. Byte 127 and carriage return are
retained. Newline or NUL ends the line and is consumed. EOF also ends the line:
the unsuccessful read leaves `readCount` one past the packet length while the
bit cursor stays at the actual packet end. The returned line has at most 1,023
characters. At that limit the native reader consumes one more byte, discards it,
and leaves subsequent bytes in the body. This layer preserves that behavior; it
does not reinterpret a capped line as a complete command.

For example, raw `ff ff ff ff` plus `statusResponse\n` followed by arbitrary bytes
produces line `statusResponse`, byte cursor 19, bit cursor 152 and exactly those
arbitrary bytes as its body. An empty marker-only datagram produces an empty
line/body, byte cursor 5 and bit cursor 32.

`ConnectionlessPacket.text` remains exact non-NUL Latin-1 framing, with no
implicit newline or terminator. It accepts up to 16,380 payload bytes. Native
`NET_OutOfBandPrint("%s", text)` is a different formatting operation: it stops at
the first C NUL and truncates to 16,379 payload bytes, sending no terminator.
The oracle establishes this distinction; the Java framing API does not silently
truncate the caller's text.

## Native comparison

The authored `ConnectionlessOracle.c` calls unchanged public
`NET_OutOfBandPrint`, `MSG_InitOOB`, `MSG_BeginReadingOOB`, `MSG_ReadLong` and
`MSG_ReadStringLine` exports. Its inherited `Sys_SendPacket` callback only prints
captured bytes; no socket opens and no external server receives traffic. Native
`q_shared.c` supplies the original formatting helpers. The build uses unchanged
official ioquake3 source at commit
`588393618dbc82e7207c21c6ddecca229944a03a`; the relevant API declarations are in
the [public qcommon header](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/qcommon/qcommon.h).
No engine routine bodies were used as implementation recipes. The audit binaries
and source checkout are ignored local artifacts, outside the mod bundle.

`AuditConnectionless.java` passes with zero differences:

| Comparison | Cases |
| --- | ---: |
| Raw lines, both cursors and remaining body bytes | 14,583 |
| Captured native printf packet bytes | 2,265 |
| Raw prefix values and bounded classification | 10,260 |

The matrix covers every byte at five positions around the line-capacity
boundary, all payload lengths 0–1,100 with EOF/NUL/newline endings, seeded random
packets through the maximum message size, long unterminated lines, formatting
size boundaries, embedded NULs and truncated prefixes. Classification includes
the documented Java transport-size policy; it is not a claim that every native
network entry point enforces that same policy.

Eight authored tests additionally cover raw status-body preservation, filtering,
EOF/capacity cursor behavior, input/output ownership, malformed/oversized
datagrams and channel assembly isolation. These and the existing eight channel
and five wire-string tests pass under Java 25.

```sh
python3 scripts/BuildConnectionlessOracle.py
java -cp craftq3-core/build/classes/java/main scripts/AuditConnectionless.java
```

The build command currently targets macOS/Clang's dead-strip linker. It reads the
existing ignored source checkout and creates only `.tools/connectionless-oracle`.

## Remaining scope

No challenge, status body, connect/session, pure-pack, authentication, timeout,
address or socket policy is implemented here. Adaptive compressed `connect`
payloads share the OOB marker but require their own decoder before raw command
parsing; this envelope API does not perform that decoding. Command tokenization
and dispatch also remain outside this layer. These checks establish framing and
raw-reader behavior, not complete network interoperability or hostile-network
readiness.
