# Swim and water-jump travel commands

`LiquidReachMovement` independently implements the observed direct travel services
for SWIM (8) and WATERJUMP (9). The original game VM owns movement physics and
player-state transitions. This helper does not decide routes or mutate movement
history. The separately verified outer integration is documented in
[BOTLIB_LIQUID_GOALS.md](BOTLIB_LIQUID_GOALS.md).

Construct it with `MovementObstruction` and a borrowed `DoubleSupplier` for random
samples. `execute(input, effectiveFlags, sourceArea, reach)` returns an immutable
`Output` implementing `ReachMovementOutput`. The optional movement and separate
action flags must be applied independently, preserving accumulated EA movement
when the optional value is empty. Direct output leaves the travel-type field zero;
the surrounding move-to-goal operation supplies it.

Swim approaches `reach.start` in three dimensions, normalizes with float32
arithmetic, issues EA movement at speed 400, and returns SWIMVIEW (2) plus the
shared obstruction flags. It calculates the ideal view from the normalized
direction. Zero distance still issues a zero-direction movement command and has
the native vertical-zero pitch of -270. Effective movement flags, velocity and
view offsets do not change these measured commands. Collision checks use the
shared obstruction contract, including cropped/full hulls and optional below
checks; they do not suppress the swim command.

Water-jump entry aims toward `reach.end`, perturbs the vertical delta with one
random sample, and returns MOVEMENTVIEW (1). It issues forward action 512 and adds
up action 32 when the normalized-return horizontal endpoint distance is strictly
less than 40. It does not issue EA_Move or perform collision queries. Prior
movement direction and speed therefore survive even when directional actions are
present. The random supplier accepts finite values in [0,1]; each is converted to
float32 before the observed double-precision vertical adjustment is rounded back
to float32. Invalid samples fail explicitly. View angle calculation preserves
native negative pitch representation and signed zero.

`finish` accepts only WATERJUMP. The directly observed completion returns a fully
cleared movement result, no command, no action, no random draw and no collision
query, across tested positions, velocities and flags. There is no exported
`BotFinishTravel_Swim` in the pinned reference; a swim completion request therefore
fails explicitly rather than inventing a direct native operation.

## Validation

Eight focused tests cover swim targeting, zero distance, angles, obstruction
metadata, optional water-jump movement, random rounding and draw count, the
40-unit up boundary, empty completion, same-area swimming and rejected inputs.

The final original-map differential compares all result fields, every float bit,
EA direction/speed/actions, unchanged movement flags/jump state, random draw
counts and BSP trace counts. All requests seed earlier movement
`(.25,-.5,.125), speed 123` and action 8192. It varies 15 effective flag
combinations, standing/crouching presence, view fields, origin/velocity, RNG
endpoints, and controlled clear/entity/world/start-solid collision responses.

- Swim: 30,000 exact requests on all three original maps containing swim links
  (q3ctf2, q3dm12 and q3dm8).
- Water-jump entry: 50,000 exact requests on all five maps containing water-jump
  links (q3ctf2, q3dm10, q3dm11, q3dm12 and q3dm8).
- Water-jump completion: 50,000 exact requests on the same five maps.
- An additional 13,000 requests with native `phys_gravity=400` and
  `phys_maxvelocity=1600` produce the same exact results.

Total: 143,000 comparisons, zero differences. The subsequently added
`moveInGoalArea(input, effectiveFlags, sourceArea, goal)` passed another 30,000
native comparisons on the three swim maps. It requires SWIMMING4, approaches
the goal in 3D, returns travel type8 and SWIMVIEW2, and slows using the measured
float cancellation ramp with a speed-below10 deadband. It always emits movement
and does not draw random samples. Separate authored observations
established angle arithmetic with 10,000 arbitrary vectors and vertical random
rounding with 2,000 samples before the production corpus. These are direct
provider results; the separate full-operation audit covers routing, history and
liquid flag transitions.

## Reproduction and provenance

`scripts/BotLiquidTravelOracle.c` is an authored host using public declarations,
`BotMoveStateMetadata.h` layout metadata, and calls to unchanged native exports.
`scripts/BuildLiquidTravelOracle.py` links it against the ignored official
ioquake3 checkout at commit `588393618dbc82e7207c21c6ddecca229944a03a`, rebuilding
movement, AAS movement/sample and vector objects with `-ffp-contract=off`. No
engine routine bodies were copied, mechanically translated or inspected to derive
this behavior. Initial transparent call observers were authored wrappers around
unchanged routines; the final corpus uses the independent host without wrappers.

Build the oracle, compile the Java modules, and run `scripts/AuditLiquidTravel.java`
with the module main-class directories on the classpath. Arguments are
`<user PK3> <oracle executable> [queries per map] [map or all] [swim|entry|finish]`.
The maximum is 10,000 queries per map. The native command stream supplies reach
coordinates, input vectors and flags, controlled random samples and collision
responses. Only original map metadata is read from the user's pack. Oracle
binaries, temporary output and original assets remain ignored and are never
bundled with the mod.

Local final logs are `/tmp/craftq3-liquid-{swim,entry,finish}-{default,altered}-final.log`.
The authored exploratory observations remain under `.tools/liquid-travel-oracle`.
