# Item goal localization

`botlib.item.ItemPlacement` implements both `ItemRegistry.PlacementResolver` for map-authored
items and `ItemRegistry.LivePlacementResolver` for observed live items. It borrows `TraceWorld`
and immutable `AasNavigation`; it owns no file, collision world, VM, or device. Coordinates and
item boxes remain in Quake units. The original qagame VM still controls actual item physics.

Normal static items perform one item-box trace from their map origin down 100 units with contents
mask **1** and ignored entity **0**. A start-solid result retains the original origin; otherwise
the trace end position is accepted, including a completely unobstructed trace. Goal localization
then operates at that resulting item position. Live items have already been placed by qagame and
receive goal localization directly, without another floor-drop trace.

Suspended items first check water contents bit **32** at the original origin. Water bypasses the
airborne check. Otherwise, the item box is traced down **32 units**, with mask **65537**
(solid/player clip) and ignored entity **−1**. Fraction exactly one selects the jump-pad query;
any smaller fraction selects ordinary localization at the **original origin**, not the trace end.
Start-solid does not independently change this branch. These details were observed by instrumenting
only host callbacks in the authored item-discovery oracle.

Airborne jump-pad selection is an explicit injected dependency:
`JumpPadResolver.bestArea(origin, mins, maxs)` returns `OptionalInt`. Empty means the capability
is unavailable; zero means a completed query found no reachable jump pad; a positive value names
the approach area. A successful suspended goal retains its original position as both item and
goal origin. An unreachable item is omitted. An unavailable query is omitted **with a diagnostic**.
The three-argument constructor installs this explicit unavailable provider.

The server supplies the verified [`JumpPadItemAreas` trajectory provider](BOTLIB_JUMP_PAD_ITEMS.md).
It derives launch metadata from BSP triggers, brush models and targets, predicts the launch against
the suspended item's box, and selects the native-observed launch approach area. Original q3dm12's
suspended armor at (−768, −1128, 160) now resolves to area 4421. The three-argument standalone
constructor still reports an unavailable provider explicitly. `isJumpPadArea` exposes the stored
jump-pad contents flag so live registry updates can preserve an already resolved launch area.

## Ordinary AAS localization

`botlib.aas.AasGoalLocator.bestReachableArea(origin, mins, maxs)` returns an immutable
`GoalArea(area, origin)`. Area zero retains a known item with no usable localization. Despite the
original function's name, the observed behavior does not require a nonzero outgoing reachability
count for every chosen area.

The procedure was established with controlled native point/trace/link callbacks:

1. Classify the origin. If it is solid, inspect the published behavior's fixed nearby probes:
   Z offsets 0, 4, 8, 12, 16; horizontal radii 0, 4, 8, 12, 16; and X/Y offsets −radius, 0,
   +radius. The first classified point wins. This is an ordered recovery search, not a distance
   ranking; repeated zero-radius probes have no mutable world effect.
2. For a classified point, trace the crouching AAS presence hull from Z + 0.25 to Z − 50.
   A start-solid trace preserves the candidate area and the raised start position. Otherwise,
   classify the trace end and use it if nonzero.
3. If that fails, link the original item box to AAS areas. Choose the first linked area with
   grounded or liquid area flags; if none has those flags, choose the first linked area. The goal
   origin remains the original origin. Empty links return area zero.

`linkedAreas(absMin, absMax, presence)` exposes the immutable ordered candidate list used by this
fallback. It expands the supplied bounds by the opposite sides of the runtime presence box and
walks the pre-expanded BSP. Native link-list order corresponds to reversing a back-child-first
traversal with duplicate areas suppressed on first encounter. It is not the ascending order
returned by the generic `boxAreas` query, and it does not reject candidates using stored area AABBs.

The initialized native defaults are **(−15, −15, −24) to (15, 15, 32)** for normal presence 2 and
**(−15, −15, −24) to (15, 15, 8)** for crouching presence 4. Some supplied AAS files store a
different compile-time crouching box; the runtime defaults are used here, as verified by the
native bounding-box query. Other presence IDs and runtime overrides of these box defaults are
not supported by this helper.

All inputs are quantized to float32 and bounded to one billion units per coordinate. Bounds must
be ordered. The recovery search performs at most 226 initial/grid classifications plus one final
classification; each classification has the shared navigation node bound. Linked-area traversal
and the presence trace have explicit per-query node budgets, one million by default and at most
two million. Exhaustion throws a diagnostic exception rather than returning an incomplete result
as a valid area. Point classification uses the verified shared rule: exact-plane points choose
the back child. Presence collision retains its separately observed boundary semantics.

## Evidence

Ten authored tests cover normal and failed drops, live placement, suspended short-trace arguments,
water-only behavior, jump-provider outcomes, exact original-position retention, solid-point
recovery, ground contact, ordered fallback, zero-reachability areas, runtime presence bounds,
ownership, and work/input limits.

The read-only full-map differential checked all **30** supplied original AAS maps. All **12,000**
ordered link lists and **12,000** localized area numbers matched native results. The maximum goal
position difference was **0.000793457031 Quake units**, within the audit's explicit 0.001-unit
tolerance; position floats are not claimed byte-identical. The native BSP callback was controlled
clear, while AAS presence traces and map structures were the actual native implementation. Ordinary
localization uses those AAS traces; this audit does not establish a native BSP collision comparison.

The public contracts are in
[be_aas_reach.h](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/botlib/be_aas_reach.h)
and [be_aas_sample.h](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/botlib/be_aas_sample.h).
No original routine body was copied or mechanically translated. The authored
`scripts/ItemReachabilityOracle.c` links an unchanged `be_aas_reach.c` reference object and supplies
controlled public callback results. It can probe point recovery, trace behavior, and linked-area
selection independently of commercial assets. Full-map observations use the ignored full native
AAS host built from the same pinned official checkout. Neither native tool ships in the mod.

After compiling Java classes and preparing `.tools/aas-oracle/probe`, run:

```sh
java -cp craftq3-botlib/build/classes/java/main:craftq3-assets/build/classes/java/main:craftq3-core/build/classes/java/main \
  scripts/AuditGoalPlacement.java /absolute/path/to/pak0.pk3 /absolute/path/to/aas-oracle
```

An optional final argument selects one map. The PK3 is opened read-only; the audit includes no
commercial source or asset bytes in the repository.
