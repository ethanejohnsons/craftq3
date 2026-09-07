# Bot reachable-area selection

`AasReachabilityArea` supplies original bot goal localization through
`reachableArea(origin, client)` and the reusable `fuzzyArea(origin)` query. It
borrows an `AasNavigation`, the BSP entity dictionaries, a `TraceWorld`, and an
`IntFunction<Optional<Entity>>` lookup. `Entity(modelIndex)` contains the only
live entity field required here. The host retains that model index after a null
entity update or frame invalidation: native probes still select the same mover
destination in both cases. This service does not simulate bot AI or movement.

Every reachable-area request first imports a three-unit downward trace with
crouching bounds `(-15,-15,-24)..(15,15,8)`, contents mask `65537`, and the supplied
client as the ignored entity. A nonworld entity hit requires a fraction below
one, no start-solid result, and an entity number below 1022. A world hit, clear
trace or start-solid result proceeds directly to fuzzy localization.

For a nonworld hit, a live brush model belonging to a BSP `func_plat` or
`func_bobbing` selects its first model reachability's destination. Class names
compare without case. The reachability scan preserves file order: elevator
travel type 11 uses the entire face field for its model index, while bobbing
type 19 uses its low sixteen bits. Travel team flags do not alter the base type.
The result does not depend on distance from the mover, live entity type, flags
or solid state. Other brush entity classes do not enable this selection.

If that mover lookup does not resolve the query, fuzzy localization runs at the
requested origin. A result with outgoing reachabilities is accepted. Otherwise,
only the nonworld-ground case performs an AAS crouching presence trace 800 units
down and applies fuzzy localization at its returned endpoint. This trace uses
the static pre-expanded AAS geometry; the native request disables dynamic entity
tracing with pass entity -1. Ordinary item drop/goal placement uses a different
algorithm maintained in `AasGoalLocator` and `ItemPlacement`.

## Fuzzy localization

The implementation preserves these observed steps and their order:

1. Classify the origin. Return immediately if that area has outgoing
   reachabilities; otherwise retain its nonzero area as the fallback.
2. Trace four units upward with a cap of ten raw area entries. Return the first
   reachable entry. Nonreachable entries from this preliminary ray do not
   replace the fallback.
3. Test vertical layers `+12, 0, -12` in order. Each layer traces nine rays with
   x offsets `+8, 0, -8` as the outer loop and y offsets `+8, 0, -8` as the inner
   loop. Among reachable entries from the whole layer, choose the smallest
   squared distance from the origin to the raw entry point. A strict comparison
   preserves the first candidate when distances tie. Return after the first
   layer containing a candidate.
4. If no reachable candidate exists, retain the original nonzero point area.
   When the original point was solid, retain the first nonzero area encountered
   in the 27 layer rays. Return zero if no area was encountered.

These are raw `AasAreaTrace` entries, including repeated areas and zero-length
boundary visits. Each ray stops at ten entries. Coordinates, splitting and
distance scoring use separate float32 operations. Public coordinates are
bounded to absolute value 1e9; clients accept -1 through 1023 and brush model
indices accept 0 through 255. The default node-work limit is one million per
trace, configurable from one through two million. A request performs at most
56 raw area traces plus one static presence trace and one imported ground
trace. Work exhaustion and malformed brush model metadata fail explicitly.

## Verification and provenance

Ten authored tests cover the imported trace contract, world/start-solid gating,
ground recovery distance, outgoing-area precedence, mover class and packed
reachability metadata, preliminary ordering, layer priority and distance ties,
fallback retention, input limits and traversal work exhaustion. Synthetic maps
contain only authored geometry.

`scripts/AuditReachabilityAreas.java` reads the user-supplied PK3 without extracting
or modifying it. The differential covers 30,000 sampled locations across all 30
original AAS maps, with 167,000 queries: fuzzy localization, clear/world/entity/
start-solid ground conditions, and all 17 platform/bobbing brush models. It
reported **zero area-selection differences**. The imported BSP trace in this
audit is deliberately controlled; the native AAS geometry and area algorithms
are real. Full world collision remains the host's separate `TraceWorld`
responsibility.

The ignored authored native host uses original `GetBotLibAPI` and links unchanged
ioquake3 code at commit `588393618dbc82e7207c21c6ddecca229944a03a`. Public contracts
come from
[be_ai_move.h](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/botlib/be_ai_move.h)
and [be_aas_sample.h](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/botlib/be_aas_sample.h).
Transparent wrappers logged point/area/presence query arguments and results;
controlled fixture and metadata changes occurred only in the probe's memory.
No original engine routine was copied or translated into Java.

The verified native reference compiles unchanged sample, movement and math
sources with `-ffp-contract=off`, matching Java's separate float operations.
The native ARM build with contraction enabled differed at two q3tourney5
locations, causing nine derived query differences in the same corpus. Recorded
raw entry coordinates demonstrate the cause: contraction changes a closest-area
tie and a boundary crossing. This platform-specific native difference is
explicit; the service uses one deterministic float32 policy.

An existing development oracle can run the audit with:

```sh
java -cp craftq3-core/build/classes/java/main:craftq3-assets/build/classes/java/main:craftq3-collision/build/classes/java/main:craftq3-botlib/build/classes/java/main \
  scripts/AuditReachabilityAreas.java \
  run/craftq3/games/baseq3/pak0.pk3 .tools/fuzzy-oracle/probe-unfused 1000
```

The oracle and original assets are development inputs, are ignored by version
control, and are absent from the distributed mod.
