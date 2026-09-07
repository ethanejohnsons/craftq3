# Progress toward the active goal

Updated 2026-09-07. These are engineering estimates based on remaining features,
integration and validation work; they are not completion guarantees.

**Full goal: approximately 71%**

`██████████████░░░░░░`

**Standalone Quake III: approximately 84%**

`█████████████████░░░`

Original QVM gameplay, local maps, bots, movement/combat, renderer/audio, menus,
remote connections, pure-pack transitions and the original multiplayer browser are
implemented with automated and native-reference evidence. Protocol-68 demos now
record local/remote games and replay through original cgame and Demos menus,
including native restart/map changes, freeze/timedemo and failure recovery.
Hosting now connects the original Create Server menu to a UDP listener and
original qagame. Both UI profiles pass private host/remote matches, pure joins,
restart, map replacement, background ticking, dedicated mode and shutdown
recovery. Native established-client wire operations now pass both game profiles, and
585 native pure-policy cases cover admission boundaries. Complete native-client
application coverage and remaining server policies are still ahead. The packaged
checkpoint passes **1,310 tests**. Hosted per-peer byte pacing, live rate changes,
measured qagame/status ping and 2,400 native timing comparisons now pass. Hosted
PK3 transfer, packet-loss recovery and pure resumption pass both game profiles;
automatic client verification, isolated cache installation, pure resumption, cache
reuse and cancellation now pass both original UI/cgame profiles.
See [validation](VALIDATION.md) for the verified build and application results.

The RoQ playback clock now streams original movies with exact frame/sample timing,
loop/hold/end behavior and bounded catch-up. All 11 retail movies pass presentation
and PCM continuity against the previously validated hashes. Native PCM
streaming now passes an original movie through Minecraft's streaming source pool,
including refill, EOF, cancellation and host/session cleanup. Movie image commands
now update reusable textures through the existing backend; live Vulkan readback
matches two original frames exactly and verifies hold, resize, ordering and cleanup.
Audio/video start synchronization, fullscreen cinematic commands, original menu
launch/skip/continuation and cgame movie syscalls are implemented. The first earned
campaign movie and next-arena transition pass both original VM profiles. Shader
video textures and remaining campaign/movie compatibility remain ahead.

Before calling standalone Quake complete, finish legacy demo protocols, network
remaining hosting policies, HTTP downloads/mod switching, menu/cinematic services, broader
campaign/team/mod coverage and outstanding gameplay/render/audio compatibility.

