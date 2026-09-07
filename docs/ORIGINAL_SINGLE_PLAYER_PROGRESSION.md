# Original Single Player progression

The production application audit is now `AuditCampaignApplication.java`. It earns
the original tutorial win using movement, aim, weapon selection and fire through
`QuakeSession` and `Q3Input`, without injecting health, ammunition, frags or campaign
variables. Original qagame creates Crash and supplies the default skill 2 and
five-frag limit.

Both retail and 1.32 profiles complete this sequence:

1. Original Single Player menu → tutorial against Crash.
2. Five earned frags → automatic `video/tier1.roq` playback.
3. The movie reaches its final video frame (365), with all 273,792 soundtrack
   samples consumed by the CPU sink.
4. Original `nextmap=levelselect` → next arena selection → q3dm1 with Ranger.
5. Normal application close saves earned progress; a fresh application reloads
   identical score, award and movie flags and shows q3dm1 in its original menu.

The current input replay wins 5–1 in retail and 5–0 in 1.32. It follows the existing
four-point tutorial route, aims only at living targets, checks BSP visibility and
selects weapons only from original inventory. Seed 42 is a development fixture
setting. This is CPU integration evidence, not a native-audio or rendered full
campaign run. The separately verified Vulkan movie and gameplay paths remain
separate evidence. Later campaign tiers still require verification.

The replay found a real cgame crash: an all-solid impact supplied a zero decal
projection and undefined (NaN) polygon corners. The renderer already treats a
zero projection as empty, but the syscall adapter decoded the corners first and
terminated the VM. It now validates every guest buffer, reads the projection and
returns zero fragments for a zero direction before decoding undefined corners.
Nonzero invalid geometry still fails validation. A regression test also checks
untouched output buffers and invalid memory ranges.

Run the production audit with Java 25 and absolute paths:

```sh
./gradlew :craftq3-fabric:auditRemoteApplication \
  -Pq3CampaignAudit=true \
  -Pq3RemoteAuditGames=/absolute/path/to/Quake \
  -Pq3RemoteAuditOutput=/absolute/path/to/isolated-audit-output
```

The output directory receives a fresh generated-config home for each run. The
user's packs remain read-only. See [validation](VALIDATION.md) for current build
and packaged replay evidence.

## Historical component replay

The record below describes the earlier `AuditSinglePlayerProgression.java`
component harness. It did not implement the cinematic engine command, so its
postgame/Next sequence is not proof of the full application's campaign transition.
The production audit above supersedes that claim: original UI automatically plays
the newly earned movie and then requests `levelselect`.

`AuditSinglePlayerProgression.java` completes the original introduction under
the default skill 2 and original five-frag limit, follows the original postgame
Next button to q3dm1, then saves and reloads the earned campaign state in a new
original UI session. It does not change the bot default or game rules.

The test actor supplies ordinary human `UserCommand` movement, aim, weapon
selection and attack input. Four waypoints take the human around the tutorial
mirror and through the map's original teleporter. In the arena, the actor aims
at the bot and fires only when an additional read-only BSP ray is unobstructed.
Weapon selection uses ammunition already granted or picked up by the original
game. Original qagame alone owns collision, hits, damage, inventory, deaths,
respawns, scores and victory. The audit never injects health, frags, items,
victory, bot commands or campaign cvars.

## Verified sequence

The original retail UI emits `spmap q3dm0`; the host performs its normal local
Single Player startup. Original qagame creates Crash. With seed 42, the human's
five frags occurred at server times 16,900, 41,200, 64,200, 89,350 and 114,600 ms.
The final score was 5–0. At 115,650 ms, original qagame emitted its complete
`postgame` command. Original UI presented the medals and Menu/Replay/Next
controls. The script clicked the submitted Next quad; the UI emitted
`spmap q3dm1`.

The original UI recorded:

| Variable | Earned value |
| --- | --- |
| `g_spScores2` | `\l24\1` |
| `g_spAwards` | `\a0\1\a4\5\a5\1` |
| `g_spVideos` | `\tier1\1` |

These values are observed outputs, never inputs supplied by the audit. q3dm1
then initialized the original game and cgame, created Ranger, and ran another
thirty seconds with 562 live bot frames. The earned progress remained unchanged.

`GameConfig.save` wrote the actual `q3config.cfg` through an isolated
`GameFileStore`. A new cvar system loaded that file before constructing a fresh
original UI. Every saved score/award/video value matched, and the original arena
menu displayed q3dm1, q3dm2, q3dm3 and q3tourney1. The user PK3 was mounted
read-only throughout. The home directory contains generated configuration only.

## Required command fixes

The local server and client use separate command buffers. `postgame` originated
from qagame's server buffer, so both buffers must offer unrecognized engine
commands to UI, then cgame, then the local game's existing fallback. The
production `QuakeSession` now installs the same route for both buffers; this is
general command routing, not a special implementation of postgame.

`Q3Client.consoleCommand` now scopes the command passed by its caller instead
of relying on the client buffer's current arguments. It restores both previous
console context and reliable server-command context afterward. A nested foreign
buffer test covers both guest ABIs and verifies that arguments do not leak into
frame callbacks.

The public UI ABI specifies `UI_ConsoleCommand(int realTime)` in
[`ui_public.h`](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/ui/ui_public.h).
The new compatible `Q3Ui.consoleCommand(Command,int milliseconds)` overload
passes current engine real time even when the UI has been hidden during a
match. The prior overload remains available. This prevents postgame timing from
using the last visible menu frame. An authored guest test verifies current time,
scoped arguments and subsequent frame isolation for both ABIs. No native
routine bodies were inspected or translated for these fixes.

All 37 client-module tests pass. The inspected 897-test package also completes
the unchanged progression replay; see `/tmp/craftq3-single-player-progression897.log`.
The final 899-test package repeats the complete victory/Next/q3dm1/saved-reload
check successfully in `/tmp/craftq3-single-player-progression899.log`. The progression proof used inspected 853-test
engine jars with isolated current `Q3Client` and `Q3Ui` classes; its script
implements the same general dual-buffer route used by the production host.

## Reproduction

Use Java 25 and built engine classes/jars containing the two command-context
changes:

```sh
java -cp '<engine classpath>' scripts/AuditSinglePlayerProgression.java \
  .tools/pak0-audit/games 300000
```

The optional second argument bounds tutorial simulation time in milliseconds. The script
creates its own home under ignored `.tools/single-player-progression`, prints
the original map/postgame commands, scores and progress, and asserts next-level
startup plus fresh-session persistence. It prints the saved home path on
success. Evidence is in `/tmp/craftq3-single-player-progression.log` and
`/tmp/craftq3-campaign-client-tests.log`; the first successful saved home is
`.tools/single-player-progression/home-11612332329993992749`.

This validates original game rules, menu submissions, command routing and
configuration persistence through CPU sinks. It does not render GPU frames,
play audible sound or claim completion of every campaign tier. The earlier
rendered gameplay and audio checks remain separate evidence.
