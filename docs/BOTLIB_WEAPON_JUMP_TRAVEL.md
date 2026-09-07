# Rocket and BFG jump travel commands

`WeaponJumpMovement` independently implements directly observed ROCKETJUMP (12)
and BFGJUMP (13) approach commands and their shared airborne completion service.
The original game VM retains damage, ammunition, projectile, weapon and physics
rules. The separately verified full move-to-goal integration is documented in
[BOTLIB_WEAPON_GOALS.md](BOTLIB_WEAPON_GOALS.md). Live gameplay remains a
separate host/runtime check.

The dependency-free constructor exposes
`execute(input, effectiveFlags, sourceArea, reach, lastReach, jumpReach)` and
`finish` with the same arguments. Its immutable `Output` implements
`StatefulReachMovementOutput`: movement, action flags, updated `jumpReach`, and
explicit optional EA view and weapon effects are independent. Consumers must
preserve earlier commands when an optional effect is empty. JUMP 16 is a request
for `ElementaryActions.jump`, including its previous-frame suppression semantics;
it must not be applied as an unconditional raw action bit. Direct result travel
type remains zero for the surrounding operation to supply.

## Approach and launch

Both weapons approach `reach.start` horizontally. The speed rises to 400 over
80 units, preserving the measured float cancellation ramp and normalization
return rounding. Even zero distance explicitly issues movement. The helper
always selects weapon 5 for a rocket jump or weapon 9 for a BFG jump, sets result
flags VIEWSET 8 and WEAPON 16, and explicitly publishes view pitch 90 with the yaw
of the emitted direction.

Launch requires horizontal start distance strictly below 5 and both measured
input-angle errors strictly below 5 degrees. Rocket entry compares input pitch
to 90 and yaw to the approach heading. BFG entry compares both input pitch and yaw
to 0, independently of the approach direction. The BFG zero-yaw condition is a
native-observed difference, including when the approach points along another
axis; the subsequently commanded pitch remains90. Angle errors wrap once by 360,
so one-turn equivalents are accepted while two-turn equivalents can fail.

A launch changes the movement target to `reach.end`, normalizes horizontally,
issues speed 400 plus ATTACK 1 and semantic JUMP 16, and stores `lastReach` as the
new `jumpReach`. A waiting approach retains the prior `jumpReach`. The emitted
view follows the endpoint heading after launch. Roll, velocity, view offset and
the tested effective flags do not change this direct entry policy. No collision
query or random sample is consumed.

## Airborne completion

A zero `jumpReach` produces a cleared result with no movement, action, view or
weapon effect. With nonzero jump history, completion calls the independently
verified `BotAirControl` on the supplied reach endpoint and current origin and
velocity. It issues the resulting movement, clamping EA speed to 400, and retains
jump history. It does not look up another reach from the stored identifier,
perform an obstruction check, set result view flags, alter the selected weapon,
or publish a new view. Earlier held weapon/view and accumulated action flags
therefore survive. Invalid kinds or indices, excessive float arithmetic and air
control work-budget exhaustion fail explicitly.

## Validation and reproduction

Seven focused tests cover approach rounding and explicit weapon/view, distinct
rocket/BFG input alignment, strict distance/angle boundaries, one-turn wrapping,
semantic jump application over seeded EA history, absent completion, active
velocity-based steering, and rejected inputs.

The direct corpus makes 108,000 comparisons: 27,000 approach and 27,000 completion
requests for each weapon. It reads rocket reach geometry from all 27 original
maps that contain type 12 links. No original type 13 links were present; BFG
requests explicitly substitute type 13 on those same coordinates and retain a
separate substituted-map count. They are authored BFG inputs, not a claim that
the pack contains BFG routes.

Every result integer and float bit, EA direction/speed/action/weapon/view, jump
history value, unchanged effective flags, unchanged supplied reach coordinates,
BSP trace count and random draw count matches the unchanged native reference.
The corpus varies standing/crouching presence, origin and velocity, 15 effective
flag combinations, exact/near/unaligned angles, zero/prior jump history and
seven seeded EA flag combinations, including previous-frame jump history. Every
request starts with movement `(.25,-.5,.125), speed 123`, weapon 7 and view `(1,2,3)`.
Clear and solid collision callbacks alternate to detect unexpected queries.
An additional 10,800 comparisons with native `phys_gravity=400` and
`phys_maxvelocity=1600` also match exactly, bringing the direct total to 118,800.

`scripts/BotWeaponJumpOracle.c` is an authored host using public declarations,
exported signatures and attributed `BotMoveStateMetadata.h` layout metadata.
`scripts/BuildWeaponJumpOracle.py` links it with the ignored official ioquake3
checkout pinned at `588393618dbc82e7207c21c6ddecca229944a03a`. Movement, AAS
movement/sample and vector objects compile unchanged with `-ffp-contract=off`.
No engine routine bodies were copied, mechanically translated or inspected to
derive the provider. An initial transparent call observer identified the shared
air-control call; the final corpus uses the independent host without wrappers.

After compiling the Java modules and building the oracle, run
`scripts/AuditWeaponJumpTravel.java` with core, assets, collision and botlib main
class directories on its classpath. Arguments are
`<user PK3> <oracle executable> [queries per map] [map or all] [entry or finish] [12 or 13]`.
The maximum is 10,000 requests per map. Pack reads are read-only; native binaries,
original assets and temporary logs remain ignored and are not bundled. Local
final logs are `/tmp/craftq3-weapon-jump-{entry,finish}-{12,13}-final.log`.
