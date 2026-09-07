# Raw AAS area traces

`AasAreaTrace.trace(map, start, end, maxAreas, maxNodeVisits)` returns immutable,
ordered `Entry(area, point)` values matching native `AAS_TraceAreas`. Coordinates
and split arithmetic are float32. Solid leaves produce no entry. Repeated visits
to the same area and zero-length boundary pieces remain present and consume
result slots. Reaching `maxAreas` stops normally; exhausting node work throws an
explicit exception. The existing coalesced `AasNavigation.trace` API is unchanged.

One native boundary rule is asymmetric. If start lies exactly on a plane and
end is in front, the crossing visits front then back, both with the start point.
A stationary on-plane query visits only back. A positive-to-plane query includes
a final zero-length back entry; a negative-to-plane query remains wholly back.
These observations are preserved because raw entry order and the cap of ten
affect fuzzy reachable-area selection.

The helper requires a validated immutable AAS map and finite float-range
coordinates. It allows 1–65,536 results and 1–100,000,000 node visits. Split
arithmetic that exceeds finite float range fails explicitly. Returned lists and
points expose no mutable query storage.

Five authored tests cover ordered crossings, solid omission, the boundary
rules, duplicate entries, result caps, a native float interpolation probe,
immutable results, cyclic input work limits and numeric limits.

`scripts/AuditAreaTraces.java` is a read-only differential over user-supplied maps
and an isolated native oracle. It mixes short fuzzy-style offsets, stationary
queries and long cross-map rays, with result caps 1, 10, 32 and 128. Across all
30 original maps, 30,000 rays produced 176,581 raw entries with zero differences
in area, order or float coordinate bits (8.7 seconds locally). Native reference
`be_aas_sample.c` was compiled unchanged with `-ffp-contract=off`; this removes
platform-specific multiply-add contraction and matches Java's float evaluation.
The authored host exposes `raw cap start3 end3`; no native routine or commercial
asset is copied into CraftQ3 or bundled.

Fuzzy-selection behavior was separately inferred through transparent wrappers
around the native point, area-trace and presence-trace APIs. The ignored
`.tools/fuzzy-oracle` compiles unchanged source with those symbols renamed solely
to let authored host wrappers log arguments/results before returning them.
Its selection model matched 2,000 randomized competing-area fixtures. Those
probes establish sampling/scoring behavior; the full reachable-area service and
entity-ground handling are maintained separately.
