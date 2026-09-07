# Ground reach execution

`GroundReachMovement` emits native-observed commands and result metadata for
WALK and CROUCH reachabilities. It does not mutate a movement handle, select a
route, update history, or simulate player physics.

The constructor takes the BSP/entity `TraceWorld`, area-presence and outgoing
reachability-count functions, and a `GapDistance` provider. The verified
`MovementObstacles.gapDistance` method is suitable for the latter. Execute with
`execute(MovementInit, effectiveFlags, sourceArea, Reachability)`. Source area is
the host's current movement area, including recovered areas; it is not silently
recomputed from the position. Effective flags come from retained movement state,
independently of the raw initialization flags.

`Output` contains a `MovementResult`, direction, speed, and elementary-action
flags. The direct reach operation leaves `result.travelType` zero; the outer
move-to-goal operation owns the executed travel type. Directions, speeds, and
geometric boundaries use separate float32 arithmetic. Native normalization returns
squared distance multiplied by the rounded reciprocal square root. That return
value can differ from the rounded square root itself, including at the 10-unit
target boundary; an authored oblique-boundary regression preserves this detail.

Walk approaches the reach start until its horizontal distance is below 10 units,
then aims at the endpoint. Its entity-obstruction query still follows the start
approach direction. Within 20 horizontal units of the selected target, an area
without NORMAL presence requests crouch. Positive gaps limit command speed to
`min(400, 40 + 2 * gap)`; effective slow-walk flag512 halves that result and adds
the WALK action. Crouch always aims horizontally at the endpoint, emits speed 400
and CROUCH, and ignores gap/slow-walk state.

The obstruction check uses the native cropped presence hull for its 3-unit
forward BSP trace, mask 33619969, ignoring the moving entity. A non-start-solid
entity hit sets blocked/entity metadata while preserving the movement command.
World and no-entity identifiers do not block this entity-specific query. If the
current area has no outgoing reaches, a full presence hull is also traced down
3 units with mask 65537; an entity under the bot adds result flag 32. Routing source
areas skip that downward trace. The helper uses measured default hull cropping
(step 18 and upper inset 10); configurable nondefault crop settings are not exposed.

Twelve authored tests cover target/crouch thresholds, gap and slow-walk ordering,
obstruction hulls/masks/entities, current-area trace selection, unsupported input,
and the actual first retail bot walk on q3dm1 (reach246). Java25 compilation and
the focused tests pass. `scripts/AuditGroundTravel.java` compares actual-map
WALK/CROUCH commands, every result field, float bits, action flags, and BSP query
counts against an isolated unchanged native botlib oracle. The environment uses
real original AAS and controlled clear BSP collision; it does not claim original
BSP collision equivalence. The production helper also uses the independently
verified Java gap provider in that comparison.
All 30,000 requests across 30 original maps matched exactly, including float bits
and BSP query counts (9.018 seconds locally).

## Movement within the goal area

`moveInGoalArea(input, effectiveFlags, sourceArea, goalOrigin)` implements the
separate native dry goal-area operation. It always emits EA_Move, including zero
speed while resting, and sets result travelType 2. It aims horizontally at the goal
and ignores slow-walk, initialization crouch presence and goal-action flags. The
obstruction checks retain the same source-area semantics as reach execution.

Speed is 400 at normalized distance 100 or above. Below that, the native float32
expression `400 - (100 - distance) * 4` is preserved; replacing it with a simple
multiplication changes observable float results. Speeds below 10 become zero;
distance 2.5 produces speed 10, while a position just below it stops. The original normalization return
and this cancellation behavior were distinguished through black-box outputs.

An independent 10,000-request controlled native audit covered positions, goal
flags, presence, retained flags, entity/start-solid combinations, routing and
nonrouting source areas, and BSP trace counts with no mismatches. The production
map audit accepts `all goal-area` after the request count to exercise this method;
`all reach` retains the original walk/crouch audit mode. All 30,000 production
goal-area queries matched every result/action/float bit and BSP trace count
(9.541 seconds); the final 30,000 reach regressions also remained exact
(10.284 seconds). Swimming flag 4 invokes a distinct native 3D/view operation and
throws explicitly in this ground provider.

The ignored `.tools/ground-travel-oracle` host calls native travel entry points
using layout metadata from `scripts/BotMoveStateMetadata.h`. Transparent wrappers
record dependency calls; original engine arithmetic/control flow is retained.
Reference objects use `-ffp-contract=off`. No native routine is copied into Java,
and no native reference binary or commercial data is bundled.

Swimming, jump/ledge/ladder travel, mover interaction,
and movement-state orchestration remain separate services. Unsupported reach
kinds throw explicitly.
