# Collision model

The collision ("CM") layer answers the geometric queries of the game, the cgame's prediction and
the bots: box and capsule traces, point contents, leaf, cluster and area queries, PVS tests and
area portals. It is built once per map from the BSP ([BSP46.md](BSP46.md)) and is immutable
afterwards, so one model serves every thread. Movement, physics and game rules stay in the QVMs.

Code (`engine/collision`):
[`CollisionModel`](../../engine/collision/src/main/java/dev/bluevista/craftq3/engine/collision/CollisionModel.java),
[`Clip`](../../engine/collision/src/main/java/dev/bluevista/craftq3/engine/collision/Clip.java)
(brush, facet and capsule clipping),
[`PatchBuilder`](../../engine/collision/src/main/java/dev/bluevista/craftq3/engine/collision/PatchBuilder.java)
and [`PatchTable`](../../engine/collision/src/main/java/dev/bluevista/craftq3/engine/collision/PatchTable.java)
(patch facets),
[`BrushTable`](../../engine/collision/src/main/java/dev/bluevista/craftq3/engine/collision/BrushTable.java),
[`Trace`](../../engine/collision/src/main/java/dev/bluevista/craftq3/engine/collision/Trace.java),
[`TraceRequest`](../../engine/collision/src/main/java/dev/bluevista/craftq3/engine/collision/TraceRequest.java),
[`TraceWork`](../../engine/collision/src/main/java/dev/bluevista/craftq3/engine/collision/TraceWork.java),
[`TraceMerge`](../../engine/collision/src/main/java/dev/bluevista/craftq3/engine/collision/TraceMerge.java),
[`AreaPortalState`](../../engine/collision/src/main/java/dev/bluevista/craftq3/engine/collision/AreaPortalState.java),
[`CollisionSource`](../../engine/collision/src/main/java/dev/bluevista/craftq3/engine/collision/CollisionSource.java),
[`BoxSource`](../../engine/collision/src/main/java/dev/bluevista/craftq3/engine/collision/BoxSource.java),
[`Contents`](../../engine/collision/src/main/java/dev/bluevista/craftq3/engine/collision/Contents.java),
[`SurfaceFlags`](../../engine/collision/src/main/java/dev/bluevista/craftq3/engine/collision/SurfaceFlags.java).
Reference: id Software's public
[surfaceflags.h](https://github.com/id-Software/Quake-III-Arena/blob/master/code/game/surfaceflags.h)
for the contents and surface flag values.

## Models and handles

Handles are ints as in `clipHandle_t`: 0 is the world, `1..inlineModelCount()-1` the inline models
(doors, platforms), and 255 and 254 the temporary box and capsule built by `tempBoxModel` (one of
each per `TraceWork`). World traces walk the BSP tree; inline models test their own brushes and
patches. Brushes take their contents from their shader and their surface flags from each side.

Per-thread scratch lives in a `TraceWork`, so queries do not allocate; hot paths pass one
explicitly, other calls use a per-thread work of the model.

## Traces

`boxTrace` sweeps a box (or the capsule fitted to it) from `start` to `end` (`CM_BoxTrace`); a null
box is a point and `start == end` is a position test. The result mirrors `trace_t`:

- `fraction` along the segment and `endPos`; contacts stop 0.125 units (`SURFACE_CLIP_EPSILON`) in
  front of the surface.
- `startSolid` when the start overlaps a solid; `allSolid` (with fraction 0) when the trace never
  leaves it.
- The hit plane, surface flags, contents and the entity number set by the caller (1022 is the
  world, 1023 none).
- Within one model, a solid replaces the result only when it is strictly nearer, so equally near
  hits keep the first one found. `TraceMerge` applies the same rule when the server or client
  combines the world with linked entities: all-solid and start-solid are sticky.

Box sweeps descend into both sides of a non-axial node plane unless they stay 2,048 units beyond
it, as the original does, so expanded axial brushes behind slanted planes cannot be missed. A
position test gathers at most 1,024 leaves.

`transformedBoxTrace` traces against a model placed at an origin with angles
(`CM_TransformedBoxTrace`): the box is made symmetric about its centre, the segment is moved (and,
for rotated models, rotated) into the model's frame, and the box keeps its axes there, an
approximation the original makes too. The hit normal is rotated back and, unlike the original, the
plane distance is reported in world space ([DIVERGENCES.md](../DIVERGENCES.md)). `entityContact`
is the position test behind `trap_EntityContact`.

## Capsules

A capsule is the vertical capsule fitted to a box, as in the original: its radius is the smaller of
the box's half extents along x and z, and its two sphere centres lie on the vertical axis. Capsules
sweep against brushes and patch facets with the sphere nearest each plane, and against temporary
capsule models as spheres and a vertical cylinder, with surfaces swollen by 1 unit
(`RADIUS_EPSILON`). Deliberate differences from the original (capsule against capsule is a true
combined capsule, rotated capsule axes are correct, point contents of a capsule model test the
capsule, box-against-capsule bounds are recomputed) are listed in
[DIVERGENCES.md](../DIVERGENCES.md). baseq3 itself does not use capsule traces.

## Patches

Patch surfaces collide through facets generated from their control grids, following the
approach of the original:

1. Each column triple is refined with de Casteljau midpoints until the curve's midpoint lies within
   16 units of the chord (`SUBDIVIDE_DISTANCE`); flat control columns collapse and duplicate
   columns (within 0.1) are removed. The grid is transposed and the rows refined the same way. A
   refined grid stops growing at 129 points per side (the patch is then counted as truncated).
2. Each grid cell's two triangles get planes from a per-patch plane list, shared when every vertex
   lies within 0.1 of a plane.
3. A cell is one quad facet when both triangles share a plane, otherwise two triangle facets.
   Borders are the neighbouring cells' planes, or planes through the edge perpendicular to the
   surface where there is no distinct neighbour.
4. Facets whose borders do not enclose them are discarded; bevels are added (the six axial planes
   of the facet's winding, planes through non-axial edges, and the reversed surface plane).

Patches whose shader has no contents are skipped. A patch needing more than 1,024 facets or
4,096 planes is skipped with a warning instead of failing the map load
([DIVERGENCES.md](../DIVERGENCES.md)); hit plane type and sign bits are recomputed. Patches block
sweeps and position tests but have no volume for point contents. `CollisionModel.build(map,
false)` builds without patch collision, like the original `cm_noCurves`.

## Point contents, leaves and visibility

- `pointContents` ORs the contents of every brush containing the point (points on a face count
  as inside), in the world or a model; `transformedPointContents` places the model first.
- `pointLeafnum`, `leafCluster`, `leafArea` and `boxLeafnums` (the original leaf order, with the
  last leaf reported past the capacity) query the BSP tree.
- `clusterVisible` and `inPVS` use the BSP visibility rows; a negative cluster or a map without
  visibility sees everything. `inPVS` also requires the two areas to be connected through open
  area portals; `inPVSIgnorePortals` does not.
- `AreaPortalState` (one per running game) keeps a reference count per area pair, adjusted as doors
  open and close, and a flood-filled connectivity. All portals start closed.

## Foreign geometry

A `CollisionSource` adds solids that are not part of the BSP to world traces and point contents
(the Minecraft bridge and building modes use it; see [MINECRAFT.md](../MINECRAFT.md)).
`BoxSource` is a fixed set of world-space boxes, clipped in insertion order so equally near hits
resolve to the earlier box.

Independent implementation; see [LICENSE-NOTES.md](../../LICENSE-NOTES.md). Tests:
`BrushTraceTest`, `CapsuleTraceTest`, `ModelTraceTest`, `PatchTraceTest`, `VisibilityTest`,
`AllocationTest`, and the `@RealData` `RealMapCollisionTest`.
