# Original cgame QVM host

`craftq3-client` executes a user-supplied `vm/cgame.qvm` through the Java QVM
interpreter. The original module performs prediction, camera movement, weapon
presentation, animation selection, event handling, scoreboards and HUD layout.
The host implements engine services and translates guest requests into owned
Java records. It contains no Quake game source, compiled game modules or assets.

## Session contract

`Q3Client(VirtualFileSystem, Q3Server, Consumer<CgameFrame>, AudioBackend,
Consumer<String>)` borrows the filesystem, local server and audio backend.
An additional constructor accepts an explicit `ClientAbi` profile for a separately
verified legacy module. `initialize(clientNum, width, height)` requires an already
initialized server and connected client. It invokes `CG_INIT`. `frame(time,
width, height)` invokes `CG_DRAW_ACTIVE_FRAME`, forwards an immutable frame to
the sink and returns that frame. `close()` invokes `CG_SHUTDOWN`, closes guest
file handles and clears presentation audio; it does not close borrowed services.

Construction and asset registration use Java CPU data. The frame sink and audio
backend are injected; their implementations determine when GPU resources or
audio devices are accessed. A session is confined to one owner at a time and may
be transferred from a loading worker to the presentation thread after loading.

`userCommand(UserCommand)` retains the latest 64 actual input commands and sends
the command to the server. The caller schedules server simulation ticks. The
client creates a newer snapshot only after `server.frameNumber()` advances.
Presentation calls between server ticks reuse the current snapshot. The
`CG_SETUSERCMDVALUE` service exposes the original module's selected weapon and
sensitivity multiplier through `selectedWeapon()` and `sensitivityScale()`.
No weapon-selection or movement rules are substituted by the host.

The local engine shares the server's `CvarSystem`; `sv_cheats` has the valid
zero handle. `cvars()` and `commands()` expose services for the input and console
adapter. Client and server command buffers have separate scheduling. `frame()`
does not advance a command buffer itself. Reliable server messages use the
single-command tokenizer, preserving quoted newlines in print messages.

Changing framebuffer dimensions performs a renderer restart: `CG_SHUTDOWN`,
guest memory reset, `CG_INIT` with the current reliable-command sequence and
snapshot, then a normal draw. The local game, cvars, host input bindings, input
history and registered assets survive. Guest file handles and looping audio are
reset. This lets the original module rebuild its cached viewport and HUD scale.

## Explicit guest profiles

All structures use little-endian 32-bit guest integers and IEEE binary32 values.
Canonical server snapshots use the modern player/entity layouts. Conversion to
the retail guest layout is explicit; private game memory is never copied into a
client snapshot.

| Structure | Retail 1999 | Q3 1.32 |
| --- | ---: | ---: |
| `entityState_t` | 204 | 208 |
| `playerState_t` | 444 | 468 |
| `snapshot_t` | 52,724 | 53,772 |
| `gameState_t` | 20,100 | 20,100 |
| `glconfig_t` | 4,164 | 11,332 |
| `glconfig_t.vidWidth` offset | 4,136 | 11,304 |
| `refEntity_t` | 140 | 140 |
| Common `refdef_t` prefix | 112 | 112 |
| `polyVert_t` / `orientation_t` / `trace_t` | 24 / 48 / 56 | 24 / 48 / 56 |

The known retail cgame SHA-256 is
`ee31bdb9865c3e11afdff3b5f65dbe9599de9527f2493a25a347a8b0ce5eb098`.
Only this exact module selects `RETAIL_1999` automatically. Other modules default
to `Q3_132`; an unknown legacy mod needs a verified explicit profile. Filename,
build-date strings and structure stride are not layout heuristics.

Retail `usercmd_t` puts byte buttons/weapon at offsets 4/5, aligned integer
angles at 8/12/16 and signed movement bytes at 20/21/22. Modern commands put
angles at 4/8/12, integer buttons at 16, weapon at 20 and movement at 21/22/23.
Both occupy 24 bytes. Retail player-state conversion retains the first 440
canonical bytes and moves modern ping at 452 to retail offset 440.

The public [cgame service declarations][cgame] and [renderer structures][types]
define modern trap and renderer contracts. The public [shared structures][shared]
define network fields. Retail differences were verified through the locally
supplied original guest's structure accesses and successful execution, not by
translating engine implementation routines.

## Implemented services

The host supports print/error, logical milliseconds, VM cvars, command arguments,
console/client commands, idempotent cgame command registration, and bounded
read-only filesystem handles. The filesystem is the same canonical PK3/loose-file
mount used by asset loading. Guest paths cannot select arbitrary host files.

Collision services include map/inline models, temporary prediction boxes, point
contents, transformed contents, box traces and transformed traces. Mark fragments
project an original cgame polygon onto eligible world triangles through
`MarkFragments`; the host writes checked point and fragment ranges back into
guest memory. Cgame owns mark shaders, color, fade and lifetime.

