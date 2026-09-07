# AAS travel times

`AasRouteTimes` supplies the origin-dependent travel time used by the native
`AAS_AreaTravelTimeToGoalArea` contract. It consumes an immutable, validated
`AasMap` and retains Quake units. `AasNavigation`'s earlier stored-cost path
search remains a separate diagnostic service: its summed reachability costs do
not include native intra-area and portal-cache costs.

## API and permissions

Construct `new AasRouteTimes(map)` or pass explicit `Limits`. Call
`travelTime(startArea, origin, goalArea, travelFlags)` or the overload accepting
an immutable `TravelPolicy`. Zero means no route. Invalid/solid/cluster-zero
endpoints, or distinct endpoints lacking outgoing reachabilities, yield zero.
The same valid area yields one before checking flags. The caller supplies the
start area; the service does not replace it with a point classification.

`route(...)` has the same flags/policy overloads and returns immutable
`Route(travelTime, reachability)`. The reachability is a global AAS index. A zero
time is normalized to `(0,0)` even when native scratch output contains a link;
the same-area result is `(1,0)`.

An area can be exited while disabled or forbidden, but cannot be entered.
Endpoint DO_NOT_ENTER contents add that permission for the query. Disabled-area
sets, presence selection, team and flags are part of cache identity. Pass a new
policy after area-enable changes; old cached permissions cannot affect it.
`clearCaches()` releases retained arrays; `cacheStats()` reports current entries,
primitive-array bytes and cumulative cluster/portal cache builds.

## Observed semantics

Local travel uses float32 distance and scales normal movement by `.33`, liquid
movement by `1`, and crouch-only movement by `1.3`. Crouch takes precedence over
liquid. Its integer result has unsigned16 conversion and a minimum of one.

Cluster caches retain a selected outgoing reachability for each area. Stable
incoming-link order and a FIFO relaxation queue are observable when alternative
entries have different costs to the selected exit. An unrestricted graph
shortest path does not reproduce those results. Portal caches add the largest
entry-to-exit local cost for each portal and native-observed cluster baselines.

An available same-cluster route takes priority even when leaving and reentering
would be cheaper. An unavailable direct route falls back through other clusters.
For a portal destination, remote routing uses its front cluster, independently
of which side contains the source. The destination portal itself cannot become
a shortcut through its other side. A portal source uses its actual origin for
an ordinary adjacent-cluster destination; remote routing and portal-to-portal
queries use the cached portal time without origin adjustment.

Equal-time exits follow the file's stored cluster-portal order, which can differ
from numeric portal order. For remote portal-source queries, native AAS returns
the area's first stored outgoing link independently of the minimum-time path.
This quirk is preserved. **This AAS first-edge API is not the movement AI
selector:** `BotGetReachabilityToGoal` separately considers candidate links,
history and avoidance state and must be verified independently before driving
physical movement.

These rules were established with authored fixtures and black-box calls to the
unchanged native library, including inspection of its public cache metadata.
No native routing routine was copied or mechanically translated.

## Validation

Eleven authored tests cover local costs, hierarchy baselines, portal origins,
same-cluster fallback, direct-route priority, nonrouting endpoints, disabled
areas, DO_NOT_ENTER, immutable policy/cache behavior, explicit limits, remote
portal first-link output and equal-time stored portal ordering.

The read-only `scripts/AuditRouting.java` accepts a user PK3, an isolated native
oracle executable, an optional query count per map and an optional map filter.
It uses fixed seeds, emphasizes portal endpoints, varies DEFAULT, DEFAULT
without JUMP and ALL flags, samples nonrouting endpoints and perturbs some
origins. It compares both travel time and normalized first-edge output. Native
output/result count is bounded and any mismatch fails the audit.

The earlier 30,000-query sweep over all 30 original maps, using reachable-area
centers, matched all native values exactly (20,802 reachable results). The
expanded 30,000-query sweep with nonrouting endpoints and perturbed origins also
matched exactly (17,841 reachable results; 12.2 seconds locally). This is a
deterministic corpus sample, not exhaustive proof of every route or policy.
The subsequent first-edge extension matched all 30,000 time/edge pairs from that
expanded sample exactly (15.2 seconds locally).
The native build uses the unchanged objects described in
[NATIVE_AAS_ORACLE.md](NATIVE_AAS_ORACLE.md); commercial maps and native code are
not included in distributable artifacts.

## Bounds and remaining work

Default limits are 256 retained cache entries, 64 MiB of primitive cache arrays
and 20 million work units per construction/query. Eviction is shared LRU. A
cache larger than the byte limit can be used transiently without retention;
map-reader limits still bound its size. Metadata and transient arrays are
additional to the retained-byte accounting. Methods that touch caches are
synchronized. Exhausted work raises a diagnostic exception rather than
returning a misleading unreachable result.

Finite float-range coordinates are required. Local conversion beyond signed32
range is rejected. Cache arithmetic above 65,535 is explicitly rejected instead
of emulating historical unsigned16 wrap; sampled original routes did not reach
that bound. Dynamic BSP collision, linked-entity obstacles and physical movement
prediction are outside this immutable AAS time service. The separately verified
movement selector and its remaining avoidance limits are described in
[BOTLIB_MOVEMENT_ROUTES.md](BOTLIB_MOVEMENT_ROUTES.md).
