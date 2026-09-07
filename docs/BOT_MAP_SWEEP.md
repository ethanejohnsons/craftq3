# Original-map bot sweep

All 30 original playable maps now have a completed 60-second movement run with
both retail pak0 qagame and the unchanged source-built modern qagame. This is
cumulative evidence: the full 782-test sweep completed 58 of 60 runs, and the
826-test platform retry completes both q3dm19 runs. It is not a new 60-run sweep
of one final package. Each uses seed 42, original bot decisions, a stationary
human target and 50 ms server steps after the measured 400 ms settling sequence.
A fixed, ignored pak0-only installation prevents later pack additions from
changing the baseline; the archive remains intact.

Movement requires repeated move-to-goal requests, ten moving frames and 128
horizontal units, excluding teleport/respawn discontinuities. It does not prove
that the bot traversed every part of a map or completed a match.

Combat was additionally required on q3dm1 and q3dm17. Both retail maps and modern
q3dm1 pass; modern q3dm17 completes movement but records no scored kill or ammo
use, and its additional combat assertion fails. The original sweep had 57 successful exits; the two platform retries add two more.
All other rows below assert movement only, even when an incidental target kill
occurred.

| Map | Retail | Modern |
| --- | --- | --- |
| q3ctf1 | Pass | Pass |
| q3ctf2 | Pass | Pass |
| q3ctf3 | Pass | Pass |
| q3ctf4 | Pass | Pass |
| q3dm0 | Pass | Pass |
| q3dm1 | Pass | Pass |
| q3dm10 | Pass | Pass |
| q3dm11 | Pass | Pass |
| q3dm12 | Pass | Pass |
| q3dm13 | Pass | Pass |
| q3dm14 | Pass | Pass |
| q3dm15 | Pass | Pass |
| q3dm16 | Pass | Pass |
| q3dm17 | Pass | Pass; combat not passed |
| q3dm18 | Pass | Pass |
| q3dm19 | Pass (826-test retry) | Pass (826-test retry) |
| q3dm2 | Pass | Pass |
| q3dm3 | Pass | Pass |
| q3dm4 | Pass | Pass |
| q3dm5 | Pass | Pass |
| q3dm6 | Pass | Pass |
| q3dm7 | Pass | Pass |
| q3dm8 | Pass | Pass |
| q3dm9 | Pass | Pass |
| q3tourney1 | Pass | Pass |
| q3tourney2 | Pass | Pass |
| q3tourney3 | Pass | Pass |
| q3tourney4 | Pass | Pass |
| q3tourney5 | Pass | Pass |
| q3tourney6 | Pass | Pass |

The q3dm19 retry records 1,018 moving frames and 22,539.42 horizontal units for
retail, and 1,036 frames and 17,383.26 units for mixed-data modern. Both have
1,200 samples. The matching-data modern profile separately records 1,064 moving
frames and 16,715.59 units, with a stationary-target death at 48.05 seconds.
These are movement-only assertions. Logs are under
`/tmp/craftq3-standing-bobbing-retries/`.

A fixed duration and one seed do not establish broader map, match, multi-bot or
mod compatibility. Ordinary gameplay bots remain disabled pending broader
integration work.

The local sweep uses engine jars captured from the inspected distributable, so
concurrent source changes cannot alter these results. Logs and JSON are under
`/tmp/craftq3-jump-clearance-sweep/`. The earlier attempt used the live
installation while its packs were changing; its mixed-corpus results are discarded.
A representative row can be reproduced after building with:

```sh
./gradlew :craftq3-server:auditBots -Pq3Map=q3dm10 \
  -Pq3Installation=.tools/pak0-audit/games \
  -Pq3BotMillis=60000 -Pq3RequireBotMovement=true
# For the separately built modern guest, add:
# -Pq3AuditVm=.tools/ioquake3-qvm-audit-build/Release/baseq3/vm/qagame.qvm
# Require a scored bot kill and ammunition use with -Pq3RequireBotCombat=true.
```

The Gradle task uses the configured installation. Exact baseline reproduction
requires pak0-only content; later patch packs and community overrides define a
different corpus.

## Previous checkpoint

The 705-test snapshot completed sustained movement in 41 of 60 runs. Visibility,
steep-incline and liquid integration added four full-minute movement passes:
modern q3tourney3, modern q3dm12 and both q3dm8 profiles. Earlier q3dm4, q3dm15 and
retail q3tourney4 failures progressed to later low-clearance or jump requests,
which remain recorded as incomplete runs in the current table.

The intermediate 755-test snapshot completed 45 of 60 movement runs. The 782-test
checkpoint adds all 13 previously incomplete ordinary/weapon-jump and low-clearance
scenarios. This is still a one-minute, one-bot, one-seed corpus.

The modern profile deliberately replaced qagame while retaining pak0 bot data.
A subsequent native weapon-choice comparison establishes that its q3dm17 combat
failure is caused by mismatched inventory definitions. A separately labeled
matching-header QA run passes combat; it does not retroactively change the table.
See [BOTLIB_INVENTORY_COMPATIBILITY.md](BOTLIB_INVENTORY_COMPATIBILITY.md).

