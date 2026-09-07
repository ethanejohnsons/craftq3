# Demo presentation clock

`client.DemoServerClock` implements demo priming, render time, synchronous packet
read-ahead and timedemo counters. It is separate from `RemoteServerClock`:
recorded snapshot arrivals do not apply the live network offset corrections.
Demo record I/O, message parsing, cgame lifecycle and rendering belong to its host.

## Public API

```java
void snapshot(int serverTime, int flags);
void gamestate();
void end();
void reset();
OptionalInt frame(int realtime, int timeNudge, float timescale,
                  boolean freeze, boolean timedemo, int wallMillis, Reader reader)
    throws IOException;
State state();
Timedemo timedemo();
byte[] timedemoDurations();
```

The host calls `frame` once per engine frame. `Reader.read()` consumes one demo
message and synchronously calls `snapshot`, `gamestate` and/or `end` for the
parser's accepted results. A command-only message can announce no clock change.
The clock reads again when needed. It does not decode bytes or invent snapshots.
Callbacks are serialized; recursive frames and full-demo resets inside a frame
are rejected. A gamestate callback is expressly allowed inside a read.

`realtime` is already scaled by the host. The supplied finite `timescale` is not
multiplied into it again; native demo timing does not use the live arrival
correction that distinguishes timescale zero/one from other values. `wallMillis`
is the frame's unscaled millisecond sample for timedemo duration accounting. The
native observer supplies this same value to its OS clock callbacks within a frame.

An empty frame result means playback is primed, has reached another gamestate,
or has ended. The host must rebuild cgame for a newly parsed gamestate before the
next frame. The callback can perform its own required parser lifecycle work.

## Native reference

The reference is official ioquake3 commit
`588393618dbc82e7207c21c6ddecca229944a03a`. Public `client.h` declares
`clientActive_t`, connection-level demo fields, the 4,096-byte timedemo duration
array, and `CL_SetCGameTime`. `DemoClockOracle.c` is an authored callback and
public-state observer linked against unchanged `client/cl_cgame.c`. The reader
supplies authored snapshot, command-only, gamestate and end events; clocks,
cvars and console callbacks are controlled host inputs. No native routine body,
disassembly or implementation recipe was read, copied or translated. The probe
does not initialize a VM, renderer, socket, filesystem or original media.

With Java 25 selected through `JAVA_HOME`:

```sh
python3 scripts/BuildDemoClockOracle.py
python3 scripts/AuditDemoClock.py
./gradlew :craftq3-client:test --tests '*DemoServerClockTest'
```

The replay compiles only the owned production clock and authored Java driver
into an ignored directory. Its reflection is confined to diagnostic state
seeding, corresponding to the native host's public metadata assignments.

## Priming and read-ahead

The first primed frame sets the connection-level skipped-frame flag and returns
without reading, even if an existing snapshot is marked new. Each later primed
frame reads exactly one message before attempting activation. A new snapshot
with flag `SNAPFLAG_NOT_ACTIVE` (2) has its new marker consumed and stays primed.
A command-only message can therefore cost another primed frame.

The first active snapshot sets the offset to `snapshotTime - realtime`, sets
ordinary previous render time and the timedemo base to the snapshot time, and
activates playback. It does not itself assign current render time. This matters
when freeze is already enabled: the initial frozen render time remains zero,
while ordinary previous time is the first snapshot time.

For ordinary active playback, the clock chooses the larger of the previous
ordinary render time and `realtime + offset - clamp(timeNudge, -30, 30)`. It
retains the native extrapolated marker when the unnudged value reaches within
five milliseconds of the current snapshot. Fresh demo snapshots consume their
marker but never reset, average or drift-correct the offset as live arrivals do.

After selecting render time, it reads while **render time is greater than or
equal to the latest snapshot time**. This supplies a later snapshot for cgame
interpolation. Command-only packets can be traversed during this loop. A
gamestate or end changes the active state and immediately stops read-ahead.
Snapshots read during this loop remain marked new until the next active frame.
The previous-frame snapshot timestamp is captured before read-ahead, matching
the native order.

`gamestate()` clears map-local snapshots, offset, render history and extrapolation
but retains the first-frame skip and timedemo statistics. `reset()` starts a
different demo and also clears those connection-level fields. `end()` prevents
further reads while leaving diagnostic history available.

## Freeze and timedemo

Freeze suppresses ordinary render-time calculation and old-time updates. It does
not suppress primed reads, activation, new-snapshot consumption, validation, or
the read-ahead condition. Timedemo runs after that branch and therefore overrides
freeze's retention of current render time.

Each active timedemo frame increments the connection counter and chooses
`baseTime + frameCount * 50`, including the first active frame at base plus 50.
It does not replace ordinary previous render time with this fixed-step result.
Switching timedemo off resumes the ordinary time history. Gamestate activation
replaces the base while preserving the connection's frame counter.

When the stored timedemo start is zero, the wall sample initializes start and
last-frame time, minimum duration to `Integer.MAX_VALUE`, and maximum to zero.
The first counted frame has no duration sample. Subsequent frames update raw
minimum/maximum durations and the circular 4,096-byte log at the previous frame
count minus one. Logged durations cap only at 255 before byte conversion; raw
negative durations retain the native signed minimum and low-byte behavior. A
start sample of zero preserves the native sentinel behavior on the next frame.
`timedemoDurations()` returns an owned array copy.

## Bounds and checkpoint

Accepted snapshots have byte-range flags. Active backward snapshot time requires
a gamestate reset and fails explicitly. Nonfinite timescale, unrepresentable
signed arithmetic, recursive frames and more than 4,096 reads in one frame also
fail explicitly. The reader's `IOException` propagates. These failures can follow
already-consumed messages; the host stops the failed playback rather than
attempting to roll back its parser. Local-server pause policy, demo seeking,
wall-clock scheduling and timedemo result reporting remain host responsibilities.

The bounded production differential passes **10,000 frames and 119,921 observed
lines exactly**. It combines 400 multi-frame message sequences with 2,000 seeded
active-clock cases, covering command-only packets, inactive snapshots, map
changes/end, nudge, freeze, timescales 0/0.5/1/2, timedemo toggles, zero start
sentinels, duration clipping and ring wrap. Seven focused tests pass, including
actual callback ordering, offset retention, gamestate/end behavior, freeze at
activation, timedemo accounting and owned samples, read-work bounds, exceptions,
backward snapshots and arithmetic failure. Actual demo parsing and application
playback are separate integration checks performed by their host.
