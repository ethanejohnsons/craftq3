# Minecraft building inside Quake maps

Open a local Minecraft world and run `/q3 build q3dm17` (or another installed BSP
map name). Minecraft Creative controls, inventory, block placement and breaking
remain active. `/q3 leave` returns to the original dimension, position, rotation,
game mode and abilities. Escape opens the normal Minecraft pause menu.

The original BSP is immutable. Its triangles, shaders and lightmaps are read
straight from the user's PK3 hierarchy. Placed blocks are real Minecraft blocks
in the dedicated `craftq3:build` dimension, saved in ordinary Minecraft chunks.
No BSP is voxelized, modified or written back into a PK3. Quake floors and walls
participate in native Minecraft entity movement through exact BSP traces. Each
axis resolves BSP and block obstacles at the same body position. A scoped analytic
shape supplies BSP sweeps and support-height candidates to Minecraft’s own step
selection, which retains grounded-state, step-height and headroom checks. The
adapter is confined to movement; it does not publish fake voxel boxes to world
queries.

A world-local `craftq3/build-maps.properties` index assigns each exact BSP SHA-256
a persistent region. Different BSP versions receive different regions; returning
to the same BSP reuses its blocks. Regions are separated by 4,096 blocks, with at
most 4,096 maps. The current adapter requires horizontal BSP coordinates within
±1,800 Minecraft blocks and a vertical span that fits the dimension's build range.
The scale is 32 Quake units per Minecraft block.

`craftq3/build-worlds.properties` records each region's source game, map path and
vertical origin. On world startup, CraftQ3 checks the exact BSP hashes and parses
collision before Minecraft creates its levels, then installs all saved regions
before level ticking. Forced-loaded chunks therefore retain support and entity
collision without opening `/q3 build`. Older hash-only indexes migrate by finding
the original BSPs in the configured game/baseq3 hierarchy. Keep those original
assets available: a missing or changed BSP stops world startup with a logged
identity error. Restoring the matching assets allows the save to open again.
A shadowing PK3 does not substitute different geometry for an existing region.
Worlds without build regions do not need PK3s for this startup step.

## Placement and rendering

Picking compares the original BSP surface with Minecraft's existing block/entity
hit. A BSP hit supplies an outside grid cell to the normal Minecraft item-use
path. Native placement rules, block state selection, inventory and breaking are
retained. New collision shapes are checked against the BSP on both client and
server, so a placed block cannot be buried inside an original solid brush.
Decorations without collision use their selection shape for this check.

A BSP floor need not align with Minecraft's block grid. The first block uses the
nearest clear outside cell, which can leave a fractional-block gap above a floor.
Native FULL, CENTER and RIGID face-support queries now combine Minecraft shapes
with coplanar outward faces of solid BSP brushes. Coverage uses polygon clipping,
so adjoining brushes can jointly support a block while narrow holes remain holes.
Floor and wall torches place, survive neighbor updates and break normally. The
support face must meet the grid boundary: a fractional gap is not treated as a
foundation. Player-clip volumes and curved patches do not supply support.
Material-specific rules, such as crops requiring farmland, still require the
corresponding Minecraft block. The BSP itself cannot be mined.

The BSP renderer shares Minecraft's color and depth attachments and its exact
projection, including view bobbing. Its building-mode pipelines use Minecraft's
reversed-depth comparison and polygon offset. Standalone Quake continues using
its own conventional depth buffer. Vanilla sky/cloud/weather passes are suppressed
inside the build session; Minecraft blocks, items, hand and HUD remain visible.

## Item frames and paintings

Item frames and paintings can attach directly to grid-aligned BSP faces. The item
path adjusts the outside hit cell for Minecraft's native hanging-entity placement
rules. Native support checks accept coplanar full BSP faces alongside their normal
Minecraft supports, while collision checks reject decorations buried in solid BSP.
Minecraft still chooses painting variants and sizes, prevents overlapping hanging
entities, handles item insertion/rotation, breaking and drops, and performs periodic
survival checks. Saved server geometry remains available after leaving the session.

Native decorations now receive a brightness approximation from the original Quake
light grid, combined with Minecraft's existing lighting. See the lighting section
below for its scope and limits. Fractional gaps do not count as
hanging support. Sloped surfaces and generalized non-grid attachment are not added.

A Vulkan q3dm17 check places a frame through the normal client item-use path,
inserts a diamond, rotates it and breaks the frame with native interactions. It
then places a painting, lets native survival ticks run, leaves the session and
checks retained BSP support. Reproduce with
`./gradlew :craftq3-fabric:runBridgeSmokeClient -Pq3BuildSmoke=true
-Pq3DecorationSmoke=true` in the private QA save.

