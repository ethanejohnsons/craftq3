# Moving-platform travel

Direct bobbing-platform entry and completion are implemented and independently
verified in `BobbingPlatformMovement`. The outer move-to-goal platform association,
route cache and history transitions are registered in the host and pass the
separate [full-entry validation](BOTLIB_BOBBING_GOALS.md). The direct corpus below does not by itself claim
a successful live platform ride. Elevator type 11 has a separate implementation
and validation task.

The authored `BotPlatformTravelOracle.c` links unchanged official botlib objects
with float contraction disabled. Only public headers, exact exported function
signatures and the existing move-state metadata declaration were used. The host
calls native elevator/bobbing entry and completion, `BotFuncBobStartEnd` and
`BotOnMover`, and records callbacks. It reads original BSP/AAS data directly from
the PK3 through unlinked streams. No upstream engine routine is included in the
Java runtime or mechanically translated.

## Endpoint metadata

Original q3dm19 contains 46 bobbing reachabilities referring to inline models 7,
10 and 11. Authored box/offset fixtures exercise the endpoint query independently
of actual gameplay. Ten thousand seeded cases match every output float bit:

- The low 16 face bits select the model. Bit 16 selects the X axis; otherwise bit
  17 selects Y; otherwise Z is selected. X takes priority when both bits are set.
  Tested unrelated bit 18 does not change that selection.
- The high and low signed 16-bit edge halves provide the start and end coordinates
  on that axis, including negative values and signed boundaries.
- Other coordinates use the float-rounded center of model bounds. The reported
  current position adds the updated entity offset only along the selected axis.
- Native callback observations confirm these are model bounds with zero angles.
  The authored callback handles nullable bounds/origin destinations.

The first geometry comparison uses an independent Python float model. Production
`MoverQueries.bobbing` is also exercised by the direct Java travel corpus below.
Reproduce the metadata comparison with:

```sh
python3 scripts/BuildPlatformTravelOracle.py
python3 scripts/ProbeBobbingPlatformGeometry.py run/craftq3/games/baseq3/pak0.pk3 \
  .tools/platform-travel-oracle/probe
```

Ignored logs are under `.tools/platform-travel-oracle/`. `geometry-report.json`
records 10,000 requests/responses and zero differences. The authored matrix varies
bounds, entity offsets, signed endpoint halves and competing axis bits.

## Captured request and remaining work

Retail q3dm19 selects reach 689 at time 6.85: source area 1324, destination area
1759, face 11, edge -54722796, start (-329.62915,-266.031128,-610), end
(-464,-624.499939,-99). The actual unsupported Java request is retained in the
fixed pak0 sweep log. A direct native probe with an authored clear world and a
model-11 entity at offset zero returns a waiting approach: type 2, flags 4,
direction (-0.0417075753,-0.999129832,0), speed 360, one obstruction trace.
The same controlled completion returns speed 400 and zero traces. These two
observations do not establish the real moving-platform collision or full
move-to-goal behavior.

The direct thresholds and commands are now verified below. The remaining work is
movement-state deadlines, cached platform association, and full move-to-goal
dispatch. Elevator type 11 needs its own corpus. Existing outer guards remain
until those behaviors are implemented and compared.

## Independent platform-top boundary audit

`AuditMoverQueries.java` compares production `MoverQueries.onMover` with unchanged
native `BotOnMover` through the capture-only platform oracle. All 25,000 seeded
requests match. This covers original q3dm19 reach 689 (model 11), finite model
bounds and entity origins at ordinary and million-unit scales, exact horizontal
16-unit contacts and adjacent float values, missing type-4 model origins,
matching and differing hit models, ignored entities, and clear/start-solid/all-solid
trace outcomes. It compares the returned classification; this independent audit
does not compare callback counts or establish full travel execution.

```sh
java -cp "craftq3-core/build/classes/java/main:craftq3-assets/build/classes/java/main:craftq3-collision/build/classes/java/main:craftq3-botlib/build/classes/java/main" \
  scripts/AuditMoverQueries.java .tools/pak0-audit/games/baseq3/pak0.pk3 \
  .tools/platform-travel-oracle/probe 25000
```

The oracle uses official ioquake3 commit
`588393618dbc82e7207c21c6ddecca229944a03a`, unchanged exported routines and an
authored bounds/entity/trace host. No engine routine bodies were used to implement
the Java query, and neither original map data nor native binaries are bundled.
The local report is `/tmp/craftq3-on-mover-boundaries.log`.


## Production contracts

