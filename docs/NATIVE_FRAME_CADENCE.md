# Bounded native frame-cadence observation

This check records actual calls through the unchanged native engine's high-level
frame entrypoints. It distinguishes the order inside a server frame from the
order suggested by a flattened list of game exports. It does not establish
whole-engine cadence parity for CraftQ3's local session.

## Observer and provenance

[`FrameCadenceOracle.c`](../scripts/FrameCadenceOracle.c) wraps `Com_Frame`,
`SV_Frame`, `CL_Frame` and `SV_BotFrame`, and records game exports invoked by
server translation units. The wrappers delegate the original calls and record
their context, arguments, server time/state and client connection state. Only
the authored fixture queues commands: one `map_restart 0` after 90 ready frames
and `quit` after 180. UI and cgame VM calls are not redirected.

[`BuildFrameCadenceOracle.py`](../scripts/BuildFrameCadenceOracle.py) uses the
existing full native client build of official ioquake3 commit
`588393618dbc82e7207c21c6ddecca229944a03a`. It recompiles the relevant unchanged
translation units with symbol-renaming macros and relinks their objects with
the authored observer. Native routine bodies are neither inspected nor copied.
Public frame signatures, game-export argument declarations and header field
metadata supply the observer's contract. This is an ignored development
executable, never an embedded engine or a mod dependency.

[`RunFrameCadenceOracle.py`](../scripts/RunFrameCadenceOracle.py) supplies a
private ignored home directory and uses the original read-only pak0 plus
unchanged source-built QA QVMs. `net_enabled=0` disables network transports;
only the engine's internal loopback is available. Master addresses, downloads
and MOTD retrieval are disabled. Local window/sound settings belong solely to
that private home. Each subprocess has a 45-second termination limit.

## Dedicated path: measured frame ownership

The same full client binary, launched with `dedicated=1`, completed 180 fixture
frames, one fast restart and normal shutdown. Its transcript contains **1,854
call observations** at `.tools/frame-cadence-oracle/dedicated.log`. The main loop
itself invokes `Com_Frame`; the fixture does not simulate a server by manually
calling `SV_Frame` or `SV_BotFrame`.

For a steady frame with initial server time T, the observed order is:

1. `Com_Frame` enters `SV_Frame(50)`.
2. Inside that server frame, `SV_BotFrame(T)` invokes game export 10 at T.
3. The same server frame then invokes `GAME_RUN_FRAME(T+50)`.
4. `SV_Frame` returns; `Com_Frame` invokes `CL_Frame(50)`.
5. That dedicated client-frame call does not invoke bot AI.

For example, frame 85 invokes bot AI at 5,850, then the game frame at 5,900.
Frame 86 invokes bot AI at 5,900, then the game frame at 5,950. A flattened
transcript therefore also contains GAME(5,900) followed by BOT(5,900), but those
calls belong to different server frames. This evidence does not support
reversing the two calls solely from their equal timestamps.

The fixture requests fast restart after frame 90 at server time 6,150. In the
next `Com_Frame`, guest shutdown/init are followed by game-only settling frames
at 6,150, 6,250, 6,350 and 6,450. The ordinary `SV_Frame(50)` then starts at
6,550 and invokes bot AI at 6,550, followed by the game frame at 6,600. This
agrees with the independently observed restart argument sequence in
[BOTLIB_ENTITY_LIFETIME.md](BOTLIB_ENTITY_LIFETIME.md).

No individual bots were added to this scheduling fixture. The original
qagame's global bot-frame export still executes with `bot_enable=1`; the result
concerns export timing, not combat or inventory compatibility.

## Local path: explicit evidence limit

The bounded `dedicated=0` attempt did not reach `Com_Frame` or an active local
client before its 45-second timeout. The final startup transcript ends after
`Hunk_Clear: reset the hunk ok`; it contains no `CADENCE` call records. The
process was terminated and no native process remains. The transcript is
`.tools/frame-cadence-oracle/local.log`.

Two preliminary private-launch failures were corrected before that attempt:
the standalone engine needed `com_basegame=baseq3`, and the existing renderer
build needed `cl_renderer=opengl1`. The final unresolved startup wait was not
investigated by adding replacement services or changing the user's settings.
Native non-dedicated `CL_Frame`/bot ordering and local restart-boundary timing
remain unverified by this check.

CraftQ3 currently schedules bot updates inside its 50 ms local-server step,
before the game-frame export at the same requested server timestamp. The
dedicated timing observation above is recorded as a distinct contract. No
production scheduling change follows from an inconclusive local probe, and
the rendered bot, combat and match-restart checks do not by themselves prove
whole-engine frame-cadence parity.

## Reproduction

With the documented ignored native builds already available:

```sh
python3 scripts/BuildFrameCadenceOracle.py
python3 scripts/RunFrameCadenceOracle.py --mode dedicated
```

The optional `--mode local` command attempts the private native window and
retains the same timeout. The recorded local attempt is inconclusive; it must
not be reported as a successful local scheduling comparison.
