# Ground move-to-goal orchestration

`GroundMoveToGoal` composes the independently verified AAS route selector, ground
contact predicate, WALK/CROUCH executors and optional barrier, ledge, teleport and
jump-pad providers for the original botlib move-to-goal operation (modern import
549). It selects and retains reachabilities, publishes movement history and timed
avoidance, and returns the native result, optional movement and independent
elementary actions. Original qagame still owns the bot AI and
player movement rules.

Construct it with `BotMovement`, `AasNavigation`, a fuzzy-area function,
`AasMovementRoutes`, the BSP/entity `TraceWorld`, `GroundContact`, an entity
moving-platform predicate, and `GroundReachMovement`. Use
`AasReachabilityArea::fuzzyArea` and `AasMovementPredictor::onGround` for the two
localization/contact callbacks. The latter preserves the host's AAS entity-link
composition. `execute(handle, Goal, TravelPolicy, float time)` borrows these
services and returns `Output(result, writtenBytes, Optional<Command>, actionFlags)`.
The original three-argument constructor remains available. `Command` retains its
legacy direction, speed and action fields; the host applies independent output
actions once and issues EA_Move only for a present command. Jump-only and no-action
results are described in [BOTLIB_REACH_OUTPUT.md](BOTLIB_REACH_OUTPUT.md).

`Output.writeTo(ByteBuffer, offset)` validates the complete 52-byte ABI range.
Successful ground travel writes all 52 bytes. Several native early returns write
only the first six integers (24 bytes), preserving the previous weapon, movement
vector and view-angle bytes, including arbitrary or nonfinite encodings. The
writer never interprets those previous bytes and preserves the caller's buffer
position and byte order. The host must validate guest arguments before executing
the operation and apply the returned command only after a successful call.

The operation synchronizes on the borrowed movement-state owner and computes
history, movement flags and the one-slot timed reach avoidance in local storage.
It publishes them together only after supported execution succeeds. Unsupported
travel and provider errors do not leave partially selected routes, timers or
commands. Movement-state initialization preserves history; full reset clears it.

## Observed ground policy

Every request first calls the AAS ground predicate. A positive result sets
MFL_ONGROUND (2); a negative result preserves a previously supplied ground flag.
Grounded requests then trace the native presence hull three units down through
BSP/entity collision (mask 65537, ignoring the moving entity). An ordinary entity
underneath returns a blocked 24-byte result with flag 32 before contents or
history updates. This early return retains a ground flag recovered by AAS.
World and no-entity identifiers do not trigger that result.

Continuing requests clear derived swimming, ladder and active-grapple flags and
sample contents two units below the origin. Grounded requests localize with the
verified fuzzy-area query. Area zero returns failure/type 8 and retains prior
route history. Reaching the goal area invokes dry goal-area movement, clears
both last area and last reachability, and retains the previous reach area,
deadline, jump reach and timed avoidance. It records the current goal and origin.

A cached link is retained when its previous area and goal still match and its
deadline is greater than or equal to the current time. The cached path rechecks
only its base travel-type permission. Native observations show that team tags,
dynamically disabled areas, timed avoidance and avoid spots do not invalidate an
otherwise retained link. Fresh selection uses the complete `TravelPolicy`, prior
area/goal context, timed avoidance and avoid spots. The full stored travel type,
including team metadata, is preserved in the result.

A newly selected WALK/CROUCH link receives a five-second reach deadline and a
six-second avoidance attempt. Fresh selection clears the previous jump reach,
even when selection fails. The previous avoidance slot follows its independently
verified strict-expiry/retry rules. Failed selection writes a 24-byte failure
result, preserves the old deadline and carries avoid-spot result flags. Both
successful and failed selection update the last area, goal and origin. Ordinary
airborne requests without a retained reach update only the last origin and write
a clear result prefix when no unsupported contact domain applies.

## Supported scope and validation

This provider supports dry WALK/CROUCH routing and dry movement within the goal
area, including cache expiry, ordinary entity-top blockage, route failure and
header-only results. Moving-platform tops, liquid movement and ladder contact
throw explicitly. Ledge entry/completion and jump-pad approach/contact/steering
are available through independent optional executor registrations below. Other
selected travel kinds remain explicit unsupported domains.

