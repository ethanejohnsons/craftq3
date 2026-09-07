# Adaptive compression of connectionless connect packets

`ConnectPacketCodec.compress(byte[])` and `decompress(byte[])` operate on complete
owned datagrams. They validate and preserve the twelve-byte prefix consisting
of four `ff` marker bytes and ASCII `connect `. Bytes after that prefix are
compressed with fresh adaptive Huffman state for every packet. The existing
pre-trained in-band `HuffmanCodebook` is not used or changed.

The codec adds no quotes, terminator or text sanitation. The caller owns userinfo
construction and connection/session validation. Embedded NUL, percent signs and
high bytes therefore round-trip exactly when supplied as raw bytes. An empty
suffix is an identity operation, matching native compression's empty-input
behavior; this does not make it a valid connection request.

## Wire format and independent implementation

After the preserved prefix, the first two bytes contain the uncompressed suffix
length in big-endian order. Tree decisions are packed into successive low-to-high
bit positions within each byte. A first-occurrence symbol follows the unseen
symbol's tree path, then its eight literal bits in high-to-low symbol order.
The tree is updated after each symbol.

The Java implementation represents adaptive ranks explicitly in a bounded array.
It exchanges equal-weight block leaders and updates parent weights, with at most
256 byte symbols and 513 nodes. It is independently authored from the adaptive
coding contract and native call results. It does not reproduce the native
linked-list/head-pointer representation or copy an engine routine. A preliminary
1,000-history probe compared exposed native tree paths and weights with an
independently authored prototype before production packet comparisons.

Inputs and expanded outputs are bounded by the protocol's 16,384-byte message
limit. Decoding checks the advertised length before allocation and checks every
consumed bit before reading it. It rejects incomplete length fields and truncated
streams. Unused trailing bytes are permitted. An excessive advertised count is
rejected rather than silently clamped to the destination buffer as native
`Huff_Decompress` does.

## Two observed native edge cases

The native encoder's tree-code transmit bound depends on the original suffix
length, including space consumed by its two-byte output length. Some short or
poorly compressible inputs reach that bound and produce a damaged stream. This
occurs in the unchanged full `NET_OutOfBandData` operation, with normal native
buffer capacity, not only in a small custom destination buffer.

For example, original outbound `connect aa` produces
`ffffffff636f6e6e65637420000286`, which lacks the second symbol's encoded bit.
Original outbound `connect "\name\Player"` produces
`ffffffff636f6e6e65637420000e4474b08b216cc79450001b1cdf` and native decompression
changes the end of the userinfo. The Java encoder checks the measured tree-code
bound and rejects such inputs instead of sending corrupted data. Expansion alone
is not rejected: a one-byte suffix can safely use a literal and expand.

When the final coded bit is byte-aligned, native framing includes an extra unused
byte. Its value depends on preceding calls. Twenty encodings of the identical
`connect a` input, interleaved with authored packets, produced seventeen distinct
full byte strings in the recorded probe; all differed only in that ignored byte.
Java retains the framing byte but always initializes it to zero. It does not
reproduce the native residual-memory disclosure. A missing or arbitrary unused
padding byte does not prevent decoding a complete bitstream.

## Native differential and tests

[`ConnectCompressionOracle.c`](../scripts/ConnectCompressionOracle.c) calls
unchanged `NET_OutOfBandData`, `Huff_Compress`, `Huff_Decompress`, `Huff_Init` and
`Huff_addRef`. Its inherited `Sys_SendPacket` callback captures bytes and opens no
socket. [`BuildConnectCompressionOracle.py`](../scripts/BuildConnectCompressionOracle.py)
compiles unchanged official ioquake3 translation units from commit
`588393618dbc82e7207c21c6ddecca229944a03a`. Only public operation declarations and
header metadata are read; no native algorithm body is inspected or translated.
Native source, objects and executable remain in ignored development directories.

[`AuditConnectCompression.java`](../scripts/AuditConnectCompression.java) compares
12,000 authored complete packets, including shuffled userinfo fields, signed
challenges, qport boundaries, high-byte names, percent signs, explicit NUL/newline,
all byte values, long repetition, maximum-length messages and incompressible
inputs. The recorded result is:

| Outcome | Count |
| --- | ---: |
| Accepted packets with native and Java decode round-trips | 9,728 |
| Accepted packets matching every native byte | 9,553 |
| Accepted packets differing only in the unused aligned padding byte | 175 |
| Rejected native encoder overflow | 2,272 |
| Rejected cases whose native packet has a truncated bitstream | 2,147 |
| Rejected cases whose native packet decodes to changed data | 125 |

There are zero meaningful-byte or decoded-data mismatches in the accepted domain.
The padding comparison uses the native decoder's exposed bit cursor to establish
that the differing final byte is outside the decoded stream; it is not a broad
byte tolerance. Every encoder rejection is independently checked against native
outbound bytes for truncation or changed decoded contents.

Seven focused unit tests cover the native default-userinfo golden packet,
independent packet state, zeroed padding, binary ownership, explicit transmit
overflow, malformed prefix/counts, consumed-bit truncation, unused tails, the
full message bound and 500 bounded random decoder inputs. They pass under Java 25
with the project's lint policy.

The full comparison is recorded in `/tmp/craftq3-connect-compression-audit.log`.
[`AuditConnectCompressionBoundaries.py`](../scripts/AuditConnectCompressionBoundaries.py)
preserves the short-packet, padding and native destination-capacity probes in
`/tmp/craftq3-connect-compression-boundaries.log`.

Separate session work used this Java compressor for a true loopback UDP connect
to an unchanged native server advertising legacy protocol 68, then decoded a
fragmented gamestate and 160 snapshots with the existing core codecs. An authored
forward user command moved the native player. That is additional server-acceptance
evidence; socket, peer/session and reliable-message behavior are outside this
codec's API and audit scope.

## Reproduction

```sh
python3 scripts/BuildConnectCompressionOracle.py
java -cp craftq3-core/build/classes/java/main scripts/AuditConnectCompression.java \
  .tools/connect-compression-oracle/probe
python3 scripts/AuditConnectCompressionBoundaries.py
```

The fixtures contain no original assets. No archive, user installation or
production network session is changed by these capture-only codec probes.
