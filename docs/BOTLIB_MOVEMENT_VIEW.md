# Movement view targets

`BotMovementView` implements `BotMovementViewTarget` (modern botlib trap 554).
Construct it with a validated `AasMap` and the map's `AasMovementRoutes` provider.
Its query is:

```java
Result target(Vec3 origin, int lastReachability, Goal goal, int travelFlags,
              float lookAhead, AasMovementRoutes.Context context)
```

The context contains the movement state's previous goal/area, current AAS time
and timed avoid-reach slot. The helper borrows these values and does not change
the movement state. Avoid spots do not participate in this particular native
query, even though they affect ordinary movement route selection.

An overload accepts `TravelPolicy` instead of the integer mask so disabled areas
and other routing permissions reach every continuation query. The host snapshots
its current disabled-area set for each call. The original integer-mask overload
retains the default policy.

`Result(success, Optional<Vec3> target)` preserves output-buffer behavior. An
empty target means the caller's existing target bytes remain untouched. A failed
query can still supply an intermediate target, so the bridge writes a present
target regardless of the success flag. A missing last reachability or a
nonpositive lookahead returns failure without writing a target.

The query follows the initial reachability from the movement origin toward its
start, then toward its end. It accumulates traveled distance using float32 and
interpolates where the requested lookahead is exhausted. If the destination area
is the goal area, the remaining distance extends toward the goal's exact origin.
The initial reachability comes from movement history and is not rechecked against
the requested travel mask. Later route choices use the supplied mask and timed
avoidance. The previous-area value advances as the route is followed; the
previous-goal value remains the one supplied by movement history.

Travel metadata changes this traversal:

- Teleporter, rocket-jump and BFG-jump types 10, 12 and 13 stop successfully at
  the link's start. A shorter lookahead still stops on the approach segment.
- Elevator, jump-pad and bobbing-platform types 11, 18 and 19 omit the
  start-to-end distance. The following route starts at the endpoint. Omitting
  that segment does not itself write the endpoint to the caller's output.
- Other travel types consume the ordinary start-to-end distance. Team tags are
  ignored when identifying the base travel type.

Missing continuation returns failure and retains the last actual target write.
Invalid reachability indices and nonfinite inputs fail explicitly. The helper
allows at most 100,000 links per request; exhausting this limit throws instead
of inventing a target. Native history can produce cycles, especially with
artificially large lookahead. A million-unit stress query on q3ctf2 exceeded this
budget while the unbounded native query eventually returned. This deliberate
work bound is separate from the parity results below.

## Validation and provenance

Ten authored tests cover untouched failure, segment interpolation, first-link
permissions, all special travel classes and team tags, failed output writes,
history/avoidance propagation without mutation, a native float regression, and
numeric/cyclic work limits, and an exact native normalization boundary.

Trap 554 is wired for both supported guest ABI profiles. It validates the complete
12-byte output vector before running the helper and writes a present target even
when the helper returns failure. Null goals, missing movement input and absent
route history return zero with output untouched. Guest tests check these empty
states, surrounding sentinel bytes, malformed goal/output ranges and nonfinite
lookahead. A populated-history bridge test executes move-to-goal, checks the
resulting view target, then confirms that an early blocked movement preserves
that history and the caller's untouched movement-result suffix.

The read-only `scripts/AuditMovementViews.java` corpus covers **30,000 requests
across all 30 original AAS maps**, with varying initial reachabilities, goal
areas, origin offsets, travel flags, previous-area history and timed avoidance.
Lookahead values range from -1 through 16,384 units. Success flags and output
presence and every coordinate now match exactly, with **zero coordinate error**.
An additional 10,000 native segment probes identified the float normalization
contract: the returned length is squared length times the rounded inverse square
root, and interpolation reuses that same inverse. Replacing it with a fresh
reciprocal of the returned length changes rounding at segment boundaries.

The public signature and travel contracts are in
[be_ai_move.h](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/botlib/be_ai_move.h).
The authored `scripts/BotMovementViewOracle.c` host links unchanged native
ioquake3 objects at commit `588393618dbc82e7207c21c6ddecca229944a03a`. It seeds
movement history through the attributed data declaration in
`scripts/BotMoveStateMetadata.h`. Controlled probes alter reachability coordinates
and types only in native process memory. Java behavior was independently derived
from returned values, without copying or translating engine routines.
The small authored `scripts/MovementViewStepOracle.c` probe calls the exported
native segment helper to isolate this numeric behavior.

An existing ignored official source build and local libarchive development
library can reproduce the oracle and audit:

```sh
python3 scripts/BuildMovementViewOracle.py
java -cp craftq3-core/build/classes/java/main:craftq3-assets/build/classes/java/main:craftq3-botlib/build/classes/java/main \
  scripts/AuditMovementViews.java run/craftq3/games/baseq3/pak0.pk3 \
  .tools/view-target-oracle/probe 1000
```

The build script compiles unchanged sample, movement and math source with
`-ffp-contract=off` to match the Java float policy. The PK3 is read directly,
without extraction or mutation. Native code and original assets are development
inputs and are absent from the mod. Visible-position prediction and physical
move-to-goal execution are separate services.
