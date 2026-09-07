# Barrier-jump travel commands

`BarrierReachMovement` provides independently observed entry and completion
commands for BARRIERJUMP travel type 4. It accepts the shared `MovementObstruction`
service and exposes `execute(input, effectiveFlags, sourceArea, reach)` and
`finish(...)`. Each immutable output implements `ReachMovementOutput`: a movement
result, an optional direction/speed command, and separate elementary-action flags.
An absent move preserves earlier `EA_Move` data; it is distinct from issuing a
zero-speed command. The original game VM owns the resulting jump and collision
physics.

Entry normalizes the horizontal approach to `reach.start`, then performs the
shared obstruction check. At a native normalization-return distance below 9 units,
it emits jump action 16 without a move. The result still contains the approach
direction and any blocking metadata. At or above 9 units, it emits a move without
a jump action. Speed reaches 360 at 60 units and preserves the measured float
cancellation `360 - (360 - min(distance, 60) * 6)`. Algebraically equivalent
expressions produced observable low-bit differences in the original-map audit.

Completion does nothing while vertical velocity is at least 250: the result is
cleared, no obstruction query occurs, and earlier elementary actions remain
untouched. Below that velocity, it checks obstruction along the raw horizontal
vector from the origin to `reach.end`, then issues that same unnormalized direction
at speed 400. Even an exact endpoint produces a zero direction at speed 400. It
does not invoke the separate air-control service.

Direct result travel type, view angles and weapon remain zero. Both methods leave
effective movement flags and jump-reach state unchanged. The outer caller owns
tagged travel type, selection, route expiration, avoidance and movement history.
The shared obstruction service currently uses the verified default `sv_step=18`.
Native overrides of `phys_jumpvel`, `phys_maxvelocity`, `phys_maxwalkvelocity`,
`phys_gravity`, `sv_gravity` and `sv_maxbarrier` did not change these direct travel
rules in the controlled boundary probes.

## Verification

Seven authored tests cover the 9-unit jump boundary, the 60-unit speed cap and
float cancellation, blocking during a jump-only action, standing-on-entity
metadata, the velocity-250 completion boundary, raw endpoint steering, exact
endpoint commands, and invalid kind or overflowing approach rejection.

`scripts/AuditBarrierTravel.java` compared 27,000 entry and 27,000 completion
requests across all 27 original maps containing type-4 reaches. All result and
elementary-action fields, float bits, movement-state flags, jump-reach state and
BSP trace counts matched exactly. Every request starts with a distinct seeded EA
direction and speed, so jump-only and early-ascent branches must preserve those
values. The other three maps are explicitly reported as having no barrier reaches.
The final entry and completion runs took 11.547 and 9.460 seconds locally.

The corpus uses original AAS with controlled clear BSP callbacks. Separate native
forced-entity probes and authored fixtures cover blocked approaches, downward
standing metadata, ignored start-solid hits, and suppression of all queries during
early ascent. These checks do not claim original BSP collision or full outer
move-to-goal history equivalence.

```text
AuditBarrierTravel <user-pak0.pk3> <isolated-native-probe> 1000 all entry
AuditBarrierTravel <user-pak0.pk3> <isolated-native-probe> 1000 all finish
```

The ignored `.tools/barrier-travel-oracle` host calls the exported original
`BotTravel_BarrierJump` and `BotFinishTravel_BarrierJump` operations. Transparent
observers report public dependency arguments and results; relevant arithmetic
uses `-ffp-contract=off`. Implementation was guided by exported declaration
metadata and independent observations, without copying or mechanically translating
native routine bodies. Native oracle artifacts and original assets are not
bundled.
