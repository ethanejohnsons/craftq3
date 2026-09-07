# Visible route position prediction

`BotVisiblePosition` implements the measured `BotPredictVisiblePosition` contract
behind game import 572. It predicts a point along the mover's route that is visible
from the supplied goal, without changing movement history or elementary actions.
The original guest decides what to do with that point.

The operation returns no target for a missing area, equal source and goal areas,
an unavailable route, or exhaustion of the native twenty-reach limit. The host
also returns zero for a null goal pointer. These failures leave the guest output
vector unchanged. Invalid guest ranges are rejected before collision queries.

Each iteration uses the verified movement reach selector with the current goal,
the immediately previous source area and no avoidance records. It traces from
`goal.origin` to the selected reach start, then to the reach end, using zero box
extents, content mask `0x10001`, and the goal entity as the ignored entity. No PVS
or point-contents query is made. A fraction of exactly one accepts the candidate;
solid flags and the hit entity do not change that decision. A clear start returns
before the end trace. Otherwise the end trace is always made, and entry into the
goal area accepts that endpoint even when the trace is blocked. Reaching the goal
area does not substitute the goal origin for the stored reach endpoint.

A blocked iteration advances to the reach destination and endpoint. At most
20 reaches and 40 traces are visited. The fortieth trace can still produce a
successful target; a later candidate is not visited. Unsuccessful intermediate
candidates are never written to the guest's output buffer.

## Independent native verification

`scripts/BotVisiblePositionOracle.c` is an authored host around the unchanged
public botlib API. It reuses the existing independently authored development
loader and supplies controlled BSP collision callbacks. The optional route
observers forward public `AAS_NextAreaReachability` and
`AAS_AreaTravelTimeToGoalArea` calls unchanged while logging their arguments.
They establish the previous-area filtering and route walk without reading or
translating engine function bodies.

The reference is the ignored official ioquake3 checkout at commit
`588393618dbc82e7207c21c6ddecca229944a03a`. `BuildVisiblePositionOracle.py` compiles
unchanged movement, AAS sample and vector arithmetic objects with float
contraction disabled. Native binaries and source objects are development-only;
none are linked into the mod. The supplied archive is opened directly, with only
unlinked temporary native file streams used by the authored host.

Controlled probes cover null and same-area goals, no route, PVS suppression,
clear/start-solid/all-solid responses, fractional traces, goal entity handling,
arrival into a blocked goal area, and the 39th/40th/41st trace boundary. Local
records are under ignored `.tools/visible-position-oracle/`.

`AuditVisiblePositions.java` then compares 30,000 requests across all 30 original
AAS maps. Every result, target float bit, output-preservation state, trace count
and ordered trace-request hash matches, with no PVS requests (10.846 seconds
locally). Each hash includes every start/bounds/end float word, ignored entity
and content mask in call order. The corpus varies source/goal areas and origins,
travel flags, goal entities, clear/blocked/floor responses, a single clear trace
ordinal, fractional responses and solid flags. It includes the native nonempty
stored first reach index in some zero-count areas. These are controlled collision
worlds; the counts do not establish all actual BSP visibility configurations.

Six focused helper tests cover trace geometry/order, early return, previous-area
context, goal arrival, failure write behavior and the work limit. The game host
regression checks both supported guest ABIs, twelve-byte writes with adjacent
sentinels, failure preservation, null goals and invalid output/input ranges.

```sh
python3 scripts/BuildVisiblePositionOracle.py
# With compiled core, assets, collision and botlib on the classpath:
java -cp <engine-classpath> scripts/AuditVisiblePositions.java \
  <user-pak0.pk3> .tools/visible-position-oracle/probe 1000 all
```

The game host supplies the current disabled-area policy and live BSP/entity
collision. It preserves the independent engine boundary: no Minecraft movement
or collision service is substituted for Quake visibility.
