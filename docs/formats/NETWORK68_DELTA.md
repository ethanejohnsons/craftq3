# Protocol 68 state deltas

Entity states, player states and user commands travel as deltas against a baseline, written with
the bit stream of [NETWORK68.md](NETWORK68.md). The codecs work on canonical little-endian byte
images of the 1.32 structures: 208-byte `entityState_t`, 468-byte `playerState_t` and 24-byte
`usercmd_t`. Conversion to and from the 1999 retail layouts happens in the VM hosts
(`engine/vm-abi` layouts), never here.

Code (`engine/net`, package `net.delta`):
[`StateFields`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/delta/StateFields.java),
[`DeltaFields`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/delta/DeltaFields.java),
[`EntityDeltaCodec`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/delta/EntityDeltaCodec.java),
[`PlayerDeltaCodec`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/delta/PlayerDeltaCodec.java),
[`UserCommandDeltaCodec`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/delta/UserCommandDeltaCodec.java),
[`LegacyDeltaCodec`](../../engine/net/src/main/java/dev/bluevista/craftq3/engine/net/delta/LegacyDeltaCodec.java).
Snapshot bodies built from these are in [NETWORK68_MESSAGES.md](NETWORK68_MESSAGES.md).

## Field tables

`StateFields.ENTITY` (51 fields) and `StateFields.PLAYER` (48 fields) list, in wire order, each
field's name, canonical byte offset and wire width: 0 for a float, a positive bit count, or a
negative bit count for a signed field (see the sign convention in [NETWORK68.md](NETWORK68.md)).
The order and widths are protocol metadata from the public `q_shared.h` declarations and the
message field tables. Changes are detected on raw 32-bit words, before any narrowing.

## Field encoding

A changed-field list is an 8-bit count `n` (one past the last changed field), then for each of the
first `n` fields a changed bit and, if set, the value:

- Entity fields add a zero bit: 0 means the value is zero (a float `-0.0` therefore arrives as
  `+0.0`).
- Floats: a bit choosing the form, then either an integer in 13 bits biased by 4096 (exact
  integers from -4096 to 4095) or the raw 32-bit pattern (infinities and NaN payloads included).
- Integers: the low `|width|` bits. Unsigned fields decode to those bits; signed ones follow the
  sign convention. Unchanged fields keep the baseline word, even when it exceeds the wire width.

A reader rejects a count larger than the table.

## Entities

An entity record is the 10-bit entity number, a remove bit and, for a live entity, a changed bit
followed by the field list when set. Numbers are 0 to 1022; 1023 (`ENTITYNUM_NONE`) alone ends an
entity list. `EntityDeltaCodec.write` emits nothing for an unchanged entity unless forced (new
entities are forced against their baseline); a null baseline is the zero state and a null target
removes the entity. A decoded removal is a cleared state with number 1023. `readBody` lets a
snapshot reader consume the number first and choose the baseline.

## Players

A player delta always follows: the scalar field list (without the entity zero bit), then one bit
saying whether any array changed. If set, the four arrays follow in the wire order **stats,
persistant, ammo, powerups**, each as a changed bit, a 16-bit slot mask and the selected values.
Stats, persistant and ammo use width -16, powerups 32 bits. Canonical offsets are 184 (stats),
248 (persistant), 312 (powerups) and 376 (ammo); wire order differs from memory order. Words that
are not networked (`externalEventTime`, `ping`, `pmove_framecount`, `jumppad_frame`,
`entityEventSequence`) keep their baseline values.

## User commands

A user command delta is keyed:

1. Time: a bit, then either an 8-bit difference from the baseline time (when the signed difference
   is below 256, so a backward step wraps) or the full 32-bit time.
2. A bit saying whether any other field changed. If set, each field has a changed bit and, if
   set, its value XOR the key XOR the command time, in the wire order: three 16-bit angles, the
   8-bit forward, right and up moves, 16-bit buttons, 8-bit weapon.

Changed wide fields decode to their unsigned wire widths. When the field group is present, a
movement byte of -128 decodes as -127, also for unchanged moves. The canonical layout is time at
0, angles at 4/8/12, buttons at 16, weapon at 20 and moves at 21/22/23. The key derivation is in
[NETWORK68_MESSAGES.md](NETWORK68_MESSAGES.md).

## Older protocols (43, 46, 48)

`LegacyDeltaCodec` decodes the pre-1.29 deltas used by `.dm3` (protocol 43) and `.dm_48`
(protocols 46 and 48) demos onto the same canonical images, so they share the snapshot path.
Only decoding exists; these protocols are never written. The layouts were measured from retail
demos (see [DEMOS.md](DEMOS.md)):

- An entity body is a remove bit, a change bit and a 5-bit mask index. Indices 0 to 30 select a
  fixed table of field masks that the stream does not carry; index 31 is followed by an explicit
  mask with one bit per field. The selected fields' values follow without per-field bits.
- Protocol 43 entities have 50 fields (no `generic1`) and a 16-bit `eFlags`.
- A player delta is one change bit per field in declaration order (no `grapplePoint`, animation
  timers before animations, ending at `damageCount`), then 3 (protocol 43), 5 (46) or 6 (48)
  trailing change bits. Only the fourth of protocol 48's is identified (`jumppad_ent`); a set bit
  for any other is rejected because its width is unknown. The four arrays follow, each with its
  own changed bit (there is no overall array bit). Protocol 43's `externalEvent` is 8 bits.

All reads and writes are transactional: malformed input, truncation or a full writer restores the
cursor and publishes no partial state. Errors are `NetFormatException` (unchecked).

Independent implementation; see [LICENSE-NOTES.md](../../LICENSE-NOTES.md). Native wire captures
in the tests come from the earlier implementation's differential work described there. Tests:
`StateDeltaCodecTest`, `UserCommandDeltaCodecTest`, `LegacyDeltaCodecTest`.
