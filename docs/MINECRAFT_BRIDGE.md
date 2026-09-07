# Minecraft gameplay bridge

Open a **local singleplayer Minecraft world** and run `/q3 bridge`. Press Escape
to return to Minecraft. The initial bridge runs original qagame and cgame QVMs
against Minecraft terrain, with Quake movement, jumping, weapon input, viewmodel,
crosshair and HUD. Quake hits damage Minecraft mobs; mobs attack back, and blocks
provide cover. Original Quake armor, death and respawn remain active.
Minecraft continues drawing the world. This was exercised on
Minecraft 26.2, Java 25 and Vulkan on macOS arm64.

The bridge uses 32 Quake units per block and anchors Quake coordinates near the
entry position. Minecraft block collision shapes, including partial shapes, become
swept Quake obstacles. Water and lava report Quake contents. Unloaded chunks form
collision boundaries. Server gameplay and client prediction use the same terrain
provider; the provider clears its shape cache each frame to observe block changes.
Quake entities retain their original linked-entity collision and game rules.
No Minecraft movement approximation replaces the original QVM movement code.

While active, the integrated Minecraft player temporarily uses Adventure mode with
bridge-controlled movement so normal mob AI can target it. Normal exit restores
the previous game mode, abilities and gravity settings. Minecraft health and hunger
remain separate from Quake health. The Minecraft body uses the actual Quake feet
position; cgame independently sets the camera eye position and bobbing.
Minecraft uses cgame's vertical FOV and camera basis, including roll. Native
walking/hurt camera bob is suppressed while the bridge is active because cgame
already supplies those camera changes. Bridge simulation now runs before
Minecraft's camera update, so native camera position, orientation and culling use
the current cgame frame. Player body yaw/pitch come from original player-state aim,
independently of the orbit or death camera. Native camera position, quaternion and
direction vectors follow cgame; the saved Minecraft camera preference is preserved.
Cgame supplies the visible Quake body in third person, without a duplicate native
Minecraft avatar.
The current position carries back into Minecraft. The bridge does not provide
multiplayer synchronization or resume the Quake match after a crash. A durable
player marker and journal now restore the prior Minecraft mode, abilities, gravity
and physics flags on rejoin, keeping the saved position.

## Quick gameplay check

1. Open a singleplayer Minecraft world and stand on solid ground.
2. Run `/q3 bridge fresh` for a clean Quake loadout.
3. Press backtick, enter `give all`, then close the console with Escape.
4. Move with WASD, jump with Space, crouch with C or Ctrl, and fire with left click.
   Number keys or the mouse wheel switch weapons. Fight nearby Minecraft mobs;
   solid blocks provide cover.
5. Press Escape during play to return to Minecraft. Your Quake loadout is saved;
   `/q3 bridge` restores it next time.

Use a non-Peaceful difficulty for hostile-mob encounters. The bridge currently
supports the local integrated server; it does not add Quake combat to multiplayer
Minecraft servers. See the sections below for pickups and transfers between worlds.

## Console and weapons

