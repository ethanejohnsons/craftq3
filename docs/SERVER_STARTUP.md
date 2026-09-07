# Fresh map startup

`Q3Server.initialize(startTime, seed)` now completes the original engine's settling
sequence before returning a running server. After `GAME_INIT`, it calls
`GAME_RUN_FRAME` at `startTime`, `startTime + 100`, `+200`, and `+300`. When bots are
enabled, `BOTAI_START_FRAME` follows each game frame at the same time. The returned
server clock is `startTime + 400`, and four simulation frames have completed.
These initialization steps do not consume pending console commands. Clients are
admitted after the sequence; the Fabric session adopts the returned clock before
initializing presentation and input. Time overflow is rejected before invoking
untrusted guest code.

Previously, fresh map loads admitted a player immediately after `GAME_INIT`.
Original game code defers part of item setup to later frames. Four retail maps
(`q3ctf4`, `q3dm10`, `q3tourney3`, and `q3tourney5`) could therefore fail during
player connection with `BG_CanItemBeGrabbed: index out of range`. Giving the
original VM its initialization frames resolves those observed failures without
changing item rules or original bytecode.

## Independent observations

The authored `ServerStartupOracle.c` surrounds the exported `VM_Call` boundary in
a local, unchanged ioquake3 dedicated build at commit
`588393618dbc82e7207c21c6ddecca229944a03a`. It records the public game-export number,
arguments and `server_t` clock/state metadata. Only the VM entry symbol is renamed
at compile time so the authored observer can forward calls; native engine routine
bodies are not modified, copied or translated into Java.

With initialization at zero, both `bot_enable=0` and `bot_enable=1` produced game
frames at **0, 100, 200, 300** while the native server remained in `SS_LOADING`.
The subsequent shutdown observation saw **400** and `SS_GAME`. With bots enabled,
the corresponding bot export followed each game frame; with bots disabled there
were no bot-frame calls. A configured `sv_fps=40` did not change this startup step.
This observation describes fresh-map startup; normal frame pacing and retained
client restart behavior are separate contracts.

The local native build and observer are development tools only:

```sh
python3 scripts/BuildServerStartupOracle.py
.tools/server-startup-oracle/probe-ded +set dedicated 1 +set com_basegame baseq3 \
  +set fs_basepath "$PWD/run/craftq3/games" \
  +set fs_homepath "$PWD/run/server-startup-oracle" \
  +set net_ip 127.0.0.1 +set net_port 27969 +set bot_enable 0 \
  +set sv_fps 40 +map q3dm10 +quit
```

No native engine, original map, game module or media is a runtime dependency or
bundled artifact. Original data remains in the user's PK3.

## Verification

An authored synthetic guest checks exact initialization timestamps, game/bot export
order, pending-command preservation, final clock/state and overflow atomicity.
The opt-in `auditStartup` task then exercises the real VMs and maps: load, complete
initialization, connect a living player and advance two seconds. **All 30 maps
with original AAS data pass for both the retail pak0 qagame and the unchanged
source-built modern qagame (60 successful map/profile combinations).**

```sh
./gradlew :craftq3-server:auditStartup
./gradlew :craftq3-server:auditStartup \
  -Pq3AuditVm=.tools/ioquake3-qvm-audit-build/Release/baseq3/vm/qagame.qvm
```

The gameplay, bot, combat and cgame development audits now anchor their input
schedules to `server.time()` after initialization. This preserves each requested
simulation duration without sending commands behind the initialized game clock.
Startup success does not assert a complete match, every door, or autonomous bot
navigation throughout a map.
