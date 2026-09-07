# Type-5 jump travel

`JumpReachMovement` implements independently observed direct `BotTravel_Jump`
entry and `BotFinishTravel_Jump` completion. It borrows a run-start provider and
point-area classifier. It does not invent a fixed run-up or issue physics commands
outside the original travel service's measured behavior.

The constructor accepts `RunStart.calculate(Vec3 start, Vec3 end)` and
`ToIntFunction<Vec3> pointArea`. `execute` and `finish` accept the usual movement
input, effective flags, stored reach area and reach, followed by `lastReach` and
`jumpReach`. Their immutable output implements `StatefulReachMovementOutput`,
which extends `ReachMovementOutput` with the resulting `jumpReach()`.

The separate `JumpRunStart` wrapper can supply the run-start callback; its physical
predictor must support the measured gap event and all encountered geometry.
See [run-start validation](BOTLIB_JUMP_RUN_START.md). The full-call validation below
supports guarded Host registration; original-QVM gameplay validation remains a
separate runtime checkpoint.

## Entry behavior

Entry obtains the run-start position and normalizes its horizontal direction from
the original reach start. It samples behind that start at 10-unit intervals,
through 80 units, with Z raised by two. Each sample is calculated from the original
start, not accumulated from the preceding sample. Samples continue while their
area equals the stored `reachArea`; the first different area ends the search.
The last sample in the stored area is the approach target, or the original start
if the first sample is already outside it. Solid area zero and another valid area
both terminate a search from a nonzero stored area. At most eight queries occur.

This history dependency was exposed by full-call validation: the initial direct
probe left `reachArea` at zero. Repeating the same native reach with current and
stored areas varied independently proved that the stored source controls sampling.
The production outer dispatcher passes its cached `reachArea`, not current area.

Two horizontal directions—from the original start to the player, and from the
approach target to the player—determine whether the player can begin the run.
The measured alignment boundary includes float `−0.8`, which is slightly below
the double constant `−0.8`; an approach distance below five units also commits.
Otherwise the helper moves toward the approach target and retains existing jump
history. Approach speed preserves the measured float cancellation:
`400 - (400 - min(distance, 80) * 5)`.

A committed run moves horizontally toward the reach endpoint at speed 400 and
stores `lastReach` as the active jump reach. The native normalization-return
distance from the original start selects the elementary action:

| Distance | Action |
| --- | --- |
| Less than 24 | Immediate jump, bit 16 |
| At least 24 and less than 32 | Delayed jump, bit 32768 |
| At least 32 | No additional action |

Distances use the observed float normalization arithmetic. For example, an input
coordinate one float below 24 can normalize to distance 24 and select delayed
jump. Velocity and think time did not alter these thresholds in the observed
matrix. Elementary jump services retain their own previous-frame action rules;
a Host must call `jump` or `delayedJump`, not simply OR those bits into input.

Entry always supplies a movement command, including zero horizontal direction.
It performs no direct BSP obstruction trace. Its borrowed run-start provider can
perform AAS prediction queries.

## Completion behavior

With no active jump reach, completion returns a fully clear movement result and
no movement command. Earlier elementary input is preserved.

With an active jump reach, it normalizes both the horizontal endpoint delta from
the player and the original reach heading. When endpoint distance is below 24 and
the direction dot product is below `−0.5`, completion again emits no command.
This near-arrival branch was exposed by the original-map corpus and independently
checked at both boundaries. At exactly 24 units or a dot product exactly `−0.5`,
movement remains present.

All other active completion cases move toward the endpoint at speed 400, including
zero endpoint delta. Completion makes no run-start, point-area or BSP queries,
adds no elementary action, and preserves jump-reach history.

## Stateful outer contract

`GroundMoveToGoal.StatefulReachTravel` accepts
`(input, flags, reachArea, reach, lastReach, jumpReach)` and returns
`StatefulReachMovementOutput`. An additional constructor appends independent
stateful ground/air executor maps after the existing `liquidGoalTravel` parameter.
All earlier constructors and stateless interfaces remain compatible. The new maps
accept the independently verified stateful types 5, 12 and 13.

The outer dispatcher converts and validates the complete output, checks returned
jump-reach bounds, and only then updates its private frame. History is published
atomically after the entire operation succeeds. Fresh selection clears prior jump
history; cached execution receives the retained history. Authored tests cover both
paths, airborne completion, and invalid outputs leaving state unchanged.

`ReachMovementOutput` also supplies empty-by-default `view()` and `weapon()`
requests. These explicit effects are forwarded independently of movement and
result flags; no effect is inferred from result metadata. The type-5 helper emits
neither. Existing `GroundMoveToGoal.Output` constructors remain available.

