# Protocol 68 snapshots and server payloads

`SnapshotDeltaCodec` and `ServerMessageCodec` compose the independently implemented Huffman,
string and canonical state codecs. They produce owned data and execute no commands. They do not
open sockets, connect to servers or play demos. The standalone server codec covers no-op, gamestate,
reliable server-command and snapshot operations; downloads, voice and other operations fail explicitly.

## Snapshot bodies

`Snapshot(sequence, time, flags, areaMask, player, entities)` owns a canonical 468-byte player state,
an area mask of at most 32 bytes and at most 256 strictly ordered canonical 208-byte entities.
Entity IDs are 0–1022. All array getters return copies; collections are immutable. `Baselines`
owns the ordered level entity states, with zero state for absent IDs. Sequence is positive and
comes from the containing netchan/demo record; the body does not transmit it.

A body contains time, an eight-bit delta distance, flags, area-mask length/data, player delta and
an ordered entity update stream ending in ID 1023. A zero distance means a full snapshot. Otherwise
the exact prior message must be available, with distance 1–255. The codec can take an explicit
previous snapshot or a history lookup called once with the required message number. Full snapshots
never consult history. This does not establish how long a transport should retain history.

Entity merging retains omitted previous entities, removes explicit deletions, updates existing
states against the prior snapshot and creates new states against level baselines. New entities are
forced onto the wire even when identical to their level baseline. Player fields follow the existing
delta contract, including non-networked fields retaining baseline values and changed narrow fields
decoding to their wire representation. Area-mask bytes beyond the transmitted length are implicitly
zero; callers needing the fixed 32-byte VM field must pad them.

Complete reads and writes are transactional. Missing/wrong history, unordered or duplicate wire IDs,
oversized masks/entity counts, invalid state sizes and truncated fields restore the starting cursor.
The 256-entity bound follows the published server snapshot limit; broader malformed native input
behavior is not emulated. Missing-history packets must be discarded or otherwise handled by the
future session owner without treating them as a usable baseline.

## Server payloads

`Message(reliableAcknowledge, operations)` retains operation order. The codec reads/writes the initial
32-bit acknowledgement and final service EOF. Operations are:

- `NoOp.INSTANCE`.
- `Command(sequence, text)`: bounded 1,023-byte reliable command text; no tokenization or execution.
- `GameState(commandSequence, configstrings, baselines, clientNumber, checksumFeed)`: configstring
  records, entity baselines, nested EOF and trailing client/checksum fields.
- `Frame(current, previous)`: the snapshot body, with null previous for full updates.

Gamestate configstring indices are 0–1023, individual strings are at most 8,191 bytes and total stored
text is at most 16,000 bytes including terminators and the initial empty byte. Configstrings and
baselines are written in numeric order. Duplicate incoming indices keep the final value, but every
received string still charges the parsing budget. The exposed map omits empty values and compacts
duplicates; it represents semantic strings rather than native storage offsets. A gamestate replaces
the level baselines and invalidates prior snapshot history for later operations in that payload.

Server command and configstring output applies the independently verified wire-string filtering
of percent signs and high bytes. Public operation constructors reject embedded NUL, non-byte text
and overlong values instead of silently discarding their content. Acknowledgement/command sequencing,
retransmission, command deduplication, system-info effects and checksum verification belong to a
session owner. The codec accepts integer metadata without invoking those effects.

At most 4,096 outer operations or nested gamestate records are accepted. Unsupported operations,
invalid baseline markers and malformed/budget-exceeding records fail the whole read or write
transaction. No partial command list escapes. Input stops at service EOF; surrounding demo/netchan
framing and legacy payload XOR are separate layers. Protocol 43 and 71 are not claimed.

## Verification and provenance

The service numbers, state layouts and limits come from the public
[protocol declarations](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/qcommon/qcommon.h)
and [client snapshot declarations](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/client/client.h).
The Java body construction and merge are original implementations. Authored native observers link
unchanged `cl_parse.c`, `msg.c`, `huffman.c` and, for complete payloads, `q_shared.c` objects from that
official pinned commit. Native engine function bodies were not used as translation recipes.

Six snapshot tests cover full updates, baseline/new/existing/removed entity merges, ownership, lookup
and distance validation, every truncated bit, malformed ordering, capacity rollback and count bounds.
Six server-payload tests cover level resets, operation order, byte filtering, duplicates, storage
budgets, unsupported services, invalid records, ownership and transaction rollback.

