# Collision host contract

`craftq3-collision` supplies original Java 25 collision geometry and queries without Minecraft,
rendering, VM, or native-code dependencies. The original game QVM remains responsible for movement,
steps, friction, gravity, projectiles, and game rules; this module answers its geometric queries.

## Public API

- `TraceWorld.trace(TraceRequest)` returns a `TraceResult` for a ray or swept axis-aligned box.
  `TraceRequest.box(start, end, mins, maxs, contentsMask)` keeps box extents relative to its moving
  origin; asymmetric player extents work directly. `ray(...)` uses zero extents. `ignoring(entity)`
  excludes one entity identifier. Negative one is the conventional no-exclusion value.
- `pointContents(point, mask, ignoredEntity)` combines the complete contents values of containing
  brushes that overlap the query mask. Overloads query all contents or omit the exclusion.
- `BspTraceWorld(BspMap)` compiles world model zero. `model(index, entity, origin, angles)` returns a
  separately positioned inline model. Angles are Quake pitch/yaw/roll degrees, including positive
  downward pitch. The moving box remains aligned to world axes when the obstacle rotates.
- `BoxTraceWorld(min, max, contents, surfaceFlags, entity)` exposes a reusable static world AABB.
  `BoxTraceWorld.at(origin, mins, maxs, ...)` accepts relative bounds. Linked game entities and later
  Minecraft collision adapters can supply these through the same interface.
- `CompositeTraceWorld(List<TraceWorld>)` chooses the earliest collision across providers, retaining
  stable provider order for equal fractions. Start-solid flags combine across all providers.
  The caller handles owner relationships and live entity contents before adding providers.

All geometry and returned collections are immutable. Queries allocate private scratch state and
can run concurrently. Inline-model cache access is synchronized. Translations reuse compiled model
rotation geometry and translate the query and resulting plane; moving a platform does not rebuild
all of its brush hulls or patch triangles.

## Results and geometry

`fraction` is in `[0,1]` along the requested origin segment; `endPosition` is that origin, not a box
corner. A clear trace ends at fraction one. Entry contacts maintain a 0.125 Quake-unit separation
from the obstacle. `startSolid` means the initial volume overlaps a shape. If a shape contains both
ends of the entire segment, `allSolid` is true, fraction is zero, and the end is the start. An escape
from initial overlap may return fraction one with `startSolid` true. `allSolid` combines results of
individual convex shapes; it does not prove that a union of overlapping shapes covers a segment.

An impact carries the unexpanded world-space plane, full contents, surface flags, entity/model,
brush/side or patch-face indexes, and texture/shader name. `Plane.NONE` denotes an all-solid result
without a meaningful entry plane. World entity number is 1022. Synthetic shapes have negative BSP
indexes; generated support bevels have side index negative one. Equal-distance hits choose brushes
by ascending BSP index, then patches by ascending face index. Additional support bevels inherit the
first brush side's material when there is no original side corresponding to that bevel.

World brush and patch candidates come from BSP splitting planes and leaf references. Swept extents
select both children when necessary; repeated nodes, leaves, brushes, and faces are deduplicated.
Geometry absent from leaf references is conservatively included. This traversal is unrelated to
render PVS: invisible geometry still collides. Inline models use their model brush/face ranges.
Rotated brushes gain world-axis and edge-cross-axis support planes for swept-box corner contacts.

Biquadratic patch faces become one-sided triangles with axis and edge support planes. Their full
control-point positions and normals determine the surface and front direction. Uniform eight-way
subdivision per three-by-three control block is the default; an overload accepts one to sixteen.
Shared control-block edges use the same parameter samples. Nonsolid surfaces (`SURF_NONSOLID`) are
omitted. Patches do not enclose a volume for `pointContents`.

## Current limits

- Patch tessellation is an approximation and does **not** reproduce Quake III's adaptive collision
  facet builder exactly. Edge contacts, curved ramps, and initial patch overlaps need comparative
  gameplay testing before claiming identical original-engine collision behavior.
- Sweeps support rays and axis-aligned boxes. Capsules, continuously rotating moving query volumes,
  and exact triangle-mesh/MD3 collision are not implemented. Render mesh faces and flares are not
  collision brushes. BSP surfaces rely on their compiled brushes or patch geometry.
- BSP traversal conservatively collects candidates for the entire segment; it does not yet shorten
  traversal after a near hit. Inline models test all of their own geometry. Server-side entity
  broad-phase filtering remains the host's responsibility.
- A compiled model may contain at most 200,000 patch triangles and 4,000,000 support planes. Rotated
  brush reconstruction permits 256 input planes and two million clipping-vertex work units per
  brush. Model caches retain at most 128 rotations/poses and one million compiled support planes;
  an oversized model remains queryable without caching. Caller-retained model instances have their
  own lifetime. Coordinate and finite-number guards reject invalid inputs rather than propagating
  NaNs. The BSP asset reader is the validation boundary for lump indexes and ranges.
- Rotated hull construction assumes a finite convex BSP brush and uses a bounded initial clipping
  polygon. Malformed, unbounded, or extremely ill-conditioned brush descriptions are unsupported.

## Verification

Run `./gradlew :craftq3-collision:test` for original, generated fixtures covering asymmetric swept
boxes, separation planes and metadata, escaping/entirely solid paths, masks and entity exclusion,
parallel/zero-length traces, nearest-hit composition, 500 deterministic BSP-versus-box queries,
leaf deduplication, translated/yaw/pitch/roll inline models, rotated-box corner bevels, and front/
back curved-patch contacts. Tests contain no commercial map data.

`./gradlew :craftq3-collision:auditCollision` optionally reads the user's existing
`run/craftq3/games/baseq3` installation in place. Override it with `-Ppk3dir=/path/to/games` and select
maps with `-PcollisionMaps=q3dm12,q3dm17`. It traces the original player bounds from each deathmatch
spawn using Quake's nine-unit spawn adjustment and requires an unobstructed initial position and
supporting floor. The local `pak0.pk3` audit passed all 20 q3dm12 and 11 q3dm17 spawn positions with
zero start-solid results. This is an integration smoke test, not a complete collision oracle.

## Behavioral references

Implementation is original Java and contains no copied or translated native routines. Published
Quake III structures and unchanged upstream source were consulted as behavior references:

- [Contents and surface flag values](https://github.com/id-Software/Quake-III-Arena/blob/master/code/game/surfaceflags.h)
- [Trace result semantics](https://github.com/id-Software/Quake-III-Arena/blob/master/code/qcommon/cm_trace.c)
- [Patch behavior and support-plane concepts](https://github.com/id-Software/Quake-III-Arena/blob/master/code/qcommon/cm_patch.c)
- [Entity filtering and transformed collision contract](https://github.com/id-Software/Quake-III-Arena/blob/master/code/server/sv_world.c)
- [Original spawn origin adjustment](https://github.com/id-Software/Quake-III-Arena/blob/master/code/game/g_client.c)

Commercial PK3 data, generated collision geometry, and native reference builds remain local and
are neither test fixtures nor release contents.