Renderer services include BSP loading; model, skin and shader registration;
scene clearing; reference entities; polygons; dynamic lights; view submission;
color and stretch-pic HUD calls; model bounds; and interpolated MD3 tags. Guest
handles are resolved before submission. A `CgameFrame` preserves view/overlay
call order. A view retains full Q3 camera axes, viewport, field of view, flags,
time and area mask, together with owned entity, polygon and light lists.

Model handles distinguish MD3s and BSP inline models. Inline registration also
registers all referenced face materials, retaining their lightmap requirements.
Missing models, skins or sounds return the standard zero handle and a diagnostic.
Malformed supported files fail rather than becoming partially decoded resources.
Texture loading uses the existing TGA/JPEG loader and its diagnostic checkerboard.
Explicit shader stages retain their script settings. Implicit MD3 materials use
diffuse entity lighting; implicit overlay shaders retain vertex color and alpha
with alpha blending. Built-in white/default images are generated locally.

Audio services register decoded WAV PCM and submit positional, entity-following
or local one-shots, per-frame entity loops, entity positions and listener axes.
The retail clear-loop trap has no kill-all argument; stale argument words must
not stop playing sources. Per-frame loop collection is replaced by
`beginFrame`/`endFrame`. Modern explicit kill-all requests clear the loop sources.
Background intro/loop tracks use decoded PCM and the logical frame clock.

The local transport retains 32 snapshots and 256 reliable commands. A snapshot
contains canonical server-selected visible entities and its area mask, converted
to the selected guest profile. The server excludes the player represented by
the snapshot's player state and reports entity-capacity overflow. The client
does not silently truncate a second time. Future snapshot, command and input
sequence requests fail; overwritten history is reported unavailable.

Memory/math intrinsics cover bounded memset/memcpy/strncpy, sin, cos, atan2,
sqrt, floor, ceil, integer/float diagnostic prints, acos and vector snapping.
Cgame floor/ceil use trap numbers 107/108; qagame has different numbers.

## Bounds and current limits

The QVM interpreter enforces memory, control-flow, stack, instruction, syscall,
deadline and cancellation checks. Cgame currently permits 100 million work units,
500,000 host calls and 30 seconds per outer invocation, including nested work.
It exposes module statistics, syscall counts and contained failure context.
Unknown services identify the syscall number and fail explicitly.

Asset registries allow 8,192 handles per kind, 64 MiB of encoded models, 256 MiB
of decoded textures and 128 MiB of encoded sounds. Readers have their own file,
dimension and element-count limits. Frame limits include 16,384 ordered commands,
32 views, and per view 1,024 entities, 4,096 polygons, one million polygon vertices
and 128 lights. Mark services bound input points to 64, output points to 65,536
and output fragments to 8,192. Every guest output range is checked before mark
projection or renderer record construction.

This is a local transport, without UDP, demos, download negotiation or remote
server discovery. UI VM hosting is separate work. Services not exercised by the
supported base-game path, including compiled font registration, preprocessor
source handles, cinematics, capsule-specific cgame traps and several later
renderer extensions, still fail explicitly. Key-catcher storage exists; full
UI/text-key routing is a separate adapter. Loading update calls flush already
submitted work; they do not recursively render the guest loading screen.

The renderer owns the supported presentation of each submitted entity type;
see the repository compatibility document for GPU limits. The modern refdef
text tail is not copied yet. Background music uses whole PCM buffers with
frame-clock transitions rather than gapless streaming. Loop velocity/doppler
and music crossfades are not represented by the current audio sink. A shader
name registered for different implicit model/HUD purposes currently retains its
first registration; explicit script definitions are unaffected.

## Reproducible validation

`./gradlew :craftq3-client:test` uses original synthetic bytecode and generated
assets. It checks both ABI profiles, null string offsets, canonical player-state
conversion, guest bounds, immutable ordered frames, implicit/explicit materials,
mark output buffers, temporary collision boxes, idempotent registrations,
snapshot timing, viewport restarts, borrowed-service ownership and fault cleanup.

`./gradlew :craftq3-client:auditCgame` requires the user's local PK3 installation.
It runs the original qagame/cgame pair for 140 movement/firing frames, changes
viewport dimensions, checks nonempty world/HUD/entity/audio submissions, and
repeats the run to compare a deterministic frame/state digest. Use
`-Pq3Installation=/path/to/games` and `-Pq3Map=q3dm12` to select local data.
Optional `-Pq3AuditVm=/path/to/qagame.qvm` and
`-Pq3AuditCgameVm=/path/to/cgame.qvm` select an unchanged reference pair.
These files remain external and are never bundled.

[cgame]: https://github.com/id-Software/Quake-III-Arena/blob/master/code/cgame/cg_public.h
[types]: https://github.com/id-Software/Quake-III-Arena/blob/master/code/cgame/tr_types.h
[shared]: https://github.com/id-Software/Quake-III-Arena/blob/master/code/game/q_shared.h
