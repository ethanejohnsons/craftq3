# Ground move-to-goal differential audit

`scripts/AuditGroundGoals.java` checks the production `GroundMoveToGoal` service
against an isolated original botlib host. It loads original AAS from a user-owned
PK3, composes the actual Java route, fuzzy localization, ground contact, obstacle,
and ground travel providers, and supplies identical movement inputs to both sides.
The oracle invokes `BotMoveToGoal`; Java does not substitute a generic route or
movement policy.

The native objects are compiled from unchanged ioquake3 source at commit
`588393618dbc82e7207c21c6ddecca229944a03a`. The relevant movement, AAS sampling,
and vector math objects use `-ffp-contract=off` so separate float32 arithmetic is
compared consistently. The ignored authored host supplies controlled BSP callbacks
and reads the original AAS. Native source bodies are not copied or mechanically
translated into the production implementation. No native reference binaries or
commercial map data are bundled.

Each case resets its movement handle and elementary-action state, initializes the
original 68-byte movement input, and seeds explicit history, effective flags,
avoidance, and optional avoid spots. Time is fixed at 10 seconds. The result buffer
is prefilled with `0x7f` to distinguish full 52-byte writes from native early returns
that write only the first 24 bytes. The comparison checks:

- Every result byte, including the untouched suffix on early returns.
- Current/previous area, goal, cached reach, reach area, jump reach, deadline,
  previous origin, and effective movement flags.
- Avoided reach, expiry, and attempt count.
- Elementary-action direction, speed and flags, with exact float bits.
- BSP trace call count.

The 22 scenarios cover fresh routes, retained routes, changed source or goal,
expired and exactly-current deadlines, disabled travel policies, same-area goals,
zero-area goals, airborne inputs without a cached reach, invalid current areas,
stale contact flags, raw initialization flags differing from effective state,
crouch presence, current/expired avoidance, and avoid spots. BSP fixtures include
clear collision, a horizontal world floor, a nonworld entity floor, and all-solid
responses. These are controlled callbacks; this audit does not establish original
BSP collision equivalence or moving-platform behavior.

The initial WALK/CROUCH 30,000-request run across all 30 original maps compared **27,823 supported
cases with zero mismatches** in 11.099 seconds locally. The other 2,177 requests
raised explicit unsupported-operation diagnostics, and the audit verified that
each left the movement snapshot unchanged. Exclusions were 12 airborne jump-pad
contacts and selected travel types: 4 (13), 5 (21), 7 (1,221), 8 (418), 9 (18),
10 (134), 18 (339), and 19 (1). They are not counted as matching behavior.

This corpus exposed the need to query AAS ground contact before checking the BSP
entity beneath the bot. A positive result adds the ground flag, while a negative
result preserves an existing ground flag. It also exposed airborne jump-pad
contact as a separate execution path, which remains explicitly unsupported by
this bounded ground provider. The oracle resets elementary actions twice between
independent cases because one native reset retains prior-jump bookkeeping.

Run with compiled Java25 module classes and a locally built ignored oracle:

```sh
java -cp craftq3-core/build/classes/java/main:craftq3-assets/build/classes/java/main:craftq3-collision/build/classes/java/main:craftq3-botlib/build/classes/java/main \
  scripts/AuditGroundGoals.java /path/to/pak0.pk3 /path/to/native-oracle 1000
```

The optional fourth argument restricts the audit to one map. The script reports
the compared scenarios and explicit exclusions, prints reproducible native
commands for each distinct mismatch category, and fails on any discrepancy.

## Cached airborne comparison

The optional final argument `air` selects cached airborne requests; use `all` as
the preceding map argument to retain all 30 maps. The same seeded positions are
available with `air-crouch`, which replaces WALK travel-type metadata with CROUCH
in Java and patches only the selected native link before each request. Geometry,
state layout and native execution remain unchanged. Original PK3 bytes are never
modified.

Each 30,000-sample run compared 26,038 supported requests exactly in every result
byte, retained history/flag/avoidance field, elementary-action float bit and BSP
trace count. WALK took 10.636 seconds locally; forced CROUCH took 10.616 seconds.
Each run excluded 1,451 AAS-grounded samples, 2,497 source areas without a selected
WALK/CROUCH link and 14 explicit airborne jump-pad contacts. The corpus includes
zero or stale retained areas/goals, expired deadlines, disabled travel masks,
slow-walk and stale contact flags, clear/solid/entity collision, timed avoidance,
avoid spots and both presence types. It confirmed that airborne WALK reuses the
ordinary executor while airborne CROUCH writes a travel-type prefix and no
command. Both retain cached state except last origin.

