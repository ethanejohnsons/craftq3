# Bridge performance

The September 7, 2026 optimization keeps player commands at 125 Hz and runs the
original Quake server at 20 Hz (the same 50 ms cadence as CraftQ3's standalone
local game). Previously every 8 ms command also ran the entire server, including
all mirrored Minecraft mobs: 125 full server updates per second.

Host damage/health exchange now follows completed server updates. Original
qagame must publish damage before a Minecraft snapshot refreshes a target's
health. Dead target names remain available until cgame consumes their obituary.
The original VM still handles movement, weapons, projectiles and damage. Server
world effects/projectiles now advance at the 50 ms server cadence, while player
input and cgame prediction continue between updates. Loadout powerup durations
use the current presentation time between server snapshots.

Other changes:

- Unchanged mob bounds, health, name and velocity skip redundant original-game
  synchronization. Movement, damage, healing, renaming and knockback invalidate
  this fast path. The 31-target / 64-block limit is unchanged.
- Terrain caches reuse converted collision boxes across adjacent frames while
  still checking current chunk presence, native collision shape and fluid height.
  Neighbor-dependent shapes and block/fluid edits invalidate cached conversions.
  Only the current and previous frame's cells are retained.
- Equal immutable images shared by cgame and the console reuse GPU textures;
  separate copies no longer trigger mipmap generation and GPU uploads each time
  the overlay switches asset sets. Changed image pixels still replace textures.

## Measurements

Native Minecraft 26.2 / Java 25 / Vulkan, macOS arm64 Apple A18 Pro. The console
fixture exercises chat entry, character changes and a rocket before leaving the
console open over the live Minecraft world. It advances 1,600 frames with 16 ms
simulation input per frame. After initial warmup, timing totals include 1,500
simulation samples and 1,499 overlay samples.

| CPU phase (milliseconds) | Before | Cache changes only | Final server cadence + caches |
| --- | ---: | ---: | ---: |
| Mean bridge simulation | 3.918 | 3.960 | 2.842 |
| Median bridge simulation | 3.622 | 3.704 | 2.377 |
| p95 bridge simulation | 5.305 | 5.442 | 5.525 |
| p99 bridge simulation | 8.824 | 8.081 | 11.365 |
| Mean Quake overlay submission | 1.683 | 1.421 | 2.000 |

Average bridge simulation time fell about **27%** in this comparison. Tail
latencies did not consistently improve, and overlay submission varied. These
are CPU timings around `BridgeGame.frame` and `BridgeScreen.drawFrame`, not total
Minecraft frame time or GPU timings. Live mob populations, JIT compilation and
background native world work vary across runs; an earlier baseline measured
5.177 ms mean simulation time. This is evidence of reduced simulation work,
not a guaranteed FPS increase or a completed stutter fix.

JFR also exposed repeated mipmap generation and mob synchronization. Render
thread samples containing `Mipmaps.generate` fell from 101 in the initial
baseline to 5 with the cache changes; samples containing `externalActor` fell
from 55 to 12. Sampling counts are diagnostic evidence, not a benchmark score.

Local evidence:

- `/tmp/craftq3-bridge-profile-before-full.log`
- `/tmp/craftq3-bridge-profile-after.log` (caches only)
- `/tmp/craftq3-bridge-profile-20hz.log`
- `/tmp/craftq3-bridge-before-full.jfr`
- `/tmp/craftq3-bridge-after.jfr`
- `/tmp/craftq3-bridge-20hz.jfr`

## Reproduce

With the private `run/saves/CraftQ3 Bridge QA` world available:

```sh
./gradlew :craftq3-fabric:runBridgeSmokeClient \
  -Pq3BridgeConsoleSmoke=true -Pq3BridgeConsoleFresh=true \
  -Pq3BridgeProfile=/tmp/craftq3-bridge.jfr
```

Profiling is development-only and requires the bridge smoke world flag. Normal
play does not start JFR. The output includes simulation and draw mean/median/
p95/p99 timings. Profiling uses a 1,600-frame duration; do not combine it with
longer fluid/travel regression fixtures.

Long sessions, dense-world frame pacing, renderer allocations and VM interpreter
CPU cost still need further profiling. No graphics quality or Minecraft view
distance setting is reduced by this change.

## Gameplay regression checks

Fresh native Vulkan runs pass:

- Retail console/chat entry, Visor selection, weapon switching and rockets.
- Retail named-mob combat: cover blocks damage, removing blocks opens the shot,
  15 delivered hits kill the target, and cgame prints the correct obituary name.
- Modern VM outgoing impulse: bullet push, rocket splash direction, resistance,
  invulnerability and no duplicate impulses.
- Retail incoming damage: original Quake death and respawn, with host game mode
  restored on exit.
- Retail fluids: swimming, drowning, air stopping damage, lava and Battle Suit
  protection, without duplicate Minecraft environmental damage.
- Modern VM travel: 399 blocks across new client chunks, floor/wall collision,
  rockets, and continued powerup time with released movement during focus loss.

The simulation-clock unit regressions check 125 command steps / 20 server steps
per second, callback ordering, independence from render-frame partitioning, and
bounded catch-up after a stall.

A ten-second JFR sample during the modern-VM travel test still showed cgame VM
execution as the largest sampled bridge cost (88 render-thread samples in
`Q3Client.frame`, versus 15 in `Q3Server.invoke`). That test completed in 2m30s;
its fixed simulation clock and changing world do not provide a controlled FPS
comparison. Further client VM/frame-pacing optimization remains worthwhile.
Evidence: `/tmp/craftq3-bridge-travel.jfr` and
`/tmp/craftq3-bridge-20hz-travel-modern.log`.
