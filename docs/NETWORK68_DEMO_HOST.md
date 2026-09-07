# Native protocol-68 demo host contracts

These are bounded observations of the original client exports, independently of a renderer,
network session or real file. The existing framing and payload codecs are described in
[formats/DEMOS.md](formats/DEMOS.md). This work supplies recording/playback host rules; it does not
claim a complete native demo clock, seeking implementation or rendered playback.

## Provenance and reproduction

The reference is ignored ioquake3 commit `588393618dbc82e7207c21c6ddecca229944a03a`.
Only public structure declarations and exact exported signatures were inspected. Native routine
bodies, disassembly and IR were not read or translated. `DemoPlaybackOracle.c` supplies authored
memory-backed FS callbacks. `BuildDemoPlaybackOracle.py` compiles the original `cl_main.c`,
`cl_parse.c`, `q_shared.c`, `msg.c` and `huffman.c`; three definition-only renames allow observation
of completion, connectionless dispatch and parser entry. The called recording, playback-read,
packet-dispatch and snapshot routines retain their original bodies.

The cgame fixture reuses the independently authored command observer, with logging added only to
our filesystem/cvar callback stubs. No user assets, files, sockets or graphics devices are opened.

```sh
python3 scripts/BuildDemoPlaybackOracle.py
python3 scripts/BuildDemoCgameOracle.py
python3 scripts/AuditDemoBoundaries.py
java -cp craftq3-core/build/classes/java/main scripts/AuditNativeDemos.java 1500
java -cp craftq3-core/build/classes/java/main scripts/AuditDemoInvalidDelta.java
python3 scripts/BuildDemoLifecycleOracle.py
python3 scripts/AuditDemoLifecycle.py --timescale
```

## Framing and completion

`CL_ReadDemoMessage(void)` requests four bytes for a little-endian signed sequence, four for a
little-endian signed payload length, then the payload. It passes exactly those payload bytes to
the server-message parser with bit/read cursors zero. The supplied sequence is published separately;
no netchan fragmentation or XOR envelope is present. Successful reads refresh `lastPacketTime`
from `cls.realtime`.

| Input boundary | Native observation | Java playback policy |
| --- | --- | --- |
| Complete 0..16384-byte payload | Parse one message | Return one owned record |
| Length -1, any sequence | Complete without parsing | `End.MARKER` |
| EOF before next sequence | Complete | `End.PHYSICAL_EOF` |
| Partial sequence or length field | Complete | `End.TRUNCATED_HEADER` |
| Short payload | Print truncation message, complete | `End.TRUNCATED_PAYLOAD` |
| Length above16384 | ERR_DROP before payload read | Fail reader |
| Length below-1 | Negative FS_Read request | Reject before allocation/read |

The observer stops negative requests before any unsafe memory operation. Those are not behavior to
reproduce. `DemoReader(InputStream, Policy.PLAYBACK)` exposes tolerated native endings distinctly;
the default `STRICT` policy retains its original canonical -1/-1 requirement and explicit
truncation errors. Both reject unsafe lengths and propagate real I/O failures. Ended playback does
not publish or count partial records, and later `next()` calls remain empty.

## Recording transcript

`CL_Record_f(void)` accepts only `CA_ACTIVE` (8) among all states0..9. With compatibility enabled,
the fixture name becomes `demos/fixture.dm_68`. It writes an initial gamestate at
`serverMessageSequence - 1`, then sets recording and waiting true. The initial payload is:

1. Reliable acknowledgement equal to current `clc.reliableSequence`.
2. Gamestate command sequence equal to current `clc.serverCommandSequence`.
3. Current nonempty configstrings, in ascending index order.
4. Current entity baselines whose stored number is nonzero, in ascending slot order.
5. Gamestate EOF, client number and checksum feed, then message EOF.

A baseline in slot0 with type7/originX123.5 is omitted. The empty-state example
`record 8 1 42 7 9 3 12345` writes sequence41, payload length10 and
`bf8aa4a995adc63b6905`. Decoding yields acknowledgement7, command sequence9, client3 and feed12345.

`CL_ParseSnapshot` preserves waiting for an invalid missing-base delta and clears it for a full
snapshot (delta byte0). A subsequent valid delta leaves waiting clear. Separately, unchanged
`CL_PacketEvent` with an explicitly accepted channel and controlled parser output checks waiting
*after* parsing: the same packet that clears waiting is recorded. This dispatch fixture does not
stand in for a complete netchan/parser comparison. A later gamestate preserves both recording and
the existing waiting flag; it does not re-arm an already active recording.

`CL_WriteDemoMessage(msg, headerBytes)` writes the supplied current sequence, the remaining byte
count, and exactly `msg.data[headerBytes..cursize)`. The helper itself does not enforce the waiting
gate. `CL_StopRecord_f` writes canonical -1/-1, closes the file, clears recording and sets its handle
to0. A second stop writes nothing. An already recording start is refused.

The native initial-gamestate corpus passed **1,500 cases**, all metadata/baseline bytes and every
meaningful encoded bit. **142 cases** had different unused padding: for example a14992-bit message
has a1875-byte native buffer whose unused final byte contained0xc0. Java zero padding is deliberate;
no uninitialized native stack residue is reproduced. The same corpus verified **1,500 complete
framed streams /3,502,152 bytes** byte-for-byte while retaining their original opaque payloads, and
**1,500 raw playback reads**.

## Cgame command and system-info behavior

