# Suspended item jump-pad approaches

`botlib.item.JumpPadItemAreas` implements `ItemPlacement.JumpPadResolver`. Its constructor borrows
`AasNavigation`, the immutable `BspMap`, an `AasMovementPredictor.World`, a settings supplier and a
diagnostic consumer. It owns bounded geometry caches; it opens no files and owns no device or VM.
`BotlibHost` supplies its existing movement world and current physics settings to this provider.
The qagame VM continues to own actual item physics and bot decisions.

`bestArea(origin, mins, maxs)` returns a completed result: zero means no qualifying trajectory,
and a positive value is the launch approach area. It does not substitute a nearest area or stored
reachability endpoint. Each query takes one immutable settings snapshot, examines at most 4096
BSP jump pads, and predicts at most 30 frames per eligible pad. Geometry traversal and volume work
retain their providers' explicit budgets. Unavailable metadata is skipped or diagnosed; nonfinite
physical arithmetic and exhausted work budgets fail explicitly.

## Launch metadata

`JumpPadLaunch.resolve(entityIndex, gravity)` uses a zero-based BSP entity index. A valid inline
model supplies unrotated brush bounds and origin zero. The absolute-bound addition is retained,
including the observed conversion of negative zero to positive zero. The center uses float32
addition followed by multiplication by 0.5. A crouching AAS trace runs from center Z + 64 down to
the center, ignoring entity −1. A start-solid result keeps the center and reports a diagnostic;
otherwise the supplied trace endpoint is used. Launch origin is that point with Z + 0.125.

The first exact, case-sensitive `targetname` matching the trigger's `target` determines the target
point. Its classname is immaterial. Missing target origin is the zero vector; a missing target or
zero height yields no launch. Trigger `speed`, `origin` and angle keys do not replace a valid target.
Velocity uses the original brush center, independently of the trace-adjusted launch origin.

The independently measured launch arithmetic uses the full three-dimensional center-to-target
delta. Flight time is the rounded square root of height divided by half gravity; the horizontal
velocity follows the observed float normalizer and 1.1 multiplier, while vertical velocity is time
times gravity. This preserves operation rounding, including the normalizer's squared-length times
inverse-root result. Negative target height and zero gravity produce nonfinite native arithmetic;
Java rejects them explicitly. Gravity is finite and bounded to 100000. Native initialization caches
physics settings; the Java host supplies one current settings snapshot per localization query.

## Trajectory and area selection

Only exact `trigger_push` classnames participate, in BSP enumeration order. The brush bounds are
linked using crouching presence 4. A trajectory is tested only if those links contain an area with
AAS jump-pad contents bit 128. The request uses entity −1, normal presence 2, airborne state, the
measured launch velocity, no command, zero command frames, 30 prediction frames and a 0.1-second
frame duration. The target is the item's absolute bounds.

The first prediction finishing before frame 30 is decisive. Its linked jump-pad areas are ranked
by the measured [polyhedral area volume](BOTLIB_AREA_VOLUME.md). Equal volumes select the last
linked candidate; zero is eligible, negative volumes are not. There is no additional outgoing
reachability or presence filter. If every volume is negative, that first early trajectory returns
zero without trying later pads. Controlled native calls establish that the outer operation tests
the frame field, not the prediction return value or event. The Java predictor's verified target
mode always produces a completed record or an explicit error.

For original q3dm12, the armor at **(−768, −1128, 160)** is reached from trigger model `*8`.
Its launch origin is **(−768, −1728, −163.87118530273438)** and velocity is
**(0, 835.5252685546875, 728.8350219726562)**. The target is hit in frame 6. Candidate areas 4421 and
4422 both have jump-pad contents; area 4421 has the larger measured volume and becomes the goal's
approach area. The goal position remains the original suspended item position.

## Target-box prediction

`AasMovementPredictor.predictHitBox(request, absoluteMinimum, absoluteMaximum)` is a separate
operation. It requires a zero ordinary stop mask and retains the standard request/frame limits.
`predict(request)` and VM trap 318 still reject unsupported masks, including bit 2048. This overload
does not broaden arbitrary stop-mask combinations.

