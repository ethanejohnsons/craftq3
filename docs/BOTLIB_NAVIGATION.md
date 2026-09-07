# AAS spatial queries and stored-cost routing

`craftq3-botlib` now contains the independent `dev.bluevista.craftq3.botlib.aas` service. It depends
on `core`, `assets` and `collision`. It does not run native code, implement game AI, create bot entities,
predict movement, or manufacture reachabilities. The original qagame VM remains responsible for
gameplay and bot decisions; a botlib syscall bridge is a separate integration step.

Construct `AasNavigation` from an immutable map validated by `AasReader`. All positions remain in
Q3's coordinate system and units. Public results copy their collections and the service keeps no
mutable per-query state, so independent queries can run concurrently.

| API | Result |
| --- | --- |
| `pointArea(point)` | Area number, or zero for solid/no area |
| `boxAreas(min, max, maxAreas)` | Unique, ascending area numbers and completion status |
| `traceAreas(start, end, maxAreas)` | Ordered non-solid area spans, entry/exit fractions and points |
| `reachabilities(area, policy)` | Permitted original outgoing reachability records and file indices |
| `route(startArea, goalArea, policy, budget)` | Found route, unreachable result, or explicit budget exhaustion |

Point queries walk the BSP from node 1 and choose the back child for a point exactly on a split.
They use float32 coordinates and arithmetic, verified against native AAS_PointAreaNum. The original
q3ctf1 point (861,-1287,261), exactly on node4764/plane1944, resolves to area1356 rather than solid.
Stationary and coplanar segment queries also select the back child. Box queries conservatively traverse both
sides of intersecting planes and reject leaves whose stored area bounds miss the box. A closed box
touching a split may include both neighboring areas. This is a conservative candidate query, not a
convex-polyhedron intersection solver.

Segment queries split a point segment at BSP planes using an iterative work stack. They preserve
travel order, merge contiguous spans of the same area, and continue across solid gaps. Zero-length
segments use point classification; zero-measure neighbors at an endpoint are omitted. The result
also reports whether the start is solid and the classification of the final endpoint, independently
of a truncated span list. This operation is not a swept actor collision trace.

`QueryBudget` caps output size and traversal work: at most 1,048,576 output records and two million
node/leaf visits, with one million visits by default. Point classification is separately bounded by
the map's node count; a segment query also performs two such bounded endpoint classifications.
Traversal or result exhaustion returns `complete=false`, preserving the available prefix. It never
silently reports an incomplete search as complete.

`TravelFlags` uses the published TFL assignments in id Software's
[be_aas.h](https://github.com/id-Software/Quake-III-Arena/blob/master/code/game/be_aas.h).
These bits are not a simple shift of the stored TRAVEL numbers: walking off a ledge uses `0x80`,
and bobbing movers use `0x01000000`. `DEFAULT` matches the published default capabilities.
`TravelPolicy.ofFlags(mask)` consumes these original TFL bits; set bits permit the corresponding
travel, area content, or team-restriction category. Stored reachability team metadata is translated
to its corresponding TFL bit. The optional explicit `Team.ONE` or `Team.TWO` additionally rejects
that team's forbidden areas and reaches even if the mask includes every bit.

Policies also carry normal/crouching presence bits and an immutable disabled-area set. Filtering
checks the source and destination areas, stored disabled flags, water/slime/lava or air categories,
do-not-enter areas, bridges, and team restrictions. Unknown travel kinds, unknown extra travel
metadata, and the invalid travel kind are rejected. Flight flags do not create new links.

Routing uses an independently implemented Dijkstra search over the stored directed reachability
graph, with deterministic area-number tie-breaking. The resulting cost is the sum of unsigned
stored inter-area times; accumulated costs use `long` and do not wrap at 65,535. Zero-cost edges and
cycles are supported. A route from an allowed area to itself is found with zero cost and no links.
`SearchBudget` limits settled areas, examined outgoing reaches, and total travel time. Exhausting
any limit yields `BUDGET_EXCEEDED`, distinct from a fully searched `UNREACHABLE` result.

This cost is deliberately incomplete for gameplay: it excludes travel within an area, approach to
the first reachability, moving-platform timing, dynamic entity obstacles, movement prediction,
and original botlib route-cache heuristics. It cannot yet replace the original botlib travel-time
syscall without further independent work and oracle validation. No original engine function was
copied or mechanically translated to implement these generic geometric and graph algorithms.

Validation uses nine authored synthetic tests, including 1,000 deterministic random segment
checks. They cover boundary/solid classification, conservative box results, ordered traces,
truncation, weighted shortest routes, masks and team permissions, zero-cost cycles, immutable
ownership, and all query/search budget types.

The optional read-only audit task is:

```sh
./gradlew :craftq3-botlib:auditNavigation -Pq3Pak=/absolute/path/to/pak0.pk3
```

All **30** supplied AAS maps passed the consistency audit: **96,728** non-dummy center queries,
**3,840** segment traces across the maps, and **2,225** direct-link shortest-route checks. Every
sampled nontrivial segment span's midpoint agreed with point classification; every audited route
had continuous original links, the correct endpoint and cost, and cost no greater than its known
direct alternative. This is internal/corpus consistency evidence, not an original-engine oracle.

Seven stored centers lie outside their own oriented face half-spaces: `q3ctf2:2535`,
`q3ctf4:603/663`, `q3dm1:1167`, `q3dm4:4477`, `q3dm6:837`, and `q3tourney1:644`. The measured
violations range from 0.000021 to 0.976 Q3 units. The audit reports these metadata anomalies and
checks the face evidence instead of assuming every stored center is a guaranteed interior point.
Float32 arithmetic changes classification for the tiny `q3dm6:837` case, so exact boundary parity
with the original engine remains an explicit numerical limitation. No asset is altered.
