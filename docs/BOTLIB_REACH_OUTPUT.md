# Independent reach movement and actions

`ReachMovementOutput` separates the movement-result structure from an optional
EA_Move request and elementary-action bits. Its `result()`, `movement()` and
`actionFlags()` accessors make three distinct outcomes representable: a move,
an action without a move, and neither. The nested `Move` interface exposes
`direction()` and `speed()`.

This distinction is observable in native botlib. A nearby barrier approach issues
EA_Jump without changing a previously accumulated EA_Move direction or speed.
Early ascending barrier completion issues neither. Treating either outcome as a
zero-speed move would erase prior input. The direct provider and its native
seeded-input comparisons are documented in
[BOTLIB_BARRIER_TRAVEL.md](BOTLIB_BARRIER_TRAVEL.md).

`GroundReachMovement.Output` retains its original four-component record and
constructor. It implements both interfaces and returns `Optional.of(this)` from
`movement()`. Existing direct-provider users can continue reading direction,
speed and action flags. Barrier output records implement the same interfaces
without changing their own fields. `GroundMoveToGoal.ReachTravel` returns the
shared interface; existing method references returning the ground output remain
valid.

The outer `GroundMoveToGoal.Output` retains `result`, `writtenBytes` and optional
`command`, and adds independent `actionFlags`. Its existing three-argument
constructor derives those flags from a present command, preserving old callers.
When a command is present, its legacy action field must agree with the outer
field; inconsistent construction is rejected. Hosts should apply the outer
flags once, independently of command presence, then issue EA_Move only when the
command is present. The JUMP bit must invoke `ElementaryActions.jump(client)` so
its prior-frame suppression semantics remain intact; it must not be ORed through
the generic action method.

These actions are separate from the guest's 52-byte movement-result ABI. The
existing 24-byte early-write rule also remains unchanged. Direction and speed
are validated while converting a provider result, before the movement service
commits history or avoidance. An invalid optional command therefore publishes
neither partial state nor actions.

Four authored outer-operation tests cover action-only results, no-action airborne
completion, the compatible constructor and action consistency, invalid optional
movement rollback, and registered barrier history without invented runtime
flags. Together with the prior outer tests and direct ground, teleport and
barrier tests, 54 focused tests passed. Full-operation barrier boundary validation
is tracked separately from this result-contract checkpoint.
