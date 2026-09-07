# Liquid move-to-goal integration

`GroundMoveToGoal` optionally composes the independently verified liquid travel
provider while retaining the existing routing, timed avoidance and atomic history
commit. Its extended public constructor appends a `GoalTravel liquidGoalTravel`
callback after the ground and airborne executor maps. `GoalTravel` returns
`ReachMovementOutput`; existing ground method references remain compatible.
Register ground types 8 and 9 with `LiquidReachMovement.execute`, airborne type 8
with `execute`, airborne type 9 with `finish`, and the final same-area callback
with `moveInGoalArea`. Existing constructors retain their explicit unsupported
liquid-contact behavior and require no changes.

The operation checks liquid contents at origin minus two vertical units. Any of
water/slime/lava bits 32/16/8 sets effective SWIMMING4 after stale swimming,
ladder and active-grapple flags are cleared. Grounded2 still controls the initial
standing-entity collision query. Either grounded or swimming state enables fuzzy
area localization and the ordinary route/cache policy. Liquid contact does not
invent a grounded flag. The retained WATERJUMP16 flag remains intact.

Cached routes share the proven inclusive deadline and travel-mask conditions.
Selecting a new swim or water-jump reach assigns the native five-second deadline
and standard attempted-reach avoidance update. Same-area swimming clears the
last area/reach, records the goal/current origin and preserves other history,
then uses the direct 3D goal provider. A callback failure publishes no partial
movement-state changes.

After liquid contact ends, a cached airborne SWIM8 reach reuses direct swim entry;
it still writes the full result and preserves the cache, deadline and goal
history, apart from normal origin/blocked adjustments. Cached airborne WATERJUMP9
returns the full cleared direct completion result with travel type9. Its absent
EA_Move and action output preserve prior accumulated input. Missing optional
executors remain explicit errors. The original VM owns all liquid physics and
player-state transitions.

## Native full-operation audit

The authored `BotLiquidGoalOracle.c` host calls the unchanged public
`BotMoveToGoal` export with caller-supplied state metadata and seeded EA input.
`BuildLiquidGoalOracle.py` builds the isolated reference with the same pinned
source and unfused arithmetic policy as the direct provider. No engine function
body was read, copied or translated to derive this orchestration.

`AuditLiquidGoals.java` covers 22 scenarios: new/cached/expired links, changed
source/goal, exact deadlines, no permitted travel, same-area/zero goals, avoid
expiry and spots, crouching, entity/world/start-solid traces, stale contact flags,
and differing raw/effective input flags. It compares every 52-byte result or
preserved early-return suffix, all history/avoidance fields, float bits, seeded
EA movement/actions, BSP trace counts and random draw counts.

| Corpus | Exact comparisons | Explicit exclusions |
| --- | ---: | ---: |
| Swim, three maps | 30,000 | 0 |
| Water-jump, five maps | 50,000 | 0 |
| Cached airborne swim | 29,660 | 340 grounded samples |
| Cached airborne water-jump | 48,422 | 1,578 grounded samples |
| Broad liquid, 30 maps | 29,978 | 22 unsupported jump/func-bob requests |
| Broad dry regression, 30 maps | 29,978 | 22 unsupported jump/func-bob requests |
| Broad airborne regression, 30 maps | 26,052 | 3,948 grounded/missing-seed-link samples |

Total: 244,090 exact comparisons, zero differences. Excluded requests never count
as supported behavior. Four focused outer tests additionally cover flag/routing
transitions, same-area history, callback failure atomicity, old constructor
behavior, and the distinct cached-air command forms. The eight direct-provider
tests and 173,000 direct comparisons are documented separately.

Native replays also used the exact captured live requests at retail q3dm8 time
11.45, modern q3dm8 time16.75, and modern q3dm12 time49.45. Under controlled liquid
contents, native code replaces the stale dry/airborne reach with swim links
3081, 4001 and2026 respectively and sets deadline to current time plus five.
The reference's imported BSP callbacks are controlled observations; these replays
do not claim complete dynamic-entity simulation or a resumed live gameplay run.

Run `AuditLiquidGoals.java` with main module class directories on the classpath;
arguments are `<user PK3> <oracle> [queries per map] [map or all]
[swim|waterjump|air-swim|air-waterjump|liquid|air]`. Omit the last argument for dry
regression. Original map metadata stays read-only in the user pack. Local final
logs are `/tmp/craftq3-liquid-full-<mode>-final.log`; exact captured-request
commands and outputs remain under ignored `.tools/liquid-goal-oracle`.