```sh
java -cp craftq3-core/build/classes/java/main:craftq3-assets/build/classes/java/main:craftq3-collision/build/classes/java/main:craftq3-botlib/build/classes/java/main \
  scripts/AuditGroundGoals.java /path/to/pak0.pk3 /path/to/native-oracle 1000 all air
```

Replacing `air` with `air-crouch` runs the metadata-controlled CROUCH comparison.
Unsupported diagnostics retain full reproduction context; the audit groups their
stable `UnsupportedMovement.reason()` values instead of unbounded case strings.

## Registered grounded jump-pad executor

The current audit also registers the independently verified `JumpPadMovement`
through the optional additional-executor map. The repeated 22-scenario corpus now
compares **28,162 supported requests with zero mismatches** in 11.036 seconds locally.
All 339 grounded jump-pad cases previously excluded are now included. The remaining
1,838 exclusions are airborne jump-pad contact (12) and selected travel types: 4 (13),
5 (21), 7 (1,221), 8 (418), 9 (18), 10 (134), 19 (1). The audit still verifies unchanged state
on every unsupported request. Cached airborne audit modes remain unchanged and do
not claim jump-pad flight or contact behavior.

## Integrated airborne jump-pad dispatch

The final executor map now includes `JumpPadMovement.finish` and verified backward
velocity contact selection. The `air-pad` mode samples actual jump-pad links,
varied airborne origins/velocities, retained state, goals, policy masks, avoidance
and controlled collision. All 19,888 included requests across 21 eligible maps
matched exactly; 1,112 AAS-grounded samples were excluded and nine maps had no
eligible link. This comparison includes every output byte, history/avoidance
field, action float/flag and imported BSP trace count (6.871 seconds locally).

The repeated ground corpus now compares 28,174 requests exactly, excluding 1,826
other travel types. Cached WALK and controlled CROUCH regressions each compare
26,052 requests exactly, with their 14 formerly excluded contact cases now included.
They each still exclude 1,451 grounded samples and 2,497 source areas without a
WALK/CROUCH link. The standalone contact-selector corpus and precise native
query-arithmetic observations are documented in BOTLIB_JUMP_PAD_CONTACT.md.

## Ledge entry and completion integration

Both maps now register the independently verified ledge 7 providers. The complete
22-scenario ground run compares 29,395 requests across 30 maps exactly, with 605
explicit exclusions: types 4 (13), 5 (21), 8 (418), 9 (18), 10 (134), 19 (1). The `air-ledge`
mode compares 29,250 requests exactly, excluding 750 AAS-grounded samples. Ground
and airborne runs took 15.527 and 13.152 seconds locally.

This corpus exposed the completed-reach blocked deadline rule: a blocked output
subtracts one second from the deadline, including already-negative deadlines.
Same-area movement and early blocked returns leave it unchanged. It also found
one reference-only float discrepancy from an ARM fused-operation dependency;
compiling unchanged `be_aas_move.c` with contraction disabled produced exact parity
without changing the Java ledge calculation. The authored build script now
includes that dependency in its strict reference objects.


## Teleport extension

After type-10 registration, the unchanged 30-map ground scenario matrix compares
29,529 requests exactly and explicitly excludes 471 other travel requests. All
134 formerly excluded teleports now compare. Targeted ground teleport source
areas compare 10,999 requests with one jump-type-5 exclusion; targeted airborne
teleport caches compare 10,704 requests after excluding 296 grounded samples.
Both targeted modes alternate effective `TELEPORTED` flag 32 over the 22-scenario
matrix. Full result bytes, untouched suffixes, history, avoidance, EA float bits
and callback counts match the unchanged native operation. See
[BOTLIB_TELEPORT_GOALS.md](BOTLIB_TELEPORT_GOALS.md) for exact commands and logs.


## Barrier and seeded-input extension

The optional barrier executors and independent action output now compare exactly
in 26,849 targeted ground requests, 25,341 cached-air requests and 29,542 broad
30-map ground requests. Every targeted case begins with nonzero EA movement and
explicit prior action bits. The ground set contains 1,703 jump-only results; the
air set contains 17,440 full-result waits that preserve earlier movement.
Near-start source sampling remains included after independently fixing the
retained zero-count reach index and exact .125 presence boundary. Remaining
exclusions are explicit other travel or AAS-grounded samples, as detailed in
[BOTLIB_BARRIER_GOALS.md](BOTLIB_BARRIER_GOALS.md).

The authored native `goal` command accepts an optional prior EA direction, speed
and action seed. The driver clears the initialized client between independent
requests; initialization must precede the reset because a reset movement handle
initially names client zero. The Java audit uses the verified elementary-action
service to apply independent output actions, including jump history semantics.
