# Airborne jump-pad contact

`JumpPadContact` provides the contact-selection part of original airborne
move-to-goal. Construct with a validated immutable `AasMap`; `find(origin, velocity)`
returns an optional `Contact(area, reachability)` and never changes movement state.
It does not select routes, apply travel permissions, or execute launch physics.

The query traces ordered raw AAS entries from the current origin to
`origin - 0.2f * velocity`, with a maximum of 16 entries. Every multiplication and
subtraction rounds separately to float32. Original `lastOrigin` does not
participate. This backward velocity ray can find a pad after the bot has left its
point area. The first actual q3dm17 airborne request traced areas 2511,2512,2512;
its current point area 2511 was not a pad, but the ray still recovered launch
area 2512 and reach 2241.

Selection visits returned areas in native trace order. An area must have
jump-pad contents 128 and a matching outgoing link. The first area with such a
link wins; within that area, the last link with base travel type 18 wins. Team
metadata does not prevent a match. Areas without matching links do not hide a
later contact. Goal, travel-policy mask, cached link, endpoint geometry and
velocity direction do not otherwise rank the matching links.

The native public reach iterator exposes a nonzero first index even for count 0.
A readable first link is therefore considered in that case. An empty range at
the array end, as found in original q3ctf4 area 4010, produces no usable link.
Java never performs an out-of-bounds lookup. A first index 0 remains the sentinel.
The default independent work budgets are 2,000,000 node visits and 2,000,000 link
visits; an overload accepts explicit limits. Exhaustion throws. The 16-entry cap
stops normally and is separate from these work budgets.

`GroundMoveToGoal` runs this lookup after the verified dry-air contact refresh.
A found contact replaces only `lastArea` and `lastReachability` before airborne
execution. It preserves current area, previous goal, reach area, jump reach,
deadline and timed avoidance. The retained current area still supplies obstruction
queries. Without contact, the cached fields remain intact. Completion updates
last origin; a blocked completed reach also shortens its deadline by one second. Ground and airborne executor maps are independent; register
`Map.of(18, jumpPad::finish)` in the airborne map to enable verified pad steering.
Missing providers and failures leave the movement state unchanged.

Six authored tests cover velocity direction, area/link order, metadata tags,
empty ranges, cap 16, work limits and an exact float boundary. A transparent native
query observer compared 30,000 ray-endpoint coordinates: separate float32 arithmetic
matched every bit; using a double 0.2 constant differed 3,292 times. The standalone
selector corpus compared 24,535 airborne requests across all 30 original maps,
including 9,031 contacts, with zero area/link differences. It excluded 5,465 samples
that the native AAS ground predicate classified as grounded.

The full move-to-goal air-pad corpus then compared 19,888 requests across the 21
original maps with jump-pad links. All result bytes, movement history, avoidance,
elementary-action floats/flags and BSP query counts matched. It excluded 1,112
AAS-grounded samples; nine maps had no eligible pad source. The corpus uses actual
AAS geometry and controlled BSP/entity callbacks, so it does not establish actual
BSP collision equivalence.

`scripts/BotJumpPadContactOracle.c` is an authored host linked to unchanged
ioquake3 reference objects at commit 588393618dbc82e7207c21c6ddecca229944a03a.
`scripts/BuildJumpPadContactOracle.py` compiles the AAS sampling source with only
public point/area-trace symbols renamed, then wraps those functions to observe
arguments and results. It does not change native routine bodies. Metadata-only
fixtures alter area/link declarations and a two-area partition in process memory.
No native implementation was copied or translated into Java, and no commercial
assets or native reference binaries are distributed.

Reproduce with compiled Java 25 module classes and the isolated authored oracle:

```sh
python3 scripts/BuildJumpPadContactOracle.py
java -cp craftq3-core/build/classes/java/main:craftq3-assets/build/classes/java/main:craftq3-botlib/build/classes/java/main \
  scripts/AuditJumpPadContacts.java /path/to/pak0.pk3 .tools/jump-pad-contact-oracle/probe 1000
```

Use the `air-pad` mode of `scripts/AuditGroundGoals.java` for the full operation.