After each main AAS world trace, the mode intersects the traced segment against the item box
expanded by the runtime player hull. It uses the actual trace endpoint. On a hit it reports event
2048, the zero-based frame/time, current velocity, and a cleared trace record containing only the
entry fraction and point. This stop precedes fluid and ground queries. If the target is missed,
this native operation bypasses ordinary contact sliding/step/impact handling for that trace and
continues the measured frame physics and fluid/ground checks. Ordinary completion clears the trace.

`AasTargetBox` preserves independently rounded endpoint-to-plane distances before dividing them.
Near/far orientation comes from the segment direction, which matters for tiny segments with
rounded plane distances. Face interiors count, including a face hit at fraction one; exact edge
and corner entries do not. An initial overlap may yield a negative fraction and an entry behind the
start. A zero-length segment has no hit. Normal and crouching presence are the two supported hulls.

## Evidence and reproduction

Fifteen authored tests cover launch callback arguments, adjusted origin versus velocity center,
target matching, metadata failures, signed zero, ordered selection, equal and zero-volume areas,
unreachable trajectories, registry publication, target face boundaries, tiny segments, initial
overlap, early query ordering, and isolation from ordinary predictor stop masks.

Production/native comparisons use unchanged official operations with contraction disabled:

* **100010** target clipping calls match return decisions and every successful 36-byte trace.
* **50000** complete target-mode predictions match every 84-byte result, trace/presence counts and
  the hash of all observed query arguments and order. Inputs vary fluids, presence transitions,
  grounded/airborne state, commands, ceilings/inclines, contact and frame duration.
* **20000** authored launch cases compare all twelve output floats at gravity 800 and 237.5.
* All **30** original AAS maps: **6000** item/target queries match selected areas, including 259
  reachable results. All **149** trigger launch records match. BSP contents query counts and
  coordinates match exactly; the audit permits at most 0.001 units but observed zero difference.

The real q3dm12 original retail VM completed a 60-second movement audit with this host integration:
594 move-to-goal calls, 780 moving frames and 7677.109 horizontal units. The unavailable trajectory
diagnostic is gone. This movement audit does not establish that the bot collected that particular
armor or prove every navigation behavior.

The public declarations are in
[be_aas_reach.h](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/botlib/be_aas_reach.h)
and [be_aas_move.h](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/botlib/be_aas_move.h).
No native routine body was read as an implementation recipe, copied or mechanically translated.
`JumpPadItemOracle.c` and `JumpPadItemObserver.c` are authored hosts/callback observers. Their build
renames only selected LLVM definition headers to interpose transparent logging; native operation
instructions remain unchanged. The host's model-bounds import uses the separately verified
`BotModelBoundsOracle.c` contract. The target-box host supplies authored collision/contents metadata.
Neither native executable ships in the mod.

After compiling Java classes and preparing the ignored official reference checkout/build:

```sh
python3 scripts/BuildJumpPadItemOracle.py
python3 scripts/BuildAasTargetBoxOracle.py
javac -cp "$Q3_ENGINE_CLASSPATH" -d .tools/target-box-oracle scripts/TargetBoxPredictionProbe.java
COUNT=50000 python3 scripts/AuditTargetBoxPredictions.py
java -cp "$Q3_ENGINE_CLASSPATH" scripts/AuditAasTargetBox.java .tools/target-box-oracle/probe
java -cp "$Q3_ENGINE_CLASSPATH" scripts/AuditJumpPadLaunch.java /absolute/path/to/pak0.pk3 .tools/suspended-item-oracle/probe-observed
java -cp "$Q3_ENGINE_CLASSPATH" scripts/AuditJumpPadItems.java /absolute/path/to/pak0.pk3 .tools/suspended-item-oracle/probe-observed
```

`Q3_ENGINE_CLASSPATH` contains the compiled core, assets, collision and botlib classes. The final
map-audit argument can select one map. Assets are read directly from the supplied ZIP, with no
extracted installation or asset bytes written into the repository. The audit host uses temporary
anonymous file streams for its native filesystem imports. All generated native binaries, objects,
query captures and compiled audit classes stay ignored development artifacts.