`AuditSnapshots.java` sends **3,000 Java-written bodies / 1,305,002 bytes** to unchanged native
`CL_ParseSnapshot`. All **62,751 entity states**, complete player/area states, metadata and bit cursors
agree. Cases include full and delta updates, every initial bit alignment, 256-entity snapshots,
ID 1022, arbitrary integer timestamps/flags and level baselines. The empty full snapshot is 25 bits,
hex `aaaa2601`, and is accepted by the native parser.

`AuditServerMessages.java` sends **1,500 complete payloads / 554,399 bytes** through unchanged native
`CL_ParseServerMessage` in legacy mode. Level metadata, configstrings, baselines, **21,888 entities**,
**2,550 reliable commands**, snapshot history, command timing and read cursors agree. It includes
full updates, delta chains, commands before/after snapshots, level restarts and string filtering.
These are native parser acceptance/state comparisons, **not a comparison against native writer
byte sequences**. Existing field/state codec audits separately establish their native byte parity.

The server observer isolates filesystem, cvar and download callbacks. It does not apply server
settings, touch installation files or download data. Neither observer nor native objects are in
the distributed mod, and these corpora require no commercial assets.

## Client payloads and movement keys

`ClientMessageCodec` handles the three integer headers (server ID, message acknowledgement and
server-command acknowledgement), up to 64 reliable client commands and an optional batch of 1–32
canonical user commands. Movement records distinguish requesting delta snapshots from requesting
a full snapshot. Their command deltas start from zero and then use the preceding command. A payload
without movement does not consult reliable server-command history. The full writer appends client
EOF; `writeBody` exposes the pre-transport boundary used by native `CL_WritePacket`.

`MessageHash.key` implements the observed `MSG_HashKey` weighted byte checksum: percent/high bytes
use the wire-string dot replacement, NUL ends the text, and a caller-supplied prefix bound applies.
The accumulator wraps at 32 bits and folds with signed shifts. The usercmd key combines the checksum
feed, message acknowledgement and the first 32 bytes of the acknowledged reliable server command;
the existing usercmd delta codec then mixes each command's timestamp. This is legacy key mixing,
not cryptographic authentication.

Client reads require that exact acknowledged command through a lookup callback when movement is
present. Missing history, invalid counts, unsupported service codes, records after terminal movement
and truncated input fail transactionally. No-op records before movement are accepted and omitted
from the returned semantic command list. Public records own their arrays/collections. Sequence
validation, rate limits, command execution, retransmission and connection negotiation remain outside
this codec. Five tests cover batches, both delta flags, history selection, reliable-only payloads,
every truncated bit, malformed counts/ordering, capacity rollback and ownership. Three checksum tests
cover independently observed values, filtering, bounds and key composition.

`AuditMessageHashes.java` compares **10,256 results** with unchanged public `MSG_HashKey`, including
the byte alphabet, NUL/prefix limits and 16 KiB inputs whose weighted sums wrap. `AuditClientMessages.java`
compares **4,000 bodies / 1,495,809 normalized bytes** against unchanged native `CL_WritePacket`;
all meaningful bits agree, and **63,909 user commands** decode correctly. It exercises every batch
size, both delta flags, arbitrary integer headers/feeds and varied acknowledged server text. This
also verifies the movement-key derivation in its native packet context.

The client observer captures the body at the transport callback and zeroes only unused tail bits.
Native stack storage can leave the trailing, wholly unused byte uninitialized when the body ends
on a byte boundary; those bits are not compared as protocol data. The comparison precedes transport
EOF insertion, XOR and netchan framing, so it does not establish complete datagram interoperability.
The native observer's transport callback does not send any packet.

## Legacy payload transformation

`LegacyPayloadXor.client` and `.server` operate on owned copies of complete compressed payloads,
including the service terminator. Apply them before fragmentation on send and after reassembly on
receive. Client payloads retain the first 12 bytes; server payloads retain the first four bytes.
The remaining bytes use a running XOR value derived from the connection challenge and message
metadata, modified by a cyclic acknowledged-command byte string and alternating shifts. The same
operation reverses the transformation. This is the original protocol's obfuscation, not encryption
providing confidentiality or authenticity.

The session supplies the exact acknowledged command and header metadata. This service neither
looks up reliable histories nor parses potentially transformed header fields. It bounds payloads to
16 KiB and consumed key strings to 1,023 bytes, ends strings at NUL and applies the native
percent/high-byte replacement. Payloads no longer than their clear prefix are returned unchanged
without consulting command text. Every result owns its bytes; validation failure cannot alter input.

