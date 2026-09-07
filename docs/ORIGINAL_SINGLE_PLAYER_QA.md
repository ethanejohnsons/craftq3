# Original Single Player menu and Crash encounter

`scripts/AuditSinglePlayerGame.java` executes the user-supplied retail UI, qagame
and cgame QVMs with read-only PK3 assets. It does not write or register
`bot_enable`; it asserts the production default is one after server creation.
It never submits `addbot`, changes a
player state, teleports a player through host writes, or replaces bot behavior.

The original menu sequence is MAIN, ordinary Escape/main-menu handling, Enter
to Single Player, click Fight, then Enter on the difficulty screen. The click
coordinates were derived from the original submitted Fight and cursor quads:
the 1280×720 presentation uses a 960×720 menu region, corresponding to the
640×480 virtual menu. Fight is centered at virtual `(576,448)`. No CD key or
key-validation cvar is fabricated.

The UI emits exactly `spmap q3dm0`. The authored host records that command and
defers game creation until command dispatch has unwound. As in `QuakeSession`,
`spmap` sets `g_gametype=2`, disables cheats, clears pause, creates the local
server/client with shared cvars and commands, initializes the game, connects the
human in slot zero, and initializes cgame before draining subsequent server
commands. Original qagame creates Crash in slot one from its arena definition.

The simulation advances in 50 ms server ticks, then supplies actual human
`UserCommand` input and runs original cgame. The optional encounter route uses
four authored human-input waypoints around the tutorial mirror and through the
original teleporter. These are normal forward/view-angle inputs; the original
game owns collision, teleportation and all bot inputs. After entering the arena,
the human stands still. Read-only observation of the server's retained bot
`UserCommand` array counts attacks. Player-state observations count health,
movement and human deaths; movements crossing the teleport flag are excluded
from the bot distance measurement.

## Verified retail run

The initial evidence used the inspected 830-test engine snapshot at
`/tmp/craftq3-platform-parity-runtime` plus the isolated empty-shader fix and an
explicit QA bot override. The final proof uses only the inspected 853-test
engine jars at `/tmp/craftq3-bot-play-runtime`, with the normal bot default and
both client fixes included. No production instrumentation was added.

| Observation | Result |
| --- | --- |
| Original UI request | `spmap q3dm0` |
| Original bot | Crash, slot 1, skill 2 |
| First live bot state | server time 13,350 ms |
| Live bot frames / moving frames | 962 / 870 |
| Measured movement | 12,086.60 Q3 units |
| First attack / attack-input frames | 37,450 ms / 54 |
| Human deaths | 1; original game later respawned the human |
| Cgame views / quads / entities | 3,600 / 64,240 / 26,399 |
| Crash entry cue | `sound/player/announce/crash.wav`, LOCAL, entity 0, channel 6 |
| Missing menu/game traps | None on this path |

Crash's original delayed entry explains the earlier ten-second startup probe
showing health zero and no movement: that probe ended before ClientBegin.
An idle-human sixty-second control also passed bot movement (922 moving frames,
12,703.95 units), with zero attacks while the human remained in the tutorial
spawn room.

The encounter exposed an engine command-routing gap: qagame queued `play` in
the server's local command buffer. The new client-owned sound-command handler
serves both local buffers, and the repeated original run now delivers the entry
cue without an unknown-command message. See [sound command proof](CLIENT_SOUND_COMMANDS.md).

## Reproduction and limits

Run with Java 25 and the built engine modules, or the inspected engine-jar
snapshot:

```sh
java -Daudit.enterArena=true -cp '/tmp/craftq3-bot-play-runtime/*' \
  scripts/AuditSinglePlayerGame.java .tools/pak0-audit/games 60000
```

Omit `audit.enterArena` for the idle-human control. The script asserts the
normal bot default, original menu request, cgame view/HUD output, bot movement,
and Crash entry cue for runs of at least fifteen seconds.
The encounter mode additionally requires attack input and a human death. It
prints sampled player states and a final result record. Local evidence is in
`/tmp/craftq3-single-player-game.log`,
`/tmp/craftq3-single-player-game-arena.log`, and
`/tmp/craftq3-single-player-game-play.log`. The final default-enabled snapshot
proof is `/tmp/craftq3-single-player-game-default853.log`.

This is CPU command/game/presentation QA. The audio sink records requests and
does not play them; no GPU rendering is performed by this script. It does not
claim arena victory, tournament progression, saved profile persistence, or
coverage of every menu. The rendered-bot and session-lifecycle checks are
separate validations.

The subsequent [progression audit](ORIGINAL_SINGLE_PLAYER_PROGRESSION.md) adds a
legitimate tutorial win, original postgame/Next, q3dm1 startup, and saved campaign
reload using a separate script.