`MoverQueries` borrows `TraceWorld`, model-bounds lookup and retained entity
metadata lookup. It searches at most 1,024 records in ascending entity order,
including entity zero. The first type-4 record with a matching model supplies the
offset, regardless of solid type. Native observations confirm that frame
invalidation and a null update retain this metadata; the host must provide the
retained record even after collision links are removed. `Entity` contains only
`type`, `modelIndex` and `origin`. `ModelBounds` contains float-rounded `min` and
`max`. Missing mover metadata produces an empty geometry query and an explicit
unsupported direct-travel exception, before movement commands are issued.

`onMover` translates the complete model bounds by the entity offset, accepts
inclusive XY bounds expanded by 16, and does not gate on height. It traces from
origin Z+24 to Z−48 with hull (−16,−16,−8)..(16,16,8), mask 65537 and the caller's
ignored entity. A non-solid-start hit with the matching model is accepted even
at fraction one. The hit record itself need not have mover type 4. Invalid
entity limits, non-finite vectors, reversed bounds and float overflow fail
explicitly; source arrays and native pointers are never exposed.

The direct provider API is:

```java
new BobbingPlatformMovement(movers, obstruction, obstacles::barrierJump);
execute(input, effectiveFlags, sourceArea, reach);
finish(input, effectiveFlags, sourceArea, reach);
```

Both return `Output(result, Optional<Move>, actionFlags, movementFlags,
clearReachDeadline)`, which implements `FlaggedReachMovementOutput`. The former
four-argument constructor remains available with `clearReachDeadline=false`. An absent move preserves previous
EA direction/speed. Runtime movement flags are an explicit replacement and must
be committed atomically with successful outer execution. An explicit
`clearReachDeadline=true` similarly requests deadline zero; unrelated travel
providers default to false. Semantic jump actions
must use the existing elementary jump service. No view, weapon or random request
is emitted by these direct routines. `MovementObstruction.check` has a boolean
`checkBottom` overload; existing three-argument calls retain their prior behavior.

## Direct behavior

Entry first checks platform contact. On the mover, it departs when the current
platform position is less than 24 units from its encoded end; otherwise it
centers horizontally with a strict 10-unit deadband. Departure requests a
normalized horizontal endpoint direction at speed 400 and checks a speed-100
barrier. Centering uses the measured float cancellation and caps speed at 400.

Off the mover, proximity within 64 units of the reach endpoint uses the raw 3D
endpoint delta and a speed ramp capped at 360, and explicitly clears the reach
deadline even when no EA move is issued. This move is issued only when its speed
exceeds 5; a speed of exactly 5 preserves prior EA movement. Otherwise the mover must be within
16 units, inclusively, of its encoded start to allow boarding. While it is away,
the result has type 2 and wait flag 4. Boarding switches from the reach start to
the platform center when the start is less than 20 units away, the center is
closer, or the two normalized target directions have a negative dot product.
The center target retains the reach-start height for swimming. Waiting and
boarding use the cropped forward obstruction query with `checkBottom=false`.
All ready-boarding moves use the measured 400-based float cancellation; waiting
uses 360-based cancellation. Boarding at the exact center explicitly issues a
zero-direction, zero-speed move. Waiting preserves prior movement whenever its
computed speed is at most 5, while still performing the ordinary barrier check.
Algebraic rewrites of the speed ramps can change float bits.

Swimming off-platform adds result flag 2. The ordinary waiting/boarding branch
then preserves earlier EA movement and skips barrier checks, while near-end
travel still requests its raw move. On-platform entry behavior is unchanged by
swimming. Successful dry barrier checks replace the earlier movement with the
normalized horizontal direction and speed 50 (or departure speed 100), emit jump,
and set runtime flag 1 while preserving the planned result direction.

Completion has no contact, obstruction or barrier query. Within a strict 16
units of the encoded platform end it returns the raw `current − encodedEnd`
vector; its speed comes independently from the bot-to-reach-end distance and
caps at 360. It issues that move only when speed exceeds 5, and adds flag 2 for
swimming. Outside this range it centers with a strict 5-unit deadband; swimming
includes the reach-start height, while dry centering is horizontal.

## Direct corpus and focused tests

The corrected production direct provider passes 240,000 comparisons with zero
differences in all 52 result bytes, EA direction/speed float bits, actions, runtime
movement flags, seeded reach deadline and BSP callback count. This consists of 30,000 entry and 30,000 completion
requests over all 46 original q3dm19 bobbing links, plus the same counts with
independently authored flat AAS geometry and X/Y/Z metadata fixtures. Original
assets remain direct PK3 reads. The fixture corpus does not substitute original
map collision: its purpose is controlled contact and barrier outcomes.
An additional 30,000 requests each target near-start waiting and near-end
arrival on both original and authored geometry, preserving seeded EA state at
the speed-5 deadbands. These targeted cases exposed gaps in the broader random
distribution and are retained as separate repeatable modes.

