# Remote presentation time

`client.RemoteServerClock` provides the presentation clock for one remote
gamestate. It has no socket, cgame, renderer, cvar or command-buffer dependency.
The network host calls `snapshot(serverTime, flags)` for each accepted valid
snapshot in message order, then `frame(realtime, timeNudge, timescale)` once per
engine frame after processing arrivals. `frame` returns `OptionalInt.empty()`
while primed and the cgame time after activation. Immutable `state()` exposes
activation, latest snapshot metadata, pending-arrival status, offset, current
time, previous processed snapshot time and the extrapolation latch.

The host must call `reset()` on each new gamestate. The supplied realtime is
already the engine frame clock; the helper does not multiply it by timescale.
Repeated snapshot timestamps still represent new arrivals. When several arrive
before a frame, the latest supplies the one pending correction. Flags are the
wire's unsigned byte. Only valid snapshots should be announced.

## Observed remote rules

- A primed clock consumes a pending snapshot. Flag `SNAPFLAG_NOT_ACTIVE` (`2`)
  leaves it primed. The first active snapshot sets the offset to
  `snapshotTime - realtime` and seeds the previous presentation time from the
  snapshot. It does not also perform ordinary offset adjustment on that frame.
- An active frame checks that the latest snapshot time has not moved backward
  relative to the preceding frame's snapshot. A new gamestate needs an explicit
  reset; silently treating backward time as a new level would mask a protocol
  or lifecycle error.
- The unnudged estimate is `realtime + offset`. The candidate presentation time
  subtracts `timeNudge` clamped to `[-30, 30]`, then is clamped to at least the
  preceding presentation time. On the first frame this also prevents positive
  nudge from preceding the initial snapshot.
- Extrapolation is latched when the **unnudged** estimate is at least
  `snapshotTime - 5`. An intervening frame does not clear that latch.
- A pending snapshot adjusts the offset **after** choosing this frame's time.
  An absolute offset error strictly greater than `500` resets the offset and
  both presentation-time values to the snapshot time. This explicit reset can
  move time backward despite the ordinary frame clamp.
- Error strictly greater than `100`, through `500` inclusive, averages the old
  and target offsets. An odd negative sum rounds downward, as observed in the
  native signed shift. The reset and average paths preserve the extrapolation
  latch.
- At error at most `100`, timescale exactly `0` or `1` enables fine correction:
  a latched extrapolation subtracts `2` and clears the latch; otherwise the
  offset increases by `1`. Other finite timescale values skip fine correction
  and retain the latch. Timescale does not scale the helper's realtime input.
- Frames without a new snapshot do not adjust the offset. Once active, snapshot
  flag `2` does not put the connection back into primed state.

The helper deliberately covers remote live presentation. Native demo/timedemo
controls, local-server pause rules and initial-snapshot console/action side
effects remain host concerns. Nonfinite timescale, invalid flags and arithmetic
outside the supported signed engine-time range fail explicitly. Frame failure
does not partly publish clock changes. This avoids depending on undefined C
signed overflow; the native comparison corpus stays within representable
arithmetic.

## Independent observation and validation

`scripts/RemoteClockOracle.c` is an authored CPU-only host. It includes the
original public `client.h` layout declarations, defines recording/no-op host
callbacks and links the unchanged native `cl_cgame.c` object. The inspected
native information was public structure metadata, exported symbols and the
definition signatures of `CL_FirstSnapshot`, `CL_AdjustTimeDelta` and
`CL_SetCGameTime`. No native routine body was read, copied or translated.
The source baseline is ioquake3 commit
`588393618dbc82e7207c21c6ddecca229944a03a`. No game asset, VM, socket, GPU context
or window is needed for these observations.

The host accepts `reset`, `snapshot <time> <flags>` and
`tick <realtime> <nudge> <timescale>`. Additional `seed`, `settings`, `first`,
`adjust` and `frame` commands allow controlled direct boundary probes. Each
reply reports complete relevant native clock state and explicit native errors.

`scripts/AuditRemoteClock.java` drives the production helper and unchanged native
entry point through the same stateful sequence. The fixed-seed corpus passed
**1,000 gamestate lifetimes, 1,426,660 operations and 1,005,000 frames** with
zero activation, offset, time, prior-snapshot, pending-arrival or extrapolation
differences. This includes inactive first snapshots, repeated and batched
arrivals, latency/jitter/stalls, positive and negative offsets, offset changes,
all nudge boundaries, exact and neighboring timescale values, and **1,000
matching backward-time rejections**.
Ten focused authored tests cover the threshold/timing contracts, reset and
atomic invalid-input behavior independently of that corpus.
The full core/client checkpoint also passed: **152 core tests and 80 client
tests**, with zero failures, errors or skips and Java 25 compilation. Its log is
`/tmp/craftq3-remote-clock-client-tests.log`.

Reproduce from the repository root, with the ordinary client classes compiled:

```sh
python3 scripts/BuildRemoteClockOracle.py
java -cp craftq3-client/build/classes/java/main scripts/AuditRemoteClock.java
```

The build script requires the existing ignored native checkout and macOS Clang
dead-strip linkage. Its compiled object is private to `.tools/remote-clock-oracle`
and does not modify shared native observers. The default corpus is 1,000
lifetimes of 1,000 generated frames; optional arguments are native executable,
lifetime count and generated-frame count. Current run log:
`/tmp/craftq3-remote-clock-million.log`.
