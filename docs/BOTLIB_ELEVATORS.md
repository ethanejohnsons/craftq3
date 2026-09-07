# Direct elevator travel

`ElevatorMovement` implements the independently observed direct entry and completion commands for AAS travel type 11. It is a Java-only helper. **Type 11 is not registered in the bot host:** selecting, retaining and finishing elevator routes still needs a separate stateful integration audit.

The original `pak0.pk3` contains 30 AAS maps and **no type-11 reachabilities**. Consequently this evidence uses authored elevator reaches and a controlled AAS world, with original model metadata read directly from the archive. It is not a claim that an original-map elevator ride was tested.

## API and ownership

```java
new ElevatorMovement(movers, modelBounds, obstruction, barrierCheck);
helper.execute(input, movementFlags, sourceArea, reach);
helper.finish(input, movementFlags, sourceArea, reach);
```

The constructor borrows `MoverQueries`, an `IntFunction<MoverQueries.ModelBounds>`, `MovementObstruction` and a `BarrierCheck`. The bounds provider receives the low 16 bits of the reach's face field, matching the model identifier used by `MoverQueries`. Both providers must describe the same retained mover metadata for the current frame. Bounds are local, unrotated model bounds; the retained mover origin translates them.

The immutable `Output` implements `FlaggedReachMovementOutput`: a `MovementResult`, an optional EA move, action flags, resulting runtime movement flags, and `clearReachDeadline`. An absent move preserves the prior EA direction and speed. A present zero direction or zero speed is still a command. The caller applies these effects once and owns route history, physical movement and lifetime.

The direct helper does not alter view angles, weapon selection, jump-reach history, avoidance records or other retained state. It consumes no random numbers. A successful barrier request contributes jump action 16 and runtime flag 1. Approaching a nearby exit requests that the existing reach deadline be cleared, including when swimming or when the new speed is below the movement threshold.

The public movement header describes the state/result records and symbolic result flags; it distinguishes elevator waiting result type 1 from bobbing waiting result type 2. The AAS interface declares the elevator travel capability. These are interface declarations, not implementation recipes. [Movement ABI](https://raw.githubusercontent.com/ioquake/ioq3/master/code/botlib/be_ai_move.h), [AAS interface](https://raw.githubusercontent.com/ioquake/ioq3/master/code/botlib/be_aas.h).

## Observed entry behavior

Contact uses the existing `MoverQueries.onMover` operation. On the mover, an absolute vertical distance from the reach end strictly below 32 selects horizontal departure at speed 400 and a barrier check at speed 100. The other riding branch moves horizontally toward the translated bounds center, with a 10-unit deadband and a speed ramp capped at 400. This riding branch does not add swimming result flags.

Off the mover, a three-dimensional distance to the reach end strictly below 64 enters the arrival branch. The result direction and EA direction retain the raw end delta. The speed ramp is capped at 360; computed speeds at or below 5 preserve the previous EA move. Swimming adds result flag 2 and skips the barrier check. A dry arrival checks a barrier at speed 50. Both cases clear the reach deadline.

For a farther arrival, a platform top at or above the reach-start height produces waiting result type 1 and flag 4. Otherwise the bot boards toward the start or the translated center at the start's height. Boarding replaces the initial target when its distance is below 20, the center is closer, or the normalized target directions have a negative dot product. Waiting applies the same speed-5 deadband; boarding always emits the computed command, even a zero-speed move.

Both farther branches perform the cropped forward obstruction query without a bottom query. Swimming retains the existing EA move after this query; dry travel also checks a barrier at speed 50. A successful barrier overrides the EA command with a horizontal normalized direction and its check speed, while retaining the original planned direction in `MovementResult`.

The barrier provider receives the unchanged caller entity. The native `EntityTrace` callback identifies the entity being tested, whereas the ordinary `Trace` callback identifies the ignored caller; the observer and audit distinguish these two callback types.

## Completion and query order

Completion chooses between the translated bounds center at the reach-start height and the reach end, based on the bot's absolute vertical distance to each height. A tie chooses the end. It always sends the normalized three-dimensional target delta at speed 300, including a zero vector. Its entire movement result is zero, and runtime flags, deadline and retained state remain unchanged. Completion performs no contact, obstruction or barrier traces.

Model bounds are requested lazily. Entry first performs contact; it requests another set of bounds only if it needs to center on the platform or inspect its height. Boarding requests center bounds separately after the height check. A nearby off-platform exit needs only the contact bounds request. Completion makes one bounds request. The implementation preserves this ordering instead of prefetching or merging these operations.

Arithmetic uses explicit Java float operations, including the distinct rounded speed expressions for boarding and waiting. Non-finite or overflowing geometry fails clearly; other travel types and required missing mover geometry are unsupported. These checks do not relax collision-provider bounds.

## Evidence and reproduction

Nine focused tests cover departure equality, centering deadbands, preserved versus explicit zero EA moves, swimming/raw directions, waiting height equality, barrier effects, completion target ties, obstruction retention and unsupported metadata.

The production differential audit passes **100,000 requests**: 30,000 varied entry and 30,000 varied completion cases, plus 20,000 boundary cases for each operation. It compares all movement-result and EA float bits, runtime flags, deadline, retained native state, seeded view/weapon, zero random draws, and hashes of ordered model-bounds and collision callback kinds/arguments. The corpus includes asymmetric translated bounds, caller entities 0–63, normal/crouch presence, runtime/swimming flags, contacts, start-solid responses, time steps from 0 through 3600, and 3,557 successful barrier outcomes.

The authored native host seeds deadline 77, jump reach 29, last reach 17, previous areas/origin, grapple fields and an avoidance record. It compares the complete move-state record before and after each direct call, allowing only the observed runtime-flag and deadline changes. No native algorithm was copied or translated. The unchanged official reference is compiled with floating-point contraction disabled for the relevant geometry/movement objects. No reference object or original asset is packaged in the mod.

Development-only drivers:

- `scripts/BuildElevatorTravelOracle.py` builds the independent observer against an existing ignored official checkout.
- `scripts/ElevatorTravelOracle.c` calls the unchanged native direct operations and records their effects.
- `scripts/AuditElevatorTravel.java` reads archive data in memory, creates the authored scenarios and compares the Java helper. Pass `-Dboundary=true` for threshold cases; an optional final argument `finish` selects completion.

The final run is recorded in `/tmp/craftq3-elevator-production.log`. Future host integration still needs native outer-state and actual ride coverage on an authored or user-supplied map containing elevator reachabilities.
