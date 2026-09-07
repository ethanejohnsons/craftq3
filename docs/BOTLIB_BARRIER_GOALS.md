# Barrier-jump move-to-goal orchestration

`GroundMoveToGoal` accepts BARRIERJUMP type 4 in both optional executor maps:
`barrier::execute` for grounded approach and `barrier::finish` for cached airborne
completion. The direct provider is described in
[BOTLIB_BARRIER_TRAVEL.md](BOTLIB_BARRIER_TRAVEL.md). It returns the shared
[ReachMovementOutput contract](BOTLIB_REACH_OUTPUT.md), preserving the difference
between an absent EA_Move and a zero-speed command. The original game VM remains
responsible for jump physics and collision.

Fresh selection uses the ordinary five-second reach deadline and six-second
avoidance attempt. The inclusive cache deadline, travel-mask checks, changed-area
and changed-goal reselection remain unchanged. New selection clears jump-reach;
retention preserves it. Requesting EA_Jump does not invent a runtime barrier flag:
effective movement flags keep their independently observed state.

Without newly detected jump-pad contact, airborne barrier completion retains
current area, cached reach, last area, last goal, reach area, jump-reach and
avoidance, even with a changed goal or expired deadline. Last origin updates.
Completion writes a full 52-byte result, including when the direct provider issues
no actions during early ascent. This is distinct from the 24-byte no-reach or
teleport-completion result. A blocked completed reach subtracts one second from
the deadline through the existing rule, including a jump-only grounded result.

The host applies independent action flags once before any optional movement.
Jump bit 16 invokes `ElementaryActions.jump(client)`, preserving the native
previous-frame jump suppression. It must not be applied through generic bitwise
EA_Action. Seeded prior movement survives both the near-start jump-only branch
and the high-upward-velocity completion branch.

## Verification method

The authored `scripts/BotGroundGoalOracle.c` driver invokes the unchanged full
native operation. Its `goal` command optionally accepts five more values after
the ordinary goal fields: prior EA direction (three floats), speed, and action
flags. These values seed the native accumulator immediately before the call;
the Java audit independently applies the output to the verified elementary-action
service. The driver resets the initialized client's accumulator before each
independent scenario; resetting before movement-state initialization would target
the wrong client and leak prior jump history.

`scripts/AuditGroundGoals.java` adds `barrier` and `air-barrier` modes. Ground
sampling includes points within a few units of the reach start, while airborne
sampling spans rising and falling velocity branches. The 22 policy scenarios
include fresh, cached, expired, exact-deadline, changed-area/goal, same-area,
denied travel, avoided reaches, avoidance spots and controlled collision worlds.
Every targeted request seeds a nonzero prior movement vector and speed; action
seeds also include attack and previous-frame jump bookkeeping.

The expanded near-start set exposed two existing dependencies: retained nonzero
first-reach indices in areas with a stored count of zero, and exact .125 AAS
splits that can be start-solid after a permitted zero-length visit. Both were
isolated against unchanged native public operations and corrected independently
before the final full-operation comparison. Their focused tests and separate
corpora belong to the routing and presence-trace services.

The optional executor/result tests cover ordinary barrier history, independent
jump-only and absent-action results, compatible old constructors and rollback of
invalid movement output. Alongside the direct ground, teleport and barrier tests,
54 focused tests passed. Native reference code and commercial map bytes remain
outside committed production files. Relevant native arithmetic is rebuilt with
`-ffp-contract=off`; no engine routine is copied or mechanically translated.


## Final original-map comparison

After both dependency corrections, the production outer operation matches:

- 26,849 targeted ground requests across all 27 maps containing type 4, including
  1,703 jump-only outputs. The 151 exclusions are other travel types 5 and 8.
- 25,341 targeted cached-air requests, including 17,440 full-result/no-command
  waits. The 1,659 exclusions are samples classified as grounded by AAS.
- 29,542 broad ground requests across all 30 maps. The 458 exclusions are other
  travel types 5, 8, 9 and 19; all 13 previously excluded type-4 requests compare.

There are zero differences in result bytes and preserved suffixes, history,
flags, avoidance, accumulated action fields or float bits, and BSP callback
counts. The targeted ground audit includes the near-start samples that exposed
the routing and presence boundaries, rather than excluding those reproductions.
Final concurrent local runs completed in 18.158, 17.459 and 22.118 seconds.

Using Java 25 and the compiled core/assets/collision/botlib classpath:

```text
AuditGroundGoals <user-pak0.pk3> <isolated-native-probe> 1000 all barrier
AuditGroundGoals <user-pak0.pk3> <isolated-native-probe> 1000 all air-barrier
AuditGroundGoals <user-pak0.pk3> <isolated-native-probe> 1000 all
```

The native oracle is rebuilt by `scripts/BuildGroundGoalOracle.py`. Exact local
results are in `/tmp/craftq3-barrier-ground-corpus-v2.log`,
`/tmp/craftq3-barrier-air-corpus-v2.log` and
`/tmp/craftq3-barrier-ground-regression-v2.log`. The collision callbacks are
controlled clear, floor, solid and ordinary-entity worlds; this comparison does
not claim coverage of every actual BSP collision configuration.