## Five-minute bot variations

Twelve retail scenarios vary bot, skill and seed over 300 seconds. The original
782-test snapshot completes nine movement scenarios. Three stop on concrete
engine boundaries: Anarki on q3tourney4 and Major on q3dm11 emit the valid signed
movement byte -128; Bones on q3tourney2 encounters a tilted step landing. The
subsequent focused fixes each complete a full 300-second retry, so all twelve
scenarios now have a completed movement run. This is cumulative evidence across
checkpoints, not a repeated twelve-scenario run of one final package.

The scenarios are Sarge/skill3/seed42 on q3dm1, q3dm17, q3tourney4, q3dm6 and
q3dm8; Hunter/4/73 on q3dm12; Anarki/5/13 on q3tourney4; Visor/4/73 on q3dm6;
Xaero/5/13 on q3dm17; Bones/3/42 on q3tourney2; Tankjr/4/73 on q3dm5; and
Major/3/13 on q3dm11. Each completed run has 6,000 samples.

The 789-test package retries Anarki with 5,860 moving frames and 69,057.86
horizontal units, and Major with 5,855 moving frames and 83,544.09 units. The
subsequent positive-unit step fix retries Bones with 5,935 moving frames and
77,382.18 units. Each also records a stationary-target death, but these retries
assert movement only. Logs are under `/tmp/craftq3-jump-clearance-long-bots/`,
`/tmp/craftq3-byte-bsp-long-retries/` and the predictor audit notes.

Sarge's q3dm17 five-minute run completes movement and kills the target at 7.45
seconds, but finishes with score zero after later bot deaths. Its extra combat
assertion requires a positive final score and therefore fails. It is not counted
as a full assertion pass.

The audit accepts `-Pq3BotName=bones`, `-Pq3BotSkill=3` (integer 1–5),
`-Pq3Seed=42` and `-Pq3BotMillis=300000`; defaults remain Sarge, skill 3, seed 42.
An explicit `-Pq3BotInventoryHeader=path/to/inv.h` supplies a bounded in-memory
QA overlay for matching modern guest definitions. It does not rewrite an archive
or alter normal game asset selection. See the inventory compatibility record
before comparing modern guest runs with a retail-data baseline.

## Platform endurance and several bots

The inspected 826-test package also completes six additional scenarios. Retail
and matching-data modern q3dm19 each run one Sarge bot for 300 seconds. Their
6,000 samples include 3,933 and 1,733 moving frames respectively; the movement
assertion does not classify every wait or establish that each platform was ridden.

Three bots (Sarge, Visor, Anarki; skill 3, seed 42) complete 60 seconds on retail
q3dm1 and matching-data modern q3dm17, plus 180 seconds on retail q3dm6. Anarki,
Visor and Xaero (skill 4, seed 73) complete 180 seconds on retail q3tourney4. All
requested bots satisfy the movement threshold in each run. The original guests
handle combat, deaths and respawns; no aiming or movement commands are injected.
These runs assert movement, not a completed match or a particular score. Details
and per-bot statistics are in `/tmp/craftq3-platform-match-matrix/results.json`.

`-Pq3BotNames=sarge,visor,anarki` selects one to seven bots and overrides the
singular `q3BotName` option. The requested skill applies to all of them. With
multiple names, the movement assertion requires every requested bot to have
samples and exceed the same movement threshold. Installation paths passed to
server audit tasks are resolved relative to the repository root; absolute paths
continue to work.

The same 826-test snapshot additionally completes eight one-minute movement
scenarios: retail and matching-data modern team deathmatch on q3dm6 and CTF on
q3ctf1/q3ctf4 with Sarge, Visor and Anarki; and both profiles on q3dm7 with seven
bots (adding Hunter, Major, Tankjr and Xaero). All requested bots satisfy the
movement assertion. Team assignments, captures, match wins and combat are not
asserted by this matrix. Logs are under `/tmp/craftq3-team-bot-matrix/`.

`-Pq3GameType=3` requests team deathmatch and `-Pq3GameType=4` requests CTF in the
audit. The bounded property accepts original modes 0–4; its default remains
free-for-all (0). This selects the original guest's game-mode cvar and does not
replace its rules.

## Complete platform differential checkpoint

After the waiting/arrival and route-expiry boundary fixes, the inspected
830-test package repeats all three q3dm19 minute profiles successfully. Retail
records 989 moving frames and 17,840.27 horizontal units; mixed-data modern 996
frames and 18,894.28 units; matching-data modern 1,048 frames and 14,541.15 units.
The same package repeats five minutes in retail and matching-data modern, with
4,098/2,563 moving frames and 77,213.90/48,550.00 horizontal units respectively.
Each long run observes a target death, but asserts movement only. Logs are under
`/tmp/craftq3-platform-parity-retries/`. These replace neither the historical
826-test statistics above nor its separate several-bot/ride observations.
