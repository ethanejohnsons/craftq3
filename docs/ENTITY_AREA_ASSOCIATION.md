# Server entity area association

`EntityWorld` stores the private server area association when an entity is linked.
It uses `BspBoxLeaves.query(map, absoluteBounds, 128)` and visits the returned leaves
in their native order. Leaf AABBs do not determine membership. Visibility clusters
do not determine whether an area is usable: negative areas are ignored, while
area zero remains valid even on a leaf whose cluster is -1.

The retained pair is the first nonnegative area and the last subsequent area
different from that first. Native sequences `1,2,3`, `1,2,1`, `1,2,3,2` and `0,1,0`
produce `1/3`, `1/2`, `1/2` and `0/1`. Only the first 128 returned leaves participate;
index 127 can supply an area and index 128 cannot. An empty or single-area
association returns an empty or one-element immutable list to the portal host.
Three or more touched areas do not create an unsupported three-area list.

The association is separate from collision linkage. Guest writes to `absmin` or
`absmax` and entity unlinking preserve the last linked pair. A new link replaces
it, including with an empty association. World reset and successful entity-memory
relocation clear associations; reducing the located entity count prunes removed
slots. This prevents old areas from returning if the count later grows.

## Native observations

The original q3tourney4 door at entity 159 has absolute bounds
`(-1,-329,255)..(81,-311,401)`. Native `SV_LinkEntity` requests 128 leaves, receives
37, and retains area 1 followed by area 0. One touched leaf, 631, has cluster 311
and area -1. Treating every visible-cluster leaf as a valid area incorrectly adds
a third sentinel area and rejects the original door's portal operation.

Additional authored metadata fixtures changed only the private native map state:
all negative areas produce `-1/-1`; valid area 0 solely on solid-cluster leaf 615
or solely on visible leaf 631 produces `0/-1`; assigning those leaves areas 0 and
1 produces `0/1`. Ordered three-area and 128-leaf boundary fixtures use q3dm2's
existing valid area-index range. The source PK3 is never modified.

A lifetime probe changes guest absolute bounds after native linking, observes
the unchanged private pair, calls `SV_UnlinkEntity`, observes the retained pair,
and then relinks at a far origin. Results are `1/0`, `1/0`, then `-1/-1`. An ignored
Java replay produces the corresponding `[1,0]`, `[1,0]`, and empty association for
both retail and Q3 1.32 guest layouts. Six focused `EntityWorldTest` tests pass,
including synthetic topology, area sentinels, ordering/cap and cache lifecycle.
The shared box-leaf helper separately passed 310,000 native queries on 31 BSPs.

## Reproduction and provenance

`scripts/EntityLinkOracle.c` is an authored observer built by
`scripts/BuildEntityLinkOracle.py` against the existing ignored official server
build. It calls unchanged map loading, initializes fields declared in the public
server/entity headers, and invokes `SV_ClearWorld` and `SV_LinkEntity` before any
game VM initialization. A transparent `CM_BoxLeafnums` wrapper records the native
capacity without changing the routine. No engine function bodies were copied,
translated or read to derive the association policy.

Run the observer with `CRAFTQ3_ENTITY_LINK_QUERIES` naming a trusted text file whose
rows are `entityNumber minX minY minZ maxX maxY maxZ`. These are requested final
absolute bounds; each span must be at least two units because the native link
operation adds one unit on each side. Use explicit `fs_basepath` to the user-owned
game packs, `fs_homepath` to an ignored isolated directory, `net_enabled 0`, and
`+map <name>`. The observer exits after its queries before guest initialization.
`CRAFTQ3_ENTITY_AREA_MODE` selects the documented q3tourney4 metadata controls;
`CRAFTQ3_ENTITY_AREA_ASSIGNMENTS` names an optional fixture file assigning valid
areas to positions in the queried leaf sequence.

Local evidence is retained under `.tools/entity-link-oracle`: `door.log`,
`mode0.log` through `mode4.log`, `order.log`, `lifetime.log`, and `java-door.log`.
All native binaries, private metadata fixtures and original commercial assets
remain outside the shipped mod. The reference checkout is the previously pinned
ioquake3 commit `588393618dbc82e7207c21c6ddecca229944a03a`; observed collision/vector
arithmetic uses `-ffp-contract=off`.