## Light-grid brightness for native entities

The active build session samples the original BSP light grid for native entity
rendering and level-based light-coordinate queries, including paintings and held
items. It converts interpolated ambient light plus half the directed contribution
to luminance, then maps that brightness into Minecraft's block-light range using
its light-map curve. Native sky light is retained, and stronger native block light
or emissive entity lighting wins. Missing/solid light-grid samples and positions
outside the original map bounds keep their native lighting.

This changes render inputs only: native chunk light data, mob spawning, block
updates and the immutable Quake lightmaps remain untouched. It is a brightness
approximation; it does not reproduce Quake's light colors or directional response
on Minecraft models. Native terrain chunk lighting, shadows cast by placed blocks
onto BSP, and lighting outside the active rendered build region remain open.

The Vulkan lighting fixture captures the same painting with the adapter disabled
and enabled, verifies brighter light coordinates with preserved sky light, and
checks both an ordinary native entity renderer and a burning entity's full light.
It also repeats decoration placement, interaction, survival and return checks.
Reproduce with `./gradlew :craftq3-fabric:runBridgeSmokeClient -Pq3BuildSmoke=true
-Pq3LightingSmoke=true` in the private QA save.

## Buckets and flowing fluids

Water and lava buckets can target original BSP surfaces through their normal
Minecraft item-use path. Nearer native blocks and source fluids retain priority,
so ordinary waterlogging and bucket pickup remain available. Placement selects a
clear outside grid cell, including above a fractional-height Quake floor.
Bucket placement and fluid spread reject cells intersecting solid BSP geometry;
neighbor-to-neighbor flow also checks for intervening BSP barriers. Player-only
clip volumes do not act as fluid solids. Native fluid levels, spread timing,
source conversion, bucket sounds and inventory behavior remain in Minecraft.

A coplanar full BSP floor can satisfy Minecraft's foundation check when two nearby
water sources form another source. Fluid queries use saved server geometry, and
apply BSP checks after Minecraft's block-state cache so equal air states at different
positions do not share an incorrect geometry result. No collision blocks are
created. Native water still falls normally in empty space outside the BSP.

Fluids remain native grid cells: a partly occupied BSP cell cannot contain a new
fluid block, and fractional floors can leave a gap below the first clear cell.
The source-conversion foundation must meet the grid boundary. Rendering does not
clip a water volume into an arbitrary BSP-shaped container. Broader sloped basins,
waterfalls, fluid interactions with fire and entity swimming need further coverage.

The live Vulkan check uses actual client bucket use and scheduled server fluid
ticks in original q3dm17. It verifies water/lava placement, spreading and pickup,
no leakage into the air cells below the BSP floor, rejection of a buried bucket
source, two-source water conversion, native slab waterlogging/pickup and a native
free-fall control outside BSP geometry. Reproduce with
`./gradlew :craftq3-fabric:runBridgeSmokeClient -Pq3BuildSmoke=true
-Pq3FluidSmoke=true` in the private QA save.

## Minecraft projectiles

Native arrows and the standard thrown-projectile query now compare BSP solids
with Minecraft block hits before selecting nearer entity hits. This preserves
Minecraft arrow damage, embedding, pickup/despawn rules and thrown-item impact
callbacks. The BSP remains immutable and no collision blocks are created.
Arrow support checks include BSP solids so a stuck arrow stays embedded through
native movement checks. Player-only clipping volumes do not stop or hold arrows.

Live original q3dm17 checks cover arrows hitting floors and walls, remaining
embedded through 20 native ticks and a movement check, snowballs impacting the
wall, nearer Minecraft blocks winning, and mob damage in front of the wall while
the same mob remains protected behind it. The test verifies that the covered mob
is available to native entity traces, preventing unloaded chunks from masquerading
as cover. Reproduce with `./gradlew :craftq3-fabric:runBridgeSmokeClient
-Pq3BuildSmoke=true -Pq3ProjectileSmoke=true` in the private QA save.

## Minecraft explosions

BSP solids now participate in Minecraft explosion exposure and block propagation.
Native entity exposure samples combine block and BSP cover; native damage and
knockback calculations remain in charge, including Minecraft's minimum damage
under full cover. Block propagation checks exact segments between native samples,
so thin BSP walls stop a blast even when neither sample lies inside the wall.
BSP geometry is immutable, and player-only clipping volumes do not stop blasts.
These hooks apply only to the building dimension and use saved server geometry.