Four focused tests cover captured native ciphertext, both prefixes, byte/size boundaries, empty and
NUL-terminated keys, filtering, ownership and inverse transforms. `AuditLegacyXor.java` compares
**12,000 encoded payloads / 10,362,895 exact bytes** with unchanged native client/server wrappers,
including queued server sends. The native receive wrappers also recover **all 12,000 original
payloads**. The observer supplies framing metadata through an isolated netchan callback and aliases
the bounded reliable-command ring to the supplied key; reliable-history selection is therefore
outside this audit. No packet is sent to a network.

Both native connection and channel compatibility flags are set explicitly. Key buffers are
zero-padded C strings: for an empty string, the native loop can otherwise consult bytes after its
first NUL. Java stays within the consumed key and does not emulate those stale-buffer effects.
The meaningful server/client payload and snapshot corpora were also rerun with both legacy flags.

Connect compression, a pinned UDP transport and client session ownership now have a
[real native UDP interoperability check](../NETWORK68_CLIENT_SESSION.md). Pure verification,
remote cgame presentation and server-side connection integration remain unfinished.

After preparing the unchanged message/reference objects described in `NETWORK68.md` and
`BOTLIB_CHAT.md`, reproduce on the tested macOS host with:

```sh
mkdir -p .tools/server-message-oracle
clang -std=c11 -O2 -ffunction-sections -fdata-sections -I.tools/ioquake3-source/code \
  -c .tools/ioquake3-source/code/client/cl_parse.c -o .tools/server-message-oracle/cl_parse.o
clang -std=c11 -O2 -Wl,-dead_strip -I.tools/ioquake3-source/code scripts/SnapshotOracle.c \
  .tools/server-message-oracle/cl_parse.o .tools/netchan-oracle/msg.o \
  .tools/netchan-oracle/huffman.o -o .tools/server-message-oracle/snapshot-oracle
clang -std=c11 -O2 -Wl,-dead_strip -I.tools/ioquake3-source/code scripts/ServerMessageOracle.c \
  .tools/server-message-oracle/cl_parse.o .tools/netchan-oracle/msg.o \
  .tools/netchan-oracle/huffman.o .tools/chat-oracle/q_shared.o \
  -o .tools/server-message-oracle/message-oracle
./gradlew :craftq3-core:classes
java -cp craftq3-core/build/classes/java/main scripts/AuditSnapshots.java
java -cp craftq3-core/build/classes/java/main scripts/AuditServerMessages.java
clang -std=c11 -O2 -Wl,-dead_strip -I.tools/ioquake3-source/code scripts/MessageHashOracle.c \
  .tools/netchan-oracle/msg.o .tools/netchan-oracle/huffman.o -o .tools/netchan-oracle/hash-oracle
clang -std=c11 -O2 -ffunction-sections -fdata-sections -I.tools/ioquake3-source/code \
  -c .tools/ioquake3-source/code/client/cl_input.c -o .tools/server-message-oracle/cl_input.o
clang -std=c11 -O2 -Wl,-dead_strip -I.tools/ioquake3-source/code scripts/ClientMessageOracle.c \
  .tools/server-message-oracle/cl_input.o .tools/netchan-oracle/msg.o \
  .tools/netchan-oracle/huffman.o -o .tools/server-message-oracle/client-message-oracle
java -cp craftq3-core/build/classes/java/main scripts/AuditMessageHashes.java
java -cp craftq3-core/build/classes/java/main scripts/AuditClientMessages.java
clang -std=c11 -O2 -ffunction-sections -fdata-sections -I.tools/ioquake3-source/code \
  -c .tools/ioquake3-source/code/client/cl_net_chan.c -o .tools/server-message-oracle/cl_net_chan.o
clang -std=c11 -O2 -ffunction-sections -fdata-sections -I.tools/ioquake3-source/code \
  -c .tools/ioquake3-source/code/server/sv_net_chan.c -o .tools/server-message-oracle/sv_net_chan.o
clang -std=c11 -O2 -DORACLE_CLIENT -Wl,-dead_strip -I.tools/ioquake3-source/code \
  scripts/LegacyXorOracle.c .tools/server-message-oracle/cl_net_chan.o \
  .tools/netchan-oracle/msg.o .tools/netchan-oracle/huffman.o \
  -o .tools/server-message-oracle/client-xor-oracle
clang -std=c11 -O2 -Wl,-dead_strip -I.tools/ioquake3-source/code scripts/LegacyXorOracle.c \
  .tools/server-message-oracle/sv_net_chan.o .tools/netchan-oracle/msg.o \
  .tools/netchan-oracle/huffman.o -o .tools/server-message-oracle/server-xor-oracle
java -cp craftq3-core/build/classes/java/main scripts/AuditLegacyXor.java
```
