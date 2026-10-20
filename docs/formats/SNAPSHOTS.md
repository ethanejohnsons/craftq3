# Snapshots

A snapshot is what one client sees at one server frame: its player state, the entities it may see
and a mask of the map areas to hide. The server builds one per client following
`SV_BuildClientSnapshot`; it then travels as a delta ([NETWORK68_MESSAGES.md](NETWORK68_MESSAGES.md))
over UDP or the loopback transport, and the client keeps a history for the cgame. Local, remote
and demo play share this path.

Code:
[`SnapshotBuilder`](../../engine/game/src/main/java/dev/bluevista/craftq3/engine/game/snapshot/SnapshotBuilder.java),
[`EntityWorld`](../../engine/game/src/main/java/dev/bluevista/craftq3/engine/game/world/EntityWorld.java)
(entity linking),
[`MapVisibility`](../../engine/game/src/main/java/dev/bluevista/craftq3/engine/game/world/MapVisibility.java)
(PVS and area connectivity),
[`Protocol68Host`](../../engine/game/src/main/java/dev/bluevista/craftq3/engine/game/net/Protocol68Host.java)
(transmission),
[`GameServer`](../../engine/game/src/main/java/dev/bluevista/craftq3/engine/game/GameServer.java)
(snapshot flags, restarts); client side
[`SnapshotPipeline`](../../engine/client/src/main/java/dev/bluevista/craftq3/engine/client/pipeline/SnapshotPipeline.java),
[`ClientSnapshot`](../../engine/client/src/main/java/dev/bluevista/craftq3/engine/client/pipeline/ClientSnapshot.java).

## Entity selection

- **View point**: the snapshot player's origin raised by its view height. Its BSP leaf gives the
  view cluster and area. The player state's `clientNum` is the viewed player (it differs from the
  receiving client while spectating); that player's own entity is never sent, since the cgame
  rebuilds it from the player state.
- **Linking**: when the game links an entity, its bounds record up to 16 clusters
  (`MAX_ENT_CLUSTERS`, with the last cluster as an overflow marker) and up to two areas, from at
  most 128 leaves (`MAX_TOTAL_ENT_LEAFS`). Only linked entities are candidates.
- **Audience flags**, checked first: `SVF_NOCLIENT` is never sent; `SVF_SINGLECLIENT` only to
  `r.singleClient`; `SVF_NOTSINGLECLIENT` to everyone else; `SVF_CLIENTMASK` to clients 0 to 31
  whose bit is set in `r.singleClient`. Retail guests have no `singleClient` field; it reads as 0.
- **Broadcast**: `SVF_BROADCAST` entities are always sent.
- **Areas**: otherwise one of the entity's two areas must be connected to the view area through
  currently open area portals.
- **PVS**: and one of its clusters must be visible from the view cluster. When the entity
  overflowed its cluster list, the clusters up to its last cluster are scanned with the original's
  rule (hidden only when the scan stops exactly at the last cluster). A view cluster of -1 or a map
  without visibility data sees every cluster ([DIVERGENCES.md](../DIVERGENCES.md)).
- **Portals**: a visible `SVF_PORTAL` entity within its `s.generic1` range (distance from the view
  point to its `s.origin`; 0 means unlimited) adds everything visible from its `s.origin2`, and
  that view's areas join the mask. Portal views nest at most 16 deep (the original recursion is
  unbounded).

Entities are sorted by number and cut at 256 (`MAX_ENTITIES_IN_SNAPSHOT`); the snapshot records
how many were visible. The area bits of every view are ORed (an invalid view area sets all) and
then inverted, so a set bit tells the renderer to hide that area; the mask has `(areas + 7) / 8`
bytes, at most 32.

## Transmission

Snapshots go out at the client's `snaps` rate and within its `rate` (every frame over loopback),
delta-coded against the newest acknowledged snapshot still retained and fewer than 32 messages
old, otherwise in full. Snapshot flags: `SNAPFLAG_RATE_DELAYED` (1) when the rate held a snapshot
back, `SNAPFLAG_NOT_ACTIVE` (2) before the client entered the game, `SNAPFLAG_SERVERCOUNT` (4)
toggled at every map spawn and restart.

A `map_restart` without delay restarts the game in place: clients and configstrings are kept, the
server id changes and `SNAPFLAG_SERVERCOUNT` toggles, which tells the cgame its prediction crosses
a restart. If latched `sv_maxclients` or `g_gametype` changed, the map is spawned again instead.

## Client side

`SnapshotPipeline` applies every accepted message in wire order: a gamestate starts a new level
(and clears snapshot history), reliable server commands go into a ring of 64, snapshots into a
history of 32 by message number together with the command sequence at their arrival and their
ping. The cgame reads them through `trap_GetSnapshot` ([CGAME.md](CGAME.md)), converted to its ABI
profile.

Independent implementation; see [LICENSE-NOTES.md](../../LICENSE-NOTES.md).