The live q3dm17 test discovers an original barrier with legal native building
space on both sides. The covered cow takes only native minimum damage with no
knockback; an exposed cow takes greater damage and knockback. Exposed glass breaks
while glass behind the BSP survives. A matching seeded native blast in an empty
QA region selects both glass positions for destruction, verifying the covered
block is within blast range. Reproduce with
`./gradlew :craftq3-fabric:runBridgeSmokeClient -Pq3BuildSmoke=true
-Pq3ExplosionSmoke=true` in the private QA save.

## Ground-mob navigation

Minecraft's ground pathfinder now queries BSP standing heights and actual
body/headroom clearance alongside native blocks. BSP occupancy outside a small
mob's body no longer rejects its whole path cell. Swept body checks between
neighbor nodes reject intervening walls, including thin barriers with clear
endpoints. Paths use the existing native
grid and movement controller; the adapter does not generate block maps or replace
Minecraft AI. Destination and waypoint heights account for BSP floors, including
fractional heights. Native doors, fluids and hazard classifications remain active.

The live q3dm17 audit uses a cow with competing random goals disabled, then lets
Minecraft's navigation and movement tick normally. It finds a path across an
original floor at Y=38.125, replans around a newly placed three-block-high obstacle,
and walks to the target. Both paths contain seven nodes, with the second taking
a detour. Original BSP walls are rejected, and water, lava, stone and magma retain
their native path types. Reproduce with
`./gradlew :craftq3-fabric:runBridgeSmokeClient -Pq3BuildSmoke=true
-Pq3NavigationSmoke=true` in the private QA save.

A second live audit lets a 0.4-by-0.7-block chicken walk a five-node path from
`(19.5,38.12890625,13.5)` toward `(19.5,38.12890625,17.5)`. One node is
blocked under the old whole-cell check but fits the chicken. A 0.9-by-1.4-block
body is rejected at the tight target; this does not assert that a larger mob has
no alternate route. The chicken reaches within 0.406 blocks through native
navigation and movement. Reproduce with `-Pq3BuildSmoke=true
-Pq3NarrowNavigationSmoke=true` on `:craftq3-fabric:runBridgeSmokeClient`
in the private QA save.

## Mob sight and combat

Native line-of-sight checks now include solid BSP cover while retaining Minecraft's
block/fluid rules, range and dimension checks, and per-tick sensing cache. This
also informs ordinary melee/ranged AI that uses those sight queries. Player-only
clip volumes do not block sight. No target selection or attack rules are replaced.

The live original-map check hides a target behind BSP geometry, moves it into
clear view, verifies the normal sensing-cache refresh, and adds/removes native
stone cover. A separate zombie then uses its normal AI to pursue a stationary cow
across the fractional floor for about five blocks and land an attack, reducing
the cow's health from 10 to 7. QA assigns that target but does not command movement
or apply damage. Reproduce with
`./gradlew :craftq3-fabric:runBridgeSmokeClient -Pq3BuildSmoke=true
-Pq3MobCombatSmoke=true` in the private QA save. Broader target acquisition,
ranged/specialized mob goals and complex combat routes remain to be verified.

## Session recovery

Entry writes a world-local `craftq3/return-<player UUID>.properties` return point
before teleporting. Normal exit restores the captured state and retains that file.
A server-join hook restores a saved player found in the build dimension after an
interrupted session. Only a subsequent world load outside the build dimension
removes the return file, confirming Minecraft has saved the return. This also
allows recovery if the process stops again immediately after a return teleport.

The entire return record is size-bounded and parsed before changing the player.
Invalid records produce a logged error and a connection error instead of loading
the player into an empty build dimension; the record is preserved. Cleanup failure
for an obsolete record does not prevent joining an already restored world.
Save-and-quit also requests normal session cleanup.

## Current limits

This is the first playable building slice. It renders the BSP as a Minecraft
building environment; original qagame/cgame matches, pickups, movers and bots are
not running in this mode. Pure Quake and `/q3 bridge` remain separate modes.