Twenty authored helper tests cover cache boundaries, stored travel metadata,
disabled-policy cache behavior, same-area state changes, recovered ground flags,
entity-query ordering, presence hulls, derived flags, output suffix preservation,
avoid spots and transactional failure. Nine movement-state tests also pass after
adding the immutable `History` snapshot and atomic publication helper. The first
retail q3dm1 request (source area 126, reach 246) matched the unchanged native
result, EA command, every history/avoidance field and both imported BSP traces.
The independent full-map comparison and exclusions are recorded in
[BOTLIB_GROUND_GOALS_AUDIT.md](BOTLIB_GROUND_GOALS_AUDIT.md).

The authored `scripts/BotGroundGoalOracle.c` host links unchanged official
ioquake3 development objects from commit
`588393618dbc82e7207c21c6ddecca229944a03a`; it uses only the attributed movement-state
layout in `scripts/BotMoveStateMetadata.h` to seed and inspect retained state.
`scripts/BuildGroundGoalOracle.py` rebuilds the reference objects with separate
float32 arithmetic (`-ffp-contract=off`) and links the isolated host. Public ABI
metadata is referenced from the official
[bot movement header](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/botlib/be_ai_move.h)
and [AAS movement declarations](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/botlib/be_aas_move.h).
No implementation routine was copied or translated into Java.

An additional ignored observer compiled the unchanged AAS movement source with
only `AAS_OnGround` symbol-renamed, then wrapped that public function to record
its arguments and result. This established query order and ground-flag recovery
without reading or changing the original routine. Native corpus hosts load the
user's PK3 read-only, use actual AAS geometry and controlled BSP/entity callbacks,
and keep binaries and commercial bytes outside distributable artifacts. They do
not claim actual BSP collision or full bot gameplay equivalence.

## Cached airborne WALK and CROUCH

The same service handles bounded cached airborne completion without changing
its constructor. When ground contact is absent, a retained WALK link invokes the
verified WALK executor using the retained current area for obstruction queries.
The operation does not relocalize, reselect or recheck goal identity, deadline,
travel permissions or avoidance. It updates last origin after successful execution. Other route history and
jump-reach metadata remain unchanged; a blocked completed reach also shortens
the existing deadline by one second, as described below.
The usual derived-contact flag refresh still precedes this branch.

Airborne CROUCH has a different observed contract: it writes only the 24-byte
result prefix with the full stored travel type and issues no elementary action.
It also updates only last origin. Both paths preserve full travel metadata tags.
Jump-pad contact takes precedence over either cached link. The verified backward
velocity-area query can select a pad link and hand it to the independently
registered airborne executor; an absent executor fails explicitly.

Separate 30-map corpora each compared 26,038 supported requests with no result,
state, action or trace-count differences. One used original WALK metadata; the
other replaced only the selected link's travel-type metadata with CROUCH in both
an immutable Java map and the isolated native runtime. Each excluded 1,451
samples that AAS classified as grounded, 2,497 source areas without a WALK/CROUCH
link and 14 jump-pad contacts. These exclusions are not parity results. Details
and the optional `air`/`air-crouch` audit modes are recorded in the audit notes.

Unsupported execution throws `UnsupportedMovement`, a subclass of
`UnsupportedOperationException`. Its diagnostic includes the selected reach,
localized area, complete movement input, goal, policy, time, prior history,
effective flags and avoidance. Its `reason()` returns the concise domain reason
for bounded audit grouping. The detailed message supports reproducing live
runtime failures without adding state mutations or gameplay fallbacks.

## Optional grounded jump-pad dispatch

An overload adds `Map<Integer, ReachTravel>` after the existing `GroundReachMovement`
argument. The original constructor retains its behavior. `ReachTravel` is a public
functional interface with `execute(input, effectiveFlags, sourceArea, reach)` and
returns `GroundReachMovement.Output`. Keys 7 and 18 are accepted; register
`Map.of(18, jumpPad::execute)` for jump pads and `7, ledge::execute` for ledges.
The independently verified providers retain their separate physics contracts.
The map is copied at construction and cannot override built-in WALK/CROUCH.

A fresh grounded jump-pad link receives a ten-second deadline and the same
six-second avoidance attempt. The ordinary verified cache rules apply, including
the inclusive deadline boundary. The direct provider preserves the raw horizontal
start displacement in its result and EA_Move command, with speed 400. Its behavior
and obstruction queries were independently compared against 30,000 native travel
requests before dispatch was enabled.

Registration covers only grounded approach. It cannot enable airborne jump-pad
contact or cached jump-pad completion; those remain explicit diagnostics. The
updated full move-to-goal corpus compares 28,162 supported requests across all
30 original maps exactly, including all 339 grounded jump-pad requests previously
excluded. The remaining 1,838 unsupported requests leave movement state unchanged.
Additional tests cover deadline 10, raw command retention, immutable dispatch scope
and rejection of both unregistered and airborne extra travel.

