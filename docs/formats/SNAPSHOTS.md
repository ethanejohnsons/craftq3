# Local Q3 snapshots

`Q3Server.entitySnapshot(client)` returns canonical entity states, a defensive 32-byte BSP area
mask, the number of visible entities, and the number omitted at the original 256-entity snapshot
limit. `entityStates(client)` remains a compatibility accessor. The local cgame transport copies
the area mask into the guest snapshot and converts canonical states to the selected guest ABI.

The server reads the requesting client's player state. Its `clientNum` identifies the viewed
player, which can differ from the requesting client during spectator follow. That player's entity
is excluded because cgame reconstructs it from player state; transmitting both creates a duplicate
event path. Visibility starts at player origin plus view height, in Q3 coordinates.

Only linked entities are candidates. `SVF_NOCLIENT`, `SVF_SINGLECLIENT`, `SVF_NOTSINGLECLIENT`, and
`SVF_CLIENTMASK` filter the viewed player before broadcast or visibility checks. The retail ABI has
no `singleClient` member; modern audience extensions require a guest ABI that supplies that member.
The authoritative entity number is its index in the located game entity array.

For ordinary entities, a BSP split-plane traversal classifies every leaf touched by the linked
world-space bounding box. At least one touched cluster must be in the viewpoint's static PVS, and
at least one touched area must be connected through currently open, reference-counted area portals.
Cluster/area membership is cached per entity number and bounds; door connectivity remains live.
This uses complete touched-cluster sets rather than a fixed cluster list with an overflow range.

`SVF_BROADCAST` bypasses PVS and area checks. Visible `SVF_PORTAL` entities add a viewpoint at
`entityState.origin2`; the modern `generic1` range restricts opening that view, measured from
`entityState.origin`. The portal entity itself remains visible when its range rejects the remote
view. Broadcast entities do not open additional portal views. Entity deduplication bounds recursive
portal processing, including cycles. Reachable areas from all accepted viewpoints are combined,
then inverted: a set mask bit means the renderer should hide that area.

Absent PVS data disables the cluster restriction while retaining available area connectivity.
Missing or invalid viewpoint visibility data, solid viewpoints, and viewpoints outside the world
model bounds conservatively retain audience-eligible entities and emit an all-visible area mask.
The area-mask format represents 256 areas; unknown area identifiers fall back conservatively.

Entities are sorted by number, independent of link order or portal traversal order. If more than
256 are visible, the first 256 numbers are retained; the result reports the omitted count and the
server logs entry into overflow. This deterministic overflow policy differs from the upstream
server's traversal-order cutoff in over-capacity scenes. No entity truncation is hidden in the
local transport.

## Verification and provenance

`SnapshotVisibilityTest` uses original synthetic fixtures covering followed-player exclusion,
audience flags, split-plane cluster membership despite overlapping leaf bounds, live area doors,
spanning entities, recursive portal cycles, portal ranges, broadcast semantics, unavailable
visibility, stable capacity handling, and defensive snapshot storage. The client ABI tests cover
transport layout separately.

Behavior was checked against the unchanged upstream ioquake3 `sv_snapshot.c`, `cm_test.c`,
`q_shared.h`, and `g_public.h` at commit `588393618dbc82e7207c21c6ddecca229944a03a` in the ignored
local reference checkout. A standalone header `offsetof` probe verified the shared early fields:
player origin 20, client number 140, view height 164; entity origin 92, origin2 104, and modern
generic1 204. The Java selection algorithm and fixtures are original implementations; no native
engine functions were copied or mechanically translated.

## Local map restart

Fast restarts preserve client connections, the cgame VM, reliable command numbering and registered scene assets. The qagame VM receives shutdown/init with restart=true, its located entities and area portal references are rebuilt, and connected players receive CLIENT_CONNECT with firstTime=false before CLIENT_BEGIN. Completed restart initialization frames advance the same monotonic server clock. A reliable `map_restart` command and toggled snapshot flag4 (SNAPFLAG_SERVERCOUNT) inform original cgame that prediction crosses a restart. A game-type or client-capacity change selects a full map spawn instead. Synthetic ABI tests verify callback arguments, sequence/buffer retention, both snapshot layouts, repeated flag toggling and cgame command ownership; original retail qagame/cgame and Vulkan lifecycle audits exercise the retained-client path.