At latest command100, requests at or below36 have expired. In demo mode native
`CL_GetServerCommand` returns false and leaves prior argv and executed sequence unchanged; live mode
raises ERR_DROP. Missing but in-window records37/99 return true with empty argv and advance the
executed sequence in both modes. A future request101 raises ERR_DROP in both. This is a retained
ring boundary, not permission to fabricate absent received commands.

Consuming `cs 1` in demo mode still updates the stored configstring and `cl.serverId`. It performs
none of the observed live `FS_PureServerSetLoadedPaks`, `FS_PureServerSetReferencedPaks` or
`Cvar_SetSafe` effects, including for supplied `fs_game` and pure fields. These remain recorded data.

An independently authored packet proves tolerant invalid-delta publication: after valid snapshot90,
packet100 contains command5, a snapshot referencing absent99, then command6. Native consumes all307
bits, keeps both commands (latest6), and preserves snapshot90 byte-for-byte. This verifies ordinary
well-formed missing-base messages; malformed bitstreams still require separate rejection. The
strict server-message reader remains a separate contract.

## Start, disconnect, and input lifecycle

`DemoLifecycleOracle.c` separately delegates unchanged `CL_PlayDemo_f`, `CL_Disconnect`,
`CL_DemoCompleted`, the complete server-message/gamestate parser, and `CL_KeyEvent`. Its build makes
exact definition-only renames for interception. Console/message key handlers and binding dispatch
are authored logging callbacks; their downstream command behavior is outside this proof. Renderer,
sound, UI VM, transport, cvar and filesystem callbacks are also controlled. `CL_InitDownloads`
records entry and supplies `CA_PRIMED`, avoiding real downloads, asset loading or a graphics device.

Starting `fixture.dm_68` requests `sv_killserver=2`, then native `CL_Disconnect(true)`. Disconnect
sets `r_uiFullScreen=1`, clears the download name and both server pure-pack lists, sets `sv_cheats=1`,
stops cinematic/sound state and requests `UI_SET_ACTIVE_MENU(UIMENU_NONE)` when the show-menu argument
is true. It then opens `demos/fixture.dm_68` with a unique handle. The initial gamestate calls
`FS_ConditionalRestart(recordedChecksumFeed, false)`, followed by download initialization and
`cl_paused=0`. The observer reports the conditional-restart request; it does not substitute a claim
that the real filesystem always needs a physical restart.

A recorded system-info fixture supplies serverId42, pure1, pack lists, `fs_game=othergame`,
`sv_cheats=0` and `timescale=9`. Native keeps the previously selected `fs_game=baseq3` and timescale2,
sets cheats1, stores serverId42 and passes recorded feed12345 to conditional restart. This separates
the ignored recorded system-info effects from the initial gamestate's real filesystem operation.
`clc.demoName` becomes `fixture.dm_68`; these measured entrypoints do not set cvars named
`cl_demoplaying`, `cl_demorecording` or `cl_demoName`. Host-facing status cvars remain an explicit
application interface, not a claim about these native entrypoint writes.

Both explicit disconnect arguments close the demo handle and clear playing/recording state.
Argument0 omits the UI menu call; argument1 includes it. The authored network callback observes
three write requests when disconnecting the primed demo; no actual packet is sent. Completion
delegates disconnect, then reads `nextdemo`: empty queues nothing; a seeded `demo next` is cleared,
appended to the command buffer with a newline and executed. CraftQ3 also clears and queues the
host's `nextdemo`, with execution on the next host frame; recorded command text never gains
authority to supply or execute such a command chain.

The full public byte-key domain0..255 was checked in isolated calls with active demo, catcher0 and
camera mode0. Keydown exits exactly keys0..127 and Mouse1(178). Other keys, including arrows132..135
and F1(145), reach binding dispatch. Eleven representative key-up cases only dispatch release
bindings. The exit path clears `nextdemo`, stops cinematic state, sets `ui_singlePlayerActive=0`,
then raises `ERR_DISCONNECT`(3). The observer intercepts that error before engine-wide unwinding;
disconnect cleanup is established by the separate direct calls. UI catcher2 instead receives the
Escape key event without stopping playback; console catcher1 Escape still requests disconnect.
With camera mode1, the tested letter, Space and Mouse1 go to bindings; Escape still disconnects but
does not clear `nextdemo` in that branch.

The optional `--timescale` check uses the unchanged dedicated engine with a private temporary home,
read-only original pak0, private loopback and empty master addresses. Its console reports timescale
default1 and system-info/cheat flags, and rejects `set timescale 2` while cheats are disabled.
It also reports `com_cameraMode` default0 with the cheat flag and rejects setting1 under the same
condition. Demo start enables cheats but does not reset an existing timescale. This check does not claim native
render or timing performance. The lifecycle suite passes **290 isolated transcripts**, plus the
dedicated registration query; evidence is in `.tools/demo-lifecycle-oracle/lifecycle.json` and
`.tools/network-server-oracle/demo-timescale-registration.log`.

## Explicit remaining scope

The bounded boundary suite passes 58 isolated native call transcripts. Six playback-policy reader
tests and the five existing strict framing regressions pass. The separate streaming storage
capability is documented in [DEMO_STORAGE.md](DEMO_STORAGE.md).

The framing observer intercepts completion; the separate lifecycle observer establishes the bounded
start/disconnect/next-demo requests above. Full download/renderer initialization, timedemo reporting,
audio, rendering performance and seeking are not established by these probes. Playback clock
observations are separately documented in [DEMO_SERVER_CLOCK.md](DEMO_SERVER_CLOCK.md).
Secure host storage, UI policy and user-facing error reporting are independently owned behavior,
not native file-layout compatibility.