## Validation and limits

- Ten direct-helper tests cover sampling, both alignment/distance boundaries,
  immediate/delayed actions, native speed cancellation, completion absence,
  history and invalid inputs.
- Thirty-six outer tests include four new stateful/effect/transaction checks.
- Five run-start tests complete a focused **51-test Java 25 pass**.
- `AuditJumpTravel.java` compared **29,000 entry and 29,000 completion requests**
  across 29 original maps containing type-5 reaches. Result and EA float bits,
  actions, history and point-query counts matched exactly. Native input was seeded
  so absent movement had to preserve its previous direction and speed. The final
  entry replay also varied stored source areas 0, 7 and 670; all 29,000 remained
  exact. Completion took 9.087 seconds on the development host.

The direct audit injects run-start coordinates and a bounded point-area answer
sequence to isolate travel policy. `AuditJumpGoals.java` then exercises the actual
`JumpRunStart` and production AAS predictor inside `GroundMoveToGoal`, against a
fresh strict native full-call oracle:

| Corpus | Exact comparisons | Explicit exclusions |
| --- | ---: | ---: |
| Grounded requests, 29 original maps | 28,977 | 23 |
| Cached airborne, mixed active/inactive jump history, 29 maps | 28,096 | 904 |

The verified tilted-step predictor extension resolves the former 373 sloped-step
exclusions. The remaining ground exclusions are 23 predictions
that do not complete successfully. Those cases continue to throw or propagate an
explicit unsupported result; the wrapper does not manufacture a run-start point.
The airborne exclusions are samples found grounded by the original AAS geometry,
which lie outside that corpus's cached-air branch. All nonexcluded result bytes,
partial-result suffixes, navigation history, avoidance, movement/actions and their
absence, and BSP trace counts match exactly. The matrix includes fresh selection,
cache reuse, expiry equality, policy/goal/source changes, solid/entity contacts,
avoidance and same-area goals. It exercises real AAS geometry with controlled BSP
clear/floor/solid callbacks; it does not claim native server collision or full
original-QVM gameplay parity.

This integration exposed the predictor's independent AAS-fluid contribution:
a run-up entering an AAS water area can stop with event 4 even when the engine BSP
contents callback returns zero. The predictor now combines the measured event
sources while retaining only BSP contents in its result. The q3dm12 regression
then matches the strict native full-call result.

`AuditJumpCapture.java` replays the captured retail q3tourney4 request at 47.15
seconds. It reproduces the native selection of reach 579/source area 671,
direction `(0.539073169, 0.842258930, 0)`, speed 400, absence of a jump action,
unchanged inactive jump history, deadline 52.15, and retained avoidance entry.
This is an original-AAS, controlled-BSP replay of a real runtime request.

The final ground corpus took 10.920 seconds and mixed airborne corpus 21.082
seconds on the development host. The focused Java 25 test pass contains 51 tests.
Ground and airborne comparison logs are retained locally under
`/tmp/craftq3-jump-ground-goals-corpus-v3.log` and
`/tmp/craftq3-jump-air-goals-corpus-v2.log` during development.

The isolated `.tools/jump-travel-oracle/probe` calls unchanged official ioquake3
exports from commit `588393618dbc82e7207c21c6ddecca229944a03a`, with relevant native
arithmetic compiled using `-ffp-contract=off`. Only public declaration/layout
metadata and authored call observations were used. Engine bodies were not read or
translated. Native reference artifacts and user assets are never bundled.

```sh
java -cp craftq3-core/build/classes/java/main:craftq3-assets/build/classes/java/main:craftq3-collision/build/classes/java/main:craftq3-botlib/build/classes/java/main \
  scripts/AuditJumpTravel.java path/to/pak0.pk3 .tools/jump-travel-oracle/probe 1000 all entry
# Replace the final argument with finish for completion.
python3 scripts/BuildLiquidGoalOracle.py
java -cp craftq3-core/build/classes/java/main:craftq3-assets/build/classes/java/main:craftq3-collision/build/classes/java/main:craftq3-botlib/build/classes/java/main \
  scripts/AuditJumpGoals.java path/to/pak0.pk3 .tools/liquid-goal-oracle/probe 1000 all jump
# Use air-jump for cached airborne requests.
java -cp craftq3-core/build/classes/java/main:craftq3-assets/build/classes/java/main:craftq3-collision/build/classes/java/main:craftq3-botlib/build/classes/java/main \
  scripts/AuditJumpCapture.java path/to/pak0.pk3
```
