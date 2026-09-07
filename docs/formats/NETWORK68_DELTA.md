# Protocol 68 entity and player deltas

`core.net.delta` encodes and decodes canonical little-endian **208-byte entity states** and
**468-byte player states** using the existing fixed-Huffman `MessageWriter` and `MessageReader`.
It has no sockets, snapshot-history ownership, server connection, or demo playback. Retail
protocol-43 structures must first pass through the host's explicit canonical ABI conversion;
this codec only implements protocol 68.

The field names, canonical byte offsets, and ordered bit widths are protocol metadata from the
published [state declarations](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/qcommon/q_shared.h)
and the [message field tables](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/qcommon/msg.c#L744-L797).
`StateFields.ENTITY` and `StateFields.PLAYER` expose immutable tables containing 51 and 48 fields.
Zero width denotes a float; negative widths use the native signed-field convention documented in
[NETWORK68.md](NETWORK68.md).

## API and ownership

`EntityDeltaCodec.write(writer, from, to, force)` returns whether it emitted a record. A null
baseline means a zero state; a null target removes an existing entity. Both null means no record.
An unchanged entity emits nothing unless forced. Target entity numbers must be 0–1022; 1023 is
reserved for the list terminator. `writeEnd(writer)` emits that standalone terminator.

`EntityDeltaCodec.read(reader, baseline)` reads the entity number and body together. Its immutable
`EntityUpdate` reports the wire entity number, `removed`, `endOfList()`, and an owned state copy.
Removal states are fully cleared except for state.number = 1023, matching the native result.
The end marker also exposes a cleared sentinel state, but is distinguished by `endOfList()`.
`readBody(reader, baseline, number)` lets a snapshot merge first read the ten-bit number, select
the appropriate baseline, and then decode the body. It rejects the list terminator as a body ID.

`PlayerDeltaCodec.write(writer, baselineOrNull, target)` always emits a player delta.
`read(reader, baselineOrNull)` returns an owned complete player state. All input states must have
the exact canonical length. Input arrays and returned entity-state arrays are defensively copied.
Player words that are not networked retain their baseline values: `externalEventTime` at byte
136, plus `ping`, `pmove_framecount`, `jumppad_frame`, and `entityEventSequence` at bytes
452, 456, 460, and 464. A null baseline gives those words zero values.

Every complete delta read/write is transactional. Invalid field counts, truncated data, malformed
state sizes, invalid entity numbers, and output-capacity failure leave the original cursor unchanged.
`readBody` restores the body cursor, leaving an already-consumed number consumed. No partially
decoded state escapes. The underlying `transaction(Function<..., T>)` helpers support nesting.
The writer clears only bits written since its checkpoint on failure, retaining earlier bits and
allowing subsequent valid writes; it does not clone its 16 KiB message buffer per delta.

## Wire behavior

The entity record starts with its ten-bit number, followed by a removal flag. A live record then
has a changed-payload flag. A changed payload identifies the final changed field with an eight-bit
count and supplies a changed flag for each field through that count. Entity changed values have
an additional zero/nonzero flag. Unchanged fields retain their original 32-bit baseline words.

A changed nonzero float chooses between a biased 13-bit integer representation and its original
32-bit float representation. Exact integers from −4096 through 4095 use the compact form. Other
values retain their raw float bits, including infinity and NaN payloads. A changed negative zero
decodes as positive zero; unchanged negative zero stays unchanged. Changes are detected using raw
32-bit words, before any narrow-field truncation or float representation choice.

Player scalar fields also use an eight-bit extent and changed flags. They omit the entity-specific
zero flag: changed integer values use their field width directly, and changed floats immediately
choose compact or full representation. Integer wire widths transmit only the low requested bits;
unsigned fields decode to those bits, while signed fields extend the specified sign convention.
This does not normalize unchanged baseline values that happen to exceed a field's wire width.

Player array changes follow the scalar fields. An overall array-change bit gates four groups in
wire order: **stats, persistant, ammo, powerups**. Each group has a changed flag, then a 16-bit slot
mask and values for the selected slots. Stats, persistant, and ammo values decode as signed
16-bit integers. Powerups retain all 32 bits. Their canonical byte offsets are respectively
184, 248, 376, and 312; wire group order differs from canonical memory order.

## Verification and provenance

Thirteen authored tests cover native-captured wire examples at nonzero bit offsets, entity omission,
forced records, removal, list termination, float edge cases, narrow signed/unsigned values, every
array slot, non-networked words, input/output ownership, field-table coverage, nested transactions,
capacity recovery, malformed field counts, and rollback at every truncated bit of the captured
messages. The eight existing message-codec tests also pass with the transaction additions.

The authored `scripts/StateDeltaOracle.c` driver reuses the host stubs from `NetchanOracle.c` and
links unchanged `msg.c` and `huffman.c` objects from the official pinned checkout above. It exposes
field-table metadata and sends authored state pairs through native encode/decode operations.
No native routine body was copied or mechanically translated. Native code remains an ignored
verification tool, with no runtime dependency or bundled source in the mod.

`AuditStateDeltas.java` verifies all 99 metadata entries and compares full encoded byte sequences,
bit positions, and full decoded canonical states. The completed audit covered **9,550 cases** and
**777,185 wire bytes**, with **zero mismatches**. Cases include each field's boundary values in
both delta directions, 6,000 seeded random sparse/dense baseline cases, all 64 player array slots,
and starting bit offsets 0–7. No commercial assets are required by either audit or tests.

After preparing the unchanged reference objects as described in `NETWORK68.md`, reproduce with:

```sh
clang -std=c11 -O2 -Wl,-dead_strip \
  -I.tools/ioquake3-source/code -I.tools/ioquake3-source/code/qcommon \
  scripts/StateDeltaOracle.c .tools/netchan-oracle/msg.o .tools/netchan-oracle/huffman.o \
  -o .tools/netchan-oracle/state-delta-oracle
./gradlew :craftq3-core:classes
java -cp craftq3-core/build/classes/java/main scripts/AuditStateDeltas.java
```

The linker option is macOS-specific. Null entity baselines are normalized to a zero structure in
the oracle adapter because the native entity writer requires a concrete baseline for live updates.
The driver validates hex input lengths before writing into its fixed state buffers. These results
establish delta compatibility; they do not establish complete snapshots or remote interoperability.

## Keyed user-command deltas

`UserCommandDeltaCodec.write(writer, key, baseline, target)` and `read(reader, key, baseline)`
operate on the published 24-byte `usercmd_t` layout: time at byte 0; three integer angles at 4/8/12;
buttons at 16; weapon at 20; and signed forward/right/up bytes at 21/22/23. A null baseline means
zero state. Inputs are copied, output is owned, and failed message operations are transactional.
This layout is separate from retail QVM command packing handled by the server adapter.

Time uses a one-bit choice followed by an eight-bit difference or full 32-bit timestamp. The
observed short form tests a signed difference below 256, including negative differences. Thus a
backward timestamp can decode as a wrapped eight-bit forward difference; the codec preserves that
wire behavior, while session-level command-time validation remains separate. Int timestamp wrap
is retained. A second bit indicates whether any command fields changed.

Changed fields have individual presence bits and use the supplied key XOR timestamp. Wire order
is three 16-bit angles, three eight-bit movement values, 16-bit buttons and an eight-bit weapon.
Unchanged wide fields retain their baseline words; changed wide fields decode to their unsigned
wire widths. Movement byte -128 becomes -127 when the field group is present, including when that
particular movement field was unchanged. An absent group copies the baseline movement bytes.
These are protocol observations, not a claim of cryptographic security or complete input packets.

Five tests cover captured native bytes, keyed fields, timestamp boundaries, unchanged high bits,
movement normalization, ownership and rollback. `UserCommandOracle.c` calls unchanged native
`MSG_WriteDeltaUsercmdKey` and `MSG_ReadDeltaUsercmdKey`. `AuditUserCommands.java` compares **32,000
keyed deltas / 226,197 wire bytes** with exact encoded bits and decoded 24-byte states at all eight
alignments. It includes all field groups, random key/state patterns, unchanged commands, negative
time differences and integer-wrap probes. Reliable-command key derivation and client/server payload
codecs are now documented in [NETWORK68_MESSAGES.md](NETWORK68_MESSAGES.md), alongside legacy
payload XOR. Transport, session integration and retail protocol 43 remain separate work.

```sh
clang -std=c11 -O2 -Wl,-dead_strip -I.tools/ioquake3-source/code \
  scripts/UserCommandOracle.c .tools/netchan-oracle/net_chan.o \
  .tools/netchan-oracle/msg.o .tools/netchan-oracle/huffman.o \
  -o .tools/netchan-oracle/usercmd-oracle
java -cp craftq3-core/build/classes/java/main scripts/AuditUserCommands.java
```

The local game/bot `UserCommand` representation separately accepts signed movement bytes
from -128 through 127, as declared by the public `usercmd_t` fields. Trap211 preserves
all three bytes through either guest ABI. Network delta normalization is a decoder
operation; it is not applied to original bot-to-server commands. The local bridge
has exhaustive byte-range round-trip coverage, and the formerly rejected original
Anarki/Major requests now complete their five-minute runs.
