# Teleport move-to-goal orchestration

This document records the full native `BotMoveToGoal` operation around TELEPORT
travel type 10. The direct approach provider is documented separately in
[BOTLIB_TELEPORT_TRAVEL.md](BOTLIB_TELEPORT_TRAVEL.md). The original game VM owns
trigger contact and teleportation; these services only retain navigation state
and issue the observed elementary movement requests.

## Native observations

Grounded teleport selection uses the ordinary route-cache rules: the current
area and goal must match the cached values, the deadline is inclusive, and the
base teleport travel flag must remain allowed. Newly selected teleport reaches
receive a five-second deadline and a six-second avoidance attempt. Reselecting
resets the jump-reach field; retaining a reach preserves it and the original
reach-area field. Changing the current area or goal, expiring the deadline or
denying teleport travel selects again. A blocked approach subtracts one second
from the reach deadline through the ordinary completed-reach rule.

Effective movement flag `TELEPORTED` (32) does not clear the flag or the cached
reach. For a grounded teleport, normal selection and history updates still
happen, but approach is suppressed: the operation writes the full 52-byte result,
clears its movement fields, retains the complete tagged travel type and emits no
EA command or approach obstruction query. Same-area goal movement precedes this
branch and behaves normally; other travel types also continue normally.

Cached airborne teleport completion emits no command. It writes only the first
24 result bytes, including the complete tagged teleport type, leaving the
caller's remaining bytes untouched. It preserves cache fields, deadline,
avoidance and flag 32, and updates only the last origin. It does not revalidate
the goal, deadline or travel mask. A newly contacted jump pad can replace that
cached reach before dispatch through the independently verified contact rule.

## Reference method

The authored `scripts/BotGroundGoalOracle.c` driver invokes the unchanged native
full operation. Its metadata-only state setup uses
`scripts/BotMoveStateMetadata.h`; no implementation routine is copied or
translated. Build it with `scripts/BuildGroundGoalOracle.py` against the existing
ignored official reference checkout. Relevant arithmetic dependencies use
`-ffp-contract=off`, matching Java float operations on this host. The driver reads
original user assets directly from PK3 files and uses explicitly controlled BSP
callbacks. No original assets, source routines or native oracle binaries are
bundled.

Initial controlled probes covered 116 operations: effective flags 0, 2, 32 and
34; fresh and retained reaches; five cached travel types; deadline equality and
expiry; changed and same-area goals; zero goals; denied travel masks; and tagged
teleport metadata. Reproducible local inputs and outputs are retained in the
ignored `.tools/teleport-goal-oracle/{matrix,cache,policy}.py` and corresponding
logs. This establishes the policy observations above; the independent Java integration
comparison follows.


## Integration and corpus

`GroundMoveToGoal` accepts `Map.of(10, teleport::execute)` in the existing optional
ground executor map. Its constructors and the existing executor interface are
unchanged. Airborne type 10 needs no registered executor. The grounded flag-32
branch returns `Optional.empty()` for the command before calling the direct
active-entry provider, whose output type represents an issued move command.
This preserves any previously accumulated input instead of issuing a zero-speed
move. Unregistered active ground travel still fails before publishing state.

Four additional authored outer-operation tests cover ordinary teleport deadlines
and blocked adjustment, flag-32 suppression with full result clearing, inclusive
cache retention and reselection, policy denial, same-area movement, tagged
metadata, and airborne arbitrary-suffix preservation. Together with the existing
movement tests and seven direct-provider tests, 31 focused tests passed.

The production full-operation comparison uses `scripts/AuditGroundGoals.java`:

- Targeted ground teleport areas: 10,999 exact requests across all 11 eligible
  original maps, with one explicit jump-type-5 exclusion. Alternating blocks of
  22 policy scenarios include and omit effective flag 32.
- Targeted airborne teleport caches: 10,704 exact requests on the same 11 maps,
  excluding 296 samples classified as grounded by native AAS geometry.
- Full ground regression across all 30 original maps: 29,529 exact requests,
  with 471 explicit other-travel exclusions (types 4, 5, 8, 9 and 19).

These compare all output bytes, including preserved partial-write suffixes,
history fields, flags, avoidance records, elementary-action float bits and
BSP callback counts. The targeted runs completed in 4.142 and 5.030 seconds; the
broad regression completed in 13.042 seconds. The callback worlds include clear,
floor, solid and ordinary-entity cases. They are controlled collision conditions,
not a claim that these counts prove every real BSP collision configuration.

With Java 25 and the compiled core/assets/collision/botlib module classpath:

```text
AuditGroundGoals <user-pak0.pk3> <isolated-native-probe> 1000 all teleport
AuditGroundGoals <user-pak0.pk3> <isolated-native-probe> 1000 all air-teleport
AuditGroundGoals <user-pak0.pk3> <isolated-native-probe> 1000 all
```

The exact local logs are `/tmp/craftq3-teleport-ground-corpus.log`,
`/tmp/craftq3-teleport-air-corpus.log` and
`/tmp/craftq3-teleport-ground-regression.log`. No original map bytes are committed.
