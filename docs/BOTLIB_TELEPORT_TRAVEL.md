# Teleport reach approach

`TeleportReachMovement(MovementObstruction)` provides the directly observed
`BotTravel_Teleport` approach command. Its
`execute(input, effectiveFlags, sourceArea, reach)` method accepts TELEPORT travel
type 10 and returns an immutable `GroundReachMovement.Output`. The original game
VM remains responsible for touching the trigger and performing the teleport.

Dry movement normalizes the horizontal vector from the bot origin to the reach
start. Effective swimming flag 4 instead retains the vertical component and sets
movement-result flag 2. The native normalization-return distance, including its
float rounding, selects speed 200 below 30 units and 400 at or above 30 units. A
zero direction still produces a move command at speed 200. The reach destination,
velocity, slow-walk flag and raw initialization flags do not replace these rules.

The helper performs the shared obstruction check along the resulting direction.
Blocking and standing-on-entity metadata remain present without suppressing the
command. Swimming combines its result flag with that metadata. No jump, crouch or
other elementary action is added; the direct result travel type and ideal view
angles remain zero. The caller owns the full tagged travel type, route deadlines,
cached-reach policy, avoidance records and movement history.

The direct native entry suppresses every command and obstruction query when
effective `TELEPORTED` flag 32 is set. Because the existing output record describes
an issued movement command, this active-entry helper rejects that branch before
any trace. The outer caller must preserve an absent command and the native cleared
result; substituting a zero-speed move would overwrite earlier elementary actions.

There is no separate `BotFinishTravel_Teleport` export in the reference build.
This service therefore exposes no inferred airborne completion operation. Full
move-to-goal completion and `TELEPORTED` flag handling require their separate outer
orchestration checks. Shared obstruction currently uses the verified default
`sv_step` value of 18; teleport approach adds no gravity or velocity setting.

## Verification

Seven authored tests cover dry versus effective swimming behavior, the 30-unit
speed boundary, zero approach, crouch hull selection without a crouch action,
steep full-hull queries, blocked/standing metadata, ignored destination fields,
and invalid travel, teleported-entry, or unrepresentable direction rejection before
a trace. Direct native flag probes cover 32, 34, 36, 38 and 546 suppression.

`scripts/AuditTeleportTravel.java` compared 11,000 dry and 11,000 swimming requests
against original native botlib calls across all 11 original maps containing
teleport reaches. All result fields, elementary actions, direction and speed
float bits, and BSP trace counts matched exactly. The other 19 maps contain no
type-10 reaches and are explicitly reported by the audit. The runs completed in
5.185 and 5.088 seconds locally. They use original AAS data and controlled clear
BSP callbacks, so they do not establish actual BSP collision or outer history
equivalence. Forced entity hits are covered by the authored tests and direct
native probes.

Run the two corpus modes with Java 25 and the normal compiled module classpath:

```text
AuditTeleportTravel <user-pak0.pk3> <isolated-native-probe> 1000 all dry
AuditTeleportTravel <user-pak0.pk3> <isolated-native-probe> 1000 all swim
```

The ignored `.tools/teleport-travel-oracle` host calls the original exported
`BotTravel_Teleport` entry point and uses transparent public-call observers. The
relevant reference arithmetic is compiled with `-ffp-contract=off`. Only exported
signatures and independently measured behavior guided the Java implementation;
native routine bodies were not copied or mechanically translated. No original
map, game asset, engine source or native oracle artifact is bundled.
