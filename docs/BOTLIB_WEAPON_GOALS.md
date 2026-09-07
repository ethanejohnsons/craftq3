# Weapon-jump move-to-goal integration

The full `GroundMoveToGoal` operation supports independently registered
ROCKETJUMP (12) and BFGJUMP (13) stateful approach and airborne completion
executors. `WeaponJumpMovement::execute` and `::finish` supply those providers;
their separate direct behavior and validation are documented in
[BOTLIB_WEAPON_JUMP_TRAVEL.md](BOTLIB_WEAPON_JUMP_TRAVEL.md). Registration is
optional. Constructors without these providers still report an explicit
unsupported-travel error without publishing partial movement state.

New type 12/13 selections use a six-second reach deadline. Their ordinary timed
avoidance remains six seconds, so both expire together. A cached reach remains
valid at the exact deadline, subject to the established goal/source/travel-mask
checks. Reselection resets active jump history before the direct provider runs;
a committed launch then stores the selected reach as `jumpReach`. Approach
commands preserve retained jump history. The stateful callback receives the
stored reach source area separately from the current area used by stateless
travel providers.

Airborne completion uses the cached reach independently of an expired deadline,
changed goal or denied travel mask, retaining unrelated history and avoidance.
It records the current origin after completion. With zero active jump history,
the completion writes a full cleared result with its travel type and no EA
movement, action, weapon or view command. Active completion publishes movement
only. Explicit view and weapon effects from approach are forwarded separately
from movement and action requests; result flag bits alone never imply an EA
write. Failure and no-route paths retain the established 24-byte prefix write,
leaving arbitrary prior suffix bytes intact.

## Production validation

Six focused integration tests exercise fresh rocket/BFG launch, explicit
weapon/view/action forwarding, six-second deadline and timed-avoidance lifetime,
cached airborne history, absent completion commands, denied travel with partial
result writes, and atomic failure when a provider is missing. Together with the
seven direct helper tests, all 13 tests pass.

The final full-operation comparison runs against the production Java classes,
without the preliminary isolated prototype:

| Corpus | Exact comparisons | Explicit exclusions |
| --- | ---: | ---: |
| Rocket ground, 27 original maps | 26,992 | 8 unregistered type 5 selections |
| BFG ground, 27 authored map variants | 26,992 | 8 unregistered type 5 selections |
| Rocket airborne | 25,353 | 1,647 samples actually grounded in AAS |
| BFG airborne | 25,353 | 1,647 samples actually grounded in AAS |
| Mixed ground, all 30 original maps | 29,979 | 20 type 5 and 1 type 19 selections |

Total: **134,669 exact comparisons and 3,331 explicit exclusions**, with zero
differences. Each query compares all 52 output bytes, including untouched
prefix-only suffixes; current and retained movement history; jump state and
timed avoidance; every EA movement, speed and view float bit; action and weapon
values; BSP trace counts; and RNG draw counts. Cases include fresh/cached/expired
routes, changed source/goal, same-area movement, empty travel masks, inclusive
deadlines, avoidance and spots, controlled world/entity/start-solid collision,
standing/crouching presence, stale contact flags, and prior EA jump history.
Airborne cases include zero and nonzero active jump identifiers. Samples
excluded as grounded are outside that airborne case generator, not failed
comparisons.

## Reproduction and provenance

`scripts/BotWeaponGoalOracle.c` is an authored full botlib host derived from the
project's earlier authored liquid-goal oracle. It loads original map data
read-only, seeds movement state and EA commands through public interfaces/layout
metadata, calls unchanged native `BotMoveToGoal`, and reports the result and
state. Every query seeds weapon 7 and view `(1,2,3)` so absent effects are visible.
`scripts/BuildWeaponGoalOracle.py` uses the ignored official ioquake3 checkout at
commit `588393618dbc82e7207c21c6ddecca229944a03a`, compiling unchanged movement,
AAS movement/sample and vector objects with `-ffp-contract=off`. No engine routine
body was inspected, copied or mechanically translated to derive this provider.

Run `scripts/AuditWeaponGoals.java` with the core, assets, collision and botlib
main class directories on the Java classpath. Arguments are
`<user PK3> <oracle executable> [queries per map] [map or all] [rocket|air-rocket|bfg|air-bfg|ground]`.
The maximum is 10,000 queries per map. BFG modes author temporary AAS variants
under `.tools/weapon-goal-oracle/overlays`, replacing rocket travel metadata with
BFG travel metadata before native and Java route initialization. Ground and air
variants use separate paths. These variants are explicitly distinct from the
user's original maps, which contained no type 13 links. The original PK3 is never
modified. Derived fixtures, original bytes, native binaries and logs remain
ignored and are never packaged with the mod.

Local final logs are `/tmp/craftq3-weapon-goals-{rocket,air-rocket,bfg,air-bfg,ground}-production.log`.
These tests establish the engine-service contract; live original-QVM gameplay
validation remains a separate host/runtime check.
