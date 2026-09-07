# Cgame source boundary

`CgameSource` now separates the existing cgame VM, assets, scene submission and
collision from the local server. Existing `Q3Client` constructors use
`LocalCgameSource`; the explicit constructor borrows a source, client cvars and
engine command buffer without creating or changing `sv_running`.
`Protocol68ClientSession` separately owns wire reliability, XOR/channel state,
gamestate and snapshot reconstruction. This document records the shared boundary;
the remote source is implemented as `RemoteCgameSource`; the transport/clock/
application providers are described in [remote play](REMOTE_PLAY.md).

## Smallest separation

The explicit constructor is
`Q3Client(fs, source, cvars, commands, frameSink, audio, output[, profile])`.
The ordinary `initialize(clientNumber, width, height)` method remains unchanged.
Sources are borrowed and never closed by `Q3Client`.

The source needs only:

* `initialize(requestedClientNumber)` returns `Initialization(clientNumber,
  serverMessageSequence, serverCommandSequence, milliseconds, selectedWeapon)`.
  The public `CG_INIT` contract accepts the sequence/client fields. The local
  initialization retains `(0,0,clientNum)`; a source without a usable player
  state supplies weapon zero and does not manufacture a snapshot.
* `refresh()`, `currentSnapshot()` and `snapshot(number)` expose completed state
  with the **original message number**, including gaps. `Snapshot` owns canonical
  468-byte player state, up to 256 208-byte entity states, a 32-byte area mask,
  time, flags, ping and reliable-command sequence/count metadata.
* `configStrings()` and `serverCommand(sequence)` expose the cgame-visible map
  and reliable-command consumption. A remote source may fail expired history;
  the local source preserves its older empty-result behavior.
* Outgoing ordinary user commands and reliable client text commands, plus a
  default-empty local console-command fallback. A remote implementation has no qagame VM
  to invoke; an unhandled local cgame command becomes outgoing reliable text.

`CgameSnapshotWriter` keeps VM-memory serialization in the client module, using
`ClientAbi` for 204/208-byte entities and 444/468-byte player states. A source
count of `-1` explicitly leaves the guest's four-byte `numServerCommands` field
untouched, as observed in native network snapshot reads; nonnegative local
counts are written as before. All remaining snapshot layout fields share one
writer. VM types remain outside network/core, and the existing client dependency
on the canonical `UserCommand` metadata type remains intact.

## Existing coupling points

`LocalCgameSource` owns the previous server interactions: selecting initial state,
capturing dense local snapshots, forwarding user/client commands and trying the
local game console. `Q3Client` owns its 64-entry user-command history; a remote
source only queues the corresponding canonical inputs for transmission. On a
consumed `map_restart`, retained command values become all-zero inputs while
their numbers and the command counter remain intact. This preserves native
retained/expired/future query distinctions rather than making old inputs absent.

The optional `additionalEngineCommands()` server-buffer `play` registration is a local connection concern;
remote clients install the command only on their own engine buffer. The
`sv_running` setting also remains host-owned: remote presentation must not infer
that a local server exists merely because a cgame VM is running.

`ClientCollision` is already independent: cgame loads a BSP through
`ClientAssets`, then prediction uses its own immutable BSP collision plus
guest-supplied entity transforms. `ClientScene`, audio requests, HUD/quads,
renderer submissions and shader/model loading need no transport-specific path.

## Sequence and state constraints

The new session accessors supply the necessary wire metadata:
`gameStateMessageSequence()`, the initial `gameState().commandSequence()`,
`snapshotState(sequence)` with the reliable sequence captured at that Frame
operation, and `serverCommand(sequence)` from the bounded ring. Preserve message
number gaps; remote snapshots must not be renumbered into the dense sequence
used by `LocalSnapshots.capture`.

`RemoteCgameSource` maintains a separate cgame **processed-command** configstring view. The network
session retains its latest wire configstrings; cgame separately advances the
outgoing server ID when it consumes systeminfo. New gamestates apply their ID
immediately. However, original cgame asks for reliable commands while advancing a
particular snapshot, then calls `GETGAMESTATE` in response to a configstring
change. Giving it the newest received map can expose changes from later
snapshots. Apply `cs`/assembled `bcs` updates to the presentation view at the
command-consumption boundary, and do not expose incomplete fragment commands as
ordinary cgame commands. Verify that consumption order with authored fixtures
and native public calls before declaring remote presentation parity.

A new gamestate is a level generation: reset presentation snapshot/command
state, load the correct BSP/assets and reinitialize cgame with its actual
baseline sequences. A viewport restart calls `refresh()` and then `restart()`;
the source prepares coherent configstrings and returns the selected CG_INIT
baselines. The local source retains its previous snapshot-number-minus-one and
current-command-sequence behavior. The current presentation clock, weapon,
connection and user-command history survive a viewport restart. Before the first usable snapshot, the adapter must
represent the absence of player state instead of fabricating a local server
snapshot. Packet arrival and presentation-clock estimation belong to the
enclosing remote session; `Q3Client.frame(milliseconds,...)` can keep accepting
the selected presentation time.

## Verification

Eight authored boundary tests exercise both guest ABIs without a qagame VM:
nonzero startup baselines, sparse/missing snapshots, owned canonical arrays,
native-unwritten count bytes, repeated rendering, coherent restart metadata,
reliable consumption/configstring order, foreign command scoping and outgoing
commands. The `map_restart` fixture verifies native counter 123: retained numbers
60..123 return successful zero usercmds; 59 fails without writing; a future number
fails; the next actual input continues at 124. All 45 client tests pass, including
the existing local snapshot/restart/play/ownership tests.

The native snapshot and command-reset observations came from the separately
authored client-message observer calling unchanged public engine entry points.
The remote provider must additionally verify partial large-configstring assembly,
expired history and new gamestate generations. Then replay the captured native
gamestate and moving snapshots through the original matching cgame, followed by
the same existing scene/audio sinks. No socket implementation or automatic
asset download is required inside this boundary.

The `CG_INIT` and snapshot command-tail requirements are public metadata in
[`cg_public.h`](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/cgame/cg_public.h).
The review used repository code and these declarations only; no native engine
routine bodies were inspected.


The remote provider and application integration are now implemented and exercised.
Eleven packet-backed source tests cover delayed initialization, fragment assembly,
retained/future/expired commands, new gamestates, outgoing consumed server ID,
snapshot metadata and renderer restarts. See [remote play](REMOTE_PLAY.md) for
production pump/clock/ping and actual Fabric session CPU evidence. The earlier
45-test local/source checkpoint above is retained as the separation's original
validation; the current aggregate has 94 client tests and 987 total tests.
