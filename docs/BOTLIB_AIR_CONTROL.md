# Airborne steering and ledge travel

`BotAirControl.control(origin, velocity, target)` provides the shared airborne
steering used by ledge and jump-pad completion. Its immutable result contains
direction, raw speed and success. The elementary-action consumer clamps raw speed
to 400; this service can return 416. It performs no collision query, handle
mutation, route selection, or movement prediction on behalf of the game VM.

The independently observed calculation projects motion in 0.1-second steps,
rounding the initial velocity scale after multiplication by the double decimal
constant. Vertical step velocity decreases by 8. Final-step interpolation can
produce a small vertical remainder; the resulting direction retains that remainder
instead of forcing its Z component to zero. A zero vertical step is followed until
descent begins. Targets above the origin can extrapolate the final step rather than
clamping the interpolation fraction to zero or one.

The speed ramp preserves the observed float cancellation
`400 - (400 - min(distance, 32) * 13)`. A direct linear multiplication or a
rearranged subtraction changes low bits. Five authored tests cover drift,
overshoot, speed cancellation, zero vertical velocity at the apex, target
extrapolation, residual Z, and bounded failure. A 4,096-step work limit and explicit
float-overflow checks prevent pathological requests from hanging or producing a
nonfinite command; reaching those limits raises an exception, not a guessed move.

`scripts/AuditAirControl.java` compared the production helper against 30,000 native
requests with zero differences in success and every float bit (0.449 seconds
locally). The corpus includes random 3D origins, velocities and targets; short
scalar targets spanning the speed ramp; and exact multiples of 80 for vertical
velocity. Separate 10,000-query projection and 10,000-query scalar-speed probes
were used to distinguish arithmetic order. Native `phys_gravity` overrides of
400, 800 and 1,600 were accepted by `BotLibVarGet` and changed the separate jump
velocity service, but left this air-control operation unchanged. Its fixed step
behavior therefore does not borrow the configurable world-prediction gravity.

## WALKOFFLEDGE

`LedgeReachMovement` accepts a shared `MovementObstruction` and exposes
`execute(input, effectiveFlags, sourceArea, reach)` for entry and `finish(...)` for
airborne completion. Both return `GroundReachMovement.Output`, leave the direct
result travel type zero, and emit no elementary-action flags. The caller owns the
full travel tag, route deadline, avoidance and movement history.

Entry checks obstruction along a normalized 3D approach to the reach start and
again along the final horizontal command direction. It turns toward the endpoint
inside 48 horizontal units. Reaches shorter than 20 horizontal units use speed 100
inside that boundary and slow from 400 to 336 while approaching from 64 to 48
units. Longer reaches calculate departure speed from the fall; impossible or
over-limit departure speeds use the observed 400-speed fallback.

The default constructor uses gravity 800 and maximum velocity 320. The overload
`LedgeReachMovement(obstruction, gravity, maxVelocity)` supports the corresponding
native `phys_gravity` and `phys_maxvelocity` settings. Probes confirmed that
`phys_maxwalkvelocity` and `sv_gravity` do not change this calculation. Gravity
must be finite, positive and at most 100,000; maximum velocity must be finite and
between zero and 100,000. Zero maximum velocity retains the native failure
fallback. Accepted departure speeds still pass through the elementary-action
400-speed clamp.

Completion checks obstruction using the raw 3D endpoint delta. When horizontal
endpoint distance exceeds 16 units, it moves the steering target another 16 units
along that horizontal direction. Otherwise it uses the endpoint unchanged. It
then invokes the independently verified air-control service.

The shared obstruction query preserves raw direction magnitude. Directions whose
absolute Z component is at most 0.7 use the default step-18 cropped presence hull;
steeper directions use the full hull. Both retain the existing optional downward
entity check for source areas with no outgoing reaches. Earlier blocking metadata
survives a subsequent clear check, and a later blocking entity replaces the earlier
entity while retaining standing flags.

Six authored ledge tests cover threshold boundaries, variable settings and invalid
domains, obstruction ordering, landing target adjustment, and the first live
q3dm17 ledge entry at reach 1349. `scripts/AuditLedgeTravel.java` compared 30,000
entry requests and 30,000 completion requests across all 30 original maps, with
zero differences in every result/action field, float bit and BSP trace count
(9.835 and 9.799 seconds). The corpus uses original AAS and controlled clear BSP
callbacks; entity obstruction behavior is covered by authored forced-hit fixtures.
It does not claim original BSP collision or full move-to-goal history equivalence.
An additional 1,000 q3dm17 entry requests matched exactly with gravity 400 and
maximum velocity 1,600, including the 400-speed elementary-action clamp.

The ignored `.tools/ledge-travel-oracle` host calls original botlib entry points.
Transparent wrappers observe public dependency arguments and results. Relevant
reference objects use `-ffp-contract=off`; original routine bodies were not copied
or mechanically translated into Java. No native oracle binary or original assets
are bundled.
