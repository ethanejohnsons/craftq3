# AAS route prediction

`botlib.aas.AasRoutePredictor` implements the original `AAS_PredictRoute` service used by import 576. It examines the immutable AAS graph with the existing cluster/portal routing caches and produces a bounded route prediction. It borrows an `AasNavigation` and `AasRouteTimes` for the same map.

```java
var predictor = new AasRoutePredictor(navigation, routeTimes);
var request = new AasRoutePredictor.Request(
    startArea, origin, goalArea, policy,
    maxAreas, maxTime, stopEvents, stopContents, stopTravelFlags, stopArea);
var result = predictor.predict(request);
result.prediction().writeTo(guestMemory, resultPointer);
int nativeReturn = result.success() ? 1 : 0;
```

`TravelPolicy` retains the caller's travel flags, presence/team policy, and disabled-area snapshot. The borrowed route-time cache retains its existing behavior and ownership. Calls on one predictor serialize access to its bounded crossed-area cache.

## Observed result and stop behavior

The helper preserves these independently measured details:

- The initial end position is the float-quantized origin; the initial end area is the requested **goal**. Contents, travel flags, event, and accumulated time begin at zero. Already being in the goal succeeds immediately.
- Native cache selection advances from each preceding reachability endpoint. Cumulative local-distance charges continue to use the **original start area's metric and original origin** for every link, including crouch/liquid scaling. Each charge also includes the stored reachability travel time.
- Stop event bits are `NO_ROUTE=1`, `USE_TRAVEL_TYPE=2`, `ENTER_CONTENTS=4`, and `ENTER_AREA=8`. No route returns false and event 1 even when that event was not requested. An observed requested event returns true. Reaching the goal also returns true. A limit reached before the goal returns false with event zero.
- A matching reachability travel flag stops at its start, in the source area, with the time accumulated before that link. A matching destination area's travel category stops at the reachability endpoint and includes that link's cost. These checks precede contents/area checks.
- Contents and area events inspect the retained crossed-area sequence before the destination. Within each inspected area, contents takes precedence over an area-number match. A contents stop uses the reachability endpoint and includes its cost; an area stop uses the reachability start and the prior cost. Both retain the previous completed link's travel flags.
- Barrier-jump and water-jump links retain a vertical trace at the start's X/Y, from start Z to end Z. Walk-off-ledge links retain a vertical trace at the end's X/Y over the same Z interval. Each has at most 32 raw AAS entries, including duplicates. Other link types have no additional entries. These sequences are derived using the separately verified `AasAreaTrace` provider.
- A positive `maxTime` is tested with a strict `>` after traversing the whole link. Zero disables this time bound. Negative nonzero time values stop after the first completed link. Reaching the goal on that link still succeeds.
- `maxAreas=0` uses the map's area count, including its reserved zero record. Positive values are capped to that count. A negative value traverses no links. These limits bound native portal cycles without inventing a successful route.

A native portal corner case is deliberately confined to prediction: after an unsuccessful direct/cache-time query, a valid portal source can still return its first stored reachability with native success 1 and time 0. This also happens with travel flags zero. Invalid, cluster-zero, or no-outgoing goals do not take that branch. An ordinary source remains unreachable. Same-area success occurs before it. The Java travel-time provider continues to return zero for this unavailable route; the prediction helper preserves the separately observed raw edge selection.

A reproducible original-map example is `q3ctf1`, source area 1423, origin `(956.2001,-1672,75.200005)`, goal 1399, and travel flags zero: the native raw routing call returns success 1, time 0, reachability 1922. A one-link prediction enters area 1422, while a second attempt reports no route. Invalid and no-outgoing goals preserve the initialized output instead.

## Guest ABI and bounds

The native layout is 36 bytes, little-endian:

| Offset | Field | Write behavior |
| --- | --- | --- |
| 0 | `endpos`, three floats | Assigned |
| 12 | `endarea`, int | Assigned |
| 16 | `stopevent`, int | Assigned |
| 20 | `endcontents`, int | Assigned |
| 24 | `endtravelflags`, int | Assigned |
| 28 | `numareas`, int | **Unassigned by native prediction; preserved** |
| 32 | `time`, int | Assigned |

The immutable `Prediction` intentionally has no `numAreas` value. Its `writeTo` validates the entire 36-byte range before changing bytes and preserves the caller's byte order, position, and limit. Hosts must not zero-fill the result before calling it.

Origins must fit finite floats. The default independent work budget is 16,384 selected links, configurable from 1 to 1,000,000. Reaching that budget throws a clear exception. Integer time overflow is checked. The crossed-area LRU retains at most 256 lists of at most 32 integers; each raw trace has a one-million-node work bound. Existing routing-cache bounds also apply. Budget exhaustion is not converted into an invented route result.

## Validation and provenance

Ten authored tests cover cumulative metrics, strict time and area limits, stop precedence and endpoint selection, invalid/same-area/disabled routing, the portal zero-time case, intermediate barrier/water-jump areas, cyclic portal caps, guest partial writes, and resource/input bounds.

`AuditRoutePrediction.java` compared **30,000 requests across all 30 original retail AAS maps**, with zero differences in success, every assigned integer, every returned float's raw bits, and the preserved `numareas` sentinel. Cases include invalid/nonrouting endpoints, ordinary and portal starts, perturbed origins, varied travel permissions, negative/zero/positive limits, combined stop masks, liquids and portal contents, and bounded cycles.

A second complete metadata comparison covered **125,701 reachabilities across 30 maps**, including **16,758 nonempty crossed-area sequences**, with exact area IDs, duplicate entries, and order. This identified and verified the additional water-jump sequence even though it was not exercised by the first prediction-request corpus.

The authored development host is `scripts/AasRoutePredictionOracle.c`. It links unchanged native botlib objects and reads the caller's ignored PK3. The public `aas_predictroute_t` layout was verified with `sizeof`/`offsetof`, and the raw portal behavior was checked independently of aggregate prediction comparisons. `scripts/AasRouteAreaObserver.c` records trace-call arguments and returned area sequences when linked to an otherwise unchanged sample object compiled with `-DAAS_TraceAreas=ObservedOriginalTraceAreas`.

For float boundary comparison, the native `be_aas_sample.c` object is compiled unchanged with `-ffp-contract=off`; remaining route objects are unchanged. This matches Java's separate float operations and the existing raw-area provider. ARM fused contraction can add or omit raw boundary visits, so this evidence does not claim parity with every compiler's contraction setting.

After building the regular Java modules and the development oracle, run:

```sh
java -cp craftq3-core/build/classes/java/main:craftq3-assets/build/classes/java/main:craftq3-botlib/build/classes/java/main \
  scripts/AuditRoutePrediction.java run/craftq3/games/baseq3/pak0.pk3 \
  .tools/route-prediction-oracle/probe-unfused 1000
```

The implementation is original Java, guided by public ABI metadata and black-box observations. No native function bodies were copied or mechanically translated. No original maps, scripts, QVMs, native binaries, or game media are bundled.

Primary references: [route API declarations](https://github.com/ioquake/ioq3/blob/main/code/botlib/be_aas_route.h), [result struct and stop-event constants](https://github.com/ioquake/ioq3/blob/main/code/botlib/be_aas.h), [AAS travel/area metadata](https://github.com/ioquake/ioq3/blob/main/code/botlib/aasfile.h), and [runtime record metadata](https://github.com/ioquake/ioq3/blob/main/code/botlib/be_aas_def.h).
