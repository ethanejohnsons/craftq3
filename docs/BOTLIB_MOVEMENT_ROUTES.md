# Movement reachability selection

`AasMovementRoutes` implements the verified portion of native
`BotGetReachabilityToGoal`: selecting an outgoing reachability using travel
permissions, previous-area history, the timed reach-avoidance slot and spherical
avoid spots. It does
not move an entity. It is separate from `AasRouteTimes.route`, whose AAS cache
selection has different portal behavior.

## Contract

Construct `new AasMovementRoutes(sharedAasRouteTimes)` to reuse bounded routing
caches, or construct from an immutable validated `AasMap`. `select` accepts a
start area, origin, goal area, travel flags or `TravelPolicy`, and immutable
`Context(previousGoalArea, previousArea, time, avoided)`. The avoided list contains
at most one `AvoidReach(reachability, expiresAt, tries)`, matching the probed native
slot count. `Context.EMPTY` supplies no history or avoidance.

An additional overload accepts up to 32 immutable `BotMovement.AvoidSpot` values;
the existing overloads use an empty spot list. The result is
`Selection(globalReachabilityIndex, travelTime, flags)`; the two-argument result
constructor remains available with zero flags. Zero/zero means
there is no permitted candidate. The score is the stored reachability time plus
`AasRouteTimes.travelTime` from that reachability's destination and endpoint to
the goal. The score excludes origin-to-first-link distance. Equal scores retain
the first stored outgoing link. Start or goal DO_NOT_ENTER contents enable that
permission for the query; other flags and disabled areas remain enforced.

When the previous goal area equals the current goal, links returning to the
previous area are excluded. The avoided link is excluded only when tries are at
least five and expiry is greater than or equal to the current time. Equality is
observable: an avoidance expiring at time 10 still applies at time 10. Selection
does not mutate the context or increment attempts; movement-state lifetime and
attempt updates belong to the caller.

## Avoid spots

`AasAvoidSpots` copies the bounded spot list and evaluates the native-observed
geometry with separate float32 arithmetic. Jump, walk-off-ledge, teleport,
elevator, rocket jump, BFG jump, grapple, jump pad and func-bob links test only the
segment from origin to reachability start. Other link kinds also test the segment
from reachability start to end, and intersect only when that segment approaches
the spot more closely than its own starting point. This lets ordinary movement
leave a spot. High travel flag bits do not change the geometric category.

Sphere intersection is strict: equality does not block. Radius is squared, so a
negative radius behaves like its positive counterpart, and zero radius never
blocks. Zero-length segments remain nonintersecting. Ordered intersecting spots
replace the result type, except type 1 (`AVOID_ALWAYS`) returns immediately. A
later type 0 can therefore clear a previous type 2 in a directly supplied list;
normal movement-state insertion handles type 0 by clearing the state instead.

Any nonzero type excludes a candidate, including type 2 (`AVOID_DONT_BLOCK`):
this selector does not guarantee a fallback. The result sets
`BLOCKED_BY_AVOID_SPOT` (256) if at least one otherwise eligible candidate with a
valid remainder route is blocked. This flag can accompany a selected alternative
or zero/zero. Candidates with no route to the goal do not set it. A blocked
candidate need not have a better score than the eventual selection to set it.

## Validation and limits

Sixteen authored tests cover scoring without origin distance, history, the fifth
attempt and inclusive expiry boundary, permission changes, stable ties,
spot categories and segment geometry, radius/type boundaries, blocked-candidate
flags, zero-count stored-first links, safe sentinels, immutability and budgets. They pass alongside the 11 AAS time/first-edge tests
under Java 25 with `-Xlint:all -Werror`.

Black-box candidate experiments against unchanged native botlib matched 30,000
baseline requests and another 30,000 history/avoidance requests across all 30
original maps. The production helper then matched all 30,000 mixed-context
requests from `scripts/AuditMovementRoutes.java`, including 19,459 nonzero choices
(12.0 seconds locally). The audit varies flags, portal endpoints, origins, goals,
history, expiry and retry counts, and fails on any differing index or unexpected
native result flag. See [NATIVE_AAS_ORACLE.md](NATIVE_AAS_ORACLE.md) for the isolated
oracle and its controlled collision callbacks. No commercial data or native
routine is bundled or copied into Java.

Independent black-box spot geometry probes matched 100,000 synthetic requests,
including degenerate segments, all native travel kinds, high flag bits, radius
signs and ordered spot types. Another 30,000 candidate probes established the
placement of the blocked flag after confirming the remainder route; setting it
before that check disagreed 5,145 times. `scripts/AuditAvoidSpots.java` compares
the production selector's chosen reachability and flags across mixed histories,
travel permissions and 0–32 spots on all 30 original maps. All 30,000 production
requests matched, including 11,516 nonzero choices (11.868 seconds locally).

Invalid endpoints return zero. For a source whose outgoing count is zero, native
`AAS_NextAreaReachability` nevertheless exposes one valid stored first index, then
ends iteration. This occurs in original q3dm4 areas 4387 and 4403, both sharing
first index 3037 with a neighboring routing area. Native move-to-goal can reach
these sources through fuzzy localization. The selector therefore evaluates that
single candidate with its ordinary permission, history, route and avoid-spot
checks. A zero first index or a first index at the end of the reachability array
remains a safe empty sentinel, as observed in original q3ctf4 metadata.

The explicit `zero-count` mode of `AuditMovementRoutes` sampled 30,000 such source
queries across all 30 maps, including 19,890 nonzero choices, with zero native
differences (11.859 seconds locally). Authored regression tests also establish that
an avoided stored-first link does not expose a second candidate, and that a blocked
one still contributes result flag 256.

The default outgoing-candidate limit is 4096 and is configurable up to the AAS
reader's reachability bound. Exceeding it throws; each underlying route query
also retains its existing work/cache limits. Context time, expiry and origin
must fit finite float32 values. AAS data and context/spot lists are immutable.

Movement-state attempt updates, physical travel execution, view lookahead and
mover interaction remain separate services. Selecting a reachability does not
establish that all of its physical travel kinds are implemented by the movement
host; unsupported execution must remain explicit there.