The final corpus includes 5,656 successful barrier checks, prior movement and action
state, standing/crouch presence, dry/swimming flags, moving offsets, endpoint and
contact boundaries, and clear/blocked/start-solid outcomes. Every request seeds
EA view and weapon and verifies their preservation, as well as zero random draws.
The direct host seeds deadline 77, so deadline-clearing behavior cannot hide
behind a zero-initialized native state. Nineteen new focused tests cover metadata/contact boundaries, waiting, boarding,
centering, swimming, raw completion output, absent moves, flags and missing
movers. Nineteen existing ground/barrier tests pass with the shared obstruction
overload.

Run the following with Java 25 after building module classes. Repeat with
`finish` as the last argument; repeat both modes with
`-Dcraftq3.audit.fixture=true` before `-cp` for authored fixtures:

```sh
java -cp "craftq3-core/build/classes/java/main:craftq3-assets/build/classes/java/main:craftq3-collision/build/classes/java/main:craftq3-botlib/build/classes/java/main" \
  scripts/AuditBobbingTravel.java run/craftq3/games/baseq3/pak0.pk3 \
  .tools/platform-travel-oracle/probe 30000
```

For the targeted entry modes, add either `-Dcraftq3.audit.waitingBoundary=true`
or `-Dcraftq3.audit.arrivalBoundary=true`, and repeat each with the fixture flag.
The eight final logs are `/tmp/craftq3-bobbing-{entry,finish,waiting,arrival}-deadband.log`
and the corresponding `fixture-` prefixed names. Physics remains in the
original guest. The direct helper consumes the configured obstacle provider;
these observations establish neither full platform routing nor a successful
live platform ride.

## Observed original-guest rides

`scripts/AuditPlatformRides.java` runs the ordinary original-guest bot audit and
adds read-only observation after each 50 ms server frame. It reads canonical
playerState ground entity, position, movement mode, teleport flag and health.
Script-confined reflection reads the already located shared-entity array through
the selected `GameAbi`: linked status, mover type, inline model and current
origin. Only models belonging to BSP `func_bobbing` or `func_plat` entities count.
It does not issue additional traces, change player commands, alter entity state,
or modify production code.

The JSONL log records boarding, every grounded contact sample, departure reason,
deaths and a summary. A qualified ride requires at least five consecutive samples
over 200 ms, at least 32 units of vertical range for both player and mover, and
at most one unit of per-step difference between their vertical displacements.
Changing ground entity, dying, changing movement mode or toggling the teleport
bit closes an episode. An episode still in progress at the audit limit is marked
`audit-end`, rather than a completed departure. This criterion measures observed
carriage and does not infer it from total bot movement or a selected route.

All three 60-second q3dm19 profiles pass the optional ride assertion using the
inspected 826-test runtime snapshot, seed 42, skill 3 and Sarge. Each observed
ride uses inline model 11 and ends by loss of ground contact:

| Guest/assets profile | Entity | Ground-contact interval | Samples | Player net Z | Mover net Z |
| --- | ---: | --- | ---: | ---: | ---: |
| Retail pak0 | 146 | 10.700–13.650 s | 60 | +581.7237 | +581.72375 |
| Source-built modern VM with pak0 bot data | 82 | 10.750–13.500 s | 56 | +583.7385 | +583.7385 |
| Modern VM with matching official inventory header | 82 | 4.800–7.500 s | 55 | +578.3410 | +578.34094 |

Every ride's maximum per-step vertical mismatch is 0.000030517578125 units.
The runs observe one, one and two subsequent deaths, respectively; this is
recorded separately from ride completion and makes no claim of safe map-wide
navigation. The modern inventory override is confined to the QA virtual mount.
Logs are `/tmp/craftq3-platform-rides/{retail,modern,matching-modern}.{log,jsonl}`.
The runtime snapshot predates the two low-speed deadband corrections documented
above; its successful ride observations remain limited to those exact captures.

Reproduce using a built runtime classpath (or the inspected snapshot):

```sh
java -Dcraftq3.audit.requirePlatformRide=true \
  -cp '/tmp/craftq3-standing-bobbing-runtime/*' scripts/AuditPlatformRides.java \
  .tools/pak0-audit/games q3dm19 /tmp/q3dm19-rides.jsonl
```

An optional fourth argument supplies a source-built qagame QVM. The existing
`craftq3.audit.botInventoryHeader`, `botMilliseconds`, `botName`/`botNames`,
`botSkill` and `seed` properties select bounded QA variations. Duration is limited
to 500–300,000 ms and one to seven bots. `requirePlatformRide` fails if no episode
meets the stated ride criterion; movement alone cannot satisfy it.


## Native outer cache and contact observations

