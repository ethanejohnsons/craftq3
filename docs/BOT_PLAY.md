# Local play with original bots

Ordinary local sessions now default to `bot_enable=1`. Original qagame owns bot
selection, movement, aim, weapons, damage, scores, readiness and respawn. The Java
host supplies botlib services and invokes the original VM; no native engine or
native bot module is embedded.

Open **CraftQ3** from Minecraft's title screen. The original **Single Player**
menu can start the introduction against Crash. The original game delays her
entry, so the initial empty arena is expected. For a directly loaded arena, use
the Quake console:

```text
map q3dm17
addbot sarge 3
addbot visor 3
```

User-owned `pak0.pk3` supplies these original maps, models and VM modules. Packs
are read in place. Modern replacement VM modules require matching game data;
see [inventory compatibility](BOTLIB_INVENTORY_COMPATIBILITY.md).

## Play and lifecycle evidence

The final 899-test package contains 638 Java 25
classes, nine nested engine modules and four GLSL resources. Recursive inspection
finds no original game data. The inspected engine snapshot is
`/tmp/craftq3-progression-network-final-runtime` in the development environment.

A fresh one-minute movement sweep passes all 30 original playable maps after
correcting entity-link lifetime and refresh. Matching-data modern qagame also
passes targeted q3dm1, q3dm19 and q3tourney3 repeats. These 33 runs each require
sustained bot movement; they do not assert a completed match on every map.
Reports are under `/tmp/craftq3-bot-enable-sweep/` in the development environment.

The original Single Player menu issues `spmap q3dm0`; the game allocates Crash in
client slot 1 without injected `addbot` commands. Ordinary human movement inputs
enter the tutorial teleporter. A sixty-second encounter observes Crash moving,
firing and killing the human, followed by original-game respawn. Menu, server
and cgame observations are separate from the GPU capture below; the exact normal-
default replay and entrance-cue check are in
[Single Player validation](ORIGINAL_SINGLE_PLAYER_QA.md).

Retail and matching-data modern games reach the fraglimit, enter intermission,
request their configured restart and continue playing after score/player-mode
reset. The retail cgame replay also consumes the actual snapshots and reliable
commands across that transition. Retail intermission configstring 14 remains
set because the original guest leaves it set; native fast restart preserves
configstrings. The host does not override guest-owned state. See
[match lifecycle](BOT_MATCH_LIFECYCLE.md) and
[entity lifetime/restart contracts](BOTLIB_ENTITY_LIFETIME.md).

The production session completes the default tutorial, automatically plays the
original Tier 1 unlock movie, returns through the original level-selection menu,
starts q3dm1 with Ranger and reloads earned scores/awards from a fresh saved-config
session. Retail and 1.32 profiles both pass. The older postgame/Next component
harness did not execute movies and is not a full campaign-transition proof. See
[campaign progression](ORIGINAL_SINGLE_PLAYER_PROGRESSION.md).

Swimming prediction now passes 80,000 controlled native comparisons and 2,000
original-map queries. Three original five-minute water-map matches total 16,226
sampled bot liquid frames; see [swimming prediction](BOTLIB_MOVEMENT.md#swimming-prediction).

## Vulkan bot capture

```sh
./gradlew :craftq3-fabric:runPlaySmokeClient \
  -Pq3Map=q3dm17 -Pq3BotTest=true \
  -Pq3Installation=/absolute/path/to/Quake
```

The installation override applies only to a development launch and does not
rewrite the saved installation. Minecraft retains the user's selected graphics
API. The capture flag adds Sarge, Visor and Anarki through original console
commands, runs at least 900 presentation frames, and sends ordinary fire-button
press/releases if the human dies. It requires all three bots to move, original
player-model submissions, a living player at capture, and zero audio failures.
The test does not enable bots through a separate cvar override: it exercises the
normal enabled default.

The final 853-test package completes a Minecraft 26.2 / Java 25 Vulkan run with
900 presentation frames: the three bots move in 860, 860 and 857 observations;
897 frames contain original player-model submissions. Five normal fire-button
inputs respawn the human, who is alive at capture. Audio registers 138 sounds and
starts 205 voices with zero failures. Original player models, weapon effects,
world geometry and HUD were visually inspected in the saved image. The log is
`/tmp/craftq3-bot-default853-vulkan.log`. The ignored capture is `run/screenshots/craftq3-play-q3dm17-bots-vulkan.png`. The
renderer now accepts cgame's zero-scale weapon models and planar singular
transforms; native CPU geometry evidence is in [model transforms](MODEL_TRANSFORMS.md).
This is a real GPU execution/capture check, not a pixel-identity claim.

## Remaining scope

Bots are available while compatibility work continues. Remaining domains include
swimming movement prediction, certain failed jump run-ups and downward/non-unit
step landings, elevator-travel integration, additional bot services, full
campaign progression and team/CTF match completion. Multiplayer sessions, demos,
cinematics and Minecraft interoperability are separate unfinished work. The
current base-map and local-match checks do not establish arbitrary mod support.

CraftQ3 currently invokes bot updates within its 50 ms local-server step. Native
client/dedicated scheduling is a separate fidelity boundary; the bounded
[frame-cadence observer](NATIVE_FRAME_CADENCE.md) establishes dedicated ordering
but does not establish local-client cadence equivalence.

The 897-test checkpoint additionally passes q3dm12 under Minecraft 26.2 / Java 25
Vulkan: 900 presentation frames, bot moving observations 857/860/857, 775 frames
with submitted player models, 129 registered sounds, 49 started voices and zero
audio failures. The user remains alive, without needing a respawn input. The
capture `run/screenshots/craftq3-play-q3dm12-bots-vulkan.png` was visually inspected;
it shows the original arena, a nearby bot, weapon and HUD. Evidence is in
`/tmp/craftq3-water-bots897-vulkan.log`. This is a live water-map rendering check;
the separate five-minute simulations establish time actually spent swimming.

The final 899-test package passes its complete campaign CPU replay and all unit
tests. Its two later Vulkan rechecks exited before capture without a logged
game/render exception, so the completed GPU evidence above remains specifically
the 897-test checkpoint; see [validation details](VALIDATION.md).