The user now prioritizes the gameplay bridge. `/q3 bridge` provides the first
playable local-world slice: original Quake movement/prediction on Minecraft block
shapes, original weapon/HUD presentation and return to the prior Minecraft game
mode. Both QVM profiles pass block-wall/removal/jump audits; a live Vulkan test
captures Minecraft terrain with the Quake weapon and HUD. Original Quake combat
now damages Minecraft mobs: both QVM profiles pass bullet/rocket/splash/cover and
restart audits, and a live Vulkan run kills an iron golem with 15 accepted hits. Reciprocal
combat now lets a zombie kill the Quake player and trigger original respawn while
preserving Minecraft health; original QVM armor/death checks pass both profiles.
Burning mob arrows now retain native fire timing and send accepted burn damage
through original qagame. Flame checks cover accepted/rejected arrows, unchanged
Minecraft health, extinguish/reignition and attribution expiration. Other fire and
potion/status sources remain incomplete. Native fireballs now recognize the active
Quake avatar despite its host movement flag. A live blaze-owned small-fireball
fixture verifies block cover, ownerless/invulnerable rejection, exact direct and
burn damage, expiry and unchanged Minecraft health. A separate live encounter now
checks native blaze AI behind cover and after exposure, accepted fireball/burn
damage, then an original rocket kill with weapon/ammo verification and clean
return. Broader ranged AI, projectile and status behavior remains ahead. Native avatar bounds and eye height now follow
original QVM standing/crouching shapes on client and server. A live fixture checks
crouch avoidance, standing and wider-side hits, a low native slab ceiling and
native dimension restoration. Explicit pose APIs and unusual interactions remain
open. Basic native-water swimming, original
drowning and recovery in air, lava damage and Battle Suit immunity also pass both
original QVM profiles and a live Vulkan reservoir. More complex fluid transitions
remain ahead.
A native skeleton-arrow audit also verifies block cover, exposed hits reaching
Quake health, unchanged Minecraft health and clean return; broader ranged AI and
projectile status effects remain ahead. Punch-enchanted native arrows now transfer
their additional push into original Quake movement. A Vulkan check verifies full
native resistance, exposed motion into a native wall, two accepted hits, unchanged
Minecraft health and no duplicate host velocity. Quake world effects now share Minecraft
world depth, with GPU pixel checks proving native-wall occlusion. Cgame vertical
FOV and camera basis drive the host view; the weapon and HUD stay visible. The
bridge now has the existing Quake console, with original `give all`, weapon
selection, bindings, completion and history; a live test fires an original-QVM
rocket after switching weapons through the console. Bridge bindings and archived
settings now persist in a per-game home; separate Minecraft launches verify
restored sensitivity and a weapon binding before new commands execute. Native mob
labels now replace numbered targets in original Quake kill feedback, with both
QVM profiles checking renames and a live named-golem kill captured on Vulkan. A
generation marker saved with Minecraft player data now restores bridge mode,
abilities and physics flags after interruption; repeated-crash and rapid re-entry
checks preserve the correct return record and saved position. Native mob melee
knockback now transfers into Quake velocity: resistance, upward/backward motion,
native-wall collision and unchanged Minecraft health pass a live Vulkan fixture;
both original QVM profiles verify the impulse integration and dead-player guard.
Mob-sourced explosion impulses now follow the same path: three ignited creepers
verify native cover and explosion resistance, exposed Quake motion into a wall,
zero duplicate host impulse and unchanged Minecraft health. Outgoing original
Quake impulses now travel with accepted mob damage; native invulnerability and
resistance, bullet push away, rocket splash push toward the shooter and absence of
extra Minecraft knockback pass live Vulkan checks. Camera simulation now precedes
native camera update, removing a frame of lag; third-person orbit no longer changes
the native player's aim or adds a duplicate avatar. Original camera-wall collision,
full Quake body visibility and first-person return are exercised on Vulkan.
Crash recovery also follows a unique saved generation across changed login UUIDs.
Base-game loadouts now persist per login/game across bridge sessions: health, armor,
weapons, ammo, holdables and remaining timed powerups. Separate Vulkan launches
verify exact restoration before simulation, subsequent rocket fire, explicit fresh
entry and a dead-exit reset. Both original QVM profiles verify restored health,
armor, ammo consumption, medkit use and powerup decay. The Quake console now accepts `minecraft` to carry a live local-match loadout into
the open Minecraft world, closing the source after destination preparation. Audio
ownership moves after native cleanup. `quake <map>` now carries the updated bridge
inventory into a new local Quake match. A live Vulkan round trip verifies exact
inventory in both directions, original rockets in both worlds, missing-map failure
preservation, audio handoff and native-player restoration. Both original QVM
profiles verify local-map health admission and subsequent bullet/armor/death rules.
Seamless portals and resuming whole prior matches remain ahead.
World-persistent pickup placement now lets players collect original weapons, ammo,
armor and health on Minecraft terrain. Real placement/remove commands and a
separate Minecraft launch pass collection, rocket firing and original weapon
respawn on Vulkan. Original-QVM audits additionally verify healing in both profiles.
Placements load at bridge entry; streaming items during travel remains ahead.
A Vulkan traversal now covers 399 blocks beyond the initial client chunk window,
with new chunks, original floor/wall collision and rocket fire, and exact native
server position at the distant wall. Losing focus now releases held input while
Quake time continues alongside Minecraft; the live fixture checks movement stops
and original Quad Damage expires normally during that interval.
The reverse `/q3 build <map>` mode now provides native Minecraft Creative placement
and breaking over original BSP surfaces, a persistent native block layer, shared
rendering depth and return to the captured Minecraft state. BSP collision now
participates in Minecraft’s per-axis movement and native step selection; a live
q3dm17 ledge test verifies grounded stepping and airborne rejection. Seven
separate-process crash/reload stages now verify durable return-state recovery,
including another crash immediately after leave or recovery. Native block support
now combines BSP brush faces and Minecraft shapes; floor/wall torches pass live
placement, neighbor-update and breaking checks. Server-owned region geometry now
retains old-map support and entity collision after leave and map switching.
Startup restores saved regions by exact BSP identity before level ticking; a
separate-process Vulkan test preserves forced-loaded torches and native entities
without opening a build session. Missing-asset startup fails before level creation
and preserves the saved region files. Native arrow and thrown-projectile queries
now include BSP impacts before entity selection. Vulkan checks verify floor/wall
arrows remain embedded, snowballs impact, nearer native blocks/entities win and
a BSP wall protects a Minecraft mob. Native buckets and water/lava spread now
recognize original BSP surfaces: a Vulkan test verifies placement, flow, pickup,
water-source formation and slab waterlogging over an original floor, with no
fluid leaking into the native air below it. Fluid cells remain grid-aligned. Item frames and paintings now attach
to original BSP walls through native placement and survival rules; native frame
item insertion, rotation and breaking, plus painting support after leave, pass
on Vulkan. Native entity/decorative brightness now samples the original light grid while
preserving native sky light and stronger emitters. Before/after Vulkan captures
verify the same painting brightens. Colored/directional lighting, native terrain
lighting and placed-block shadows on BSP remain open. Native explosions now include BSP cover in
entity exposure and block propagation, preserving native damage/knockback rules
and shielding Minecraft blocks behind immutable geometry. Ground navigation now
queries BSP floors, walls and headroom through Minecraft’s existing pathfinder;
a live mob traverses a fractional-height floor and replans around placed blocks.
Native water/lava/solid/hazard path types are preserved in the live audit. BSP
sight now shares exact solid occlusion with blasts; native sensing, cover removal
and an actual zombie pursuit/attack pass on Vulkan. Body-sized path clearance now
lets a small mob traverse a previously rejected tight passage; swept path edges
reject intervening BSP walls, including thin barriers between clear endpoints.

Still required for the full objective:

- Player targets, full damage attribution/status behavior, remaining native push paths and weapon-specific resistance policies, further entity/projectile integration and
  broader transparency/camera behavior.
- Broader native movement, support rules, lighting, entities and interaction coverage
  in the new Minecraft building mode over unchanged BSP worlds.
- The remaining standalone Quake compatibility work listed above.

See [bridge evidence and limits](MINECRAFT_BRIDGE.md) and
[building evidence and limits](MINECRAFT_BUILDING.md). The full scope remains in
[the roadmap](../ROADMAP.md).

For prioritization, see the numbered [What’s left list](WHATS_LEFT.md).
