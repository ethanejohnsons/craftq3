# Elementary actions and bot input

`botlib.ea.ElementaryActions` collects the original qagame VM's action requests per client.
It does not implement bot AI, move actors, select tactics, interpret weapons, or send network
commands by itself. The host supplies the Quake client-command callback and consumes input snapshots.

The ABI reference is id Software's published
[botlib.h](https://github.com/id-Software/Quake-III-Arena/blob/master/code/game/botlib.h).
`BotInput` contains think time, movement direction and speed, view angles, action flags, and an opaque
weapon integer. `writeTo(ByteBuffer, offset)` writes the original 40-byte little-endian structure,
preserving the buffer's position and byte order. Its layout is:

| Offset | Value |
| ---: | --- |
| 0 | Float think time, seconds |
| 4 | Three float movement-direction components |
| 16 | Float movement speed |
| 20 | Three float view angles, degrees |
| 32 | 32-bit action flags |
| 36 | 32-bit weapon ID |

`ElementaryActions(maxClients, CommandSink)` accepts 1–64 client slots, indexed from zero.
Named button methods and `action(client, flags)` accumulate bits; the raw method preserves all
32 bits for original/extension actions. `move`, `view`, and `selectWeapon` replace their respective
values. Directions and view angles are float-quantized without normalization or angle wrapping.
Weapon IDs remain opaque integers; there are no Java weapon rules.

`getInput(client, thinkTime)` updates the stored think time and returns an immutable snapshot.
`snapshot(client)` observes it without changing think time. `resetInput` clears time, movement, and
regular action bits while preserving view and weapon. It retains `JUMPED_LAST_FRAME` when the
previous input contained `JUMP`. That marker suppresses `jump` and `delayedJump` for the following
frame, including clearing the relevant bit if it was supplied through a raw action. A subsequent
reset without `JUMP` removes the marker. A delayed jump alone remains `DELAYED_JUMP`; it is not
converted to a jump and does not set jump history. `endRegular` has no externally observable action
or command effect in the reference behavior.

The native reference clamps speed to **[-400, 400]**, even though the public header's field comment
describes a nonnegative range. The Java service preserves that observed signed behavior. Finite
think times are bounded to 0–3,600 seconds, direction components to ±1,000,000, and view components
to ±1,000,000,000 degrees. Invalid values fail before partially changing an input. These are explicit
host-side resource/value limits, not inferred bot gameplay rules.

`say` and `sayTeam` pass `say ` and `say_team ` prefixes followed by the literal text to the callback.
They do not add quoting, escape semicolons, or trim text. `command` passes its text unchanged.
The resulting command must be non-NUL Latin-1 text of at most 1,023 bytes, including any prefix.
The callback is synchronous and must treat the string as a Quake client command, never an operating
system command. The service borrows the callback and does not own host command resources.

Operations are synchronized and client slots are isolated. `clearClient` is the host's disconnect
or slot-reuse operation and clears held view/weapon along with frame input. Idempotent `close`
invalidates all slots and subsequent input or command requests fail explicitly.

The implementation was independently written from the public ABI and observed outputs. An
untouched `be_ea.c` from the official
[ioquake3 repository, commit 588393618dbc82e7207c21c6ddecca229944a03a](https://github.com/ioquake/ioq3/tree/588393618dbc82e7207c21c6ddecca229944a03a)
was compiled into an ignored reference object. Its routine bodies were neither read as an
implementation guide nor copied or mechanically translated into Java. The authored verification
driver calls public EA functions and provides only allocation/formatting/callback stubs. The
reference object and native executable remain under ignored `.tools/ea-oracle`; neither is a
dependency of the mod or its default tests.

Eight synthetic unit tests cover action combinations, raw flags, frame resets, held state,
jump/delayed-jump sequences, command text and bounds, per-client isolation, lifecycle, and exact
ABI offsets. A further **10,000 seeded operations across four clients matched every byte** of the
native reference's 40-byte inputs, including signed speed, raw action masks, resets, think time,
view angles, and weapon IDs. Separate native probes confirmed literal command formatting.

To reproduce the optional oracle audit with an existing official source checkout and Java 25:

```sh
mkdir -p .tools/ea-oracle
clang -c .tools/ioquake3-source/code/botlib/be_ea.c \
  -I .tools/ioquake3-source/code -o .tools/ea-oracle/be_ea.o
clang scripts/EaOracle.c .tools/ea-oracle/be_ea.o \
  -I .tools/ioquake3-source/code -o .tools/ea-oracle/ea-oracle
./gradlew :craftq3-botlib:classes
java --class-path craftq3-botlib/build/classes/java/main:craftq3-core/build/classes/java/main \
  scripts/AuditElementaryActions.java .tools/ea-oracle/ea-oracle
```

The driver expects a little-endian native target. The audit uses no commercial assets and opens no
game window or audio device. These checks establish this elementary-action boundary, not complete
botlib AI or gameplay compatibility.
