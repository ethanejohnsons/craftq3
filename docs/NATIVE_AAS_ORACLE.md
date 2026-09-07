# Native AAS development oracle

The ignored `.tools/aas-oracle/probe.c` is an authored host for unchanged ioquake3
botlib objects from source commit `588393618dbc82e7207c21c6ddecca229944a03a`.
It calls `GetBotLibAPI`, initializes the full library, supplies original BSP entity
text/model bounds and the native MD4 BSP checksum, loads the map's original AAS,
and advances initialization frames before accepting queries. No native routine
is copied into CraftQ3. Oracle code, binaries and commercial data are not bundled.

The executable is `.tools/aas-oracle/probe`. Arguments are a user PK3 (or trusted
fixture root), map name, and optional authored fixture overlay directory. The
optional overlay is local to this process and falls back to the PK3 for missing
paths; it does not alter user mounts. PK3 reads use libarchive. Native requests to
write files are directed to unlinked temporary streams. OS-path logging is
unavailable. The host retains hunk allocations until shutdown.

**BSP collision callbacks are controlled fixtures, not actual BSP collision.**
The default trace is clear and default point contents is zero. Native AAS static
presence traces, area queries, reachability and routing operate on the actual
loaded AAS. This distinction matters for item discovery, movement and suspension
probes. Linked dynamic entities are absent unless a later oracle protocol adds
explicit entity updates. Debug drawing is discarded.

The line-oriented protocol writes results to stdout and native diagnostics to
stderr. Every normal query flushes stdout. `READY 1 <map>` means initialization
completed. Example arguments: `run/craftq3/games/baseq3/pak0.pk3 q3dm17`.

| Command | Result |
|---|---|
| `point x y z` | `POINT area` |
| `best originX Y Z minX Y Z maxX Y Z` | `BEST area goalX Y Z traces=N` |
| `jump originX Y Z minX Y Z maxX Y Z` | `JUMP area 0 0 0 traces=N` |
| `area number` | `AREA number returnValue contents flags presence cluster min3 max3 center3`, then `REACH outgoingCount` |
| `bbox presence` | `BBOX min3 max3` using native initialized presence bounds |
| `links absMin3 absMax3 entity presence` | `LINKS area...` in native linked-list order; unlinks after reading |
| `trace start3 end3 presence passEntity` | `TRACE startSolid fraction end3 entity lastArea blockingArea planeNumber` |
| `areas start3 end3` | `AREAS area...`, capped at 4096 results |
| `raw cap start3 end3` | `RAW count area@entryX,entryY,entryZ...`, retaining native duplicate/boundary entries; cap1–1024 |
| `cost area start3 end3` | `COST unsigned16Time` from native intra-area travel cost |
| `route startArea origin3 goalArea travelFlags` | `ROUTE time`, zero if unavailable |
| `first startArea origin3 goalArea travelFlags` | `FIRST success time reachability`, raw native AAS outputs |
| `movefirst startArea origin3 goalArea travelFlags` | `MOVEFIRST reachability flags`, native movement selector with empty history/avoidance |
| `movehistory startArea origin3 goalArea travelFlags previousGoal previousArea avoidedReach expiry tries` | `MOVEHISTORY reachability flags avoidedReach expiry tries`, with one native avoidance slot |
| `moveavoid startArea origin3 goalArea travelFlags previousGoal previousArea avoidedReach expiry tries count (spotOrigin3 radius type)*` | `MOVEAVOID reachability flags`, with up to32 spherical spots |
| `spotset travelType origin3 reachStart3 reachEnd3 count (spotOrigin3 radius type)*` | `SPOTSET rawType`, isolated native spot geometry with up to32 spots |
| `reachable origin3 client` | `REACHABLE area traces=N`, native movement reachability-area selection |
| `frame time` | Advances native frame time and reports `TIME time` |
| `traceverbose enabled` | Emits controlled BSP trace arguments to stderr |
| `entity id origin3 mins3 maxs3 solid modelindex flags` | Updates native entity state/linking with other fields zeroed; `ENTITY result` |
| `unentity id` | Removes native entity state; `ENTITY result` |
| `caches [area]` | Public cache type/cluster/goal/baseline metadata, optionally that area's cached time and local reachability index |
| `enable area enabled` | `ENABLED previousState` |
| `clear` | Subsequent BSP traces report no hit |
| `floor z` | Subsequent BSP traces collide with an authored horizontal solid floor |
| `solid` | Subsequent BSP traces report all-solid/start-solid |
| `contents bits` | Sets the constant BSP PointContents callback result |

The presence bounds observed after native setup are NORMAL(2):
`(-15,-15,-24)..(15,15,32)` and CROUCH(4):
`(-15,-15,-24)..(15,15,8)`. These are not assumed from the AAS bounding-box lump.

Build by linking the authored host directly with the existing unchanged CMake
botlib `.o` files, `q_math.c.o`, `q_shared.c.o`, and `md4.c.o`, plus libarchive.
The CMake objects contain LTO; precombining them with `clang -r` can internalize
unreferenced public APIs, so link the host and all objects in the same command.
The locally available libarchive headers/library are under
`/opt/homebrew/opt/libarchive/{include,lib}`. Oracle callbacks make no network or
Minecraft calls.
