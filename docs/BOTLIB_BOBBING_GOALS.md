# Full bobbing-platform move-to-goal validation

`AuditBobbingGoals.java` compares production `GroundMoveToGoal` with unchanged
native `BotMoveToGoal`, using the independently implemented direct platform
provider described in [BOTLIB_PLATFORMS.md](BOTLIB_PLATFORMS.md). The audit owns
only its fixture setup and comparison code. Route selection, AAS queries,
obstruction, movement commands and history updates come from production Java.

## Measured cache and contact behavior

An ordinary fresh type-19 route receives a ten-second deadline and the existing
reach-avoidance update. An occupied, unexpired avoidance slot remains unchanged;
it is not a special platform exception. An ordinary cached type-19 route may
survive a changed source area or final goal. It still requires an allowed travel
flag, an unexpired deadline (equality is retained), and a destination area that
has not yet been reached. Reaching the cached link's destination triggers route
selection even when the final goal lies farther away.

Standing contact introduces two distinct stages. Before localization, the
matching cached model link is retained, or the first matching model link is
selected. A replacement gets a ten-second deadline and preserves the stored
reach-source area, jump history and avoidance. Consequently, a failed
localization or same-area goal may observe that changed link/deadline before
returning. After successful localization, a same-area goal retains its existing
movement behavior. Otherwise an allowed, uncompleted platform link receives a
five-second standing deadline, including when its old deadline expired. A
rejected route keeps the deadline from before that refresh; failed route and
failed localization prefixes retain the standing result flag 64.

The direct provider publishes movement flags and optional deadline clearing as
explicit effects, which the outer service commits with validated output. The
audit distinguishes absent movement from a zero-speed move using nonzero seeded
EA input. This caught both exact-center boarding (an explicit zero-speed move)
and sufficiently slow waiting/arrival (preserving earlier input). Speed-ramp
cancellation is compared by float bits, including the distinct 400-based ready
boarding and 360-based waiting calculations.

Independent controlled native matrices vary cached destination, current area,
policy and deadlines 9, 10, 15 and 77 at time 10. They establish that destination
arrival invalidates ordinary and standing platform caches, while standing
refresh occurs only for an allowed, uncompleted platform route. Reproducible
request and response logs are kept under the ignored
`.tools/bobbing-goal-oracle/standing-arrival-policy.*` and
`ordinary-arrival-policy.log` paths.

## Corpus design

The driver reads all 46 original q3dm19 bobbing links and BSP model bounds
directly from the supplied PK3. It varies the three original models (7, 10, 11),
platform offsets, encoded start/end positions and boundary offsets. Twenty-two
named scenarios cover fresh/cache/expiry/equality, changed area and goals beyond
the cached destination, missing policy flags, no cached air reach, entity
contact, invalid localization, avoidance, crouching, solid collision, stale
contact flags, effective versus incoming flags, and a zero goal area.

Every accepted query compares:

- All 52 result bytes, including the untouched `0x7f` suffix after a 24-byte
  native early return.
- All published history fields, effective movement flags and retained avoidance.
- EA movement, speed, actions, view and weapon, including preservation when no
  move is emitted. The broad host begins view and weapon at zero; the separate
  direct provider corpus also checks nonzero view/weapon seeds.
- The total BSP and entity-trace callback count.

The broad fixture supplies clear, floor, solid and indexed collision replies.
Standing mode additionally overrides only the direct three-unit downward query;
ordinary AAS traversal remains active. Its three retained mover records have
authored zero-size collision bounds at their offsets. A nonzero initial update
ensures each record is linked before returning to zero; Java supplies matching
AAS links. This avoids mistaking native initial entity-link lifecycle differences
for movement-policy differences. Captured live-contact mode instead links the
twelve original BSP model bounds at their observed entity offsets.

The source AAS and BSP remain unmodified. No original assets are extracted into
the repository or included in the mod. No original VM movement or gameplay rule
is replaced by this audit.

The final production replay has zero differences in 87,009 accepted requests:

| Mode | Compared | Explicit exclusions | Differences |
| --- | ---: | ---: | ---: |
| Ordinary ground | 30,000 | 0 | 0 |
| Standing contact | 30,000 | 0 | 0 |
| Cached airborne completion | 27,009 | 2,991 | 0 |

The airborne exclusions are sampled positions for which the native AAS ground
test reports actual ground contact; they belong to the separately checked ground
domain. Accepted airborne queries include 5,416 absent-move and 21,593 issued-move
results. Ground and standing have no exclusions. Final reports are
`/tmp/craftq3-bobbing-ground-final.log`,
`/tmp/craftq3-bobbing-standing-final.log`, and
`/tmp/craftq3-bobbing-air-full.log`.

## Captured first contacts

Three captured q3dm19 first-standing requests include the actual twelve mover
records, bot input, route/avoidance history, time and two controlled contact
responses. All three match native result bytes, movement/history/avoidance and
callback counts exactly:

| Captured profile | Time | Speed | Deadline | Trace callbacks |
| --- | ---: | ---: | ---: | ---: |
| Retail qagame | 10.75 | 400 | 15.75 | 3 |
| Modern qagame with original baseline data | 10.95 | 400 | 15.95 | 3 |
| Modern qagame with matching inventory declarations | 5.05 | 326.222351 | 10.0500002 | 4 |

The final matching-inventory profile distinction is documented in
[BOTLIB_INVENTORY_COMPATIBILITY.md](BOTLIB_INVENTORY_COMPATIBILITY.md). These are
single-call reproductions with captured mover phases and controlled collision
responses, not independent native full-game simulations. The ignored input files
are `.tools/bobbing-goal-oracle/{retail,modern,matching-modern}-live-input.txt`;
the Java comparison report is `/tmp/craftq3-bobbing-live-final.log`.

## Reproduction and limits

The authored `BotBobbingGoalOracle.c` calls public native exports and observes
declared movement-state fields. `BuildBobbingGoalOracle.py` links unchanged
official ioquake3 commit `588393618dbc82e7207c21c6ddecca229944a03a` with float
contraction disabled. No native function body was used as an implementation
recipe or mechanically translated. The oracle and its source checkout remain
ignored development artifacts and are not part of CraftQ3's runtime.

After compiling module classes under Java 25:

```sh
python3 scripts/BuildBobbingGoalOracle.py
java -cp "craftq3-core/build/classes/java/main:craftq3-assets/build/classes/java/main:craftq3-collision/build/classes/java/main:craftq3-botlib/build/classes/java/main" \
  scripts/AuditBobbingGoals.java .tools/pak0-audit/games/baseq3/pak0.pk3 \
  .tools/bobbing-goal-oracle/probe 30000 ground
```

Repeat with `standing` or `air` as the final argument. `capture` replays the three
local captured input transcripts when those ignored files are present. The
matrix uses a fixed random seed, limits counts to 30,000 per invocation, bounds
native output and process lifetime, and writes reproducible commands under
`.tools/bobbing-goal-oracle/`.

These comparisons establish the measured type-19 orchestration and direct
commands. They do not establish elevator type 11, arbitrary community-map mover
behavior, or native-server collision parity. Live original-QVM gameplay checks
are separate from this controlled differential corpus.