Native walking/falling and step selection meet BSP collision. A q3dm17 ledge is
verified live, but broader stairs/slopes, fluid/entity interactions, support-block and block-specific
behavior still need integration and map coverage. Server-side collision and face
support remain available for saved regions after leaving, switching maps and
restarting Minecraft. Parsed geometry stays in memory until the server stops;
there is no region eviction yet. Client rendering still follows the active build
session.
Ground mobs now have BSP-aware native path queries, with flat and fractional-floor
walking and placed-block detours verified. BSP clearance uses actual mob bodies and swept path edges; a small-mob tight
passage now passes live. The native grid can still reject off-center passages
and slopes even where a physical route exists.
Large mobs, broader stairs/slopes, swimming/flying navigation and wider combat
behavior need further coverage. Native zombie pursuit and an attack on a stationary
target now pass on a fractional BSP floor.
Transparent materials, lighting between the two layers and unusual maps need
more testing. Explosions now respect BSP cover; blast-triggered block effects and
fire behavior need broader integration coverage. Projectile impact callbacks still see the native
block state at the surface cell (often air); material-specific effects, every
projectile type and persistence of embedded arrows need further coverage. Multiplayer building is not supported.

## Evidence

Ten geometry/index tests cover swept native-sized bodies, floor/wall sliding, grid
and fractional-floor placement, legal item-use hit coordinates, solid/missed
queries, durable map identities and rejection of aliased/corrupt region indexes,
plus mixed floor/wall collision, step candidates, excessive ledge height, native
and BSP headroom, and sorted heights for short entities.
Four return-record tests additionally cover every required field, exact position/
rotation/mode/abilities, repeatable reads, invalid values and malformed or oversized
files without deleting recovery data.
Ten support tests add brush unions, native/BSP combinations, holes, boundaries,
all six directions, excluded clip/patch geometry and native support-type semantics
(180 comparisons across real Minecraft block shapes).
A region-boundary test additionally covers negative coordinates, exact cell-region
edges and out-of-range lookups.
Eight additional tests cover manifest validation and exact mounted-source identity
lookup, including shadowed maps and missing assets.
The full build passes 1,246 tests, including formatting and compiler warnings as
errors. The live Vulkan smoke uses original retail q3dm17 and the real Minecraft
item-use/destroy-block paths; it verifies placed blocks on the integrated server
and checks return to the captured state. See [validation](VALIDATION.md) for the
latest package, persistence and runtime evidence.


The final explicit Vulkan result also verifies a block persisted across separate
Minecraft launches: `placed=true broken=true returned=true persisted=true`.
Packaged standalone Quake and both forward-bridge QVM profiles pass their existing
regressions; a fresh standalone Vulkan input capture passes too.

The latest native movement audit also exercises Minecraft’s real `Entity.collide`
and `Entity.move` on an unchanged q3dm17 ledge: a grounded player rises 0.125 blocks,
while an airborne player remains blocked. The same Vulkan run passes placement,
breaking, persistence from a separate process, and dimension/state restoration.

Nine separate-process lifecycle checks now pass on Vulkan: seven forced-crash/
recovery/cleanup stages and normal shutdown/reopen. The tests include a second
crash immediately after recovery and a crash after `/q3 leave`, before Minecraft
saves the restored player. Position, rotation, mode and all saved abilities are
checked. This covers process interruption; power-loss durability and reconstruction
of a missing return record are not established.

The native support smoke passes on original q3dm17 with floor and wall torches,
server acceptance, support-neighbor changes, breaking and return:
`PASS support floor=true wall=true neighbors=true broken=true returned=true`.
Reproduce with `./gradlew :craftq3-fabric:runBridgeSmokeClient -Pq3BuildSmoke=true
-Pq3SupportSmoke=true` in the prepared private QA world.

The latest support smoke retains a wall torch after normal placement/breaking and
replacement, leaves the map, and enters q3dm1 in a different region. Native support
updates preserve the old torch in both cases, and a native armor-stand movement
probe still lands on the original floor. Rendering and PK3 handles close on leave;
immutable geometry/support providers remain server-owned until shutdown. This
retains parsed geometry for visited maps in memory during that server lifetime.

A separate-process Vulkan test now saves a wall torch and real armor stand in
forced-loaded build chunks, closes Minecraft, and reopens without a build session.
Both saved regions restore before ticking; torch support, neighbor updates, saved
entity position and a native collision move pass. An intervening missing-map
startup fails before level creation and leaves all 24 region/entity storage files
byte-identical. After restoring the fixture's source descriptor, the same saved
torch and entity pass the reload test. Original PK3s remain untouched.
Reproduce the two positive stages with `-Pq3BuildSmoke=true -Pq3SupportSmoke=true
-Pq3WorldReloadSmoke=prepare`, then `-Pq3WorldReloadSmoke=verify`, on
`:craftq3-fabric:runBridgeSmokeClient` in the private QA world.
