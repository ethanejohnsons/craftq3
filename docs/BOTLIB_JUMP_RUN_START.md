# Type-5 jump run-start service

`JumpRunStart` implements the observed request and fallback policy of the public
`AAS_JumpReachRunStart(aas_reachability_t*, vec3_t)` service. It is a dependency of
jump travel type 5. It does not enable type-5 movement or replace physical
prediction with an assumed run-up distance.

The Java API accepts a borrowed
`Function<AasMovementPredictor.Request, AasMovementPredictor.Prediction>` and exposes
`calculate(Vec3 start, Vec3 end)`. The provider owns physics settings, world queries,
and work limits. A provider using `AasMovementPredictor.predict` must explicitly
handle its empty result; exceptions are propagated. Gap event 64 is required.

## Measured request and fallback

Each call raises the reach start by one unit and requests one prediction:

| Field | Value |
| --- | --- |
| Entity | −1 |
| Origin | reach start + Z1, float arithmetic |
| Presence / on-ground | 2 / true |
| Initial velocity | zero |
| Command | normalized horizontal start − end, multiplied by 400 |
| Command frames / total frames | 1 / 2 |
| Frame time | 0.1f |
| Stop mask | 124: water, slime, lava, fall damage, gap |
| Native stop-area / visualize | 0 / 0 |

The native predictor's integer return status does not affect run-start selection.
If the returned event includes slime 8, lava 16, or fall damage 32, the service
returns its raised initial origin. Otherwise it returns the prediction endpoint,
including water 4 and gap 64 outcomes. All 256 event masks were tested with both
success 0 and success 1: 512 exact controlled outcomes.

The request stays unchanged under observed overrides of `phys_maxvelocity` and
`phys_maxwalkvelocity` to 100 or 0, `phys_gravity` to 400, `phys_friction` to 0,
`phys_walkaccelerate` to 2, `phys_maxstep` to 7, and `sv_gravity` to 400. These
settings can affect the underlying predictor; the wrapper does not replace or
cache that predictor's configuration.

Zero-length and purely vertical reaches issue a zero horizontal command. For tiny
finite deltas whose squared length underflows to zero, native normalization leaves
the original tiny delta intact. This behavior is preserved. Float overflow and
nonfinite vectors fail explicitly before a provider call; native NaN propagation
outside that domain is not reproduced.

## Gap-event observations for predictor integration

These are independently measured call-boundary contracts. They do not by
themselves establish full physical-predictor integration parity.

After ordinary liquid entry checks and the on-ground query, a requested gap event
is considered only when the predicted position is not grounded. An earlier
requested leave-ground event wins before the gap check.

The native service traces vertically down from the current predicted position,
using crouch presence 4 and ignored entity −1, independent of the request entity. The depth is the float
sum `phys_maxbarrier + 48`; the observed default is 33 + 48 = 81 units. Overrides
0, 1, 12, and 100 produce depths 48, 49, 60, and 148. Subtraction occurs after the
float addition: `position.z - (maxBarrier + 48)`. `sv_maxbarrier` does not control
this query.

A gap requires all three observed conditions:

- The downward trace does not start solid. Its fraction is otherwise irrelevant
  to this decision.
- The actual `trace.endPosition.z` is below the sequentially rounded threshold
  `float(float(position.z - phys_maxstep) - 1)`. This is approximately a margin
  of `maxStep + 1`, but calculating a drop or grouping the additions can change
  the boundary. The predictor owner's 24,000 isolated threshold probes establish
  the exact operation order.
- The **requested bottom of the downward trace** is not in `CONTENTS_WATER` 32.
  This is distinct from its actual collision endpoint. Slime and lava bits alone
  do not suppress a gap.

A gap result reports the position at the beginning of the current prediction
frame, the velocity after that frame converted from displacement using `1/dt`,
and the ordinary movement trace from that frame. It does not substitute the
additional downward trace. Its event is 64, end-contents is zero, time is
`frame * dt`, and the frame index is retained. Rising and falling velocities both
participate in the gap check.

## Validation and provenance

Five authored unit tests cover the complete request, event-mask fallback,
vertical/zero and subnormal reaches, overflow, and provider failure propagation.
Java 25 compilation with `-Xlint:all -Werror` and those focused tests passed.

`AuditJumpRunStart.java` compares the production wrapper's entire request and
selected endpoint with the original native export. It passed **29,000 exact
requests on 29 original maps containing jump reaches**, with controlled prediction
outputs spanning the event masks and return statuses. The audit took 8.749 seconds
on the development host. It validates the wrapper, not the predictor's trajectory.

`AuditGapEvent.py` exercises controlled gap trace endpoints, start-solid flags,
fractions, water/other contents and step thresholds. The final audit passed **16,000 exact
decisions**, with 2,000 cases each at `phys_maxstep` 0, 7, 7.1, 7.125, 19, 19.1,
50, and 100000. Request entity −1, 0 and 3 cases all use ignored entity −1 in the
gap trace. Near-zero origins exercise float cancellation boundaries.
It is a native decision probe, not a substitute for a production predictor
differential. The water query is skipped unless both the start-solid and strict
drop checks pass. Gap end-area lookup uses the restored frame-start position.

The isolated oracle is `.tools/jump-travel-oracle/probe`, built with
`.tools/jump-travel-oracle/build.py`. It calls unchanged native exports from the
local official ioquake3 reference at commit
`588393618dbc82e7207c21c6ddecca229944a03a`. Relevant arithmetic objects use
`-ffp-contract=off`; authored wrappers log public call arguments/results and can
supply controlled dependency outcomes. Only public declarations and structure
metadata were consulted. Engine implementation bodies were not read or
translated. Native artifacts and original assets remain outside distributable
modules, and the user's PK3 is opened read-only.

Example wrapper audit after normal module compilation:

```sh
java -cp craftq3-core/build/classes/java/main:craftq3-assets/build/classes/java/main:craftq3-collision/build/classes/java/main:craftq3-botlib/build/classes/java/main \
  scripts/AuditJumpRunStart.java path/to/pak0.pk3 .tools/jump-travel-oracle/probe 1000
python3 scripts/AuditGapEvent.py path/to/pak0.pk3 .tools/jump-travel-oracle/probe --count 2000
```

The separate [type-5 travel validation](BOTLIB_JUMP_TRAVEL.md) now covers direct
entry/completion, transactional jump history, full move-to-goal dispatch and a
captured runtime request. Its production run-start corpus retains explicit guards
for unsupported sloped steps and unsuccessful prediction. Original-QVM gameplay
remains a separate runtime checkpoint.