## Registered airborne jump-pad dispatch

A further overload accepts `Map<Integer, ReachTravel> additionalAirTravel` after
the ground-executor map. Register `Map.of(18, jumpPad::finish)` independently from
`Map.of(18, jumpPad::execute)`. The complete verified contact and history policy is
in [BOTLIB_JUMP_PAD_CONTACT.md](BOTLIB_JUMP_PAD_CONTACT.md). It replaces the former
point-only contact diagnostic with the observed 16-entry backward velocity query.

The integrated air-pad corpus compared 19,888 requests over 21 eligible maps exactly.
The updated ground regression compared 28,174 requests, while cached WALK and
metadata-controlled CROUCH each compared 26,052 requests across 30 maps exactly.
Those latter runs now include their formerly excluded jump-pad contacts. Current
ground exclusions total 1,826 other travel types. No unregistered travel execution
or provider failure publishes partial movement state.

## Ledge registration and blocked reach deadlines

Both optional executor maps now accept WALKOFFLEDGE 7 as well as JUMPPAD 18. Register
`LedgeReachMovement::execute` for grounded entry and `::finish` for airborne
completion. Fresh ledge selection uses the verified five-second deadline and
six-second avoidance attempt. The ordinary inclusive cache rules remain unchanged.
Without a newly contacted jump pad, airborne ledge completion retains all route
history except last origin and the blocked-result adjustment below.

A completed reach whose result has `blocked != 0` subtracts exactly one second
from the retained reach deadline, once per operation. This applies to grounded
and airborne travel, including WALK, ledge and jump-pad execution. It does not
alter timed avoidance. Same-area movement, the early ordinary-entity-top result
and the area-zero failure return before this adjustment. Native controlled probes
confirmed fresh WALK deadline 15 becoming 14, cached 99 becoming 98 and negative
cached deadlines decreasing similarly.

The full operation now matches 29,395 supported ground requests across all 30 maps,
with 605 other travel requests explicitly excluded. The airborne-ledge corpus
matches 29,250 requests, excluding 750 AAS-grounded samples. These comparisons cover
all output bytes, retained history/flags/avoidance, action floats and BSP counts.
They include the actual q3dm17 reach 1349 request at time 29.25: selected source 1546,
deadline 34.25 and three BSP queries (one outer ground query and two entry queries).

The reference build also compiles unchanged `be_aas_move.c` with
`-ffp-contract=off`. One full-operation ledge speed differed by a float step when
that dependency still came from the original ARM LTO build. Its strict standalone
native build matched Java exactly; the Java movement expression was unchanged.


## Teleport registration and completion

The optional ground map also accepts TELEPORT 10 through
`Map.of(10, teleport::execute)`. Cached airborne teleport completion is handled
internally without an executor or command. Grounded effective `TELEPORTED` 32
preserves the ordinary cache and selection rules but suppresses approach and
writes a full cleared result; the airborne branch preserves the caller's result
suffix. The exact ordering, absence of EA commands and native references are in
[BOTLIB_TELEPORT_GOALS.md](BOTLIB_TELEPORT_GOALS.md).

The updated 30-map ground regression matches 29,529 operations exactly, including
all 134 formerly excluded teleport requests. There are 471 remaining exclusions
for other travel types. Targeted type-10 ground and airborne corpora additionally
match 10,999 and 10,704 operations respectively, with flag 32 alternated across the
full policy-scenario matrix. Four outer regression tests and seven direct entry
tests cover this milestone.


## Barrier registration and independent actions

Both optional executor maps accept BARRIERJUMP 4: register `barrier::execute`
for ground approach and `barrier::finish` for cached air completion. The ordinary
five-second deadline, six-second avoidance, cache checks and blocked-deadline
adjustment remain intact. Jump-only approach does not overwrite an earlier
movement command or invent a runtime barrier flag. Details are in
[BOTLIB_BARRIER_GOALS.md](BOTLIB_BARRIER_GOALS.md).

The final targeted ground corpus compares 26,849 requests exactly, including
1,703 jump-only results, with 151 explicit other-travel exclusions. Airborne
barrier completion compares 25,341 requests exactly, including 17,440 full-result
waits without a movement command; 1,659 grounded samples are excluded. The broad
30-map ground regression compares 29,542 requests exactly and explicitly excludes
458 other travel requests. All targeted cases seed prior EA movement and actions.
