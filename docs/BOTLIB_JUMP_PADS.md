# Jump-pad movement

`JumpPadMovement` implements grounded approach and airborne completion for AAS travel type 18. Original
qagame still owns the trigger, launch velocity and player physics. The engine-side
helper only supplies bot movement input and obstruction results.

Entry uses the raw horizontal difference from the current origin to the reach's
start. It preserves that magnitude in both the movement result and `EA_Move`;
normalizing it changes native behavior. The obstruction query uses the same raw
direction, with the shared three-unit multiplier and cropped presence hull.
Speed is 400, including zero horizontal difference. Slow-walk flags, vertical
separation, reach endpoint and current velocity do not change the entry command.

The shared `MovementObstruction` service retains ordinary entity-blocking and
standing-on-entity results. The helper emits its command even when blocked. It
validates the action direction before borrowed world queries or state updates.

`GroundMoveToGoal` accepts this verified executor through its additional ground
travel map. Newly selected jump-pad reaches receive a ten-second deadline and
the ordinary six-second avoided-reach attempt. Existing cache permissions and
inclusive deadline behavior apply. Airborne execution uses a separately registered
executor, so adding a ground executor cannot implicitly enable flight behavior.

Airborne completion borrows `BotAirControl` with the reach endpoint as its target,
checks obstruction using the returned direction, and publishes the move with
speed capped at 400. It preserves the small vertical component that can result
from native float interpolation. The steering helper's measured projection uses
fixed 0.1-second steps independently of the `phys_gravity` botlib variable; it
does not change qagame's actual launch velocity or player physics.

`JumpPadContact` traces at most 16 AAS areas from the current origin backward by
0.2 times velocity. It selects the first traced area with jump-pad contents and
a type-18 reach, taking the last such reach in that area's link order. This can
find the launch pad behind a player who is already airborne outside its area.
Contact replaces only the previous-area and cached-reach fields. Completion then
updates the previous origin; the retained source area, goal, reach area, jump
reach and avoidance remain unchanged. A blocked completed reach subtracts one second from the
retained deadline, following the shared movement rule. Goal permissions do not select
the physical launch-pad contact.

## Validation

Five focused tests preserve raw magnitude, zero-displacement actions, the first
retail q3dm17 movement request's float bits, airborne steering and pre-query input validation.
`scripts/AuditJumpPadTravel.java` compares 30,000 entry requests across all 30
original AAS maps. It uses real reach coordinates with type-18 metadata,
varied origin offsets, velocity, presence and effective flags. Every result
field, action, float bit and BSP query count matches the unchanged reference.
The separate `finish` mode adds 30,000 exact airborne completion comparisons.

The complete move-to-goal corpus includes 339 grounded jump-pad requests among
29,542 supported comparisons across 30 maps after teleport/barrier integration; all match. Its 458 explicitly
excluded requests remain separate from that result. The contact selector matches
24,535 airborne requests across 30 maps, including 9,031 selected contacts;
5,465 native-grounded samples are excluded. Full move-to-goal airborne type-18
execution matches 19,888 requests across the 21 maps with eligible pad links,
excluding 1,112 grounded samples. All result bytes, history, flags, avoidance,
actions and BSP query counts match.

The actual original retail qagame on q3dm17 now passes grounded entry and airborne
completion as well as the later WALKOFFLEDGE route. The strict sixty-second movement
and stationary-target combat audit passes: 1,187 moving frames, 18,194.77 horizontal
units, one scored kill and ten ammunition units spent. Original qagame supplies all
bot decisions; this does not establish full navigation or match compatibility. A
guest bridge regression also checks raw grounded approach followed by cached
airborne steering, including exact native float bits, under both supported ABIs.

Reproduce the entry oracle and corpus using the ignored official reference
checkout and locally owned PK3:

```sh
python3 scripts/BuildJumpPadTravelOracle.py
java -cp craftq3-core/build/classes/java/main:craftq3-assets/build/classes/java/main:craftq3-collision/build/classes/java/main:craftq3-botlib/build/classes/java/main \
  scripts/AuditJumpPadTravel.java run/craftq3/games/baseq3/pak0.pk3 \
  .tools/jump-pad-travel-oracle/probe 1000 all
# Add `finish` as the final argument for the airborne corpus.
```

The authored `scripts/BotJumpPadTravelOracle.c` host calls exported native
functions from unchanged ioquake3 objects at commit
`588393618dbc82e7207c21c6ddecca229944a03a`. The attributed movement-state declaration
is in `scripts/BotMoveStateMetadata.h`. The driver reads ZIP entries directly into
memory or unlinked temporary streams. Native code and original game data are
development inputs and are absent from the mod. Java behavior was derived from
returned values and query callbacks, without copying engine routines.