Press backtick (`) to open the original-material Quake console. Enter `give all`
for the original game's weapons/ammo, then close the console with Escape or
backtick. Number keys and the mouse wheel select weapons. Commands such as
`weapon 5`, `bind f "weapon 5"`, `sensitivity 6` and `cg_fov 100` use the existing
Quake command/cvar/QVM path. `disconnect` requests return to Minecraft.

The console supports editing, Tab completion of command/cvar names, history,
Page Up/Down and wheel scrolling. Opening it releases held game inputs and frees
the mouse; Escape closes it first, and Escape during play exits the bridge.
Simulation and Minecraft mobs continue running while it is open. Losing window
focus also keeps both worlds running, while releasing held controls and freeing
the mouse; powerup timers and projectile motion continue. Bindings and archived settings now save on normal exit to
`craftq3/home/bridge/<game>/q3config.cfg` and load on the next bridge entry.
`autoexec.cfg` in that directory can add startup commands; `writeconfig` saves
immediately. Quake loadouts also persist across bridge sessions as described below.
This local experimental mode already enables Quake cheats; `give all` executes
inside the supplied game QVM, with no host-written inventory/weapon rules.

## Placing Quake pickups in Minecraft

While in normal Minecraft, stand where you want an item and run:

- `/q3 pickup add weapon_rocketlauncher`
- `/q3 pickup add ammo_rockets`
- `/q3 pickup add item_armor_combat`
- `/q3 pickup add item_health_large`

Tab completion suggests the original base-game item names. `/q3 pickup list`
shows saved IDs and positions; `/q3 pickup remove <id>` removes a placement.
Enter `/q3 bridge fresh` to collect them with a fresh inventory, or `/q3 bridge`
to use your saved loadout. Re-enter the bridge after editing placements.
Original qagame controls falling to the floor, touch collection, inventory grants
and respawn; original cgame draws and sounds the items directly from your PK3s.
Structurally valid names that the selected game does not recognize may not spawn.

Placements save per world, selected game and dimension under
`<world>/craftq3/bridge-pickups/<game>/`. Up to 256 placements are supported per
dimension. Entry loads placements within 256 blocks whose chunks are already
loaded; it does not stream further items as you travel. Item availability and
respawn timers start over with each bridge session. These records contain only
IDs, classnames and positions; they do not copy original game assets.
Malformed records report an error and remain unchanged.

## Swimming and environmental damage

Minecraft water and lava supply fluid volumes at their native heights. Original
qagame handles swimming, air supply, drowning, lava damage and Battle Suit
protection; cgame runs the corresponding view and sound effects. Hold Space to
swim upward. Minecraft health stays separate, and native environmental damage is
suppressed while Quake owns these effects.

The live `-Pq3BridgeFluidSmoke=true` fixture on `runBridgeSmokeClient` uses a
five-block-deep native reservoir in the private QA world. One second of upward
input raises the Quake player about 3.988 blocks. After sinking, two original
drowning ticks reduce health from 100 to 90; replacing the water with air stops
further damage. Full lava submersion reduces health from 100 to 10, while a Battle
Suit granted through the original console prevents damage during a subsequent
2.5-second lava interval. Minecraft health remains unchanged, no native damage
enters the mob-attack queue, and exit restores Creative mode.

`AuditBridgeGameplay.py --fluids --classpath '<runtime>/*'` separately exercises
these original rules in both retail and 1.32 QVM profiles. This closes the basic
swimming/world-effects validation gap; it does not establish every flowing-fluid,
waterlogged-block, current, shoreline or status-effect interaction. The native
fixture edits the reservoir to switch environments; it does not test walking
between independently generated pools. See [validation](VALIDATION.md).

## Keeping your Quake loadout

Normal exit saves Quake health, armor, owned weapons, selected weapon, ammo,
holdable item and remaining timed powerups. Run `/q3 bridge` again to restore them;
`/q3 bridge fresh` bypasses the saved loadout and starts with the original game's
defaults. Leaving while dead clears the checkpoint, so the next entry also starts
fresh. Powerup timers pause while outside the bridge.

The checkpoint is per Minecraft login UUID and selected game, shared across local
Minecraft worlds, at `craftq3/home/bridge/<game>/loadout-<UUID>.dat`. It is tied to
the exact qagame module; changing that module starts a new compatible inventory.
Invalid checkpoints report an error and remain unchanged; `fresh` can bypass one.
A successful normal exit replaces the previous checkpoint atomically.

This currently supports the base-game health scale and inventory layout, including
retail and 1.32 QVM profiles. Position, velocity, score, objectives and match state
are not saved here. Minecraft inventory remains separate. Local standalone Quake
matches can now send a live loadout into the bridge as described below. This is a normal-exit save,
not crash-resume support for the Quake match.

## Entering Minecraft from a Quake match

Open your Minecraft singleplayer world, then use `/q3 map q3dm17` (or another map)
to play standalone Quake. While alive in that local match, open the Quake console
with backtick and enter `minecraft`. The bridge opens at your Minecraft player's
position with the live Quake loadout: health, armor, weapons, ammo, holdable and
remaining timed powerups. This overrides any older bridge checkpoint. On normal
exit, that inventory becomes your saved bridge loadout.

The source match closes once the destination is prepared. Its map, bots, scores
and objectives do not follow the player. Source and destination must have identical
qagame modules and a supported base-game inventory. Menus, demos, remote clients,
dead/spectating players and hosts with connected remote players cannot export a
loadout. Rejected preparation leaves the source match open and reports the reason
in its console. An open local Minecraft world is required; this command does not
select or create a world from the title screen.

Audio prepares its PCM assets without taking the source's sound engine. The source
then closes, and the destination waits for native audio cleanup and player setup
before its first simulation frame. To carry your updated inventory back, use the
return command below. Seamless portals remain future work.

## Returning to a Quake map

While alive in the bridge, enter `quake q3dm17` (or another map name) in its console.
This starts a new local Quake match with your current health, armor, weapons, ammo,
holdable and remaining timed powerups. The source bridge closes after destination
preparation, saving its checkpoint and restoring the native Minecraft player.
The destination waits for that restoration and audio cleanup before simulation.
A missing map or incompatible game module leaves the bridge open with an error.

The destination uses its normal spawn location and a new match: scores, bots and
objectives from the previous match are not resumed. A later ordinary map change or
respawn follows the original game's inventory rules; it does not reapply the
carried loadout. Enter `minecraft` again to transfer the updated inventory back.

This currently requires an active base-game player with the standard health scale
and `dedicated 0`. When exact health restoration needs damage, the server adds one
inert hurt-trigger descriptor without changing original BSP geometry. The supplied
QVM performs healing/damage, and the original cheat setting is restored before
cgame starts. Maps without a free inline-model slot cannot use that admission path.

## Original player hitbox and crouching

Native hit queries now use the original QVM's player bounds, converted at 32
Quake units per block. The base-game standing body is 0.9375 blocks wide and 1.75
blocks tall, with eye height 1.5625; crouching lowers the body to 1.25 blocks and
the eye to 1.125. Hold C or Ctrl to crouch. Original Quake movement decides when
there is enough room to stand, including Minecraft partial-block shapes.

An immutable shape snapshot crosses to the integrated server alongside alive and
grounded state. Client and server native bounding-box, width, height and current
eye-height queries use that shape at the avatar's feet. This lets projectiles,
exposure and native aiming consume the Quake body. The shape is refreshed from
public QVM entity bounds/player state; no Java standing/crouching gameplay rule
replaces the original game. The adapter does not write native saved pose/dimension
fields; its query overrides end on exit, revealing the native dimensions. Explicit
pose-dimension APIs and other unusual native pose interactions need more coverage.

`-Pq3BridgeHitboxSmoke=true` exercises native small-fireball rays: one passes over
the crouched player into a back wall, the same height hits while standing, and a
side ray hits the wider Quake hull beyond Minecraft's default body. Native
projectile margins remain in effect. Direct impacts reduce Quake health to 50;
fire is cleared by QA to isolate the hitbox comparison. A top-slab ceiling prevents
standing after crouch input is released, and removing it allows standing again.
Both sides compare their body and eye queries with original QVM snapshots and
verify native dimension restoration after exit. Native Minecraft health remains
unchanged. Both QVM profiles separately verify original standing/crouching hull
and view-height values. See [validation](VALIDATION.md).

## Third-person camera

Use the bridge console to enter `cg_thirdPerson 1`; `cg_thirdPerson 0` returns to
first person. `cg_thirdPersonRange 128` sets a four-block camera distance and
`cg_thirdPersonAngle 90` changes the orbit angle. The supplied cgame controls the
camera and clips it against Minecraft terrain. Orbiting does not turn the player
or change weapon aim. Minecraft's saved first/front/back camera preference does
not override the Quake view and resumes when you leave the bridge.

## Mob combat

The closest 31 living Minecraft mobs within a 64-block search region are mirrored
into hidden original qagame client slots using their actual bounding boxes.
Original QVM weapons, projectile traces and splash rules compute the Quake damage.
No extra Quake player model is drawn over the Minecraft mob. Damage crosses to the
integrated server thread, where Minecraft applies its own defenses and death/drop
behavior. A sequence ledger prevents duplicate sends and prevents stale health
snapshots from undoing unacknowledged hits. QVM state stays on the client thread.

The experimental health policy maps each mob's maximum health to 100 Quake health
points. Minecraft armor/resistance still applies afterward; invulnerable targets
remain protected. This can make original Quake frag feedback precede an actual
Minecraft death for protected mobs. Mob type/custom names now reach original Quake userinfo and kill messages. Renaming
a mob updates its existing proxy; unchanged labels do not rebroadcast userinfo.
Labels use at most 34 legacy characters, replace unsupported Unicode/info-string
delimiters/color markers with safe text, and collapse whitespace.
Outgoing hits now carry the original Quake impulse with their damage. The adapter
measures each proxy's public player-state velocity change after the original game
frame, with the baseline reset when a new native snapshot resets that proxy.
The damage ledger keeps the impulse attached to the same hit, sends it once, and
preserves outstanding damage across stale acknowledgements. An accepted native
hit adds the mapped impulse to the mob's velocity: Quake units/second convert to
blocks/tick, scaled by one minus Minecraft's general knockback-resistance attribute.
Minecraft then moves the mob with native gravity and collision. Rejected damage
also rejects the impulse. The `craftq3:quake` damage type disables Minecraft's
additional source-position knockback, so rocket splash can push toward the shooter
when the explosion occurs behind a mob. This policy uses the general knockback
resistance attribute for all outgoing Quake weapons.

## Incoming attacks

Minecraft runs mob AI, attack difficulty scaling and its normal damage cooldown.
The bridge intercepts accepted damage before Minecraft armor or health changes,
then maps one Minecraft damage point to five Quake points (rounded, bounded to
1–255 per accepted hit). A generated set of `trigger_hurt` map entities delivers
the selected integer damage through original qagame. The host exposes a contact
only during the intended player's command; these entities are never terrain or
prediction obstacles. Their 255 inline descriptors plus world model fit Quake's
256-model limit. No private qagame health/death routine is copied or replaced.
Original armor absorption, pain, death and respawn run in the supplied QVM.
Queued pre-death damage is cleared on death; the Minecraft avatar is protected
while the Quake player is dead. Fire after the original respawn delay to respawn.

Accepted burning arrows and small fireballs owned by Minecraft mobs carry their ongoing burn
into Quake health. Native projectile ignition supplies the duration, and native fire
keeps its tick timing and damage cooldown. Each accepted burn follows the existing
five-Quake-points-per-Minecraft-point damage path through original qagame. Minecraft
health stays unchanged. Rejected projectile hits do not start an attributed burn.
Attribution expires at the projectile's ignition deadline and clears on extinguish or
Quake death; subsequent unrelated fire cannot inherit it. This is the existing
generic incoming-damage policy, including its hurt-trigger obituary, rather than
a new Quake damage type. Other fireball effects, potion/status effects and precise attacker
attribution are still incomplete.

Mob-sourced native `LivingEntity.knockback` now transfers its velocity change to
Quake. Minecraft still computes direction, strength and knockback resistance; the
adapter supplies Quake's grounded state during that calculation, captures the
result, and restores the host avatar's prior motion. The immutable server-thread
snapshot carries the impulse once to the Quake thread. Blocks/tick convert to
Quake units/second at 32 units/block and 20 ticks/second, with the axes mapped to
Quake's Z-up coordinates. The engine adds it to public player-state velocity;
original QVM movement handles gravity, friction and collision. Dead Quake players
do not receive impulses. This is an additive host impulse, with no replacement of
Quake movement or implementation of private damage/knockback routines.

Mob-sourced explosions also transfer their native computed impulse to Quake.
Minecraft still calculates blast exposure, distance falloff and its separate
explosion-knockback resistance attribute. The bridge captures the native push and
restores host velocity, then uses the same Quake impulse path as melee. Its native
explosion packet keeps the effects but carries zero additional Minecraft player
impulse. Ignited creepers now knock the Quake player back; cover and resistance
can suppress that impulse. Native blast damage still follows the existing incoming
damage policy, including Minecraft's minimum damage through full cover.

The map-entity contract comes from the original
[Q3Radiant Editor Manual](https://www.asc.ohio-state.edu/lewis.239/Gauge/q3rmanhtml.htm),
with behavior checked against both original QVM profiles. Current incoming kills
use the original hurt-trigger obituary rather than naming the Minecraft attacker.

## Current limits

- Player targets and multiplayer are not connected. Only the nearest 31 living mobs are outgoing combat targets. Incoming
  attacks currently require a mob damage source or the still-active burn from an accepted mob projectile; native skeleton arrows now pass
  live cover/damage/Punch-knockback checks; special status effects, precise
  attacker attribution, damage above 255 Quake points, weapon-specific native
  resistance policies and native pushes outside the melee/explosion/arrow adapters need further policy and
  validation. Explosion sources must currently resolve to a mob, matching incoming
  damage; environmental and player-owned explosions are not bridge combat sources.
- The reverse direction is now available through `/q3 build <map>`; see
  [Minecraft building](MINECRAFT_BUILDING.md) for its separate controls and limits.
- Quake world effects now render against Minecraft world depth before its hand/HUD
  depth clear. Submitted materials use the reversed depth convention; the weapon
  remains in the near depth range, and original HUD/model-icon views draw later.
  Opaque native wall occlusion passes GPU pixel checks. Translucent-layer ordering,
  sub-viewports/asymmetric FOV effects, broader camera/character configurations and block-specific
  surface effects need further integration.
- This is a local-world prototype with persistent base-game Quake loadouts.
  The console exposes original loadout commands and persists bindings/settings.
  Pickup placement is supported; streaming placements while traveling and seamless traversal remain ahead. Bots have no generated navigation data for Minecraft terrain.
- Cinematics and other remaining standalone Quake work are still incomplete;
  the user has prioritized the gameplay bridge before those tasks.

## Evidence

`GridTraceWorldTest` compares 500 deterministic swept/ray/stationary queries with
exhaustive box collision, including negative cells, partial/tall boxes and contents.
It also covers changed cells and long diagonal traces without scanning the entire
bounding volume. `AuditBridgeGameplay.py` runs both original qagame/cgame profiles
against the packaged external-world adapter without a BSP asset for the bridge.
Both stop at x=144.867 before the block wall, reach x=1014.467 after wall removal,
jump to z=69.625, and emit 300 original cgame views with HUD commands.

`runBridgeSmokeClient` opens the separate `CraftQ3 Bridge QA` copy of the existing
test world, enters the bridge, supplies movement/jump input, captures Vulkan output,
exits and checks restoration of the integrated player's actual game mode. A clean
process exit alone is insufficient: the Gradle task requires its explicit result.
The user's source test world is not edited by this smoke setup.

Add `-Pq3CombatSmoke=true` to run the combat variant. It constructs cover and spawns
an iron golem in the private QA world, fires while covered, verifies zero damage,
removes cover, verifies a real Minecraft kill, captures the frag message and checks
restoration of the actual prior game mode. The final Vulkan run reports
`PASS cover=true appliedHits=15 killedTargets=1 moved=0.0 restoredMode=CREATIVE`.

`AuditBridgeGameplay.py --combat --classpath '<packaged runtime>/*'` checks both
retail and modern original QVMs. Bullets leave health 100 behind cover and 86 when
exposed; rockets kill the full-size target; a short target below the direct rocket
path takes splash damage after cover removal (100 to -16). Hidden proxy models,
health resynchronization, removal, admission without telefrags and restart cleanup
also pass. Three `CombatLedgerTest` cases cover stale acknowledgements, duplicate
send prevention and invalid input. That checkpoint passed 1,199 tests; the latest full build passes 1,267.


`-Pq3IncomingSmoke=true` runs a separate reciprocal-combat variant. A zombie with
increased attack strength uses normal AI and attacks twice, killing the Quake
player. The test verifies Minecraft health did not change, removes its private
fixture zombie, fires to respawn through original Quake code, and verifies exit
restores Creative mode. The final Vulkan run reports
`PASS incomingHits=2 death=true respawn=true health=118 moved=0.0 restoredMode=CREATIVE`.
Both packaged movement profiles also check that Quake body feet remain above the
host floor with all 255 damage adapters installed. The QA setup recovers a dead or
below-build-limit test avatar after interrupted runs; it only edits the selected
private QA world.

The corrected live movement/jump test moves 26.539 Minecraft blocks and restores
Creative mode. Its inspected Vulkan capture shows Minecraft terrain and mobs with
the original Quake weapon/HUD and no extra Minecraft hand.


A native ranged-attack fixture now runs with `-Pq3RangedSmoke=true` on
`:craftq3-fabric:runBridgeSmokeClient`. It schedules the skeleton's own
`performRangedAttack` method every 40 Minecraft ticks with a Punch II bow; native
arrow creation, aiming, flight, collision and impact damage remain unchanged.
The skeleton's AI is disabled to isolate the projectile path; this does not prove
its full target-selection/strafe behavior. A native arrow stops at the fixture's
stone wall with zero incoming hits. The bow now has native Punch II: after removing
cover, full native knockback resistance permits Quake damage but prevents movement.
Removing resistance lets a second arrow push the Quake player into a rear Minecraft
wall. The two hits preserve Minecraft health and leave no duplicate host velocity.
The skeleton/arrows are removed, the resistance attribute is restored, and exit
restores the captured Creative mode. Native arrow enchantments and resistance
calculate their extra impulse; the bridge queues it for original Quake movement.
This also covers the arrow push path that bypasses ordinary melee knockback.
Potion-tipped arrows, other fireball effects and precise attribution remain ahead.


`-Pq3DepthSmoke=true` verifies shared depth using a development-only magenta
Quake polygon and a native stone wall. A 17-by-17 pixel probe contains zero magenta
pixels with the polygon behind the wall and 289 with it in front. Both screenshots
retain the original weapon and HUD. Quake geometry uses the actual Minecraft
projection/view and explicit 32-unit coordinate mapping; normal full-window
projection aligns the probe with original cgame FOV. The fixture also verifies
restoration of Creative mode. See the latest [validation](VALIDATION.md) checkpoint.


`-Pq3BridgeConsoleSmoke=true` drives actual screen key/character/mouse callbacks.
The live Vulkan fixture opens the console (including a repeated toggle key), runs
`give all`, selects railgun 7, binds F to rocket launcher 5, closes the console,
uses the binding and fires a native original-QVM rocket observed in cgame output.
It then exercises completion/history and captures the original-material console
with weapon/HUD beneath it. Exit restores Creative mode. This verifies original
commands and local input; the separate restart test below covers settings
persistence. That checkpoint did not yet carry inventory between standalone and bridge; the round-trip support above now does.


Bridge settings use the existing bounded GameConfig serializer and descriptor-pinned
GameFileStore with atomic file replacement. The per-game bridge home is separate
from standalone settings. PK3s remain read-only. If storage cannot be opened, the
bridge logs that settings will not persist; a failed export preserves the previous
config and does not prevent normal cleanup.

A two-process Vulkan check saves `sensitivity 6` and `bind f "weapon 5"`, exits,
then reopens Minecraft and asserts both restored values before any test command.
The second process also repeats original weapon selection/firing and checks return
mode. Reproduce with `-Pq3BridgeSettingsSmoke=prepare`, then
`-Pq3BridgeSettingsSmoke=verify`, on `:craftq3-fabric:runBridgeSmokeClient`.
These development runs use `run/craftq3-bridge-settings-qa/<game>/` so they do not
change normal preferences. Other bridge smoke runs disable saved settings.
Normal exit persistence is verified; crash-time config saving is not claimed.


Both original QVM profiles now verify named actor admission, renaming without
replacing the slot, unchanged-name stability and the legacy name-length boundary,
plus the existing health/damage/armor/death/respawn checks. A live Vulkan outgoing
combat run names its iron golem `Bridge Guardian`; the original cgame capture
shows `You fragged Bridge Guardian`, and Minecraft records that named entity's
actual death. Incoming kills still use the original hurt-trigger obituary;
precise attacker attribution remains ahead.


## Interrupted-session recovery

Before changing the Minecraft avatar, the bridge atomically writes its prior mode,
all packed abilities, gravity and physics flags to a generation-specific record
under the save's `craftq3/bridge-return-<player UUID>/` directory. Minecraft saves
the matching generation marker alongside its player state. Entry waits for this
journal write; a failure returns to Minecraft without starting bridge movement.

Normal exit restores the captured state and clears the in-memory marker, retaining
the record. A rejoin with a saved marker validates and restores that exact generation
without teleporting, then clears the marker in memory. Only a later fresh load with
no marker confirms Minecraft saved the restoration and permits record cleanup.
Older generations survive rapid exit/re-entry so an older saved avatar can still
find its matching record. Invalid/missing recovery data prevents entry with an
explanation and leaves recovery data unchanged. Server shutdown clears live
controller references; it does not discard the durable recovery records.

This restores Minecraft player state, not Quake match/inventory state. Normal exit
and process interruption are covered; power-loss durability and external rollback
of saved player files are not established.


The recovery fixture uses a stable private QA player and non-default ability speeds.
Seven separate-process stages cover: checkpoint/crash; recovery followed by another
immediate crash; recovery and normal shutdown; confirmed clean reload; exit/re-entry
followed by a crash before another player save; recovery of the older saved marker;
and final cleanup. Mode, every packed ability, gravity/physics flags and saved
position are checked. Reproduce with `-Pq3BridgeRecoverySmoke=checkpoint`,
`recover-crash`, `recover`, `reload`, then `reentry-crash`, `recover`, `reload` on
`:craftq3-fabric:runBridgeSmokeClient`. These stages deliberately halt their own
Minecraft process only when configured to do so and only in the private QA save.


`-Pq3KnockbackSmoke=true` exercises the native zombie `doHurtTarget` path with AI
disabled to isolate two melee hits. The first hit uses full native knockback
resistance and reduces Quake health without moving the player. The second removes
that resistance and moves the player about 1.027 blocks backward and 1.173 blocks
upward through original Quake movement. A native stone wall stops the body at its
Quake hull boundary. Minecraft health is unchanged and exit restores Creative.
Both original QVM combat audits also verify public velocity injection, subsequent
movement, host-wall collision, rejection without mutation for an oversized
impulse, and no velocity mutation on a dead player. See the latest validation
checkpoint for logs and package identity.


`-Pq3BridgeExplosionSmoke=true` ignites three actual creepers in the private QA
world. AI is disabled only to hold their positions; native fuse ticking and
explosion code run normally. The first blast has zero native exposure behind an
obsidian wall; the second removes that wall and gives the player full native
explosion-knockback resistance. Both produce zero impulse and no Quake movement.
The third removes resistance: the native impulse is approximately
(-0.378, 0.175, 0) blocks/tick, and original Quake movement carries the player about
1.027 blocks backward and 0.225 blocks upward before the rear wall stops the hull.
The fixture verifies zero duplicate impulse in the native packet data, unchanged
Minecraft health, Quake damage and return to Creative. Original `give all` supplies
armor for this three-blast fixture. This flag is distinct from the reverse building
mode's `-Pq3BuildSmoke=true -Pq3ExplosionSmoke=true`.


`-Pq3OutgoingImpulseSmoke=true` verifies original Quake bullets against a native
iron golem with goals removed while native physics remains enabled. Invulnerability
rejects damage and impulse despite confirmed Quake hits. Full native knockback
resistance accepts damage but prevents motion; removing resistance makes bullets
push the golem away (about 0.642 blocks in the fixture). A short native chicken
below the rocket path then receives original splash from the wall behind it and
moves about 0.981 blocks toward the shooter. Absorption keeps this short target
available for the movement check. Every accepted hit checks that its instantaneous
native velocity change equals the converted original impulse, with no extra native
hit knockback. Exit restores Creative. Both original QVM profiles independently
verify covered shots with zero impulse, positive bullet/direct-rocket impulses,
negative-X backstop splash and removal of old velocity on proxy refresh. Two ledger
tests verify impulse/hit pairing, single sends across stale acknowledgements and
rejection of invalid impulses without issuing partial hits.


`-Pq3BridgeCameraSmoke=true` checks first-person weapon/model visibility, third-person
Quake body visibility, camera collision against a rear native wall, expansion after
wall removal, orbiting independently of player aim and return to first person.
It deliberately selects Minecraft's front camera and verifies that preference
remains unchanged while the native avatar stays excluded. Native camera position,
forward vector and quaternion are compared with the current cgame view every
checked frame, including transitions. The captured full Quake body is available at
`/tmp/craftq3-bridge-camera-third-person.png`.

A saved singleplayer marker can also be imported by Minecraft into another login
profile. If the current profile lacks the marked generation, recovery now searches
up to 4,096 canonical profile directories in this world for that exact token.
Missing or ambiguous generations fail without mutation; a unique generation is
validated and applied normally. Cross-profile recovery retains the source record,
with cleanup still limited to a fresh unmarked join for its owning profile. Live
camera-crash recovery exercised this path after the development launcher changed
its generated player UUID; two additional tests cover lookup, preservation and
missing/ambiguous rejection.


## Persistent loadout validation (2026-09-07)

`AuditBridgeGameplay.py --loadout --classpath '<packaged-engine-runtime>/*'`
checks both original qagame profiles at health 1, 73, 187 and 200. It compares the
complete restored inventory, fires an original rocket, checks powerup decay and
original armor absorption, kills the one-health player, and consumes a restored
medkit. The retail game heals to 100; the modern profile heals to 125. Both pass
without substituting host healing or weapon behavior.

Separate Minecraft/Vulkan processes use
`./gradlew :craftq3-fabric:runBridgeSmokeClient -Pq3BridgeLoadoutSmoke=<stage>`
with stages `prepare`, `verify`, `fresh`, `dead`, then `verify-dead`. They share a
fixed QA login and isolated `run/craftq3-bridge-loadout-qa` home. Preparation grants
original items, fires a rocket and activates Quad Damage. Verification compares
all saved fields before simulation and then fires another rocket. Fresh entry
checks that an existing rocket checkpoint is bypassed; death and reload check
that a cleared checkpoint restores the original defaults. Each exit checks the
native player's restored Creative mode. See [validation](VALIDATION.md) for the
completed runs and packaged build.


## Round-trip validation (2026-09-07)

`./gradlew :craftq3-fabric:runBridgeSmokeClient -Pq3BridgeRoundtripSmoke=true`
opens the private Minecraft QA world, starts original `q3dm17`, carries its loadout
into Minecraft, fires a rocket, rejects a missing return map without changing the
source, and returns via `quake q3dm17`. It checks exact inventory before simulation,
fires another original rocket in Quake, checks audio ownership and verifies native
Creative mode was restored before the Quake screen resumed. The final capture shows
the original map and Quake HUD with no Minecraft world pixels. Both original QVM
profiles also pass CPU transfer/map-admission and subsequent damage tests.
See [validation](VALIDATION.md) for the final build, captures and regression logs.


## Travel and focus validation (2026-09-07)

`./gradlew :craftq3-fabric:runBridgeSmokeClient -Pq3TravelSmoke=true` builds a
400-block elevated stone course in the private QA save. The far chunk is absent
from the client at entry. Original Quake movement reaches its wall after new
client chunks arrive; floor height, original collision, native chunk tracking and
server position are checked there. A rocket fires at the destination, and exit
restores Creative. The course uses the existing native chunk-loading path, with
no teleport or forced client chunk load during travel.

The same fixture feeds a deterministic focus-loss interval through the real screen
focus branch. Held forward input stops, while original game time and Quad Damage
both advance by 1,280 ms during the sampled interval. Focus recovery permits
movement again. This checks screen handling; it does not automate OS window focus.
The bridge remains a non-pausing screen, so leaving its window does not freeze
Quake while native mobs and world ticks continue. Pure Quake's separate focus-pause
behavior is unchanged. See [validation](VALIDATION.md) for exact results.

## Flame-arrow validation (2026-09-07)

`-Pq3BridgeFireSmoke=true` on `runBridgeSmokeClient` equips a native skeleton with
Flame I. AI is disabled to schedule its native ranged attack; arrow flight, impact,
ignition, cooldowns and fire ticking run normally. An invulnerable player rejects
one arrow without inheriting a burn. Two accepted arrows produce six accepted
native burn ticks that reach original Quake damage while native health stays
unchanged. Exact health totals after both burn intervals also match all accepted
damage, checking for missed or duplicate transfers. Clearing fire and reigniting
it in the same server tick does not retain
mob attribution; neither does reignition after natural expiration. An initial
unattributed native fire is also rejected by the bridge damage policy. The fixture
restores the native invulnerability/resistance values and exits to Creative.
See [validation](VALIDATION.md) for the package, logs and separate Punch regression.
This does not establish every fire source or burn/death/respawn combination.

## Native fireball targeting and burn validation (2026-09-07)

Minecraft's hurting-projectile filter normally skips entities with `noPhysics`.
The bridge uses that flag because original Quake owns player movement. That one
filter now recognizes a living, server-controlled bridge avatar, preserving the
native owner, vehicle, target-eligibility and nearest-hit checks. Other entities
and players outside the bridge keep the native filter. Small fireballs also carry
accepted mob-owned ignition into the existing timed burn-attribution path.

`-Pq3BridgeFireballSmoke=true` on `runBridgeSmokeClient` launches native small
fireballs in an enclosed, lit private QA room. A blaze supplies ownership; the
fixture schedules and aims projectiles while its AI is disabled. Native flight,
acceleration, collision, impact damage and fire ticking remain unchanged. Cover
stops the initial shot. An ownerless projectile and a mob shot at an invulnerable
player both impact without starting Quake damage or attributed fire. The accepted
blaze-owned shot deals 25 Quake damage, followed by four five-point burn ticks;
health ends at 55 exactly. Native health is unchanged, fire expires normally, and
exit restores Creative. That projectile fixture alone does not establish blaze AI
or all hurting projectiles. Fireball-created block fires, other status effects and broader
unusual native-avatar pose interactions remain open. See [validation](VALIDATION.md).


## Native blaze encounter validation (2026-09-07)

`-Pq3BridgeBlazeSmoke=true` on `runBridgeSmokeClient` runs a native blaze with AI
and gravity enabled in an enclosed, lit private QA room. The fixture assigns the
bridge player as its target, verifies blocked native sensing and no projectiles
or damage for 120 native ticks, then removes the wall and reassigns that target.
Minecraft's normal goals handle charging, movement, aiming and projectile creation.
The fixture does not create fireballs or call ranged-attack routines itself.

The live Vulkan encounter observed three native fireballs, one accepted impact
and one burn tick. After original armor absorption, Quake health finished at 91;
Minecraft health stayed unchanged. The test then aims ordinary Quake input at the
moving native body, verifies both selected and equipped rocket launcher plus ammo
consumption, and requires native death and an accepted bridge kill. Original
cgame reports the rocket obituary. Exit restores Creative mode.

Weapon selection waits until cgame has received the inventory granted by `give all`.
The initial test selected too early and used a different weapon; adding explicit
weapon/ammo assertions caught that fixture mistake before the final successful run.
The final evidence is `/tmp/craftq3-bridge-blaze-rocket-live.{log,result,png}`.
This verifies one enclosed encounter, with an assigned target and automated player
input. General target acquisition, arbitrary terrain, multiplayer and all ranged
mob/status combinations remain outside this check. QA clears remaining blaze
fireballs and player fire after the kill; natural burn expiry is covered separately.

## Chat entry and character selection (2026-09-07)

`/q3 bridge` and `/q3 bridge fresh` now queue their launch until Minecraft's chat
submission returns. Previously, the command installed the bridge screen inline,
then ChatScreen closed that new screen while finishing Enter. The apparent
backtick failure was a bridge that had already exited. The queued launch checks
that the player/world and screen still match before creating any bridge resources.

Backtick opens the console. For a different visible Quake character, enter:

```text
cg_deferPlayers 0
model visor
headmodel visor
cg_thirdPerson 1
```

Use `cg_thirdPerson 0` for first person. `cg_deferPlayers 0` requests immediate
model loading rather than the original game's deferred placeholder behavior.
`give all` grants the original QVM weapons; number keys or `weapon 5` select them.
Close the console with backtick or Escape; Escape during play exits the bridge.

Bridge model/head-model/name settings now register as archived player information.
Changed values reach original qagame, then its configstrings update cgame's model
selection; no Java model override substitutes for that path. Saved settings still
load through the bridge's isolated home. Console lines also split on actual line
breaks, preserving ordinary letters in command feedback.

The development console check enters through an actual ChatScreen Enter event,
then checks bridge survival, repeat-safe backtick, original `give all`, railgun
selection, a visible Visor model, return to first person, a bound rocket launcher,
actual rocket presentation, console editing/completion and native-mode restoration.
Use `-Pq3BridgeConsoleSmoke=true` with `runBridgeSmokeClient`; add
`-Pq3BridgeConsoleFresh=true` to test the fresh command and `-Pq3Installation=...`
for an alternate read-only installation. See `VALIDATION.md` for completed runs.