`BotBobbingGoalOracle.c` is an authored full `BotMoveToGoal` host derived from the
existing authored ground-goal observer. It links the same unchanged strict
native objects. `BuildBobbingGoalOracle.py` builds its separate ignored binary;
no shared native object or original asset is patched. It records the full result,
all published movement-history fields, avoidance, EA commands and collision
callback arguments. Controlled `groundhit` and `platformhit` commands replace
only the three-unit direct BSP ground query or the direct platform-contact hull,
respectively. Other BSP collision remains explicitly clear/floor/solid or an
indexed reply; native AAS traversal remains active.

Measured cache rules are:

- A fresh ordinary type-19 selection receives deadline `now+10` and the normal
  avoidance attempt (`now+6` when the single avoidance slot is free or expired).
  A different occupied, unexpired avoidance slot is preserved.
- Cached type 19 ignores changes in current area and goal area. It still requires
  `deadline >= now`, an allowed FUNCBOB travel flag, and a destination different
  from the current area. Expired, denied or arrived entries follow ordinary
  route selection. This exception does not require contact.
- The direct near-end entry effect clears the deadline after successful output;
  the outer layer must publish the explicit provider request. A blocked result
  still follows the independently measured outer timeout adjustment.

Standing contact accepts a real entity ID even at trace fraction one, but rejects
start-solid hits. World 1022 and no-entity 1023 are ignored. Ordinary entity hits
write only the first 24 result bytes, with `blocked=1`, the entity ID and flags
32; prior history and EA movement are retained. For original q3dm19, models
1–6, 8, 9 and 12 follow this ordinary blocked path. Only models 7, 10 and 11 have
bobbing reach associations; their first matching reaches are 394, 567 and 554.
The hit record need not have type 4, although the separate mover-origin lookup
requires a retained type-4 record.

For a recognized bobbing contact, an existing type-19 reach with the same model
is retained as the contact candidate; other travel types do not qualify even if
their face field matches. If the cache does not qualify, the first matching reach
is selected and deadline `now+10` is written **before** fuzzy localization. This
preserves the old reach source, jump reach and avoidance. If localization fails,
the first 24 result bytes contain failure 1, type 8, blocked 1 and flags 64;
current area becomes zero but the remaining state retains those earlier contact
updates. A matching cached contact makes no deadline update at this early stage.

Same-area goal handling takes precedence over the later contact refresh. It
clears last reach and last area while retaining whichever deadline existed after
the early contact stage. Otherwise a valid contact refreshes the selected reach
with deadline `now+5` only when its travel policy is allowed and its destination
differs from the current area. Rejected policy preserves the pre-refresh
deadline; destination arrival causes normal route selection. The final ordinary
travel output replaces the temporary contact result flag. Successful travel sets
current/last area from fuzzy localization, the requested goal area and current
origin, while preserving the stored reach source/jump/avoid fields when retained.

The separate full-goal audit compares 30,000 ground and 30,000 standing requests
without exclusions, plus 27,009 airborne requests with 2,991 actual-ground
samples excluded. Every result byte, retained EA effect, history field and
callback count matches; all three phase-accurate live capture replays also match.
See [the full-goal report](BOTLIB_BOBBING_GOALS.md) for its independent fixtures
and reproduction commands.

Focused native matrices are kept under `.tools/bobbing-goal-oracle/`:
`ordinary-cache-matrix.log`, `cache-standing-matrix.log`,
`standing-type-boundary.log`, `standing-prelocalization-order.log` and
`missing-model-links.log`. These are controlled behavioral observations; the
separate full production/native audit covers the composed Java implementation.

## Captured first-contact replays

`ReplayBobbingContacts.py` reconstructs native commands from the ignored captured
host contact logs, including all 12 retained mover origins. It copies no engine
routines and embeds no original assets. With the captured ground result and a
controlled matching platform-top result, the native outputs are:

| Profile | Time | Fuzzy area | Retained reach/source | Deadline | Move speed |
| --- | ---: | ---: | --- | ---: | ---: |
| Retail | 10.75 | 1456 | 695 / 1403 | 15.75 | 400 |
| Modern | 10.95 | 2063 | 790 / 1730 | 15.9499998 | 400 |
| Modern with matching bot inventory data | 5.05 | 1733 | 790 / 1730 | 10.0500002 | 326.222351 |

All three return a full 52-byte type-19 result, runtime flags 2, jump reach zero,
unchanged avoidance and current last-origin. Logs and replay input files are
`.tools/bobbing-goal-oracle/{retail,modern,matching-modern}-live-*`. These replays
use the actual platform phase and captured contact state; their other collision
callbacks are controlled, so they do not constitute uncontrolled native gameplay.
