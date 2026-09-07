# Validation record

Validated locally through 2026-09-07, macOS arm64, Apple A18 Pro, JetBrains Java 25.0.3. Target remains Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.156.0+26.2 and Loom 1.17.20.

## Bridge chat entry, console and team-command checkpoint (2026-09-07)

The latest full build passes **1,310 tests**, zero failures/errors/skips, formatting
and compilation. The 2,023,992-byte distributable has SHA-256
`c83deb5387f52a0d2a918ff25da2bf24fe569c56f24ee394b7cf5205b39f858a`.
Recursive inspection finds 918 Java 25 classes, nine engine jars and four GLSL
resources, without original assets or native binaries. Evidence:
`/tmp/craftq3-bridge-console-final-build.log` and
`/tmp/craftq3-bridge-console-final-aggregate-report.json`.

A new native test reproduces the user's console failure through actual ChatScreen
Enter submission: `/q3 bridge` installs a screen synchronously, then Minecraft's
chat submission closes it. The log shows enabled → ShutdownGame → failed screen
survival. Evidence: `/tmp/craftq3-bridge-chat-before.log`, normal failing process
exit. Command entry now queues the launch until the chat callback returns, guarded
against a changed world/player or intervening screen. Direct internal transfers
retain their existing path.

The bridge now registers archived model/head-model/name player information and
forwards changes through original qagame to cgame. Console feedback splits actual
line breaks instead of literal r/n characters. Two corrected native
Minecraft 26.2/Java 25/Vulkan runs pass chat survival, repeat-safe backtick,
`give all`, railgun selection, visible Visor character selection, first-person
return, rocket-launcher binding, actual rocket presentation, editing/completion
and restoration of native Creative mode:

- Retail `/q3 bridge`: `/tmp/craftq3-bridge-chat-retail.log`,
  `/tmp/craftq3-bridge-chat-retail.result`, `/tmp/craftq3-bridge-chat-retail.png`.
- Modern API 4 `/q3 bridge fresh`: `/tmp/craftq3-bridge-chat-modern-resumed.log`,
  `/tmp/craftq3-bridge-chat-modern.result`, `/tmp/craftq3-bridge-chat-modern.png`.

Both completion markers report PASS and both processes exit normally. Both console
captures were visually inspected. An earlier character assertion used an assumed
MD3 header name and allowed deferred player loading; it was corrected to observe
the actual character model prefix with the original `cg_deferPlayers 0` setting.
One modern setup run paused on focus loss and was explicitly terminated; the
unattended QA startup now resumes its own native pause screen. No user gameplay
pause preference is changed. These are native development-runtime checks; packaged
contents are separately inspected above.

The team application audit also found cgame-registered `addbot` bypassing local
qagame console dispatch. Registered commands now use the common scoped dispatcher;
a synthetic guest regression fails before the fix and passes both ABI profiles,
including local handling, remote fallback, cgame handling and argument/context
restoration. A second fix registers team model settings as player information so
modern team startup does not receive an empty model path. Original two-versus-two
bot TDM/q3dm1 and CTF/q3ctf1 now pass earned scores/captures, intermission, original
restart, reset scores, retained teams and 300 subsequent world frames in both
profiles. CTF observes real enemy flag possession and its reset. Logs:
`/tmp/craftq3-team-application-retail-carriers.log` and
`/tmp/craftq3-team-modern-userinfo.log`, both with successful process exits.
The initial CTF audit guessed a later-version flag-status configstring; its failure
was corrected to inspect canonical player state. See `TEAM_MATCHES.md` for scope.

The numbered remaining roadmap is in `WHATS_LEFT.md`; full project completion is
not claimed by this checkpoint.

## Production campaign and impact-decal checkpoint (2026-09-07)

That checkpoint build passes **1,309 tests**, zero failures/errors/skips, formatting and
compilation. The 2,021,826-byte distributable has SHA-256
`b5ce0b9f845e98f48ec27c49802748e5ee02a855dc68613af222dc5842e304bc`.
Recursive inspection finds 918 Java 25 classes, nine engine jars and four GLSL
resources, without original assets or native binaries. Evidence:
`/tmp/craftq3-campaign-final-build.log`,
`/tmp/craftq3-campaign-final-aggregate-report.json` and immutable runtime
`/tmp/craftq3-campaign-final-runtime`.

The production application audit earns five tutorial frags with ordinary player
input at the original default skill and frag limit. It follows the original UI's
automatic `tier1.roq` movie, `nextmap=levelselect`, and next-arena selection into
q3dm1 with an original bot. Normal shutdown writes the earned scores, awards and
movie unlock; a fresh application reload verifies them and the original arena
menu. No health, ammunition, score or progression is injected. The map-start seed
uses the existing deterministic capture setting, cleared before normal persistence.

Both inspected packaged-runtime profiles pass with normal process exits:
`/tmp/craftq3-campaign-packaged-retail.log` (API 3, 5–1) and
`/tmp/craftq3-campaign-packaged-modern.log` (API 4, 5–0). Both reach the movie's last
frame 365 and consume 273,792 PCM frames. Audio is consumed by a CPU sink in this
audit; native movie playback/timing evidence remains the separate checkpoints below.
The historical component-only postgame/Next audit did not execute movie commands
and is now explicitly labeled accordingly.

The replay exposed an actual cgame failure after an all-solid impact: the trace
has a zero normal, so the original VM supplies undefined decal corners with a zero
projection. The client adapter now validates every memory range, then returns no
fragments for a zero projection before decoding those corners. A regression checks
negative zero, untouched output buffers, invalid memory ranges and continued
rejection of nonfinite corners with a nonzero projection. Existing valid decal
tests and both complete campaign replays pass. Failure/diagnostic evidence:
`/tmp/craftq3-campaign-mark-crash.log` and
`/tmp/craftq3-campaign-mark-diagnostic.log`.

The final native Minecraft 26.2/Java 25/Vulkan gameplay regression also passes:
ordinary movement covers 229.86 Quake units, firing consumes five rounds, and
23 sound starts report zero audio failures. The completion marker and normal
process exit agree. Evidence: `/tmp/craftq3-campaign-native-gameplay.log`,
`run/craftq3-smoke/play.result` and
`run/screenshots/craftq3-play-q3dm17-input-vulkan.png`.

This proves the first campaign unlock/continuation, not the complete campaign.
Remaining tiers, team modes and broader mod compatibility remain open.

## System cinematic/menu checkpoint (2026-09-07)

That checkpoint build passes **1,308 tests**, zero failures/errors/skips, formatting and
compilation. The 2,021,752-byte distributable has SHA-256
`3402520d00184ff6d1dc0d884395825e66938c6e68029c894399466014a89a9a`.
Recursive inspection finds 918 Java 25 classes, nine engine jars and four GLSL
resources, without original assets or native binaries. Evidence:
`/tmp/craftq3-system-cinematic-final-build.log`,
`/tmp/craftq3-system-cinematic-final-aggregate-report.json` and immutable runtime
`/tmp/craftq3-system-cinematic-final-runtime`.

QuakeSession now handles `cinematic <movie> [0|1|2]` as a queued engine transition.
It validates the opening video before replacing a healthy match or movie, closes
an admitted match/connection, suppresses menu/game input during playback, resizes
movie submission, handles ordinary skip keys and executes/clears `nextmap` once
at EOF or skip. Hold and loop options use the existing movie service. Disconnect,
replacement and close cancel without executing a continuation; runtime failures
return to a menu. The screen hides an open console at movie entry and routes skip
keys before gameplay bindings. Cinematics keep advancing on focus loss so sound
and presentation use the same ongoing timeline.

Original retail API 3 and 1.32 API 4 UI/cgame application audits pass normal EOF,
hold, loop, skip, resize, opening-file failure preserving a match/movie, nextmap
return-to-game, and session close. The original Cinematics menu launches
`video/idlogo.roq`; its own continuation returns to the Cinematics submenu.
Initial evidence: `/tmp/craftq3-system-cinematic-audit.log` and
`/tmp/craftq3-system-cinematic-modern-audit.log`. The first audit invocation used a
relative installation from the wrong working directory and failed before loading;
rerunning with the absolute installation passed.

Both audits also pass against the inspected packaged runtime, with normal process
exits: `/tmp/craftq3-system-cinematic-packaged-retail.log` and
`/tmp/craftq3-system-cinematic-packaged-modern.log`.

The real Minecraft 26.2/Java 25/Vulkan screen plays the complete 129,150-sample
idlogo soundtrack, follows `nextmap` into q3dm17, launches another movie through
the original menu and skips with Space. It returns to the original Cinematics
submenu with zero audio failures. Evidence:
`/tmp/craftq3-system-cinematic-final-live.log`,
`/tmp/craftq3-system-cinematic-final-live.result`,
`/tmp/craftq3-system-cinematic-movie.png` and
`run/screenshots/craftq3-system-cinematic-return-menu.png`. Both captures were
visually inspected. An initial run passed the feature assertions but failed the
Gradle completion-file gate; the fixture now writes the expected marker only
after the final screenshot is saved, and the corrected process exits successfully.

The ordinary Vulkan lifecycle regression also passes original menu/map/restart/
disconnect transitions, persistent settings and audio cleanup: six generations,
42 sound starts and zero active voices/loops or failures at its final check.
Evidence: `/tmp/craftq3-system-cinematic-gameplay-regression.log`.

These checks establish CraftQ3's numeric command options and original menu routing.
An attempted independent reference-client option audit failed to reach a usable
renderer/startup state, so exact native-engine command-option parity is not claimed
from that attempt. No native engine implementation bodies were opened or copied.
Shader videoMap, automatic startup intro policy, full campaign-triggered movie
coverage and broader mod/movie timing remain open. See `CINEMATICS.md` for usage.

## Independent cinematic audio checkpoint (2026-09-07)

That checkpoint build passes **1,308 tests**, with zero failures/errors/skips, formatter
and compiler checks. The 2,014,490-byte distributable has SHA-256
`13f06bfb13827b5f8bf8aaba8e6fd8896426b7466474cd8f7e95c331f65a0a81`.
Recursive inspection finds 917 Java 25 classes, nine engine jars and four GLSL
resources, without original assets or native binaries. Evidence:
`/tmp/craftq3-independent-audio-final-build.log`,
`/tmp/craftq3-independent-audio-final-aggregate-report.json` and immutable runtime
`/tmp/craftq3-independent-audio-final-runtime`.

The earlier combined A/V interruption is fixed. RoQ PCM now comes from an owned
worker with a four-second bounded queue, independent of video presentation. It
splits large chunks into contiguous blocks and discovers soundtrack EOF even when
the movie continues silently. Native readers remain nonblocking. Cancellation
wakes blocked producers and closes their input; decode/format failures reach the
consumer. Four new tests cover full multi-capacity sample delivery, short audio
with a long video tail, cancellation under backpressure and malformed/changed
formats. A fifth test verifies that finishing the queue wakes a blocked producer.

A shared daemon scheduler requests native channel refills every 20 ms while stream
voices exist, through Minecraft's own sound executor and ChannelAccess. At most
one request waits on that executor. Requests check session generation and host
availability; close cancels the session timer. No extra device or raw audio API
is introduced. This prevents renderer readback from starving the short native
streaming buffers.

The native combined check reaches original idlogo EOF with 205 presented frames,
all 129,150 audio samples consumed and at most 6 ms measured clock skew. It then
passes loop/reprime, silent hold, cancellation, GPU release and audio close, with
three native starts and zero failures. Evidence:
`/tmp/craftq3-independent-audio-live.log` and `/tmp/craftq3-cinematic-av.png`.
A repeat against the final source also passes with 204 presented frames, the same
sample count and at most 14 ms measured skew:
`/tmp/craftq3-independent-audio-final-live.log`.
The native streaming lifecycle regression also passes prebuffering, EOF, stop,
host sound reset, asset reset and close: five starts, zero failures, no retained
active/pending voices in `/tmp/craftq3-independent-audio-lifecycle-live.log`.

The packaged `AuditRoqSoundtracks.java` run matches all **5,444,948** original
samples across eleven movies against the saved FFmpeg-verified hashes, using the
new asynchronous source rather than the video timeline's audio callbacks:
`/tmp/craftq3-independent-audio-soundtracks.log`. Packaged retail and 1.32 QVM
movement/crouching regressions pass in `/tmp/craftq3-independent-audio-gameplay.log`.
All these processes exit successfully.

Fullscreen cinematic engine commands, original movie-menu integration, shader
video textures, retail UI extension ABI and broader movie/mod timing coverage
remain unfinished. This does not establish complete cinematics or subjective
speaker synchronization. Distant Minecraft pickup streaming also remains open:
original qagame spawns map items at initialization, and the current unloaded-chunk
collision policy makes blindly admitting distant placements unsafe. No pickup
behavior was changed at this checkpoint.

## Gameplay-priority checkpoint (2026-09-07)

The gameplay bridge is the current priority. The complete build passes **1,303
tests**, with zero failures, errors or skips. The 2,010,099-byte distributable has
SHA-256 `9a98fb1838ed128fb23d2138a68a234c690ca0bb5511aca417952532a881b96f`.
Inspection finds 916 Java 25 classes, nine engine jars and four GLSL resources;
recursive inspection finds no original assets or native binaries. Evidence:
`/tmp/craftq3-gameplay-priority-build.log`,
`/tmp/craftq3-gameplay-priority-aggregate-report.json` and the immutable runtime
`/tmp/craftq3-gameplay-priority-runtime`.

Two fresh Minecraft 26.2/Java 25/Vulkan checks pass and exit normally:

- Native blaze combat: 120 covered ticks, three native fireballs, one accepted
  impact and burn, then an original rocket kills the blaze. Quake health ends at
  91, native health stays unchanged, and exit restores Creative mode. Evidence:
  `/tmp/craftq3-gameplay-priority-blaze.{log,result,png}`.
- Quake → Minecraft → Quake: exact inventory transfer, original rockets in both
  worlds, missing-map rejection preserving the current session, audio ownership
  and restored Creative mode. Evidence:
  `/tmp/craftq3-gameplay-priority-roundtrip.{log,result,png}`.

Both original retail and 1.32 QVM movement/crouching audits pass against the
packaged runtime in `/tmp/craftq3-gameplay-priority-movement.log`. The modified
streaming playback layer also preserves all 7,715 frames and 5,444,948 audio
samples across all eleven original movies against the saved reference hashes:
`/tmp/craftq3-gameplay-priority-roq.log`. Both audit processes exit successfully.
That CPU playback audit does not resolve the native A/V issue described below.

A short gameplay walkthrough now appears in `MINECRAFT_BRIDGE.md`. These checks
use only the private `CraftQ3 Bridge QA` save. Multiplayer Minecraft combat and
simultaneous original Quake matches in the Minecraft building mode remain open.

The in-progress cinematic service adds bounded handle ownership, optional video
prefetch, native audio-start timestamps and CIN syscall routing. Seven new CPU
tests pass, including synthetic modern UI and both cgame ABI profiles, loop/hold,
cleanup and renderer-frame stability. The retail UI extension guard remains.
**Combined native A/V playback has not passed:** the original idlogo check stops
around frame 91 after Minecraft's channel tick releases the stream. The diagnostic
run reports a closed PCM queue; its normal process exit is not a playback pass.
Evidence: `/tmp/craftq3-cinematic-av-diagnostic-live.log` and the temporary trace
`/tmp/craftq3-cinematic-close-trace.log`. Temporary trace instrumentation was
removed. Fullscreen commands, movie menus, shader video textures and this audio
lifecycle issue are deferred while gameplay takes priority. The earlier separate
silent-video and PCM tests do not establish combined playback.

## Dynamic movie rendering checkpoint (2026-09-07)

That checkpoint build passes **1,296 tests**, zero failures/errors/skips, formatting and
compiler checks. The 1,993,046-byte distributable has SHA-256
`35a5fe75d191380ba0979c712e5226c717219792296ecf752a26b427235a0d0e`.
Recursive inspection finds 912 Java 25 classes, nine engine jars and four GLSL
resources, without original assets or native binaries. Build/inspection:
`/tmp/craftq3-video-render-final-build.log` and
`/tmp/craftq3-video-render-final-aggregate-report.json`; immutable packaged runtime:
`/tmp/craftq3-video-render-final-runtime`.

A new immutable movie-image command preserves ordered HUD/view submission. The
backend uploads frames through Blaze3D into a reusable texture, skips unchanged
snapshots, replaces dimensions and releases absent/closed streams. Image commands
have a 32-stream/64 MiB budget. Three tests cover extent/UV/color and opaque-quad
geometry, command ordering, invalid/conflicting snapshots and distinct-image
budgets while allowing repeated draws.

The native Minecraft 26.2/Java 25/Vulkan fixture plays original idlogo frames into a
512×256 rectangle. Readback at frames 90 and 180 checks 129,284 pixels per frame,
with zero RGB error against the decoder's converted images. It also verifies
background/movie/HUD ordering, held-image upload suppression, same-size allocation
reuse, dimension replacement and all four clamped colors of a resized 2×2 image.
Removing the image leaves zero textures/bytes after 182 uploads and two allocations.
A third active allocation is then reclaimed at backend close. Both renderer and
close PASS records appear in `/tmp/craftq3-video-render-final-live.log`; the process
exits normally. Captures `/tmp/craftq3-movie-render-{90,180,resize}.png` were saved,
and frame 180 was visually inspected. Earlier fixture checks sampled a blended
resize pixel and then mismatched the relocated HUD marker; correcting that fixture
layout produced the final passing run. Original movie readback passed throughout.

The original retail UI main-menu regression also passes on Vulkan:
`/tmp/craftq3-video-ui-regression.log`, API 4, 175 commands, one model, 20 shaders,
five sounds and zero audio failures. Its native process exited normally.

Cinematic command/syscall integration, fullscreen presentation policy and audio/video
start synchronization remain unfinished. This is native movie pixel/resource
coverage with silent playback, not complete end-to-end cinematics.

## Native streaming audio checkpoint (2026-09-07)

That checkpoint build passes **1,293 tests**, zero failures/errors/skips, formatting and
compiler checks. The 1,979,767-byte distributable has SHA-256
`112935a9c7827cb47f8aedcc2791520de85363af9993343f1090e257916d9ecd`.
Recursive inspection finds 907 Java 25 classes, nine engine jars and four GLSL
resources, without original assets or native binaries. Build/inspection:
`/tmp/craftq3-stream-final-build.log` and
`/tmp/craftq3-stream-final-aggregate-report.json`; immutable packaged runtime:
`/tmp/craftq3-stream-final-runtime`.

The platform now exposes an owned, nonblocking PCM stream and bounded queue with
exact sample continuity/backpressure. MinecraftAudioBackend waits for prebuffering,
uses the native streaming source pool, and releases producers on cancellation,
engine suspension, asset reset or session close. The adapter handles refills through
Minecraft's AudioStream/Channel APIs, preserving volume and shared-source ownership.
Four new CPU tests check queue boundaries, continuity, capacity, readiness, finite
EOF, underrun and closure. The native adapter rejects malformed/unaligned blocks
and reports actual stream failures through existing diagnostics.

A live Minecraft 26.2/Java 25/Vulkan run incrementally submits all 129,150 sample
frames from original `video/idlogo.roq`. Native EOF arrives in about 5.925 seconds.
The same process checks waiting for prebuffering, active stop, host sound reset,
asset reset and close. It ends with five voice starts, zero active/pending voices,
zero static registrations/buffers and zero failures. Evidence:
`/tmp/craftq3-stream-fixed-live.log`, including `CraftQ3 streaming audio PASS`.
The first run completed the movie but caught a cancellation/refill race; it logged
an explicit fixture failure despite Gradle exiting normally. Retired voices now
return no data to queued refills, and short source reads serialize with stop/suspend.
The corrected run passes all lifecycle stages and exits normally.

The existing native silent-audio regression also passes channel replacement and
overlap, positional/entity sounds, loops, host stopAll/restart and final cleanup:
`/tmp/craftq3-stream-static-regression.log`. The final build additionally converts
runtime source failures to checked stream errors at the native reader boundary.

Movie audio/video synchronization, dynamic textures and original menu/cgame
cinematic integration remain incomplete. This is native PCM playback/resource
coverage, not a complete cinematic or subjective listening validation.

## Streaming RoQ playback layer checkpoint (2026-09-07)

That checkpoint build passes **1,289 tests**, zero failures/errors/skips, formatting and
compiler checks. The 1,968,033-byte distributable has SHA-256
`800ee6035612dfec6ffcc4196b959a07fa6cd50cc8ba3efecb23d1a28112e81b`.
Recursive inspection finds 902 Java 25 classes, nine engine jars and four GLSL
resources, without original assets or native binaries. Build and inspection:
`/tmp/craftq3-roq-playback-final-build.log` and
`/tmp/craftq3-roq-playback-final-aggregate-report.json`; immutable packaged runtime:
`/tmp/craftq3-roq-playback-final-runtime`.

The new platform-independent `RoqPlayback` owns a streaming decoder and a monotonic
presentation clock shared with an audio-sink contract. Integer common clock units
preserve frame/sample timing across loops. Current/future frames are bounded,
PCM retains its original sample positions, and ONCE/HOLD/LOOP, silent playback,
long-stall catch-up and cancellation have explicit lifecycles. Eight new tests
cover those behaviors, malformed/reopened media and both stream/audio cleanup
failures. Four VFS tests cover streaming search/reference semantics, early close,
CRC corruption, changing/symlinked loose files and 65 MiB streamed movies while
buffered reads and nonmovie archive entries retain the 64 MiB limit. RoQ stream
entries have a separate 512 MiB bound. CRC validation finishes at EOF; early
cancellation does not drain the entry.

The final packaged audit runs all 11 user-supplied retail movies at 16 ms clock
steps. Every presented planar frame and all contiguous PCM match the saved hashes
previously verified against FFmpeg: 7,715 frames and 5,444,948 sample frames, with
clean presentation/audio termination. Log:
`/tmp/craftq3-roq-playback-final-audit.log`. Reproduce with Java 25:
`java -cp '<runtime>/*' scripts/AuditRoqPlayback.java <installation> <AuditRoq-results>`.
Both retail and 1.32 original-QVM movement/crouching regressions also pass against
the final runtime in `/tmp/craftq3-roq-playback-final-gameplay.log`. Both audit
processes and the final build exited successfully.

This is playback-layer evidence. Native audio output, dynamic textures, original
menu/cgame CIN syscalls and fullscreen cinematic commands still need integration
and live validation. No in-game movie playback claim is made at this checkpoint.

## Native blaze AI encounter checkpoint (2026-09-07)

That checkpoint build passes **1,277 tests**, zero failures/errors/skips, formatter and
compiler checks. The 1,959,580-byte distributable has SHA-256
`b827a2b50d5d2440ef201d398035b8bb85ecfb2c81f5a46224b2603407287c7b`.
Recursive inspection finds 896 Java 25 classes, nine nested engine jars and four
GLSL resources, with no original assets or native binaries. All nine engine jars
are byte-identical to the preceding audited hitbox runtime. Build and inspection:
`/tmp/craftq3-bridge-blaze-release-build.log` and
`/tmp/craftq3-bridge-blaze-release-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-bridge-blaze-release-runtime`.

The live `-Pq3BridgeBlazeSmoke=true` encounter passes on Minecraft 26.2, Java 25
and Vulkan, with normal native blaze AI and physics. Native cover prevents sight,
shots and damage for 120 server ticks. Removing cover permits a native three-shot
volley; one accepted fireball and one burn tick reach original Quake health/armor.
The player then kills the blaze with the original rocket launcher. Assertions check
selected/equipped weapon, consumed rocket ammo, native death, bridge kill count,
unchanged Minecraft health and return to Creative. Health ends at 91. The fixture
assigns the native target and supplies player aiming/fire input; it never spawns
fireballs or schedules a native ranged-attack method. Remaining fire/projectiles
are cleared after the kill. Broader encounters and status behavior remain open.

Successful terminal result and inspected screenshot:
`/tmp/craftq3-bridge-blaze-rocket-live.{log,result,png}`. The screenshot shows the
original rocket model/HUD in the native room. An earlier test exposed a fixture
ordering error: selecting a weapon before cgame received `give all` inventory
left another weapon equipped. The stricter check rejected that run, and the QA
process was stopped after bridge closure. Selection now waits for the inventory
snapshot; the final run passes weapon and ammo assertions. This checkpoint adds
native encounter coverage and development-only observations; gameplay adapters
retain the preceding hitbox/fireball implementation.

## Original player hitbox checkpoint (2026-09-07)

That checkpoint build passes **1,277 tests**, with zero failures/errors/skips, formatting
and compiler checks. The 1,951,053-byte distributable has SHA-256
`0dbe81782500c67737448e10e3295133ab28e2bc7b99989644d32b201267a32a`.
Recursive inspection finds 894 Java 25 classes, nine nested engine jars and four
GLSL resources, without original assets or native binaries. Build/inspection:
`/tmp/craftq3-bridge-hitbox-final-build.log` and
`/tmp/craftq3-bridge-hitbox-final-aggregate-report.json`.

Client/server native bounding-box, body width/height and current eye-height queries
now use original QVM player bounds at the native avatar's feet. An immutable
snapshot carries shape, alive and grounded state across the server handoff.
Native saved dimension/pose fields are not written by this adapter. Two new tests
check axis conversion, translated/asymmetric hulls, original standing/crouching
measurements and invalid extents before native use.

The live `-Pq3BridgeHitboxSmoke=true` fixture passes on Minecraft 26.2, Java 25 and
Vulkan. A horizontal native small fireball at 1.65 blocks passes over the crouched
body and reaches the back wall; the same height hits when standing. A side ray
0.65 blocks from the center hits the wider original hull with native projectile
margins. Two accepted direct impacts take Quake health 100→50; QA clears fire to
isolate these impacts. A top-slab ceiling at 1.5 blocks prevents standing after
crouch is released, then removing it permits standing. Both native sides compare
their body and eye queries with QVM snapshots. Exit verifies original native
0.6×1.8 dimensions and 1.62 eye height on client/server, plus Creative mode.
`/tmp/craftq3-bridge-hitbox-live.log` and `.result` record successful terminal exit.

Both retail and 1.32 original-QVM movement audits pass standing/crouching hull and
view-height checks in `/tmp/craftq3-bridge-hitbox-qvm.log`. All nine packaged engine
jars are byte-identical to that audit runtime. Live regressions also pass:
`/tmp/craftq3-bridge-hitbox-incoming.log` and `.result` verify native incoming
attacks, original death/respawn and return; `/tmp/craftq3-bridge-hitbox-fluids.log`
and `.result` repeat swimming, drowning/recovery, lava/Battle Suit, unchanged native
health and no duplicate environmental hits. Each native process exited normally.

Native explicit-pose dimension APIs, unusual poses and broader mod-defined hull
behavior remain incomplete. The implemented query overrides do not claim to
replace every Minecraft pose or dimension API.

## Small-fireball bridge checkpoint (2026-09-07)

The small-fireball checkpoint build passes **1,275 tests** with zero failures/errors/skips, formatting
and compiler checks. The 1,936,378-byte distributable has SHA-256
`2bbd7e5d8dafa1e10b60140ec758786b9508f83c70d5661bb15455e67760f9dd`.
Recursive inspection finds 889 Java 25 classes, nine nested engine jars and four
GLSL resources, with no original assets or native binaries. Metadata retains
Minecraft 26.2 and Java 25. Build and inspection:
`/tmp/craftq3-bridge-fireball-final-build.log` and
`/tmp/craftq3-bridge-fireball-final-aggregate-report.json`.

The new native fixture exposed fireballs skipping the bridge avatar because of
its `noPhysics` movement flag. The adapter changes only that field check in native
hurting-projectile target selection for a living active bridge player. The native
owner/vehicle and target-eligibility checks still run. Small-fireball impacts now
retain their accepted mob-owned ignition duration through the existing burn path.

`-Pq3BridgeFireballSmoke=true` passes on Minecraft 26.2 / Java 25 / Vulkan.
Native cover stops a shot; ownerless and invulnerable-target impacts are rejected
without starting burns. One accepted blaze-owned small fireball plus four native
burn ticks takes original Quake health 100→55 exactly, with unchanged Minecraft
health, natural expiry and restored Creative mode. Native projectiles are launched
and aimed by QA code, with blaze AI disabled; flight, collision, impact damage and
fire ticking are native. The enclosed, lit fixture prevents stray external attacks
from invalidating the exact-damage comparison.
`/tmp/craftq3-bridge-small-fireball-room-live.log`, `.result` and `.png` retain the
successful terminal run and its capture. The capture visibly retains native
terrain with the original weapon and HUD at 55 health.

All nine packaged engine jars are byte-identical to the runtime that passed both
QVM combat profiles in `/tmp/craftq3-bridge-fire-final-combat.log`. Those checks
cover the shared original damage/armor/death/impulse path; native fireball behavior
is established by the live fixture above. Full blaze AI, fireball-created block
fires, other hurting-projectile behavior and native-avatar hitbox/pose fidelity
remain incomplete.

## Mob-arrow burns checkpoint (2026-09-07)

The Flame checkpoint build passes **1,275 tests** with zero failures/errors/skips, formatting
and compiler checks. The 1,925,881-byte distributable has SHA-256
`8520c9fcbf703258753000fd63efbe1e2058633f35fe841ba5aae377139a8d46`.
Recursive inspection finds 886 Java 25 classes, nine nested engine jars and four
GLSL resources; no original assets or native binaries are bundled.
`/tmp/craftq3-bridge-fire-release-build.log` and
`/tmp/craftq3-bridge-fire-release-aggregate-report.json` record this package.

Accepted mob-owned burning arrows now grant a bounded attribution interval from
the native ignition duration. Native `ON_FIRE` damage is admitted only while that
interval and the native burn remain active; it then uses the existing original-QVM
incoming-damage path. Rejected arrows grant no interval. Extinguishing clears it
immediately, including same-tick reignition. Expiration and the controller's dead
state also end attribution. Ordinary native fire stays outside this damage path.

The native `-Pq3BridgeFireSmoke=true` fixture uses a Flame I skeleton and tests
unattributed fire, an invulnerable target's rejected arrow, two accepted arrows,
ongoing native burn ticks, immediate extinguish/reignition and natural expiration.
It compares exact Quake health against all accepted native damage after both burn
intervals, so dropped or duplicated burns fail. Native health stays unchanged, and
exit restores Creative mode. Missed native shots are retried while waiting for an
impact; native aiming, flight and collision are unchanged. AI is disabled only to
schedule the attack. The release run passes on Minecraft 26.2 / Java 25 / Vulkan:
two accepted arrows, one rejected arrow and six burn ticks. The interrupted/full
burn checks end at health 70/60, matching the accepted damage totals exactly.
`/tmp/craftq3-bridge-fire-release-live.log` and `.result` record successful native
and Gradle terminal exits.

The separate Punch-arrow regression still passes native cover, full resistance,
Quake movement into a native wall and absence of duplicate native velocity:
`/tmp/craftq3-bridge-fire-arrow-regression.log` and `.result`. Both retail and 1.32
QVM combat regressions pass at `/tmp/craftq3-bridge-fire-final-combat.log`.
All nine engine jars in this release are byte-identical to that audited runtime.
Those engine checks establish the shared incoming/armor/death/impulse path; they
do not by themselves establish native fire behavior. Fireballs, potion effects,
precise attacker attribution and broader burn/death/respawn combinations remain open.

## Bridge swimming and world-effects checkpoint (2026-09-07)

The swimming checkpoint build passes **1,275 tests** with zero failures/errors/skips, formatting
and compiler checks. The 1,916,540-byte distributable has SHA-256
`d4cf06f0720d83a69d89ef7856b9442e3b4c472bc658ed99881c58ab25921214`.
Recursive inspection finds 884 Java 25 classes, nine nested engine jars and four
GLSL resources; no original assets or native binaries are bundled.
`/tmp/craftq3-bridge-fluid-final-build.log` and
`/tmp/craftq3-bridge-fluid-final-aggregate-report.json` record this package.

The new native `-Pq3BridgeFluidSmoke=true` regression passes on Minecraft 26.2,
Java 25 and Vulkan in `CraftQ3 Bridge QA`. Original Space input swims upward
3.987870 blocks through native water. Drowning reduces Quake health 100→90;
replacing water with air stops the damage. A fully submerged lava hit reduces
100→10. An original Battle Suit prevents damage in a subsequent 2.5-second lava
interval. Native health remains unchanged, the incoming native-damage queue stays
empty, and exit restores Creative. Log/result:
`/tmp/craftq3-bridge-fluid-live.log` and `/tmp/craftq3-bridge-fluid-live.result`.
The process and Gradle both exited successfully. The fixture drains/refills one
reservoir; it does not establish shoreline traversal or every native fluid state.
No production swimming or environmental-damage rule was replaced for this test.

The packaged runtime also passes `AuditBridgeGameplay.py --fluids` in both
retail and 1.32 QVM profiles. The synthetic host-volume test observes respective
one-second rises of 126.975372 and 132.423035 Quake units, drowning 100→90, cessation
in air, lava 100→10 and original Battle Suit protection. These CPU tests invoke
qagame; live cgame integration is covered by the native fixture.
`/tmp/craftq3-bridge-fluid-final-qvm.log` records both successful profiles.

## Gameplay bridge checkpoint (2026-09-07)

This previous checkpoint superseded earlier package counts below. `./gradlew build` passes
with **1,275 tests**, zero failures/errors/skips, formatting and compiler checks.
The 1,910,884-byte `craftq3-fabric/build/libs/craftq3-0.1.0.jar` has SHA-256
`9cd76e5c75eb00b3884ad155680cbe19d9944f14c7e59b9271479f97c2f626d0`.
Recursive inspection finds 883 Java 25 classes, nine nested engine jars and four
GLSL resources, with no original assets or native binaries. Build and inspection:
`/tmp/craftq3-gameplay-final-build.log` and
`/tmp/craftq3-gameplay-final-aggregate-report.json`.

Live Minecraft 26.2 / Java 25 / Vulkan checks, all with successful terminal exits:

- `-Pq3BridgeRoundtripSmoke=true`: Quake → Minecraft → Quake, exact carried
  loadouts, original rockets in both worlds, missing-map failure preserving its
  source, native return state and audio ownership. Log:
  `/tmp/craftq3-gameplay-bridge-roundtrip.log`. The deliberate missing-map error is
  an expected recovery check.
- `-Pq3RangedSmoke=true`: native skeleton bow with Punch II; native cover blocks
  an arrow, full native resistance suppresses push while damage reaches Quake,
  then zero resistance admits the next arrow's additional impulse of approximately
  (-1.199709, 0.1, -0.026445) blocks/tick. Original Quake movement reaches the rear
  Minecraft wall (1.026842 blocks backward, peak rise 1.835857). Both damage and
  impulse use the existing server-to-QVM handoff. Native avatar velocity is restored
  immediately, Minecraft health stays unchanged, and exit restores Creative.
  `/tmp/craftq3-gameplay-arrow-live.log`, `.result` and `.png` retain the evidence.
  AI is disabled only to schedule the native attack; this is not full skeleton AI
  or status-effect coverage.
- `-Pq3BuildSmoke=true`: native placement, breaking and return inside original
  q3dm17, grounded 0.125-block stepping and airborne rejection.
  `/tmp/craftq3-gameplay-final-building.log` and `.result`. This uses a new test cell;
  `persisted=false` does not test cross-launch block persistence.

Both retail and 1.32 original-QVM combat audits pass against the packaged runtime:
`/tmp/craftq3-gameplay-final-combat.log`. They cover weapons, cover, splash,
armor/death/respawn, incoming/outgoing impulses and collision; native arrow
resistance is established separately by the live fixture above.

The RoQ decoder fix also has a terminal-word regression. Packaged decoding
matches FFmpeg on all 11 original movies: 7,715 planar video frames and 5,444,948
PCM sample frames. `/tmp/craftq3-gameplay-final-roq/report.json` records hashes and
retained reference EOF diagnostics. See [decoder scope](formats/ROQ.md); movie
playback is not integrated. No original assets were extracted or bundled.

## Build and automated tests

`./gradlew build` passes, including Spotless and Java compiler warnings-as-errors. At the earlier 1,224-test checkpoint, **1,224 tests** pass with zero failures/errors/skips: core 196; assets 117; collision 21; VM 26; server 79; client/input/config/UI/network 195; render 58; platform/audio 17; Fabric 29; botlib 486. Botlib coverage includes navigation, route selection, presence traces, actions, preprocessing, characters, fuzzy weights, goal and movement state, weapons, initial/reply chat, item tracking/selection/placement, movement views, grounded WALK/CROUCH/jump-pad/ledge/teleport/barrier/swim/water-jump/ordinary-jump/weapon-jump approach, cached airborne completion, directional movement and route prediction. Strict one-minute original-qagame movement and stationary-target combat audits pass on q3dm1 and q3dm17. Ordinary local bots are enabled; remaining movement domains and broader campaign/team coverage remain incomplete. The predictor supports verified flat floors and positive inclines, flat and tilted ceilings, axis-aligned and oblique vertical walls, steps including positive unit-normal landings, dry and swimming frames, fluid entry, gap stops and damaging impacts; downward/non-unit step planes and other documented domains remain unsupported.

Tests include legacy byte-encoded PK3 names/comments, explicitly UTF-8 entries, malformed UTF-8 archive diagnostics, bounded parsers and malformed images/archives, a 1,000-case deterministic BSP header mutation smoke test, original q3dm17's zero-effect flare sentinel, indexed/patch winding, interpolated patch UVs/colors/normals, PVS and frustum selection, convex brush edges, fog segment clipping, light-grid direction/weights, ordered texture transforms/deformations, rolled/reflected sprites, dynamic-light falloff, frame timing and overbright restoration. Further tests cover MD3 frames/tags and malformed ranges, PCM decoding/channel lifetime, AAS v4/v5 metadata and node graphs, brush/patch/entity collision, checked QVM instruction/memory/budget behavior, retail/modern ABI layouts, snapshot PVS/area portals, scoped console arguments, shared cvars, saved-config precedence and key release/tap timing. This is not a sustained fuzzing campaign.

The distributable `craftq3-fabric/build/libs/craftq3-0.1.0.jar` was inspected: metadata requires Minecraft 26.2 and Java 25; all nine configured engine module jars and four custom GLSL resources are present (including botlib). The latest recursive archive inspection found 812 Java classes in the 1,660,660-byte distributable. Class-file major version is 69 (Java 25). No PK3/BSP/TGA/JPEG/QVM/MD3/WAV/AAS files or screenshots are bundled, including inside the nested engine jars. `git diff --check` passes.


The 819-test checkpoint also includes the connectionless command-line envelope
and a stateless datagram classifier: 14,583 native line/cursor/body comparisons,
2,265 native printed frames and 10,260 prefix/classification cases match. Raw
response bodies remain byte-preserving. This is a parsing prerequisite; it does
not establish challenge/connect, sessions or socket transport. Reproduction and
bounds are in [formats/CONNECTIONLESS68.md](formats/CONNECTIONLESS68.md).

The 830-test package registers bobbing-platform entry, cached completion and
standing contact through both game ABIs. Its direct provider matches 240,000
native cases, including separate waiting/arrival deadband corpora. Full
move-to-goal compares 30,000 ordinary ground, 30,000 standing and 27,009 airborne
calls exactly; 2,991 genuinely grounded inputs are excluded from the airborne
corpus. Three captured first-standing calls also match complete output/state and
trace counts. Explicit runtime flags and deadlines commit atomically. See
[BOTLIB_BOBBING_GOALS.md](BOTLIB_BOBBING_GOALS.md).

All three q3dm19 profiles complete a fresh one-minute retry of this package.
Retail and matching-data modern also complete five minutes. The earlier 826-test
package completed four runs with three bots each and eight additional team/CTF/
seven-bot scenarios. These assert movement, not completed matches. An independent
826-test ride observer records sustained model-11 rides in all three profiles,
with player/mover vertical displacement agreeing within 0.00003052 units per
step. Details are in [BOT_MAP_SWEEP.md](BOT_MAP_SWEEP.md) and
[BOTLIB_PLATFORMS.md](BOTLIB_PLATFORMS.md).


## Original bots in local play

Ordinary local sessions now enable original bots by default. A fresh sixty-second
movement sweep after the entity-lifetime fixes passes all 30 original playable
maps; three targeted matching-modern repeats also pass (33/33). Retail and
matching-modern matches reach fraglimit/intermission, request restart and resume
play. The retail cgame replay survives the entire transition with 301 further
world-view frames. Original Single Player menu routing starts Crash without an
injected `addbot`; a sixty-second encounter observes movement, firing, a human
death and original respawn.

The entity-lifetime bridge matches native callback counts, all 84 prediction
bytes and all 140 entity-info bytes over 10,000 mixed sequences for each ABI
(20,000 production-host comparisons). Targeted probes also establish allocated
entity-slot tracing, exact relink triggers, restart timestamps and retained
configstrings. See [BOTLIB_ENTITY_LIFETIME.md](BOTLIB_ENTITY_LIFETIME.md).

The final 853-test package passes a 900-frame Vulkan bot/respawn capture on
Minecraft 26.2 / Java 25: bot movement observations are 860/860/857, 897 frames
contain player-model submissions, the human respawns through five normal input
presses, and 205 audio starts report zero failures. Original models, world, effects
and HUD were visually inspected in the saved capture. Native CPU probes accept zero-scale and planar
singular model axes; a twenty-second original cgame geometry replay passes
437,654 surfaces, including four previously crashing zero-scale weapons.
See [MODEL_TRANSFORMS.md](MODEL_TRANSFORMS.md),
[BOT_MATCH_LIFECYCLE.md](BOT_MATCH_LIFECYCLE.md), and
[BOT_PLAY.md](BOT_PLAY.md) for precise scope and reproduction. These observations
do not establish full campaign progression, completed team/CTF matches or all
remaining bot movement domains.

## Original asset corpus

The user's original `run/craftq3/games/baseq3/pak0.pk3` remains ignored and is read directly. Local audits found:

- All **354 MD3 models**, **591 WAV sounds**, and **30 AAS navigation files** parsed/decoded successfully. AAS includes 26 version 5 files and four version 4 files; routing/AI correctness is separate from reader validation.
- All **1,819 TGA/JPEG images** decoded successfully (68,121,529 total pixels).
- All **1,399 shader definitions / 3,021 stages** loaded; no structurally rejected shader files. The library reports 88 diagnostics, mainly legacy/test directives and duplicate definitions; these do not imply 88 missing map textures.
- All **31 maps in pak0** pass BSP parsing, scene construction and material/image loading. The final optional audit also includes the two generated fixtures: **33 maps passed, zero failed**.
- `q3dm12`: 9,998 world surfaces, 74,974 triangles, 224,922 flattened vertices; 254 patch surfaces; 32 lightmaps. The starting camera selects 1,938 PVS surfaces and approximately 951 frustum surfaces (deformed surfaces are retained conservatively by the renderer).
- `q3dm17`: 1,439 world surfaces, 13,844 triangles, 41,532 vertices; 32 patches; both BSP and texture loading succeed after accommodating its original zero-effect flare records.
- `q3dm12` and `q3dm17` report no missing stage textures. Native Q3 does not draw its stored inner skyboxes, so missing legacy `full_*` inner-box names are not loaded/rendered.

Reproduce the optional corpus audit after building:

```sh
java -cp craftq3-core/build/classes/java/main:craftq3-assets/build/classes/java/main:craftq3-render/build/classes/java/main scripts/AuditAssets.java run/craftq3/games
```

A later audit of the expanded current installation passes **71 of 71 maps**, including
`reqbath`; a separate original-only regression remains **31 of 31**. Native BSP-loader
observations support two narrow compatibility changes: patch surfaces ignore unused mesh-index
fields, and non-finite lightmap UV components are zeroed only on vertices used exclusively by
vertex-lit (-3) surfaces. Active triangle indices, shared lightmapped vertices and other attributes
remain strict. See [formats/BSP46.md](formats/BSP46.md).

The installation can be elsewhere; pass its containing directory instead. Automated unit tests never require commercial data.

## GPU regression checks

Actual Minecraft clients were launched with both **OpenGL 4.1 Metal - 90.5** and **Vulkan 1.2.334 / MoltenVK 1.4.2**. Runs load the map, capture frame 180 and exit. Shader time is fixed at 1.0 seconds, and the diagnostics panel is hidden for comparisons.

| Capture | OpenGL versus Vulkan | Result |
| --- | --- | --- |
| Original `craftq3_shaderlab` fixture, 1708×960 | 1,493,538 nonbackground pixels in each; **0 differing pixels** | Exact match; semantic checks pass on both |
| User's `q3dm17`, 1708×960 | 759,551 nonbackground pixels in each; **1,423 differing pixels (0.086785%)** | Passes the 0.1% regression threshold |
| User's `q3dm12`, Vulkan | Textures, lightmaps, rails and light cones visually inspected | Clean capture/shutdown; diagnostics working |

The original shader fixture contains eight panels for implicit lightmaps, layered animation, alpha cutouts, scrolling, clamped rotation, animated maps, a fogged curved patch and a mirror. A striped card lies outside the direct camera view and must appear in the mirror. Two distinct red/blue sky backgrounds exercise per-material BSP coverage masks. `VerifyShaderCapture.java` checks all panels, blue visibility through alpha holes, the green animation frame, the orange reflected card, and separate sky colors. `CompareCaptures.java` rejects blank images and differences above 0.1%.

```sh
./gradlew :craftq3-fabric:runSmokeClient -Pq3Map=craftq3_shaderlab
java scripts/VerifyShaderCapture.java run/screenshots/craftq3-craftq3_shaderlab-materials-vulkan.png
java scripts/CompareCaptures.java run/screenshots/craftq3-craftq3_shaderlab-materials-opengl.png run/screenshots/craftq3-craftq3_shaderlab-materials-vulkan.png
```

Select each graphics backend in the development client and restart between runs. The local test configuration was restored to Vulkan afterward. `-Pq3Panel=true` includes diagnostics; FPS text is intentionally nondeterministic. `-Pq3Mode=BRUSHES` selects another diagnostic mode. Captures remain under ignored `run/screenshots/`.

At the tested q3dm12 camera, the Vulkan diagnostic panel settled at **60 FPS / 16.67 ms** with the local frame cap; one captured 120-frame window's worst interval was 17.89 ms. This is a local observation, not an uncapped benchmark or a performance guarantee for other maps/devices. Frame intervals include actual rendering and are independent of camera movement delta clamping. Draw counts describe the main map pass, and CPU preparation time is not GPU execution time.

## Original VM gameplay and audio

The Java runtime executes the user’s original retail qagame and cgame bytecode directly. A separately built modern ioquake3 QVM pair exercises the published 1.32 ABI without altering the user’s packs; provenance and hashes are in `LICENSE-NOTES.md`.

- The qagame audit checks startup, spawn, 100 movement/jump/firing frames, death, attack-triggered respawn and a repeat of all 140 frames with identical final canonical player state. Both retail and modern bytecode pass on q3dm17. An additional q3dm12 movement/firing audit passed before the respawn assertion was added.
- A two-client original-qagame combat audit places players using the VM’s own local cheat command, then supplies aimed usercmds. It checks projectile/hitscan collision, damage, death and scoring without Java damage rules: target health 125→−1, shooter score 0→1, ammo 100→82 at Q3 time 3200 ms.
- The cgame audit runs original prediction/presentation for 140 frames, including sustained firing and a viewport restart. The final retail run submitted 423 views, 3,686 HUD quads, 6,589 entities and 174 mark projections; repeated runs produced digest `1b0e7d711ae45ac597e1621b35252597230d4e1cde3c2b7715afc14aaaf2d85b`. These counts describe that fixed script, not gameplay rules.
- Actual Vulkan and OpenGL clients passed the scripted `Q3Screen` key/mouse callback test: forward movement of 215.595 Q3 units, jump/turn/fire input, yaw 72.405°, ammo 100→95 and health 123 after 57 server frames. The capture uses seed 42 and 16 ms presentation steps. It proves the screen-to-QVM adapter path; physical keyboard/mouse automation is not claimed.
- The post-config-bootstrap Vulkan replay repeated the same movement/ammo/state results.
- The corrected snapshot excludes the player reconstructed by cgame from playerState. With the same input, audio starts dropped from 57 to 25; ten map loops remain stable with zero audio allocation/playback failures. A probe also verifies that Minecraft SoundInstance playback is rejected during the pure Quake view.
- The independent silent audio smoke exercises overlapping/channel-replaced/positional/entity sources, omitted loops, Minecraft stopAll/resume and close. Eight native channel starts were confirmed; cleanup leaves zero voices, loops, buffers or failures. See `AUDIO.md` for limitations.

```sh
./gradlew :craftq3-server:auditGameplay
./gradlew :craftq3-server:auditCombat
./gradlew :craftq3-client:auditCgame
./gradlew :craftq3-fabric:runPlaySmokeClient -Pq3Map=q3dm17 -Pq3InputTest=true
```


The later fresh-map initialization fix was rechecked on Vulkan with the original game/client/UI: keyboard/mouse callbacks moved the player **229.8614 Q3 units**, ammunition changed **100 → 95**, health was **123**, and yaw changed **72.4054°**. In-game menu pause/resume and automatic capture/shutdown passed. The HUD, world, weapon and menu captures were visually inspected. This run used 26 sound starts with zero audio failures; it is a new Vulkan regression, not a repeated OpenGL pixel comparison.

The captured original HUD, machinegun, items and world were visually inspected. q3dm17’s black sky is intentional in the original `textures/skies/blacksky` shader. The newer gameplay OpenGL/Vulkan pair differs at 8,144 pixels (0.496682%), exceeding the strict 0.1% changed-pixel threshold. Of those pixels, 8,123 differ by exactly one channel unit; no connected changed region exceeds ten pixels. With the explicit `--channel-tolerance=1` option, 21 pixels (0.001281%) remain above tolerance and pass the 0.1% threshold. Maximum channel delta is 170 at isolated edges; mean absolute RGB error is 0.0023522883 on a 0–255 scale. The distribution is consistent with interpolation/raster rounding and isolated edge coverage; that cause is an inference. Strict tolerance zero remains the default, and this is not exact pixel identity. These original-content captures remain ignored.

## Original UI and session lifecycle

The original retail UI bytecode parses 30 arenas and 32 bot descriptions and runs with its verified API-3 import layout. An actual Vulkan client rendered its CD-key entry dialog: 18 shaders, four sounds, 31 frame commands and zero audio failures. This is the original UI content, not a recreated menu. No key is invented or logged. A separate development preview remains available, and the production title-screen entry now opens the original UI. The original in-game menu overlays cgame and pauses/resumes the local clock.

```sh
./gradlew :craftq3-fabric:runUiSmokeClient
```

The first capture is `run/screenshots/craftq3-ui-vulkan.png`. The optional `-Pq3UiMain=true` scenario sends an explicit Escape press/release and lets the disconnected host reopen MAIN after the UI clears its key catcher; this exercises the original VM’s normal menu transition.

The original MAIN scene also passed Vulkan capture: one MD3 banner model, 20 shaders, four registered sounds, 162 commands and zero audio failures. The in-game menu test exercised Escape, confirmed a frozen game clock while the native menu rendered over the world/HUD, and verified resumed movement time afterward.

```sh
./gradlew :craftq3-fabric:runPlaySmokeClient -Pq3Map=q3dm17 -Pq3InputTest=true -Pq3MenuTest=true
./gradlew :craftq3-fabric:runLifecycleSmokeClient
./gradlew :craftq3-client:auditCgame -Pq3AuditRestart=true
```

The Vulkan lifecycle test passed original MAIN → q3dm17 → retained-client arena restart → MAIN → q3dm1 → MAIN. It verifies settings and binding persistence, stable cvar handles, deferred post-map commands, old-server closure on disconnect, missing-map preservation, restart counter/flag and no remaining world audio loops. Final audio diagnostics: 116 registered sounds, 35 starts, zero loops/pending voices/failures. The original menu and second-map captures were visually inspected. Native qagame/cgame restart auditing repeats two restarts without replacing cgame or its asset registrations; the retail deterministic digest is `4270580515a80bd8c467b86c908550c6332f047e162866ea8e38f2b40b10f1c3`; the unchanged modern qagame/cgame pair also passes with digest `cee292c49161e159067dfaca8c2390dbdf3c16293b6c2bf0e1d3dc57f14bfc14`.

All lifecycle scripts use the production session but are opt-in development runs. No synthetic CD key or Java replacement for menu/game rules is used. Server browsing, bot-dependent single-player completion, demos, cinematics and CD-key storage remain incomplete.

The engine console now renders the user-mounted `console` shader and `gfx/2d/bigchars` atlas through the Quake renderer. A Vulkan screen-callback test opens it, completes `sensitiv` to `sensitivity`, submits value6, recalls history and sends mouse movement that must not alter aim while the console is open. The run passed its gameplay and cvar assertions; its screenshot was visually inspected with colored text and the original animated backdrop. Reproduce with `runPlaySmokeClient -Pq3InputTest=true -Pq3ConsoleTest=true`. Editor tests cover insertion/deletion, bounded history/drafts, completion inside a token and rejection of multiline/control-character insertion. Console frame tests cover shader time, atlas coordinates, colors and bounded wrap/scroll behavior.

The gameplay, UI and lifecycle Gradle smoke tasks now require an explicit success result after runtime assertions and a newly saved PNG. Stopping Minecraft with exit code0 alone cannot pass a failed smoke. The result is kept under ignored `run/craftq3-smoke/` and is cleared before each run.

## Native-engine visual comparison

An unchanged official ioquake3 renderer was built separately under ignored `.tools/` with its supported standalone option, allowing the same pak0-only content to be used without patch-pack overrides. Commit and provenance are recorded in `LICENSE-NOTES.md` and the ignored oracle provenance file. No upstream engine is linked into CraftQ3.

An ioquake3 1708×960 capture used FOV90, yaw270, no HUD/gun/entities, gamma1, two map-overbright bits and zero display-overbright bits. Logged eye position was `(-24,-1626,130)` versus CraftQ3's `(-24,-1624,130)`; setviewpos's velocity/friction produced this small difference. The local reference is `run/ioquake3-oracle/baseq3/screenshots/oracle-q3dm12-aligned.png`.

Visual inspection shows close agreement in geometry, texture orientation/scale, rail transparency, lightmap gradients and ceiling light cones. The central back wall is somewhat darker in CraftQ3. Camera offset, animation time and filtering are not controlled tightly enough for pixel metrics; this is visual evidence rather than a claim of exact ioquake3 parity.

## Integration findings and remaining coverage

- Explicit Q3 D32_FLOAT depth clearing is necessary on the tested MoltenVK path; inline attachment clears previously produced blank captures. The renderer retains explicit clears.
- Namespaced pipelines are registered before Minecraft discovers shader source identities. The same GLSL/material implementation works on both host backends without raw OpenGL/Vulkan calls.
- Blaze3D 26.2's mip extent accessors shift without clamping to one. Narrow GPU mip chains stop before either extent becomes zero.
- Blaze3D `LINES` denotes Minecraft's expanded line geometry. Native debug edge lists use `DEBUG_LINES`; using `LINES` incorrectly filled triangles during the brush smoke test and was corrected.
- The Q3 map pass precedes GUI drawing. While a QuakeView is active, a separate GUI extraction hook excludes queued vanilla HUD, toasts and subtitles and extracts only CraftQ3 diagnostics. The normal command path and GUI hooks compile and were checked against actual Minecraft bytecode; full world/chat/key automation is not claimed.
- Realms/test-profile authentication messages and MoltenVK's primitive-restart initialization warnings do not prevent capture/shutdown.
- Extended camera input, resizing, resource reloads and long sessions need broader manual/device coverage. Optional flares, entity text/shadows and animated portal-camera timing need more work. Inline models, interpolated MD3s, HUD portraits and multiple cgame views are now supported; native visual fidelity needs wider comparisons.
- Noise and fog quantization, vertex-based dynamic lighting, limited portal recursion and hardware gamma are documented fidelity limits. Collision, MD3, QVM/gameplay and audio have local implementations and tests. Remaining menu actions, bots, demos, networking and Minecraft interoperability remain incomplete; see `COMPATIBILITY.md`.

## Botlib and wire-protocol checkpoint

The latest independent native comparisons extend the local gameplay foundations. These are service
comparisons, not a claim of complete Quake networking or autonomous bot matches:

- Local bot user commands accept the full signed-byte movement range, including -128. Original
  Anarki and Major five-minute runs exposed the former -127 minimum; both now complete 300 seconds.
  Both guest ABIs round-trip all 256 motion byte values, with output guards and out-of-range checks.
  The separate wire user-command decoder retains native -128 normalization when its field group
  is present; local trap211 does not apply that network-specific rule.
- Protocol 68 netchan: 2,000 messages, 13,813 packets and 16,193 receptions; exact native results.
- Fixed-Huffman fields: 131,072 mixed fields across 512 messages and 297,752 bytes; exact bits/values.
- Wire strings: 13,376 writes / 1,653,264 wire bytes and 20,064 reads match, including bit cursors.
- Entity/player deltas: 9,550 cases and 777,185 wire bytes; all 99 metadata fields and decoded states match.
- User-command deltas: 32,000 keyed commands / 226,197 wire bytes match, including decoded state
  and timestamp/field-width boundary cases.
- Snapshot bodies: the native client accepts 3,000 bodies / 1,305,002 Java-written bytes; all
  62,751 entities, player/area states, metadata and bit cursors match.
- Server payloads: the native legacy client accepts 1,500 complete payloads / 554,399 bytes,
  with matching level state, 21,888 entities, 2,550 commands and snapshot chains/restarts. These
  two corpus results compare parser states, rather than native writer byte sequences.
- Client payloads: 4,000 native packet bodies / 1,495,809 normalized bytes match in every meaningful
  bit, with 63,909 decoded usercmds. Only unused native tail bits are normalized. The key-hash helper
  separately matches 10,256 native results, including wrapping accumulators.
- Legacy payload XOR: 12,000 sends / 10,362,895 bytes match, and 12,000 native receives recover the
  original payloads. Both directions and queued sends are included; reliable ring selection and
  transport are isolated from this comparison.
- Both original demo files round-trip record framing byte-for-byte; protocol 43 payload playback is pending.
- The protocol-68 demo reader decodes all 1,500 native-checked server records / 566,407 framed bytes,
  retaining the verified level resets and snapshot states. It returns commands without executing
  them; presentation and original protocol-43 demos remain separate work.
- Item metadata: 35 declarations and 8,260 bytes match. Controlled placement discovery matches 5,124
  item records and 4,959 queries across 31 maps/four game types; live tracking matches 1,500 frames.
- Goal contact/visibility: 18,000 boundary/random predicates match with controlled trace/entity inputs.
- Item choice: 4,000 LTG/NBG scenarios and 52,000 goal/timer checks match with controlled routing/weights.
- Ordinary AAS goal placement: 30 maps, 12,000 ordered links and 12,000 area IDs match; maximum goal
  position difference 0.000793457031 Q3 units. Airborne jump-pad trajectory selection remains unavailable.
- Chat patterns: 6,713 generated queries and 29,728 captures match, including complete 328-byte result records.
- Initial chat: all 53,090 comparisons match across the original message corpus and five fixed random
  samples. Six artificial single-character overlap cases remain outside exact synonym-helper parity.
- AAS route costs and first-edge observations: 30,000 queries match across all 30 navigation maps.
- Route prediction: 30,000 requests across 30 maps match every assigned field and float bit, preserving
  the native unassigned numareas slot. Another 125,701 reachability records match all crossed-area
  metadata, including 16,758 nonempty sequences. Trap576 is wired with both guest ABIs tested.
- AAS movement-route selection: 30,000 choices match with verified history and avoided-reachability
  contexts. Another 30,000 selections across 30 maps match with spherical avoid spots and blocked
  flags; 100,000 controlled spot geometry cases also match. Physical execution remains separate work.
- Reachable-area selection: 167,000 fuzzy/clear/world/entity/start-solid/mover queries across 30 maps
  and 17 mover models match the unchanged reference with float contraction disabled.
- Movement state: 20,000 mixed reset, clock, attempt and initialization operations match all native
  reach IDs, expiry float bits, retry counts and effective flags.
- Movement view targets: 30,000 queries across 30 maps match success, output-write behavior and
  coordinates exactly after 10,000 native segment probes isolated the normalization rounding.
  Trap554 is wired; empty and populated history guest ABI tests pass.
- WALK/CROUCH reach execution: 30,000 requests across 30 maps match every result field, elementary
  action, float bit and BSP query count; another 30,000 dry same-area requests match exactly.
  Full move-to-goal testing covers 30,000 requests across 30 maps: 29,542 supported comparisons
  match every result byte, movement-state value, action and BSP query count; 458 requests enter
  explicitly unsupported physical branches. This includes grounded jump-pad, ledge, teleport and barrier approaches.
  Separate type-18 entry and finish corpora each match 30,000 queries exactly; type-7 entry and
  finish corpora each add 30,000 exact queries. Two cached-airborne WALK/CROUCH corpora add
  52,104 exact comparisons. Full airborne ledge execution matches 29,250 requests; full airborne
  jump-pad execution matches 19,888 requests across 21 eligible maps. The jump-pad contact selector
  matches 24,535 airborne queries, including 9,031 selected contacts. Trap549 is wired for both ABIs.
- Teleport approach: 22,000 direct requests across 11 maps match. Full move-to-goal integration
  matches 10,999 targeted ground and 10,704 cached-air requests, including teleported-state
  suppression and partial-result preservation; excluded requests are documented separately.
- Barrier-jump approach/completion: 54,000 direct requests across 27 maps match. Full integration
  matches 26,849 targeted ground requests and 25,341 cached-air requests, including 1,703 jump-only
  outputs and 17,440 full-result waits that preserve the existing movement accumulator.
- Liquid travel: 173,000 direct swim, water-jump entry/finish and same-area comparisons match
  native results, actions and random draws. Full move-to-goal integration adds 244,090 exact
  supported requests, with 5,910 excluded grounded/seed/unsupported-travel cases documented in
  [BOTLIB_LIQUID_GOALS.md](BOTLIB_LIQUID_GOALS.md). Trap549 liquid paths are wired for both ABIs.
- Ordinary jumps: 58,000 direct entry/finish comparisons and 29,000 run-start wrapper requests
  match native behavior. Full production move-to-goal with the real AAS predictor matches 28,604
  grounded and 28,096 cached-air requests; 396 physical/failed-prediction and 904 grounded-air
  samples are explicitly excluded. The Host registers type5 with current physics settings,
  immediate/delayed jump services and retained airborne jump state, tested in both ABIs.
  [BOTLIB_JUMP_TRAVEL.md](BOTLIB_JUMP_TRAVEL.md) records the domains and captured request replay.
- Weapon jumps: 118,800 direct rocket/BFG entry and completion comparisons match native results,
  view/weapon/action effects and jump history. Production full move-to-goal adds 134,669 exact
  supported comparisons with 3,331 explicit exclusions; BFG cases use labeled authored metadata
  substitutions because original maps lack BFG links. Both Host ABIs cover view/weapon publication,
  consecutive-jump suppression and cached airborne steering with prior effects preserved.
  See [BOTLIB_WEAPON_GOALS.md](BOTLIB_WEAPON_GOALS.md).
- BSP box-to-leaf enumeration: 310,000 queries across all 31 original BSPs match ordered leaf IDs,
  capacities, last-leaf state and leaf metadata. Twenty authored boundary/plane fixtures also match.
  Cached entity-area association separately follows measured order, 128-leaf capacity and unlink
  lifetime; see [BSP_BOX_LEAVES.md](BSP_BOX_LEAVES.md) and
  [ENTITY_AREA_ASSOCIATION.md](ENTITY_AREA_ASSOCIATION.md).
- Visible route positions: 30,000 requests on all 30 maps match targets, output preservation,
  trace counts and ordered trace-request hashes, with no native PVS query. Trap572 is wired with
  both guest ABIs tested; see [BOTLIB_VISIBLE_POSITION.md](BOTLIB_VISIBLE_POSITION.md).
- Directional movement decisions: 50,000 controlled decision/action/flag queries match; the obstacle
  helper matches another 50,000 results/query counts and 15,000 trace-coordinate pairs. The real
  predictor and obstacle services are now connected to trap550, with swimming, airborne action
  preservation and invalid-input guest coverage for both supported ABIs.
- Movement prediction: 20,000 flat/air/jump/crouch scenarios and 30,000 wall/step scenarios match the
  native return/domain results; maximum numeric differences are 0.00049 and 0.000213 respectively.
  The exact .125 presence boundary is covered by 404 boundary/neighbor rays and a 300,000-ray
  original-map regression with zero mismatches (maximum coordinate error 0.000015).
  Tilted ceilings add 70,000 exact discrete/query-count comparisons. Rejected step fallback adds
  30,000 comparisons, including the exact former modern q3dm1 failure. Walkable tilted floors add
  40,000 default and 25,000 nondefault-steepness comparisons, with maximum numeric error 0.0001.
  Steeper positive impacts add 70,000 exact float/event/trace/query-count comparisons, including
  threshold equality, tiny normals, extreme momentum and both captured map failures.
  Gap stops add 9,000 exact production comparisons, 24,000 native threshold probes and 16,000
  independent boundary checks. Native sentinel probes also establish that ordinary, zero-frame
  and fluid-entry successes clear their complete trace record; both guest ABIs cover this behavior.
  Low-clearance retention/recovery adds 20,000 exact output and ordered-query-hash comparisons.
  All four captured modern-map requests return the same bounded failure with 42 exact trace
  requests each. AAS-area fluid events add 1,400 exact dry-entry comparisons and the captured
  q3dm12 run-up; BSP contents remain separate from AAS event flags.
  A further 60,000 predictions using directional stop masks 60/61 match all return/event/content,
  frame and trace-presence fields and query counts; the latest regression has maximum numeric error 0.0001.
  Another 40,000 oblique wall/step predictions match all discrete outputs and query counts, with
  maximum numeric error 0.00001. Another 30,000 flat-ceiling predictions match discrete outputs
  and query counts, with maximum numeric error 0.0001. A 20,000-query native friction probe pins float operation order.
  The dynamic AAS adapter also matches 20,000 queries across ten maps (4,967 dynamic hits), with
  exact discrete fields and maximum numeric error 0.00001. All nine model-bound float outputs match
  in another 10,000 cases. Trap 318 exposes the bounded measured domain; unsupported domains remain.
- Chat normalization: all 255 nonzero bytes and 100,000 seeded strings match the observed native
  whitespace removal, skipped spans and preserved trailing text.
- Camp/location goals: 1,253 records/cursor results match across 31 maps and two controlled area modes.
- Reply chat: 28,344 generated requests produce 26,718 matching replies and 40,454 matching random
  draws, including shared recent history, capture overrides and priority selection. The corpus uses
  three first-draw samples, zero subsequent samples and zero synonym contexts. A varying stream adds
  9,448 matching requests / 13,277 draws; varying contexts, clocks, states and supplied variables add
  9,448 matching requests / 557,460 draws.

Detailed contracts, corpus limitations and reproducible authored drivers are linked from
[NETWORK68.md](formats/NETWORK68.md), [NETWORK68_DELTA.md](formats/NETWORK68_DELTA.md),
[NETWORK68_MESSAGES.md](formats/NETWORK68_MESSAGES.md),
[DEMOS.md](formats/DEMOS.md), [BOTLIB_ITEMS.md](BOTLIB_ITEMS.md), [BOTLIB_GOALS.md](BOTLIB_GOALS.md)
and [BOTLIB_ITEM_PLACEMENT.md](BOTLIB_ITEM_PLACEMENT.md). The original retail and source-built
modern qagame profiles pass strict sixty-second movement and stationary-target combat runs:

| Profile / map | Moving frames | Horizontal Q3 units | Target health | Kill time | Ammo spent |
| --- | ---: | ---: | --- | ---: | ---: |
| Retail q3dm1 | 1,188 | 16,518.28 | 125 → −12 | 18,900 ms | 15 |
| Retail q3dm17 | 1,187 | 18,194.77 | 125 → −9 | 7,450 ms | 10 |
| Modern q3dm1 | 1,156 | 17,036.46 | 125 → −42 | 36,800 ms | 26 |

Each run has 1,200 samples and a bot score increase from zero to one. These are the post-startup
settling results. The modern profile's former tilted-ceiling and rejected-step failures are resolved.
`auditBots` runs up to ten simulated seconds by default; `-Pq3BotMillis=...` selects 500–300000 ms.
It records the exact failing frame and never labels a caught unsupported service or completed startup
as autonomous gameplay. `-Pq3RequireBotMovement=true` requires the requested duration to finish,
repeated original move-to-goal calls, at least ten moving frames and 128 horizontal units; samples
exclude teleport-bit changes and non-normal player movement. `-Pq3RequireBotCombat=true` also
requires a stationary target death, a bot score increase and ammunition use without a respawn.
No aim or firing command is injected for the bot; original qagame supplies its decisions. This
verifies the listed stationary-target encounters, rather than broad match or map compatibility.
The all-map sweep of the 782-test build completes sustained movement in 58 of 60 map/profile
combinations; only the two q3dm19 moving-platform requests stop execution. Modern q3dm17 passes movement but fails
its additional combat assertion. The full per-map record and limits are in
[BOT_MAP_SWEEP.md](BOT_MAP_SWEEP.md). These historical checks preceded ordinary-bot enablement; the remaining physical
movement domains are still listed explicitly in the compatibility notes.

## Fresh-map initialization regression

Four native-observed startup frames now complete before player connection. The original retail and source-built modern game VMs each pass initialization, living-player connection and two seconds of simulation on all 30 playable AAS maps (60 combinations). Four formerly failing retail map connections now succeed. The synthetic guest verifies export order, timestamps, final clock and queued-command preservation; reproduction and native observation details are in [SERVER_STARTUP.md](SERVER_STARTUP.md).

Both profiles also pass movement/firing/death/respawn and deterministic replay after this clock change. The retail two-client combat audit scores a kill (health 125 to -1, 18 ammunition units spent, frame3600). Retail and modern game/client pairs pass 140 presentation frames, viewport restart and two retained-client map restarts. Their new respective digests are `f74454f5c071c372226bfe82ab7c5356c6e8865e4faa3a47638671303c14e4bf` and `bcf44083b041ec2f832abd6aaca5a178350477be434a40e570922c5f5c73e781`; these replace the pre-settling regression baselines for this audit configuration.

## Progression, swimming and actual UDP checkpoint

The inspected 897-test checkpoint passes original tutorial victory, postgame Next,
q3dm1 startup with Ranger, and fresh-session saved progression; details are in
[the complete campaign replay](ORIGINAL_SINGLE_PLAYER_PROGRESSION.md). Its
original q3dm12 Vulkan smoke passes 900 frames with three moving bots and no
audio failures; [bot play](BOT_PLAY.md) distinguishes that GPU check from the
three five-minute water-map simulations and native swimming corpora.

The same inspected jars also complete [real protocol-68 UDP interoperability](NETWORK68_CLIENT_SESSION.md):
challenge/connect, fragmented gamestates, keyed movement, reliable ring wrap,
deliberate packet loss, forced full-snapshot recovery, a normal native map change
and disconnect. Remote cgame/UI integration and pure verification remain open.
The aggregate report is `/tmp/craftq3-progression-network-aggregate-report.json`;
its isolated runtime is `/tmp/craftq3-progression-network-runtime`.

The final aggregate adds the native-verified initial-solid contact correction and
two raw-AAS regressions, bringing the total to 899 tests. It contains 638 Java 25
classes and is 1,247,022 bytes, with SHA-256
`14485b08d135141a2b114fa0e4aa4796e4dcebf91639a4eb6ac77b2566aea234`.
Only the botlib nested jar differs from the 897-test checkpoint; the network
modules are byte-identical to the actual UDP-tested modules. The final report
and frozen runtime are `/tmp/craftq3-progression-network-final-aggregate-report.json`
and `/tmp/craftq3-progression-network-final-runtime`.

The final 899-test package also repeats the complete original default tutorial
victory/Next/q3dm1/fresh-config reload successfully; its log is
`/tmp/craftq3-single-player-progression899.log`.

Two attempts to repeat the q3dm12 Vulkan capture on the final 899-test package
ended with a clean Minecraft shutdown before `play.result` was written. Neither
log contains a game/render exception, and no new crash report was generated;
the cause of the early exits is unresolved. They are **incomplete smoke checks**,
not passing captures. Logs are `/tmp/craftq3-water-bots899-vulkan.log` and
`/tmp/craftq3-water-bots899-vulkan-retry.log`. The last fully completed, visually
inspected q3dm12 Vulkan capture remains the 897-test checkpoint described above.


## Direct remote play and suspended-item checkpoint

The complete aggregate build passes **987 tests** and package inspection:
671 Java 25 classes, nine nested engine jars, four GLSL files, and no original
assets/QVMs or native engine bundled. The jar is 1,312,677 bytes, SHA-256
`3d5f4781e386ac145782fd588df26ea9c0f7f8f19eacc1809d6721378b415ea5`.
The build log is `/tmp/craftq3-remote-play-aggregate-build.log`, report
`/tmp/craftq3-remote-play-aggregate-report.json`, and inspected engine modules
`/tmp/craftq3-remote-play-runtime`. The packaged 43 Fabric classes also compare
byte-for-byte with the compilation used by the actual application CPU audit.

[Direct remote play](REMOTE_PLAY.md) now connects the original cgame and UI to
protocol 68 networking, processed configstrings/server IDs, a native-observed
clock and snapshot ping. Exact native observations include 5,547 command queries,
16,389 systeminfo effect queries, 1,005,000 clock frames and 20,000 ping queries.
The common source boundary preserves local rendering behavior: the inspected
modules repeat the original retail 140-frame movement/fire/viewport replay with
unchanged digest `d5310cda6d39abcf61f465c8958d3132f993f1e1374524a22713ae96750adbc6`.
That run submits 423 views, 3,686 quads, 6,741 entities and 71 voices; log
`/tmp/craftq3-remote-play-retail-cgame.log`.

The production connection/clock/cgame CPU audit completes two native-server maps,
a normal fast restart and two viewport restarts. The actual Fabric QuakeSession
CPU audit additionally traverses original main/connection/in-game menus,
remote menu playback without pause, 80 background CPU frames with reliable score
requests, main-menu return, error/input-clock recovery and 60 subsequent local
frames. The latter passes 235 q3dm1 frames and 90 q3dm17 frames, 975 views,
52,527 quads and133 voices; log `/tmp/craftq3-remote-application-final.log` and
`.tools/remote-application/application-result.json`. Original source-built public
QA modules match the native server; original media is read directly from pak0.
The modern QA module/media combination reports expected unavailable patch-era
media; this does not establish arbitrary retail/patch/mod compatibility or pixel
identity. Native server processes are isolated to loopback and are not shipped.

[Jump-pad item localization](BOTLIB_JUMP_PAD_ITEMS.md) now publishes the original
q3dm12 suspended armor as goal 15, itemInfo 2, approach area 4421 at its original
position. Native comparisons pass 100,010 box clips, 50,000 complete target-mode
predictions, 20,000 launch cases, 6,000 item queries and 149 launch records across
all 30 original AAS maps. The shared area-volume provider also matches 106,758
native queries. A 60-second original-qagame movement run passes with the host
integration and no unavailable-trajectory warning. This establishes localization
and continued bot movement, not that a bot collected this particular armor.

No new Minecraft/Vulkan window was launched in this checkpoint. The last completed
Vulkan capture remains the 897-test checkpoint above; later unfinished window
attempts are not passing evidence. The current remote application proof is CPU-only.
Pure-server verification, server-side sessions, browsing, downloads, demo/cinematic
presentation, remaining gameplay/fidelity coverage and both Minecraft interoperability
directions remain unfinished; the full roadmap and goal remain unchanged.

The inspected 987-test engine jars repeat the production pump/clock/ping/cgame
exchange: 154 snapshots, 415 frames, two maps, one fast restart, two viewport
restarts, 1,219 views, 50,733 quads, 11,289 entities and 42 voices, with zero rejected
packets and a processed final disconnect. Log:
`/tmp/craftq3-remote-pump-cgame-native987.log`.


## Pure PK3 client checkpoint

The complete aggregate build passes **1,014 tests** and package inspection:
677 Java 25 classes, nine nested engine jars, four GLSL files, and no original
media/QVMs or native engine modules. The 1,328,149-byte distributable SHA-256 is
`1806c4ec1630303c782d617f6bbfe45990d32b8be3de448e6056f0642fe3c834`.
The full report is `/tmp/craftq3-pure-aggregate-report.json`; the log is
`/tmp/craftq3-pure-aggregate-build.log`. Packaged Fabric classes were compared
byte-for-byte with the compilation used by the final CPU application audit.

Native comparisons establish:

- 20,000 arbitrary checksum buffers and 40,007 ordered CRC/feed checks, plus
  direct original pak0 metadata replay. Native normal checksum is 1566731103;
  the signed pure checksums match at feeds 0, 42, -1 and 0x12345678.
- 12,597 filesystem operations across three authored installations, including
  loose-first default lookup, duplicate checksum ordering, pure read/list
  eligibility, original requested path spelling and exact pack reference
  transcripts. Another 57 native boundary assertions cover empty ZIPs and
  related API distinctions.
- 16 pure-wire acceptance/rejection cases, nine vdr/restart/map-transition
  lifecycle stages, and 4,000 exact client cp/vdr formatting calls.

See [PK3_CHECKSUMS.md](PK3_CHECKSUMS.md),
[NETWORK_PURE_FILESYSTEM.md](NETWORK_PURE_FILESYSTEM.md) and
[NETWORK_PURE_VERIFICATION.md](NETWORK_PURE_VERIFICATION.md). These observers use
unchanged native operations, public declarations, authored fixtures and direct
read-only original media access; no native routine bodies were used as recipes.

The actual Fabric CPU application joins a private native pure server using
matching public-source QA QVM packs and original pak0 media. A higher-priority
client-only QVM/map override is excluded from the pure view, and restored on
disconnect. The final run renders 235 first-map and 90 second-map frames,
975 views, 52,782 quads and 121 voice starts; it passes native fast restart,
80 background frames/reliable score requests, the original in-game menu, and
60 local frames after disconnect. The server records admission on both maps
with no unpure rejection. Log: `/tmp/craftq3-pure-final-application.log`.
The nonpure regression renders 236/90 map frames, 978 views, 53,397 quads and 122
voice starts with the same menu/background/recovery assertions; log:
`/tmp/craftq3-nonpure-application-after-pure.log`.

A missing-required-pack application test retains identical public QVM bytes in
a differently checksummed client-only pack. Preflight reports
`Missing server PK3s: baseq3/z_qa_cgame.pk3` after four connecting frames,
preserves the original UI, renders ten recovery menu frames, then 60 local
frames/180 views. Native records one ClientConnect and zero ClientBegin.
Log: `/tmp/craftq3-pure-connection-failure.log`.

The inspected engine jars also repeat the original retail 140-frame
movement/fire/viewport-restart audit with unchanged digest
`d5310cda6d39abcf61f465c8958d3132f993f1e1374524a22713ae96750adbc6`:
72 models, 144 shaders, 108 sounds, 175 marks, 423 views, 3,686 quads, 6,741 entities
and 71 voices. Log: `/tmp/craftq3-pure-retail-cgame.log`.

Content-view transitions retire old UI/cgame registrations and reset the
production audio asset cache; six no-GPU cache tests verify retirement batches
and replacement-buffer ownership. Stale extracted frames are not rendered
across session generations. This checkpoint does **not** include a new
Minecraft/OpenAL device run: the last completed actual Vulkan gameplay capture
remains the 897-test checkpoint. Pure UI/audio/device presentation still needs
that separate regression. Downloads, automatic game-directory switching,
mid-gamestate pure/allowlist changes, server/browser/demo integration, broader
campaign/team matches, remaining fidelity work and Minecraft interoperability
remain incomplete.


## Original multiplayer browser checkpoint

The complete aggregate build passes **1,082 tests**, including Spotless and
compiler warnings-as-errors, with zero failures/errors/skips. The inspected
1,394,673-byte distributable contains **710 Java 25 classes**, nine nested engine
jars and four GLSL resources. It contains no original media/QVMs or native engine
modules. SHA-256:
`f4b1f74abbe47b0e3c8b7aea338a8b3a09ea6eeaebe311a392de6536e21e76e9`.
All 45 packaged Fabric classes are byte-identical to the final actual CPU
application classes. Reports and immutable engine runtime are in ignored local
`/tmp/craftq3-browser-aggregate-report.json` and `/tmp/craftq3-browser-runtime`.

The increment adds 58 client tests, six master-parser tests and four platform
transport tests. It covers the original retail/1.32 guest ABI, full versus minimal
output-buffer padding, pending-status preservation, invalid-pointer ordering,
server storage/sorting/identity, ping lifetimes, master/status parsing, bounded
asynchronous DNS, atomic cache restore, endpoint filtering and nonblocking socket
ownership. The native comparisons cover 99,531 LAN comparisons (1,108 explicit
NA_BAD display exclusions), 179,794 ping operations, 15,000 status operations and
3,000 master packets. See [browser details](SERVER_BROWSER.md) and its linked
native contracts/probes.

The actual Fabric CPU application ran both original UI profiles through
Multiplayer, refresh, a displayed native server row, positive ping, original Join
and return to the main menu. Retail API3 recorded two refreshes, ping63,
exactly one UI-generated connect, 90 remote frames, 270 views and 9,792 quads.
API4 recorded two refreshes, ping70, exactly one UI-generated connect,
90 remote frames, 270 views and 9,379 quads. These are observed timings and frame
counts, not fixed expectations for public servers. The tests substitute only the
LAN target list with the private loopback endpoint, leaving production transport,
reply parsing and UI row/Join behavior intact. No fabricated row or injected
connect command supplies success. Public masters are disabled before opening the
menu. Results live in `.tools/browser-application/{retail,modern}`.

The inspected runtime also passes the original pak0 qagame/cgame replay: 140
movement/fire frames and viewport restart, unchanged deterministic digest
`d5310cda6d39abcf61f465c8958d3132f993f1e1374524a22713ae96750adbc6`,
72 models, 144 shaders, 108 sounds, 175 marks, 423 views, 3,686 quads,
6,741 entities and 71 voices.

The final pure CPU application regression passes 236/90 remote map frames,
978 views, 52,778 quads and 128 audio voices, including native fast restart,
80 background frames/reliable requests, original remote menu continuity,
disconnect/error recovery and 60 subsequent local frames. The native server
admits both maps; client-only overriding packs are filtered and then restored.
Logs: `/tmp/craftq3-browser-pure-application.log` and
`/tmp/craftq3-browser-retail-cgame.log`.

No new Minecraft window, GPU capture or physical audio-device smoke was run for
this browser increment. The last completed Vulkan smoke remains the separate
897-test checkpoint. Public-master reachability, arbitrary mod compatibility,
IPv6 multicast LAN discovery and native binary-cache import remain unverified or
unsupported as documented. Server hosting, demos, downloads, remaining Quake
compatibility and both Minecraft crossover modes remain ahead.


## Protocol-68 recording and playback checkpoint (2026-09-06)

The full Java 25 build passes **1,138 tests**, with zero failures, errors or skips:
core 182, assets 94, collision 10, VM 26, server 67, client 192, render 58,
platform 14, Fabric 9 and botlib 486. The inspected distributable contains 731
Java-25 classes, nine nested engine jars and four GLSL shaders, with no original
media/QVMs or native engine bundled. All 45 packaged Fabric classes match the
classes used by the final actual CPU application audit.

- Artifact: `craftq3-fabric/build/libs/craftq3-0.1.0.jar`, 1,444,522 bytes.
- SHA-256: `6ebd627e04cb053479b5f9623bfbda76e439fa9d5a027588f7c8f83173354d51`.
- Build: `/tmp/craftq3-demo-aggregate-build.log`.
- Inspection: `/tmp/craftq3-demo-aggregate-report.json`.
- Immutable extracted engine runtime: `/tmp/craftq3-demo-runtime`.

The actual Fabric CPU application records 59 local snapshots in both original
retail and matching-modern profiles, then replays them through original cgame
(516 views with the freeze check) and the original Demos menu (546 views).
The final modern run records 177 snapshots from a private unchanged native server
and replays 1,698 views across its fast restart and q3dm1-to-q3dm17 transition.
Packet/view counts are observed run results, not timing guarantees. Playback
creates no game server or network connection. The same audit verifies timedemo,
nextdemo command chaining, native exit keys, clean EOF, missing-file/map recovery,
and preservation of an existing recording when the initial gamestate exceeds
an eight-byte recording quota. Both UI profiles pass. Logs and JSON results:
`.tools/demo-application/{retail,modern}`; repeat with
`scripts/AuditDemoApplication.py` using Java 25. No public discovery or game traffic
is used, and original PK3s remain direct read-only archive inputs.

Native evidence includes 10,000 demo-clock frames, 290 lifecycle/key transcripts,
initial/later recorder message comparisons, and 108 outbound configstring commands
(86,022 bytes) with no native differences. The parser retains commands around
unusable delta snapshots without publishing their invalid state. Streaming storage, atomic replacement and quota limits have focused tests; the new nonzero-body
regression exercises player fields/arrays, entity change/removal/addition, retained
snapshots and later recovery. See [demo use](DEMO_PLAY.md),
[recording](DEMO_RECORDING.md), [local feed](LOCAL_DEMO_FEED.md),
[storage](DEMO_STORAGE.md), [clock](DEMO_SERVER_CLOCK.md),
[catalog](DEMO_CATALOG.md) and [native contracts](NETWORK68_DEMO_HOST.md).

The inspected runtime passes the original pak0 qagame/cgame regression again:
140 movement/fire frames plus viewport restart; digest
`d5310cda6d39abcf61f465c8958d3132f993f1e1374524a22713ae96750adbc6`,
72 models, 144 shaders, 108 sounds, 175 marks, 423 views, 3,686 quads,
6,741 entities and 71 voices. The final pure-server CPU regression also passes:
235/90 map frames, 975 views, 53,705 quads, 132 voices, 80 background frames,
remote menu continuity/error recovery, and 60 local frames after disconnect.
Native admission succeeds on both maps and client-only overriding packs are
filtered/restored. Logs: `/tmp/craftq3-demo-retail-cgame.log` and
`/tmp/craftq3-demo-pure-application.log`.

No Minecraft window, Vulkan demo capture or physical audio-device smoke was run
for this increment. The last completed Vulkan smoke remains the 897-test
checkpoint. Protocol-43 payload playback, seeking, video export, exact native
timedemo summary formatting and broader demo render fidelity remain incomplete.
Server hosting, downloads, remaining Quake compatibility and both Minecraft
crossover modes remain ahead.


## Server connection and admission foundation (2026-09-06)

The full build passes **1,145 tests**, with no failures, errors or skips. The seven
new core tests exercise established server-side protocol-68 sessions: fragmented
gamestates, both reliable/XOR directions, loss/retransmission and snapshot delta
bases, duplicate movement/commands, late malformed gaps, level changes and
acknowledgement boundaries. This is component coverage; a hosted UDP listener and
original-menu server creation are not connected yet.

The inspected jar is 1,452,950 bytes, with 734 Java-25 classes, nine nested engine
jars and four GLSL shaders. No original media/QVM or native engine is bundled.
SHA-256: `27ec757b6f271d3276b567643a89a684fd4306d852e7b8f3f5ee67f66c4c4d1b`.
Build/inspection: `/tmp/craftq3-server-foundation-build.log` and
`/tmp/craftq3-server-foundation-aggregate-report.json`; immutable engine runtime:
`/tmp/craftq3-server-foundation-runtime`.

The original retail and public source-built 1.32 qagame audits both pass delayed
human admission, rejection of premature movement, first-command entry, a retained
player plus a pending player across fast restart, 60 two-player movement/snapshot
frames, and disconnect/slot reuse. Original game-generated entry announcements
verify that pending players do not enter early. The final runs use the inspected
runtime and direct read-only original pak0 ZIP inputs. Logs:
`/tmp/craftq3-retail-admission.log` and `/tmp/craftq3-modern-admission.log`.

An authored native observer records 43 bounded acknowledgement, command sequence,
movement and admission-policy cases without inspecting/translating native routine
bodies. The exact callback scope, native/Java error-path differences and remaining
host responsibilities are in [server session notes](NETWORK_SERVER_SESSION.md).

The packaged original retail qagame/cgame regression passes 140 movement/fire
frames, viewport restart and two retained-client map restarts. Its deterministic
restart-mode digest is
`f0014d443855fa90899c82d464efdc40d8a7a1dcd012a9b0a871535bd41182b0`, with
72 models, 144 shaders, 108 sounds, 197 marks, 423 views, 3,686 quads,
6,741 entities and 75 voices. The original q3dm1 bot regression passes 200 samples,
188 moving frames and 2,686.5 horizontal Quake units traveled. This particular bot
check asserts movement/lifecycle, not combat. Logs:
`/tmp/craftq3-server-foundation-retail.log` and
`/tmp/craftq3-server-foundation-bots.log`.

No public networking, Minecraft window or new Vulkan capture was used in this
increment. The full standalone game and both requested Minecraft crossover modes
remain incomplete; hosting transport/application integration is next.


## Hosted application integration (2026-09-06)

The full build passes **1,151 tests**, zero failures/errors/skips. Six new tests
cover hosted UDP transport and pre-admission challenge/discovery boundaries.
The inspected JAR contains 741 Java 25 classes, nine nested engine jars, four
GLSL resources and no original assets or native modules. SHA-256:
`10ac6f4587f5fb19fce6ae830ac4559fb97fd46ac0f56b6603d67adf2e86a637`.
Build and inspection: `/tmp/craftq3-host-integration-build.log`,
`/tmp/craftq3-host-integration-aggregate-report.json`; immutable engine runtime:
`/tmp/craftq3-host-integration-runtime`.

Both original Create Server menus pass actual host/remote Fabric CPU sessions
using private ephemeral loopback, original read-only pak0, and retail or public
source-built modern QVMs. Each records 222 first-map and 93 second-map frames,
with 915 retail / 918 modern views in the final run. Pure joining, restart,
same-port q3dm1 → q3dm17 replacement, menus/background ticking, disconnect,
dedicated mode and host-shutdown return to the original menu pass.
Reproduce with `scripts/AuditHostedApplication.py`; final logs live under
`.tools/hosted-application/{retail,modern}/application.log`, with adjacent JSON
results. `/tmp/craftq3-hosted-application-audit.log` summarizes both profiles.

The inspected packaged engine also passes two UDP clients against original
retail qagame: 160 snapshots each, maximum horizontal movement 463.6 / 392.2
units, 26 deliberately dropped outgoing datagrams, and client disconnect.
Log: `/tmp/craftq3-host-integration-wire.log`. This complements the source-class
Fabric application audit; it is not native-client interoperability proof.

Packaged retail deterministic replay remains unchanged after 140 input frames,
a viewport restart and two retained-client map restarts: digest
`f0014d443855fa90899c82d464efdc40d8a7a1dcd012a9b0a871535bd41182b0`,
423 views and 6,741 entities. The ten-second q3dm1 bot regression records
200 samples / 188 moving frames / 2,686.527 horizontal units and passes the
movement-only assertion. Logs: `/tmp/craftq3-host-integration-retail.log` and
`/tmp/craftq3-host-integration-bots.log`.

Native-client hosting proof, independent pure-validator comparison, measured
status ping, byte-rate scheduling, downloads and remaining server services are
still pending. No Minecraft window or new Vulkan capture was used. Both
Minecraft crossover modes remain unimplemented. See
[hosting usage and limits](NETWORK_SERVER_SESSION.md).


## Native hosted-wire and pure-policy checkpoint (2026-09-06)

The full build passes **1,154 tests**, no failures/errors/skips. Inspected JAR:
1,478,996 bytes, 743 Java 25 classes, nine nested engine jars, four GLSL resources,
no original assets/native modules. SHA-256:
`55b5217d5e2e6b31d10fb4a0aa5c59750ec82d50b00e7184e3c4175e2c381e97`.
Build/inspection: `/tmp/craftq3-host-native-build.log`,
`/tmp/craftq3-host-native-aggregate-report.json`; immutable engine runtime:
`/tmp/craftq3-host-native-runtime`.

The inspected engine passes 585 comparisons with unchanged native pure
validation: 584 agreements plus the explicitly stricter nondecimal-checksum
case. Old-generation responses are now ignored, preserving existing pure
admission. The native reset observer also confirms that `vdr` clears both flags.
Native fixture source/linkage and result bounds are described in
[hosted sessions](NETWORK_SERVER_SESSION.md). Logs:
`/tmp/craftq3-host-native-pure.log`, `.tools/pure-server-oracle/comparison.json`,
`.tools/pure-server-oracle/transcript.log`.

Both original game ABIs pass the native established-client wire audit against
the inspected engine: two gamestates, 166 snapshots / 103 first-map snapshots,
14 lost outgoing datagrams, movement, fast restart without a map reload, full
map replacement and stale-pure-response continuity. Maximum first-map movement:
335.436 retail / 413.269 modern Quake units. Logs:
`/tmp/craftq3-host-native-client-audit.log` and
`.tools/hosted-native-client-oracle/{retail,modern}/audit.log`.
The complete native client application is not exercised: handshake, filesystem
and frame callbacks are authored boundaries around unchanged client packet,
XOR, parsing and config-command operations. These fixtures are development-only.

Original host/remote Fabric CPU application regression results are recorded in
`/tmp/craftq3-host-native-application.log`; no new Vulkan capture is claimed.
Measured host status ping, byte-rate scheduling, downloads and remaining server
services are still incomplete. The full standalone game and both Minecraft
crossover modes remain the active objective.


## Hosted timing and status checkpoint (2026-09-06)

The full build passes **1,158 tests**, no failures/errors/skips. Final inspected
JAR: 1,483,710 bytes, 745 Java 25 classes, nine nested engine jars, four GLSL
resources, no original assets/native modules. SHA-256:
`f11d8bb9e6d5739166ec4ced41acd3b8d7876997d32a96580cce076b4bdc1ef7`.
Build: `/tmp/craftq3-host-timing-build.log`; final inspection/runtime:
`/tmp/craftq3-host-timing-final-aggregate-report.json`,
`/tmp/craftq3-host-timing-final-runtime`.

2,400 native rate/ping/userinfo cases match. Four new tests cover sequence-ring,
fragment completion, duplicate/future acknowledgement, byte pacing, live rate
change and default/bound behavior. Reproduce with `BuildHostTimingOracle.py`
and `AuditHostTiming.py`; comparison JSON:
`.tools/host-timing-oracle/comparison.json`.

Both original gameplay ABIs pass actual delayed-UDP tests against the final
packaged engine. A 96 ms one-way delay and 23 retail / 24 modern dropped packets
produce 128 ms retail / 155 and 128 ms modern measured player/status ping. The
1000-byte/sec client receives 43 retail / 47 modern snapshots before changing
its rate to 25000; each then receives 68 more. The second client receives
159 retail / 156 modern snapshots. Every outgoing packet is checked against its
current pacing budget; movement and disconnect pass. Status parsing was fixed
for original player configstrings without the leading info separator.
Logs: `/tmp/craftq3-host-timing-udp.log` and
`.tools/hosted-timing/{retail,modern}/audit.log`.

The native established-client regression and original Create Server/Fabric CPU
regressions are in `/tmp/craftq3-host-timing-native-client.log` and
`/tmp/craftq3-host-timing-application.log`. No new Vulkan capture is claimed.
Simulation-frequency changes, LAN force-rate policy, downloads, master/admin
services and complete native application coverage remain; details and observed
clock/numeric boundaries are in [hosted sessions](NETWORK_SERVER_SESSION.md).
The full standalone Quake and two Minecraft crossover modes remain the goal.


## Download transfer checkpoint (2026-09-06)

The full build passes **1,166 tests**, zero failures/errors/skips. The inspected
JAR contains 752 Java 25 classes, nine nested engine jars and four GLSL resources,
with no original assets or native modules. Size: 1,496,275 bytes. SHA-256:
`075608720c3906a94790278a2f4d170b075fb058db8d6471e3b99c0796ba4a01`.
Build/inspection: `/tmp/craftq3-download-transfer-build.log`,
`/tmp/craftq3-download-transfer-aggregate-report.json`; immutable engine runtime:
`/tmp/craftq3-download-transfer-runtime`.

Seven new core tests cover the download codec, 48-block streaming window,
retransmission/acknowledgement guards, lengths/errors, real 64 MiB block wrap and
session file resets. One asset test covers indexed archive streaming and forged
origin / substituted-symlink rejection. The inspected engine passes 281 native
writer/parser comparisons and both original gameplay profiles' actual private
UDP transfers: 65,729 fixture bytes, 66 blocks and 70 deliberately lost packets
per profile, followed by gamestate/pure resumption, original-game entry and an
official-pack refusal. Logs: `/tmp/craftq3-download-transfer-native.log` and
`/tmp/craftq3-download-transfer-udp.log`.

The original-menu host/remote Fabric CPU regressions also pass both profiles,
including restart, map replacement, dedicated mode and shutdown recovery:
924 retail / 918 modern views; `/tmp/craftq3-download-transfer-application.log`.
No new Vulkan capture is claimed.

This establishes the transfer service. The receiver audit uses a staging sink
and a pre-mounted fixture for pure resumption. Automatic client missing-pack
selection, checksum/ZIP validation, installation and content refresh remain
unfinished; the Fabric menu still reports missing packs for manual installation.
See [download support and limits](NETWORK_DOWNLOADS.md). The full standalone
Quake game and both Minecraft crossover modes remain the active objective.


## Automatic download installation checkpoint (2026-09-06)

The full build passes **1,181 tests**, with zero failures/errors/skips. Fifteen new
asset/client tests cover verified cache publication, reopen/mount/version reuse,
CRC/checksum/traversal/duplicate rejection, cancellation, symlink and lock guards,
file quotas, unsolicited traffic and waiting for a fresh gamestate before play.
The inspected package contains 759 Java 25 classes, nine nested engine jars and
four GLSL resources; no original assets or native modules are bundled. Size:
1,519,861 bytes. SHA-256:
`d483c6c57a02424f3ba284ca71a0c34f30ef5815e65243f6dab1f577987891b4`.
Build/inspection: `/tmp/craftq3-download-install-final-build.log`,
`/tmp/craftq3-download-install-final-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-download-install-final-runtime`.

`AuditDownloadApplication.py --runtime /tmp/craftq3-download-install-final-runtime`
passes both original retail UI API 3 and modern UI API 4 profiles. Actual Fabric
CPU host/client sessions have different pack inventories. Two authored missing
PK3s download into a separate cache, original UI progress cvars update, every
member CRC and the advertised checksum pass before publication, original
cgame/UI reload and pure gameplay resumes. A new client session reconnects with
both sides' downloads disabled using persisted cache entries. Cancellation returns
to the menu with no staging residue. A modified stored member in an authored host
fixture fails CRC validation and returns to the menu without publishing the pack.
The audit asserts the application/controller/cache classes load from inspected
jars. Original pak0 is read-only and never transmitted or extracted. Log:
`/tmp/craftq3-download-install-final-application.log`.

The preceding package with byte-identical engine modules also passes 281 native
download writer/parser comparisons and both profiles' private lossy UDP transfer
regressions (65,729 bytes, 66 blocks, 70 lost packets, fresh gamestate/pure entry
and official-pack refusal). Logs: `/tmp/craftq3-download-install-native.log`,
`/tmp/craftq3-download-install-udp.log`. The final Fabric-only change prevents
recording during connection loading. Existing hosting lifecycle regressions run
against the final inspected runtime; see
`/tmp/craftq3-download-install-final-host-regression.log`.

Downloads are opt-in, target the already-mounted game, and use the isolated
`craftq3/downloads` cache. HTTP redirects, automatic game-directory switching,
cache eviction/UI and complete native home/base/CD path layering remain ahead.
No new Vulkan capture or full native-client application coverage is claimed.
Standalone Quake and both Minecraft crossover modes remain the active goal.


## First playable Minecraft bridge (2026-09-06)

The user prioritized gameplay interoperability before the remaining standalone
Quake compatibility work. `/q3 bridge` now opens a local-world prototype: original
qagame and cgame run over live Minecraft block collision, while Minecraft renders
terrain and Quake renders its weapon/HUD. Escape closes the bridge and restores
the prior Minecraft game mode. Mob damage and the reverse building mode remain
unfinished; see [bridge support and limits](MINECRAFT_BRIDGE.md).

The complete build passes **1,194 tests**, zero failures/errors/skips. Three new
grid tests cover 500 exhaustive collision comparisons, changed cells, fluids and
long diagonal traversal. Ten additional tests belong to the paused, unconnected
RoQ decoder; they do not establish cinematic playback. The inspected JAR contains
771 Java 25 classes, nine engine jars and four GLSL resources, with no original
assets or native modules. Size: 1,552,202 bytes. SHA-256:
`59afbcf29306b7783c9108b5157c7a28f61bfd03f96d3a4dd2b4ad7d5d8b49fc`.
Build: `/tmp/craftq3-bridge-build.log`; inspection:
`/tmp/craftq3-bridge-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-bridge-runtime`.

Both packaged QVM profiles pass `AuditBridgeGameplay.py`: an external block wall
stops the original player at x=144.867; removing it permits x=1014.467; a jump
reaches z=69.625. Both produce 300 cgame views and original HUD commands (2321
retail / 2021 modern). No bridge BSP file is supplied. Log:
`/tmp/craftq3-bridge-packaged-gameplay.log`.

A live Minecraft 26.2 / Java 25 Vulkan smoke opens only the separate
`run/saves/CraftQ3 Bridge QA` copy of the test world. The final run moves 24.6047
blocks, captures the Minecraft terrain with the Quake weapon/HUD, exits the bridge
and asserts that the actual integrated player's mode equals its previous CREATIVE
mode before stopping Minecraft. Log: `/tmp/craftq3-bridge-final-smoke.log`;
result: `run/craftq3-bridge.result`; inspected capture:
`run/screenshots/craftq3-bridge.png`. This uses development classes from the final
build; it is not a separate installed-JAR Minecraft launch. The original test world
and original PK3s are not modified. The packaged standalone q3dm17 cgame regression
is logged at `/tmp/craftq3-bridge-pure-regression.log`.


## Minecraft bridge mob combat checkpoint — 2026-09-06

The complete build passes **1,197 tests**, zero failures/errors/skips, including
three new damage-ledger tests. Spotless and warnings-as-errors pass. The inspected
1,574,334-byte Fabric JAR contains 780 Java 25 classes, nine nested engine jars,
four GLSL resources and no original game/native assets. SHA-256:
`809c513d211cbc291002b8752c354378a12c8575defaaab0e241ed92b6f47def`.
Build: `/tmp/craftq3-bridge-combat-build.log`; inspection:
`/tmp/craftq3-bridge-combat-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-bridge-combat-runtime`.

Both packaged original QVM profiles pass bullet cover/exposure (100/86 health),
rocket damage (-100 health), and splash against a short host-sized target below
the direct shot (100 behind cover, -16 exposed). Health synchronization, hidden
proxy models, removal, admission without telefrag and map restart cleanup pass.
Log: `/tmp/craftq3-bridge-combat-packaged.log`. Both packaged movement/jump/block
removal audits still pass with the prior exact results:
`/tmp/craftq3-bridge-combat-movement.log`. Packaged standalone q3dm17 gameplay and
viewport restart still pass with unchanged digest
`d5310cda6d39abcf61f465c8958d3132f993f1e1374524a22713ae96750adbc6`:
`/tmp/craftq3-bridge-combat-pure-regression.log`.

The final development client, built from the same production code, passes on
Minecraft 26.2 / Java 25 / Vulkan. The private QA world fixture verifies no damage
through a real stone wall, removes it, then verifies an actual iron-golem death
and actual restoration of Creative mode. Result:
`PASS cover=true appliedHits=15 killedTargets=1 moved=0.0 restoredMode=CREATIVE`.
Log: `/tmp/craftq3-bridge-combat-final-smoke.log`. The inspected screenshot shows
Minecraft death particles/drops and the corrected original Quake frag message.
The latest `run/craftq3-bridge.result` and `run/screenshots/craftq3-bridge.png`
now contain this combat run, superseding the first bridge movement capture.

This proves outgoing mob damage, not reciprocal mob attacks or multiplayer.
Minecraft defenses apply after the normalized Quake damage; protected mobs can
outlive original Quake frag feedback. See [bridge limits](MINECRAFT_BRIDGE.md).


## Reciprocal Minecraft combat checkpoint — 2026-09-06

The full build passes **1,199 tests**, zero failures/errors/skips. Two additional
collision-window tests cover both ABI layouts, inert hurt descriptors, selected
contact, reset cleanup and invalid damage. The inspected 1,586,790-byte Fabric JAR
contains 787 Java 25 classes, nine nested engine jars, four GLSL resources and no
original game or native assets. SHA-256:
`3a18296467b0075d02acbd698f8f1590d8695db3092550a1afaead2a00ccb298`.
Build: `/tmp/craftq3-incoming-build.log`; inspection:
`/tmp/craftq3-bridge-reciprocal-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-bridge-reciprocal-runtime`.

Both original qagame profiles pass incoming 25-point damage, original armor
absorption, lethal queued damage, original respawn and all outgoing combat checks
from the prior checkpoint. `/tmp/craftq3-reciprocal-packaged.log` records those
packaged runs. Both original cgame/qagame movement audits now include all 255 hurt
adapters and check body feet above the floor; their exact prior movement/jump/HUD
results are unchanged: `/tmp/craftq3-reciprocal-movement.log`. The 255 descriptors
plus the world fit the original 256-model limit. The packaged standalone q3dm17
regression also retains its prior deterministic digest:
`/tmp/craftq3-reciprocal-pure-regression.log`.

A final live Minecraft 26.2 / Java 25 / Vulkan run uses normal zombie AI with
increased fixture attack strength. Two accepted attacks kill the Quake player;
Minecraft health is asserted unchanged, the fixture zombie is removed, original
Quake fire input respawns the player and exit restores Creative mode:
`PASS incomingHits=2 death=true respawn=true health=118 moved=0.0 restoredMode=CREATIVE`.
Log: `/tmp/craftq3-reciprocal-incoming-smoke.log`; preserved result and inspected
capture: `/tmp/craftq3-reciprocal-incoming.result` and
`/tmp/craftq3-reciprocal-incoming.png`. This proves natural melee attack delivery;
projectile/status-effect variants still need broader live coverage.

A fresh outgoing live run also passes after the mode/physics changes:
`PASS cover=true appliedHits=15 killedTargets=1 moved=0.0 restoredMode=CREATIVE`.
Log: `/tmp/craftq3-reciprocal-outgoing-smoke.log`; preserved result and inspected
capture: `/tmp/craftq3-reciprocal-outgoing.result` and
`/tmp/craftq3-reciprocal-outgoing.png`. Both final captures have the Quake weapon
without the unwanted Minecraft hand.

An earlier repeat exposed an eye-height/body-placement bug: Minecraft feet were
slightly below the Quake floor, and repeated QA setup eventually crossed the
Minecraft build limit. That unsuccessful run is retained at
`/tmp/craftq3-incoming-final-smoke.log`. The fix uses original Quake body bounds for
avatar feet and an independent cgame camera position. The QA setup also recovers
its private test avatar after an interrupted/dead/below-build-limit run. The final
incoming and outgoing runs above use the corrected code.

Remaining limits include the 1–255 incoming damage policy, hurt-trigger obituary
attribution, shared rendering depth/FOV, expanded status/knockback behavior, player
targets, multiplayer and Minecraft building inside Quake BSP maps. See
[Minecraft bridge](MINECRAFT_BRIDGE.md).


The final live movement/jump regression also passes with the targetable avatar:
`PASS moved=26.53917668970623 restoredMode=CREATIVE`, with no Minecraft movement
rejection. Log: `/tmp/craftq3-reciprocal-movement-smoke.log`; preserved result and
inspected capture: `/tmp/craftq3-reciprocal-movement.result` and
`/tmp/craftq3-reciprocal-movement.png`. The current shared run result/screenshot
paths contain this movement run. All three final live runs use the same packaged
production code; the distributable SHA-256 was rechecked afterward and is unchanged.


## Minecraft building in original BSPs — 2026-09-06

The full build passes **1,204 tests**, zero failures/errors/skips. Five new tests
cover native-sized swept bodies over BSP-equivalent geometry, floor/wall sliding,
fractional/grid placement, valid native click coordinates, rejected solid/missed
queries, durable BSP identities and rejection of aliased region indexes. Spotless
and warnings-as-errors pass. The inspected 1,622,205-byte Fabric JAR contains 799
Java 25 classes, nine nested engine jars, four GLSL resources and no original game
or native assets. SHA-256:
`9d35ee2007aff16134b54fc9ac6096d1b646b7555ee856255b50ebae671c6523`.
Build: `/tmp/craftq3-building-build.log`; inspection:
`/tmp/craftq3-minecraft-building-final-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-minecraft-building-final-runtime`.

The final development client uses the same production code and passes on
Minecraft 26.2 / Java 25 / Vulkan with original retail q3dm17. The smoke uses the
native Minecraft item-use and destroy-block paths, verifies stone on the integrated
server, breaks it, replaces it and checks restoration of dimension, position and
game mode. It also verifies the block at `(15,39,-36)` persisted from a previous
Minecraft process in the same private QA world. The explicit result is:
`PASS building placed=true broken=true returned=true persisted=true cell=BlockPos{x=17, y=39, z=-35}`.
Log: `/tmp/craftq3-building-persistence-smoke.log`; preserved result:
`/tmp/craftq3-building-final.result`; inspected captures:
`/tmp/craftq3-building-final.png` and `/tmp/craftq3-building-final-overview.png`.
The source BSP remains read-only; actual Minecraft chunk storage persists blocks.

The initial image exposed a depth convention mismatch. The corrected building
pipeline uses Minecraft's reversed depth and exact projection; standalone Quake
keeps its own conventional depth. The final image shows native stone blocks and
Minecraft's hand/hotbar against original q3dm17 surfaces. Earlier fixture failures
are retained in `/tmp/craftq3-building-smoke.log`,
`/tmp/craftq3-building-depth-smoke.log` and
`/tmp/craftq3-building-packaged-smoke.log`: these exposed native hit-coordinate
bounds, candidate/body overlap and the test area filling with previously saved
blocks. The final fixture searches additional reachable clear cells.

Both packaged original qagame profiles still pass all forward-bridge combat
checks, including armor, incoming damage, death, respawn, outgoing rockets/splash
and cover: `/tmp/craftq3-building-final-bridge-regression.log`. Packaged standalone
qagame/cgame movement/firing/viewport restart retains the prior deterministic
digest `d5310cda6d39abcf61f465c8958d3132f993f1e1374524a22713ae96750adbc6`:
`/tmp/craftq3-building-final-pure-regression.log`. A fresh standalone Vulkan input
smoke also passes: `/tmp/craftq3-building-pure-vulkan-smoke.log`, with explicit
`PASS` in `run/craftq3-smoke/play.result` and inspected capture
`run/screenshots/craftq3-play-q3dm17-input-vulkan.png`.

This establishes the first native Creative building slice, not complete Minecraft
mechanics over arbitrary BSPs. Step-up/slope/support/fluid behavior, unusual block
materials and maps, BSP-aware AI/projectiles, mixed lighting, Quake entities inside
building mode and abrupt-process return recovery still require broader work.
See [Minecraft building](MINECRAFT_BUILDING.md).


## Shared BSP/block movement and native stepping — 2026-09-06

The full build passes **1,209 tests**, zero failures/errors/skips, including
formatting and warnings-as-errors. Five additional tests cover native/BSP axis
composition after a floor changes the path height, reachable and excessive BSP
ledges, native/BSP headroom, and sorted candidate heights for small entities.
The old whole-world post-clipping pass is replaced by an analytic shape scoped to
Minecraft’s movement and step-candidate collectors. Native world shape queries
remain independent of this movement adapter.

The inspected 1,628,861-byte JAR contains 802 Java 25 classes, nine nested engine
jars, four GLSL resources and no original or native assets. SHA-256:
`18d853098c2203b482dd91594686c0fd5f26f0ff9f4e104e28cb8a821c08edd7`.
Build: `/tmp/craftq3-movement-build.log`; inspection:
`/tmp/craftq3-minecraft-movement-final-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-minecraft-movement-final-runtime`.

A development client built from the final production code passes on Minecraft
26.2 / Java 25 / Vulkan. In unchanged original retail q3dm17, the smoke invokes
Minecraft’s actual collision resolver and `Entity.move`: a grounded player steps
0.125 blocks at `(19.25,38.12890625,-21.25)`, while the airborne case remains blocked.
The fixture restores its temporary player position before the next client tick.
Placement, breaking, server block storage, return state and a block persisted from
a separate Minecraft process all pass in the private QA world. Result:
`PASS building placed=true broken=true returned=true persisted=true movement=step=true airborne=true rise=0.125 at=19.25,38.12890625,-21.25 cell=BlockPos{x=15, y=39, z=-33}`.
Log: `/tmp/craftq3-movement-final-live.log`; preserved result and inspected capture:
`/tmp/craftq3-movement-final.result`, `/tmp/craftq3-movement-final.png`.

Both packaged original QVM profiles pass the existing reciprocal combat audit:
`/tmp/craftq3-movement-final-combat.log`. Packaged standalone qagame/cgame movement,
firing and viewport restart pass with the same deterministic digest
`d5310cda6d39abcf61f465c8958d3132f993f1e1374524a22713ae96750adbc6`:
`/tmp/craftq3-movement-final-pure.log`.

This is one original-map movement case plus bounded synthetic geometry coverage.
It does not establish all stairs/slopes, support blocks, fluids, BSP-aware AI,
projectiles, unusual block behavior or all-map compatibility.

A fresh forward `/q3 bridge` development-client Vulkan movement/jump regression
also passes: `PASS moved=26.53917668970623 restoredMode=CREATIVE`.
Log: `/tmp/craftq3-movement-final-forward.log`; preserved result:
`/tmp/craftq3-movement-final-forward.result`. The distributable hash is unchanged
following both live client runs.


## Durable Minecraft return recovery — 2026-09-06

The full build passes **1,213 tests**, zero failures/errors/skips. Four new tests
cover all 14 return fields, exact coordinates/rotation/game mode/abilities,
repeatable reads, every missing required field, invalid numbers/flags/dimensions/
modes, malformed property escapes and oversized records. Invalid data is rejected
before teleporting and remains on disk. Minecraft identifier errors and malformed
property escapes now become checked recovery failures rather than escaping the
join handler.

Return journals are retained after both normal leave and recovery. A later join
outside the build dimension confirms the return reached Minecraft storage before
removing the journal. The former immediate deletion allowed a second crash to
reload old build-dimension player data without any return point. Failure to clean
an obsolete journal does not block joining an already restored world. Invalid
active recovery records instead produce a connection error and preserve the file.

The final 1,636,053-byte Fabric JAR contains 804 Java 25 classes, nine nested engine
jars, four GLSL resources and no original/native assets. SHA-256:
`ab43fdf37585856b839cd25996d6c4ebf5b1fd079c3eb4c9517e4bd5be22cfb6`.
Build: `/tmp/craftq3-recovery-release-build.log`; inspection:
`/tmp/craftq3-minecraft-recovery-release-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-minecraft-recovery-release-runtime`.

Seven separate Minecraft 26.2 / Java 25 / Vulkan development-client processes
verify the crash lifecycle in the private `CraftQ3 Bridge QA` world:

1. Save the player inside q3dm17 and terminate without shutdown hooks.
2. Reopen from the saved build dimension, restore all 14 fields, then terminate
   again immediately without saving the restored player; the journal remains.
3. Reopen again from the old build-dimension save, restore and stop normally.
4. Reopen from the saved outside dimension, verify state and journal removal.
5. Enter/build/save again, execute the normal `BuildingSession.leave` path, verify
   restoration and journal retention, then terminate before another Minecraft save.
6. Reopen the old build-dimension save and recover normally.
7. Reopen outside the build dimension and confirm state and journal cleanup.

All seven emit explicit `PASS recovery` results. Logs and preserved results:
`/tmp/craftq3-recovery-final-01_checkpoint.{log,result}` through
`/tmp/craftq3-recovery-final-07_reload.{log,result}`. The fixture uses a fixed QA
identity and valid Adventure abilities with nondefault flight/walking speeds.
The first exploratory reload used nonstandard Adventure flight/invulnerability
overrides; vanilla Minecraft resets those during ordinary loading. That fixture
failure is retained in `/tmp/craftq3-recovery-reload.log`; the final seven-stage
sequence uses normal mode capabilities. The production restoration implementation
is identical in these runs and the final package; the QA driver was subsequently
extended for normal shutdown. Clients use development classes, not the packaged
Fabric JAR. Deliberate termination requires development mode and the exact private
QA-world path/property; production gameplay cannot activate this driver.

The ordinary building regression also passes with the final production code:
`PASS building placed=true broken=true returned=true persisted=true movement=step=true airborne=true rise=0.125 at=19.25,38.12890625,-21.25 cell=BlockPos{x=13, y=39, z=-33}`.
Log, preserved result and inspected Vulkan capture:
`/tmp/craftq3-recovery-final-building.log`,
`/tmp/craftq3-recovery-final-building.result`,
`/tmp/craftq3-recovery-final-building.png`.

Reproduce with `./gradlew :craftq3-fabric:runBridgeSmokeClient -Pq3BuildSmoke=true
-Pq3RecoverySmoke=<stage>` in the prepared private QA world. Run stages
`checkpoint`, `recover-crash`, `recover`, `reload`, `leave-crash`, `recover`,
`reload` in order. These assertions establish process-interruption recovery,
not power-loss durability, absent return-file reconstruction or arbitrary external
modifications to saved dimensions/player data.


Two further final-package-code development-client runs verify normal shutdown
while still inside the build session and a fresh reopen. Both pass:
`PASS recovery quit build-saved=true journal=true` and
`PASS recovery reopen loaded-build=true restored=true journal=true`.
Logs/results: `/tmp/craftq3-recovery-final-08_quit.{log,result}` and
`/tmp/craftq3-recovery-final-09_reopen.{log,result}`. Reproduce with stages `quit`
then `reopen`. Native shutdown saved this player in the build dimension; the next
join restored all 14 return fields. Its journal remains until the next confirmed
outside load, as exercised by stages 4 and 7. All nine logs confirm Vulkan and a
successful Gradle task with an explicit result. The final distributable hash was
rechecked after testing and is unchanged.


## Native block support on original BSP faces — 2026-09-06

The full build passes **1,223 tests**, zero failures/errors/skips, with formatting
and warnings-as-errors. Eight collision tests cover all cardinal faces and their
outward directions, boundary gaps, adjoining brushes, combined native/BSP support,
an off-center hole missed by corner/center sampling, excluded playerclip/patches,
oblique clipping, many seams and malformed requests. Two Fabric tests verify all
support types on a BSP floor, fractional-grid gaps and 180 comparisons with
Minecraft’s own FULL/CENTER/RIGID checks across ten real block states and six faces.

`BspTraceWorld.supportsFace` subtracts coplanar solid-brush regions and rectangular
native supports from each required planar area. It uses the existing BSP candidate
traversal, has private scratch state and does not alter movement traces or generate
blocks. Coverage uses a 1e-7 Quake-unit clipping tolerance and a 1e-5 coplanarity
tolerance. A 4,096-fragment bound declines support if exceeded. Non-cardinal faces,
curved patches and playerclip are not advertised as native support surfaces.

The Fabric hook augments failed native face-sturdiness queries in the active build
session. It preserves Minecraft’s native support types, including side-face
projection semantics, and combines partial native shapes with BSP coverage.
Non-colliding decorations additionally check their selection bounds against BSP
solids during placement. Crop/soil/material rules remain native and do not acquire
a fictional supporting block state.

The inspected 1,654,062-byte JAR contains 810 Java 25 classes, nine nested engine
jars, four GLSL resources and no original/native assets. SHA-256:
`52dcac06ad90ee058607b3bbbba15e450f6e3306f96222f3d85c29e9b60e51a4`.
Build: `/tmp/craftq3-support-build.log`; inspection:
`/tmp/craftq3-minecraft-support-final-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-minecraft-support-final-runtime`.

The development client uses the final production code on Minecraft 26.2 / Java 25 /
Vulkan and unchanged retail q3dm17. A floor torch at `(28,28,-1)` and west-facing
wall torch at `(-5,37,11)` use Minecraft’s actual item-use path and are checked on
the integrated server. Their native support cells are air. Temporarily inserting
and immediately removing a QA support-neighbor block triggers native shape
updates; only the BSP remains, and both torches survive. Native breaking and
return-to-world also pass. Explicit result:
`PASS support floor=true wall=true neighbors=true broken=true returned=true`.
Log: `/tmp/craftq3-support-live.log`; result: `/tmp/craftq3-support.result`;
inspected captures: `/tmp/craftq3-support-floor.png`, `/tmp/craftq3-support-wall.png`.
The driver is selected by `-Pq3BuildSmoke=true -Pq3SupportSmoke=true` on
`:craftq3-fabric:runBridgeSmokeClient`, which opens only the private QA copy.

Both packaged QVM profiles retain passing reciprocal combat checks:
`/tmp/craftq3-support-combat-regression.log`. Packaged standalone qagame/cgame
movement, firing and viewport restart retain the deterministic digest
`d5310cda6d39abcf61f465c8958d3132f993f1e1374524a22713ae96750adbc6`:
`/tmp/craftq3-support-pure-regression.log`.

This verifies active-session face support and two original-map decoration cases.
Support-dependent updates after leaving/unloading a BSP session, broader block
materials, mixed lighting and all-map coverage remain unfinished. No claim is
made that a fractional grid gap supports an attachment or that torches relight
the original BSP lightmaps.

Fresh development-client Vulkan regressions also pass with the final production
code. Building placement/breaking, cross-process stone persistence, BSP stepping,
airborne rejection and return pass:
`PASS building placed=true broken=true returned=true persisted=true movement=step=true airborne=true rise=0.125 at=19.25,38.12890625,-21.25 cell=BlockPos{x=13, y=39, z=-35}`.
Log/result: `/tmp/craftq3-support-building-regression.{log,result}`.
Forward Quake movement/jump and mode restoration pass:
`PASS moved=26.53917668970623 restoredMode=CREATIVE`.
Log/result: `/tmp/craftq3-support-forward-regression.{log,result}`.
The final distributable hash was rechecked afterward and is unchanged.


## Retained building-world geometry — 2026-09-06

The full build passes **1,224 tests**, zero failures/errors/skips, with formatting
and warnings-as-errors. The added region lookup test covers negative coordinates,
exact 4,096-block region boundaries, the last legal region and nonfinite/outside
coordinates. Minecraft server levels own immutable collision/support providers
independently of the current rendering session. Region selection uses world X,
with slot zero covering its negative half. Entity movement considers every
registered region touched by its swept bounds; face support and placement checks
select the containing region. Ordinary dimensions retain their native path.

The registry uses weak server-level keys and a server-stopped cleanup hook.
Providers contain geometry and support data, with no level/server reference cycle.
Leaving closes renderer/filesystem resources while keeping visited-region geometry
available for Minecraft's remaining chunk/entity updates. Parsed geometry remains
resident until server shutdown. Client rendering/picking remains session-scoped.

A development-client Vulkan run on original q3dm17 verifies native floor/wall
torches, support-neighbor changes, native breaking and wall-torch replacement.
After leaving, it removes a temporary support-neighbor block and confirms the old
torch survives; an unregistered native armor stand passed through `Entity.move`
lands on the old BSP floor. It then enters original q3dm1 in a distinct region,
checks that the old/new providers are distinct, and repeats both old-map checks.
The fixture then removes its retained torch and returns to Minecraft. Result:
`PASS support floor=true wall=true neighbors=true broken=true returned=true after-leave=true after-switch=true retained-collision=true`.
Log: `/tmp/craftq3-retained-world-live.log`; preserved result:
`/tmp/craftq3-retained-world.result`. Reproduce with
`:craftq3-fabric:runBridgeSmokeClient -Pq3BuildSmoke=true -Pq3SupportSmoke=true` in
the private QA copy. The development client uses the final production code.

The inspected 1,660,660-byte JAR contains 812 Java 25 classes, nine nested engine
jars, four GLSL resources and no original/native assets. SHA-256:
`edc1f73843e3d459a99b4385eb53840f5dbbcefad3676fa86787d68c3abc759a`.
Build: `/tmp/craftq3-retained-world-build.log`; inspection:
`/tmp/craftq3-retained-world-final-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-retained-world-final-runtime`.

This establishes retention during one Minecraft server lifetime. Saved background
regions are not yet rehydrated before map entry after a whole-world restart.
Geometry memory grows with the maps visited during that server lifetime; there is
no chunk-based eviction yet. Rendering of other regions and multiplayer remain
outside the verified scope.

The final-code ordinary building Vulkan regression also passes placement,
breaking, cross-process stone persistence, grounded stepping, airborne rejection
and return:
`PASS building placed=true broken=true returned=true persisted=true movement=step=true airborne=true rise=0.125 at=19.25,38.12890625,-21.25 cell=BlockPos{x=13, y=39, z=-36}`.
Log/result: `/tmp/craftq3-retained-world-building-regression.{log,result}`.
All nine packaged engine JARs are byte-identical to the preceding support
checkpoint, whose original Quake and reciprocal combat regressions passed. The
Fabric lifetime changes are covered by the live tests above. The final artifact
hash was rechecked after testing and is unchanged.

## Saved-region startup checkpoint (2026-09-06)

The full Java 25 build passes **1,232 tests**, zero failures/errors/skips, including
formatting and warnings-as-errors. Eight new tests cover immutable region source
manifests, malformed/incomplete/oversized metadata, aliases, unsafe paths and
nonfinite origins, exact shadowed-source lookup and unavailable BSP identities.
The shared ZIP read path retains bounded reads and CRC validation; ordinary search
selection is unchanged.

Startup now preflights all saved BSP identities and collision before Minecraft
creates levels, then installs every saved region before level ticking. The
world-local manifest stores only source descriptors and coordinates; legacy
hash-only entries migrate using the configured game/baseq3 hierarchy.

Three separate Vulkan launches in the private `CraftQ3 Bridge QA` save establish:

- Prepare: original q3dm17 floor/wall torch item use, retained support/collision
  after leave and q3dm1 switching, followed by saving a wall torch and real armor
  stand in forced-loaded chunks. Result:
  `PASS world checkpoint regions=2 torch=true entity=true forced-chunks=true`.
  Log/result: `/tmp/craftq3-cold-world-prepare.{log,result}`.
- Negative startup: the private fixture's map descriptor points at an unavailable
  path. Startup fails with the exact missing path/hash before creating levels.
  All **24 build-dimension region/entity storage files remain byte-identical**.
  The descriptor is restored in a `finally` block; original PK3s are untouched.
  Log: `/tmp/craftq3-cold-world-missing.log`; hashes/result:
  `/tmp/craftq3-cold-world-missing-report.json`.
- Reopen: both regions restore before ticking, without any build session. The
  saved torch survives support updates; the actual persisted armor stand retains
  its floor position and native movement lands on the BSP again. Result:
  `PASS world reload regions=2 torch=true entity=true collision=true no-build-session=true`.
  Log/result: `/tmp/craftq3-cold-world-verify.{log,result}`.

The checked package contains **820 Java 25 classes**, nine nested engine jars,
four GLSL sources, 1,685,934 bytes, and no original/native game assets. JAR:
`craftq3-fabric/build/libs/craftq3-0.1.0.jar`; SHA-256:
`cccd3913f8b6e899a6ebfc9bb8dad023124632a24c064fb7d6f80346cdeb24b9`.
Build: `/tmp/craftq3-cold-world-final-build.log`; inspection:
`/tmp/craftq3-cold-world-final-aggregate-report.json`; immutable packaged runtime:
`/tmp/craftq3-cold-world-final-runtime`.

Both packaged original QVM profiles pass forward-bridge bullet/rocket/splash/
cover, health synchronization, removal/restart, incoming damage, armor and
original death/respawn regression. Log:
`/tmp/craftq3-cold-world-combat-regression.log`.
Packaged original qagame/cgame pass 140 movement/firing frames and viewport restart
with unchanged digest
`d5310cda6d39abcf61f465c8958d3132f993f1e1374524a22713ae96750adbc6`:
423 views, 6,741 entities, 3,686 quads and 71 voices. Log:
`/tmp/craftq3-cold-world-pure-regression.log`.

This checkpoint covers retained server collision/support and cold startup, not
background client rendering, region eviction, BSP-aware mob navigation or the
remaining movement/lighting/projectile/multiplayer work.

A fresh ordinary-building Vulkan regression also passes native grounded stepping
and airborne rejection (0.125-block rise), item placement/breaking and return to
Minecraft. This run did not request an existing-block assertion (`persisted=false`);
the separate cold-reload fixture above establishes persistence. Log/result:
`/tmp/craftq3-cold-world-building-regression.{log,result}`.

## Native projectile checkpoint (2026-09-06)

The full build passes **1,237 tests**, zero failures/errors/skips, with formatting
and warnings-as-errors. Five new tests cover all six BSP impact directions,
nearer native hits and equal-distance preservation, missed rays, fractional
surface heights and region transforms, player-only clipping exclusion and solid
occupancy for embedded arrows.

The live Vulkan test runs Minecraft's real arrow/snowball ticks and entity-hit
queries on original retail q3dm17 in the private QA world. Arrows embed in the BSP
floor and wall, stay embedded through 20 native ticks and a native movement check,
and stop at a nearer Minecraft block. A snowball selects the BSP wall and its
native impact callback removes it. A zombie behind the wall takes no arrow damage;
the same zombie in front takes damage. An independent unoccluded native entity
trace verifies the covered target is accessible before asserting cover.

Result:
`PASS projectiles floor=true wall=true embedded=true snowball=true native-block=true entity-front=true entity-cover=true returned=true`.
Log/result: `/tmp/craftq3-projectiles-final-live.{log,result}`. Reproduce with
`./gradlew :craftq3-fabric:runBridgeSmokeClient -Pq3BuildSmoke=true
-Pq3ProjectileSmoke=true` in the private QA save. The first run started before
entity chunks were ready and failed its exposed-target check; that fixture failure
is preserved at `/tmp/craftq3-projectiles-live.log`. The final fixture waits for
world loading and explicitly verifies native target availability.

The checked JAR contains **825 Java 25 classes**, nine nested engine jars, four
GLSL sources, 1,698,988 bytes and no original/native game assets. JAR:
`craftq3-fabric/build/libs/craftq3-0.1.0.jar`; SHA-256:
`16e7fadc5cb97137b3b56703b06a0288eb08a20dde426599380734a99064a88e`.
Build: `/tmp/craftq3-projectiles-final-build.log`; inspection:
`/tmp/craftq3-projectiles-final-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-projectiles-final-runtime`.
All nine packaged engine jars are byte-identical to the preceding cold-world
checkpoint, whose original qagame/cgame and both bridge-combat QVM regressions
passed. This increment changes only Fabric integration and its tests/QA driver.

Native arrow callbacks retain damage, embedding and lifespan behavior; no BSP
blocks or Java substitute weapon rules are added. Standard projectile utility
queries and arrow rays are integrated, not every specialized projectile path.
Explosions, material-specific impact effects and embedded-arrow persistence still
need dedicated integration/coverage. Native callbacks read the actual Minecraft
state at the BSP surface cell, which can be air.

## Native explosion checkpoint (2026-09-07)

Three new geometry tests verify thin walls between native explosion samples,
forward/reverse occlusion, solid origins and stationary samples, clear paths and
player-only clip exclusion. Minecraft's exposure and propagation hooks use exact
BSP solids without creating proxy blocks or modifying the map. Native damage,
knockback, block resistance and selection rules remain in charge.

The live Vulkan test discovers a real q3dm17 barrier with clear native building
cells on both sides: blast center `(-27,51.5,-1.5)`, exposed cell `(-26,51,-2)`,
covered cell `(-22,51,-2)`. Native entity queries confirm both cows are available.
Exposure is exactly zero behind the wall and one in front. The covered cow's
health follows the original native calculator (10 to 9) with no knockback; the
exposed cow dies and receives knockback. This preserves Minecraft's minimum
covered damage rather than substituting a different damage rule.

The block test places glass in those legal cells. A matching native blast in an
unused, empty QA region verifies both glass positions are within destruction
range: seed 1, far-cell distance 5.5. The same seed and radius over the BSP destroy
exposed glass and preserve covered glass; a post-blast trace confirms the original
BSP is unchanged. The test calculator restricts block mutations to its fixture
cells. All temporary cells and mobs are cleaned up; PK3s remain read-only.

Result:
`PASS explosions entity-cover=true exposed-damage=true knockback=true block-cover=true exposed-block=true native-baseline=true immutable-bsp=true health=9.0/0.0 returned=true`.
Log/result: `/tmp/craftq3-explosion-verified-live.{log,result}`. Reproduce with
`./gradlew :craftq3-fabric:runBridgeSmokeClient -Pq3BuildSmoke=true
-Pq3ExplosionSmoke=true` in the private QA save.

The first implementation retraced from the explosion center at every native step
and measured about 104 ms for block propagation in this fixture. The final
implementation tests each exact consecutive step segment, resetting per ray;
subsequent runs measured about 29–52 ms. These are fixture timings, not a general
performance guarantee. Ordinary dimensions bypass BSP work. Fire, specialized
blast-triggered effects, wider maps and chains of simultaneous explosions still
need broader coverage. This does not add Quake matches or BSP-aware mob navigation
to building mode.

The final full build passes **1,240 tests**, zero failures/errors/skips,
including formatting and warnings-as-errors. The package contains **831 Java 25
classes**, nine nested engine jars, four GLSL sources, 1,713,695 bytes and no
original/native game assets. JAR: `craftq3-fabric/build/libs/craftq3-0.1.0.jar`;
SHA-256: `3ffbb87f4a86ce1d111a754a2d58d69cae759fceae68dd284c0f29304bb7c098`.
Build: `/tmp/craftq3-explosion-verified-build.log`; inspection:
`/tmp/craftq3-explosion-verified-final-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-explosion-verified-final-runtime`.
All nine engine jars remain byte-identical to the cold-world checkpoint with
passing original qagame/cgame and both bridge-combat QVM regressions. Changes are
confined to Fabric integration, tests and the private QA driver.

## Ground-navigation checkpoint (2026-09-07)

The full build passes **1,243 tests**, zero failures/errors/skips, including
formatting and warnings-as-errors. Three new geometry tests cover rounded native
path-node heights across grid/fractional floors, unsupported points and missing
floors, and native-sized body headroom against BSP ceilings.

The live Vulkan test uses original q3dm17 and Minecraft's real pathfinder,
navigation and mob ticks. A cow with competing random goals disabled starts at
`(19.5,38.125,-1.5)` and targets `(25,38,-2)`. The BSP collision places its actual
feet at Y=38.12890625. The initial path contains seven nodes. Three native stone
blocks at `(22,39,-2)` and above force a new seven-node route with a detour. The mob
walks that path and finishes about 0.552 blocks from the target; it is not moved
along the route by QA code. Every returned node has BSP support and body clearance.

The same test checks an original BSP wall: the native block-only classification
is OPEN while the adapted path node is BLOCKED. Native water, lava, stone and magma
surface classifications are preserved. Temporary blocks and the cow are removed
before returning to Minecraft.

Result:
`PASS navigation native-path=true replan=true walked=true bsp-floor=true wall=true native-types=true nodes=7/7 distance=0.5516411606304146 returned=true`.
Log/result: `/tmp/craftq3-navigation-types-live.{log,result}`. Reproduce with
`./gradlew :craftq3-fabric:runBridgeSmokeClient -Pq3BuildSmoke=true
-Pq3NavigationSmoke=true` in the private QA save.

The ordinary-world reciprocal-combat regression also passes:
`PASS incomingHits=4 death=true respawn=true health=121 moved=0.0 restoredMode=CREATIVE`.
Log/result: `/tmp/craftq3-navigation-forward-final.{log,result}`. The first run timed
out with no attacks (`/tmp/craftq3-navigation-forward-regression.log`); a diagnostic
rerun passed without navigation-code changes. Inspection found the incoming
zombie spawn partly intersected the outgoing test's cover wall. The fixture now
creates that wall only for outgoing shots and explicitly selects the player as
the incoming target at its diagnostic step when still alive. Native zombie AI,
attacks and original Quake death/respawn still perform the gameplay. No production
bridge damage logic changed.

The checked package contains **838 Java 25 classes**, nine nested engine jars,
four GLSL sources, 1,731,605 bytes and no original/native assets. JAR:
`craftq3-fabric/build/libs/craftq3-0.1.0.jar`; SHA-256:
`24ce4adaadba2e7cb91ddc84e29d9c55ffe9a7e8701225024575f76972551e2f`.
Build: `/tmp/craftq3-navigation-verified-build.log`; inspection:
`/tmp/craftq3-navigation-verified-final-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-navigation-verified-final-runtime`. All nine engine jars are
byte-identical to the cold-world checkpoint with passing original qagame/cgame and
both bridge-combat QVM regressions.

This establishes ground-path planning and actual walking over a flat fractional
BSP floor, block-change replanning and sampled native type preservation. It does
not establish complete mob navigation: conservative cell clearance can reject
narrow passages/slopes, and larger mobs, further stairs/slopes, swimming/flying,
chase/attack behavior and wider map coverage remain open. No BSP voxelization,
fake block states or replacement Minecraft AI are introduced.

## Native sight and mob-combat checkpoint (2026-09-07)

The full build passes **1,243 tests**, zero failures/errors/skips, including
formatting and warnings-as-errors. Existing exact-solid occlusion tests cover thin
barriers, solid origins, clear rays and player-only clip exclusion. This increment
adds live native perception/combat checks and reuses the same occlusion query for
explosions and living-entity sight.

On original q3dm17, the native block ray is clear but a BSP wall hides the target.
The fixture then moves that target into clear view: direct sight updates while
Minecraft's sensing cache remains unchanged until its normal tick reset. Native
stone cover blocks sight, removing it restores sight after reset, and the original
128-block range and cross-dimension rejections still hold. The selected observer
position is `(-7,31,-21.6875)` with a visible adjacent target at
`(-7,31,-17.6875)`; all tested bodies are outside BSP solids.

A separate native zombie starts at `(19.5,38.125,-1.5)` and targets a stationary
cow at `(25.5,38.12890625,-1.5)`. Normal zombie AI finds and walks a path, moves about
5.023 blocks, and lands an attack reducing health from 10 to 7. The QA driver
assigns the target and prevents sunlight burning; it does not call moveTo, steer
movement, execute attack goals manually or apply damage. Temporary actors and
cover are cleaned up before returning to Minecraft.

Result:
`PASS mob-combat bsp-sight=true native-cover=true sensing-cache=true range-dimension=true chase=true attack=true moved=5.023224738894497 health=10.0/7.0 returned=true`.
Log/result: `/tmp/craftq3-mob-combat-live.{log,result}`. Reproduce with
`./gradlew :craftq3-fabric:runBridgeSmokeClient -Pq3BuildSmoke=true
-Pq3MobCombatSmoke=true` in the private QA save.

The checked JAR contains **840 Java 25 classes**, nine nested engine jars, four
GLSL sources, 1,740,708 bytes and no original/native assets. JAR:
`craftq3-fabric/build/libs/craftq3-0.1.0.jar`; SHA-256:
`fb2dafb35f5a9df6cd8752f7f5fdc85203104156e49bedd86873a06b482c604d`.
Build: `/tmp/craftq3-mob-sight-final-build.log`; inspection:
`/tmp/craftq3-mob-sight-final-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-mob-sight-final-runtime`. All nine engine jars are byte-identical to
the cold-world checkpoint with passing original qagame/cgame and both bridge QVM
combat regressions. Production changes are confined to Fabric integration.

This proves direct BSP sight, native sensing-cache/cover behavior and an ordinary
zombie pursuit/attack on a clear fractional floor. It does not prove every mob's
target acquisition, ranged or specialized goals, narrow/sloped routes or complete
combat across the original map set.

The ordinary-world reciprocal-combat Vulkan regression also passes after the
sight hook: `PASS incomingHits=2 death=true respawn=true health=118 moved=0.0 restoredMode=CREATIVE`.
Log/result: `/tmp/craftq3-mob-sight-forward-regression.{log,result}`.


## Body-sized ground-navigation checkpoint (2026-09-07)

The full build passes **1,246 tests**, zero failures/errors/skips, including
formatting and warnings-as-errors. Three new geometry tests cover small versus
large bodies in tight passages, actual height under low ceilings, and thin walls
between clear endpoints in both travel directions. Native block/hazard aggregation
is preserved; BSP clearance uses the mob body, and each accepted native neighbor
edge receives upward, horizontal and downward BSP sweeps as applicable.

The live Vulkan test on original q3dm17 uses a chicken with competing random goals
disabled. Its 0.4-by-0.7 body follows a five-node native path from
`(19.5,38.12890625,13.5)` to `(19.5,38.12890625,17.5)`. One returned node
would fail the former whole-cell clearance check; a 0.9-by-1.4 body is rejected
at the tight target. Normal navigation/movement reaches within 0.406 blocks.
This larger-body check does not establish that no alternate route exists.

Result:
`PASS narrow-navigation walked=true actual-body=true size-rejection=true nodes=5 tight-nodes=1 distance=0.40527096385100947 returned=true`.
Log/result: `/tmp/craftq3-narrow-navigation-live.{log,result}`. Reproduce with
`./gradlew :craftq3-fabric:runBridgeSmokeClient -Pq3BuildSmoke=true
-Pq3NarrowNavigationSmoke=true` in the private QA save.

The native sight/chase/attack regression passes with 5.023 blocks of zombie
movement and target health reduced from 10 to 7. Log/result:
`/tmp/craftq3-narrow-navigation-combat.{log,result}`.

The checked JAR contains **843 Java 25 classes**, nine nested engine jars, four
GLSL sources, 1,752,474 bytes and no original/native assets. JAR:
`craftq3-fabric/build/libs/craftq3-0.1.0.jar`; SHA-256:
`5b4161a042d4edb3e29d47ddedfe13eee08c92785b498d5cab6541d637295941`.
Build: `/tmp/craftq3-narrow-navigation-build.log`; inspection:
`/tmp/craftq3-narrow-navigation-final-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-narrow-navigation-final-runtime`. All nine engine jars are
byte-identical to the cold-world checkpoint with passing original qagame/cgame
and both bridge-combat QVM regressions. Production changes are confined to Fabric.

Native grid spacing still limits off-center routes; broader slopes/stairs, large
mobs, swimming/flying, and wider map/combat coverage remain open.

The existing native navigation regression also passes after the edge/body changes:
`PASS navigation native-path=true replan=true walked=true bsp-floor=true wall=true native-types=true nodes=7/7 distance=0.5516415423161228 returned=true`.
Log/result: `/tmp/craftq3-narrow-navigation-replan.{log,result}`. This retains
fractional-floor walking, replanning around native placed blocks, BSP-wall rejection
and native water/lava/stone/magma classifications.


## Native ranged bridge checkpoint (2026-09-07)

The full build passes **1,246 tests**, zero failures/errors/skips, with
formatting and warnings-as-errors. The added development-only fixture checks
existing production behavior in a real Minecraft 26.2 / Java 25 Vulkan process.

An ordinary-bow skeleton six blocks from the avatar fires through its native
`performRangedAttack` method every 40 Minecraft ticks. The fixture disables its
AI and schedules attacks; aiming, arrow creation, flight, collision, damage-source
ownership and accepted-hit handling remain native. A covered arrow stops next to
the actual fixture wall, and its inward surface cell belongs to that stone wall.
The bridge records zero hits under cover. After removal, two exposed arrows reach
original Quake damage handling, with an observed 20-point health drop. Minecraft
health remains unchanged. The fixture removes its skeleton/arrows and exit
restores Creative mode.

Result:
`PASS ranged=true cover=true native-arrows=true incomingHits=2 minecraft-health=true shots=3 quake-damage=true largest-drop=20 moved=0.0 restoredMode=CREATIVE`.
Log/result: `/tmp/craftq3-ranged-verified-live.{log,result}`. Reproduce with
`./gradlew :craftq3-fabric:runBridgeSmokeClient -Pq3RangedSmoke=true`
in the private QA save. Full target-selection/strafe AI, status-effect projectiles,
fireballs, precise attacker attribution and knockback are not established.
No production damage policy or original QVM logic changed.

The checked JAR contains **844 Java 25 classes**, nine nested engine jars,
four GLSL sources, 1,758,349 bytes and no original/native assets. JAR:
`craftq3-fabric/build/libs/craftq3-0.1.0.jar`; SHA-256:
`a409b78516c46c990face8d8ad7e6dca3cc040f6362b2ba300cda49c710cab3e`.
Build: `/tmp/craftq3-ranged-final-build.log`; inspection:
`/tmp/craftq3-ranged-final-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-ranged-final-runtime`. All nine engine jars are byte-identical to
the cold-world checkpoint with passing original qagame/cgame and both bridge
combat QVM regressions.


## Shared bridge world-depth checkpoint (2026-09-07)

The full build passes **1,250 tests**, zero failures/errors/skips, including
formatting and warnings-as-errors. Four new tests verify explicit coordinate
mapping at million-block anchors (100 sample points), the full rolled cgame basis
without scaling camera axes, reversed viewmodel depth on both clip conventions,
and separation of world versus HUD submissions with resources/order retained.

Quake world views now draw immediately after Minecraft's level pass, before its
hand/HUD depth clear. They use the actual host projection, view rotation and
coordinate mapping, the existing main depth attachment and reversed comparisons.
Viewmodel depth compression maps to the near end of that reversed range. HUD
quads and no-world model-icon views draw later with their original projection.
Minecraft takes cgame's vertical FOV and basis, including roll; native walking and
hurt bob do not apply a second camera perturbation.

The Vulkan fixture draws a magenta submitted Quake polygon behind a native stone
wall, then in front, while keeping both original world-view entities and HUD.
GPU readback samples the same 17-by-17 region in both cases: zero magenta pixels
behind cover, all 289 in front. The inspected captures retain the original weapon
and HUD. Result:
`PASS shared-depth=true hidden-pixels=0 visible-pixels=289 moved=0.0 restoredMode=CREATIVE`.
Log/result: `/tmp/craftq3-bridge-depth-live.{log,result}`; captures:
`/tmp/craftq3-bridge-depth-hidden.png` and `/tmp/craftq3-bridge-depth-visible.png`.
Reproduce with `./gradlew :craftq3-fabric:runBridgeSmokeClient -Pq3DepthSmoke=true`
in the private QA save.

The native reciprocal-combat regression also passes:
`PASS incomingHits=2 death=true respawn=true health=118 moved=0.0 restoredMode=CREATIVE`.
Log/result: `/tmp/craftq3-bridge-depth-combat.{log,result}`.

The checked JAR contains **846 Java 25 classes**, nine nested engine jars, four
GLSL sources, 1,768,315 bytes and no original/native assets. JAR:
`craftq3-fabric/build/libs/craftq3-0.1.0.jar`; SHA-256:
`ffd27421a8f46f758f4224445b0ad91dba537f21f9bee3fa1db235bd6fc4a72c`.
Build: `/tmp/craftq3-bridge-depth-build.log`; inspection:
`/tmp/craftq3-bridge-depth-final-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-bridge-depth-final-runtime`. All nine engine jars are byte-identical
to the cold-world checkpoint with passing original qagame/cgame and both bridge
combat QVM regressions.

This establishes native opaque-wall occlusion and original weapon/HUD presentation
for the normal full-window first-person bridge. Translucent-layer ordering,
asymmetric/sub-viewport effects, third-person behavior and wider camera/material
coverage remain open. No OpenGL-specific API or asset conversion was introduced.

The standalone original-qagame/cgame Vulkan smoke also passes after the backend
change: 61 server frames, 168 entities and 16 started audio voices with zero audio
failures. The inspected q3dm17 capture retains BSP geometry, original world
entities, weapon and HUD. This is a rendering/input smoke, not a claim of complete
visual fidelity. Log/result: `/tmp/craftq3-bridge-depth-pure-live.{log,result}`;
capture: `run/screenshots/craftq3-play-q3dm17-vulkan.png`.


## Bridge console/loadout checkpoint (2026-09-07)

The full build passes **1,250 tests**, zero failures/errors/skips, including
formatting and warnings-as-errors. This increment reuses the existing bounded
console editor, renderer and command/cvar dispatcher. Gameplay commands pass
through original cgame/qagame; host code does not write inventory or weapon rules.

The native Vulkan smoke drives real key, character and mouse callbacks. It opens
the console with a repeated toggle key (without closing it again), runs `give all`,
selects railgun 7, binds F to `weapon 5`, closes with Escape and uses the new
binding to select the rocket launcher. Normal mouse input fires a rocket observed
as an original cgame world model. A later console opening exercises completion
(`sensitiv` to `sensitivity 6`) and recalls the previous echo command. The inspected
capture shows original console materials/text, the recalled input, rocket launcher
and HUD, with no overlapping bridge helper text. Exit restores Creative mode.

Result:
`PASS console=true original-loadout=true weapon-switch=true binding=true rocket=true focus=true moved=0.0 restoredMode=CREATIVE`.
Log/result: `/tmp/craftq3-bridge-console-final-live.{log,result}`; capture:
`/tmp/craftq3-bridge-console-final-live.png`. Reproduce with
`./gradlew :craftq3-fabric:runBridgeSmokeClient -Pq3BridgeConsoleSmoke=true`
in the private QA save. The console's disconnect request uses normal bridge
cleanup; this smoke verifies Escape/input focus and final cleanup rather than
that command's independent lifecycle. Settings/loadout persistence and automatic
inventory transfer remain unimplemented.

The checked JAR contains **847 Java 25 classes**, nine nested engine jars,
four GLSL sources, 1,774,129 bytes and no original/native assets. JAR:
`craftq3-fabric/build/libs/craftq3-0.1.0.jar`; SHA-256:
`53ae44deac570c9c6c9aaf676c3a6441499705a389032f25609cb4001cefbf4b`.
Build: `/tmp/craftq3-bridge-console-final-build.log`; inspection:
`/tmp/craftq3-bridge-console-final-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-bridge-console-final-runtime`. All nine engine jars are byte-identical
to the cold-world checkpoint with passing original qagame/cgame and both bridge
combat QVM regressions. Production changes are confined to the Fabric bridge.


## Persistent bridge settings checkpoint (2026-09-07)

The full build passes **1,250 tests**, zero failures/errors/skips, including
formatting and warnings-as-errors. The existing GameConfig and GameFileStore
coverage checks archived values, bindings, home/PK3 precedence, nested exec,
ambiguous-export rejection, bounded paths and atomic saved-file replacement.
Production integration now gives BridgeGame an owned per-game settings store,
loads config after input/cgame command registration, saves on normal close and
closes the store even when other resources fail. Failed initialization does not
export settings; repeated close calls are harmless.

The normal path is `craftq3/home/bridge/<game>/q3config.cfg`, with optional
`autoexec.cfg` in that directory. Bridge config stays separate from standalone
config and original read-only PK3s. The host's existing `writeconfig` command is
also available. Storage/save failures are logged while gameplay/cleanup continues;
the existing atomic serializer/store preserves the last successful export.

Two separate Minecraft 26.2 / Java 25 Vulkan processes pass. The first uses real
console callbacks to set sensitivity 6 and bind F to weapon 5, then leaves normally.
Its saved config contains `seta sensitivity "6"` and `bind 0x66 "weapon 5"`.
The second checks both values at frame zero, before entering any test commands,
then repeats original loadout, weapon selection, binding and rocket firing checks.
It returns to Creative mode successfully.

Final result:
`PASS console=true original-loadout=true weapon-switch=true binding=true rocket=true focus=true settings-restored=true moved=0.0 restoredMode=CREATIVE`.
Logs/results: `/tmp/craftq3-bridge-settings-prepare.{log,result}` and
`/tmp/craftq3-bridge-settings-verify.{log,result}`; first saved snapshot:
`/tmp/craftq3-bridge-settings-prepared.cfg`. Reproduce with
`-Pq3BridgeSettingsSmoke=prepare`, then `-Pq3BridgeSettingsSmoke=verify`, on
`:craftq3-fabric:runBridgeSmokeClient`. Both use the private QA world and isolated
`run/craftq3-bridge-settings-qa/baseq3/` preferences. Ordinary bridge smokes skip
persistence. This establishes normal-exit settings persistence, not crash-time
saving or inventory transfer.

The checked JAR contains **847 Java 25 classes**, nine nested engine jars,
four GLSL sources, 1,775,664 bytes and no original/native assets. JAR:
`craftq3-fabric/build/libs/craftq3-0.1.0.jar`; SHA-256:
`9a5441860e7d9d4da88b28d2dfa7c48e2e38168de9c7e18d94427c519ff4cb97`.
Build: `/tmp/craftq3-bridge-settings-final-build.log`; inspection:
`/tmp/craftq3-bridge-settings-final-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-bridge-settings-final-runtime`. All nine engine jars are byte-identical
to the cold-world checkpoint with passing original qagame/cgame and both bridge
combat QVM regressions. Production changes remain in Fabric integration.


## Named Minecraft combat targets checkpoint (2026-09-07)

The full build passes **1,253 tests**, zero failures/errors/skips, including
formatting and warnings-as-errors. Three additional unit tests cover ordinary and
Latin-1 names, whitespace/control normalization, removal of color/info-string
syntax, Unicode replacement by code point, bounded scanning and the name-length
limit. Names use at most 34 output characters and scan at most 1,024 UTF-16 input
positions, with a readable fallback when nothing remains.

MinecraftCombat now transfers each mob's plain type/custom label in its immutable
server-thread snapshot. Q3Server supplies the sanitized label through ordinary
connect/userinfo callbacks; subsequent renames preserve the actor slot, and its
existing equality check avoids rebroadcasting unchanged userinfo. Names remain
available through original cgame obituary consumption. Original game health,
weapon and kill rules are unchanged.

Both original retail and modern QVM profiles pass named admission, renaming,
unchanged-userinfo stability and 34-character name round trips. The earlier
35-character trial was shortened by the retail QVM, so the host limit now matches
the observed round-trip boundary. Both profiles also pass bullet cover/exposure
(100/86 health), rockets, splash cover/exposure (100/-16), health synchronization,
hidden models, removal, admission without telefrags, restart, incoming damage,
armor, death and respawn. Log: `/tmp/craftq3-actor-names-verified-qvm.log`.

The live Vulkan outgoing combat fixture kills an iron golem named `Bridge Guardian`.
Original cgame displays `You fragged Bridge Guardian`; the Minecraft server records
that named entity's real death. Its cover/damage/kill/return checks pass. Log/result:
`/tmp/craftq3-actor-names-live.{log,result}`; inspected capture:
`/tmp/craftq3-actor-names-live.png`. This run preceded the 35-to-34 boundary adjustment;
its short label is unchanged by that adjustment, and final packaged QVM audits
exercise the corrected boundary. Incoming deaths still use hurt-trigger attribution.

The final package also passes original qagame/cgame's 140 movement/firing frames
and viewport restart with unchanged deterministic digest
`d5310cda6d39abcf61f465c8958d3132f993f1e1374524a22713ae96750adbc6`:
423 views, 6,741 entities, 3,686 quads and 71 voices.
Log: `/tmp/craftq3-actor-names-pure.log`.

The checked JAR contains **848 Java 25 classes**, nine nested engine jars,
four GLSL sources, 1,777,002 bytes and no original/native assets. JAR:
`craftq3-fabric/build/libs/craftq3-0.1.0.jar`; SHA-256:
`ca5801734eb1561232411a42cbefc8877ba09ec8125e6a8d57b3f3b6ab6f8ae3`.
Build: `/tmp/craftq3-actor-names-verified-build.log`; inspection:
`/tmp/craftq3-actor-names-verified-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-actor-names-verified-runtime`. This checkpoint changes the engine's
external-actor userinfo path as well as its Fabric adapter; the packaged original
QVM regressions above validate that changed engine jar.


## Interrupted bridge recovery checkpoint (2026-09-07)

The full build passes **1,257 tests**, zero failures/errors/skips, including
formatting and warnings-as-errors. Four new tests cover complete state round trips,
strict schema/flag/speed validation, malformed and oversized records, and canonical
UUID markers. Invalid records remain intact.

Before entering `/q3 bridge`, Fabric atomically publishes a return-state record
and sets a generation marker that Minecraft saves with player data. Recovery
matches that marker to its record, restores mode, all packed abilities and physics
flags, and preserves Minecraft's saved position. Normal exit clears the in-memory
marker; journal generations remain until a subsequent fresh join confirms that
Minecraft saved the cleared marker. Rapid exit/re-entry retains older generations
because the last saved player data may still refer to one. Entry waits for the
server-side preparation; invalid recovery data fails before restoration and is
preserved. This does not resume the Quake match or guarantee power-loss durability.

Seven separate Minecraft 26.2 / Java 25 Vulkan processes pass:

- `checkpoint`: saves the active bridge marker and halts without shutdown saving.
- `recover-crash`: restores the original state and position, then halts before
  Minecraft can save the cleared marker.
- `recover`: restores again from the retained generation and exits normally.
- `reload`: loads the saved restored state without a marker and removes confirmed
  recovery records.
- `reentry-crash`: saves an active session, leaves and verifies restoration, enters
  again without another player save, and halts with both generations retained.
- `reentry-recover`: restores from the older generation referenced by saved player
  data, preserving position, and exits normally.
- `reentry-reload`: confirms the restored save and record cleanup.

Each checks full state equality, including nondefault movement speeds. These
intentional halts are guarded development fixtures in `CraftQ3 Bridge QA`.
Logs/results: `/tmp/craftq3-bridge-recovery-<stage>.{log,result}` for the stage names
above. Re-entry recovery/reload use the same `recover`/`reload` launch modes as the
first cycle, with separate evidence filenames.

The checked JAR contains **853 Java 25 classes**, nine nested engine jars,
four GLSL sources, 1,790,983 bytes and no original/native assets. JAR:
`craftq3-fabric/build/libs/craftq3-0.1.0.jar`; SHA-256:
`67bc533dd3c2af6e9f751ad8b4aa62b530634979940cf5c0e5a697afa63b2de9`.
Build: `/tmp/craftq3-bridge-recovery-build.log`; inspection:
`/tmp/craftq3-bridge-recovery-final-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-bridge-recovery-final-runtime`. All nine engine jars are byte-identical
to the named-target checkpoint with passing original qagame/cgame replay and both
bridge combat QVM profiles. This checkpoint changes Fabric integration only.

A final ordinary reciprocal-combat Vulkan regression also passes: two incoming
hits, original Quake death and respawn, unchanged Minecraft health and restoration
to Creative on exit. Log/result:
`/tmp/craftq3-bridge-recovery-combat.{log,result}`. Gradle completed successfully.


## Native melee knockback checkpoint (2026-09-07)

The full build passes **1,257 tests**, zero failures/errors/skips, with formatting
and warnings-as-errors. Integration evidence for this feature uses original QVMs
and real Minecraft damage/movement paths rather than a substitute gameplay test.

A Fabric wrapper around the six-argument native `LivingEntity.knockback` captures
the velocity change for an active, living bridge player hit by a mob. Minecraft
computes strength/direction/resistance using the latest original Quake grounded
state. The wrapper restores the host body's previous motion and ground flag even
on failure. A bounded queue transfers immutable impulses with combat snapshots,
converting blocks/tick to Quake units/second and mapping axes. The engine adds the
impulse to public `playerState_t.velocity`; original QVM movement applies gravity,
friction and host-terrain collision. It rejects excessive impulses without changing
state and ignores impulses on dead players. No private game routine is reproduced.
Public velocity and ground-state field layouts were checked against the existing
public `q_shared.h` contract and exercised in both original QVM profiles.

The new `-Pq3KnockbackSmoke=true` Minecraft 26.2 / Java 25 Vulkan fixture passes
native zombie melee with AI disabled, full resistance with no displacement, a
second hit with resistance removed, original Quake backward/upward motion, native
wall collision, unchanged Minecraft health and restoration of Creative mode.
Measured maximum displacement is 1.027283 blocks backward and 1.172700 blocks
upward. The body stops at the Quake hull boundary against the stone wall.
Log/result: `/tmp/craftq3-knockback-live.{log,result}`.

The ordinary mob-AI regression passes two incoming hits, original death/respawn and
Creative restoration: `/tmp/craftq3-knockback-incoming.{log,result}`. The native
skeleton-arrow regression also passes cover, exposed damage, unchanged Minecraft
health and return, with four shots, two accepted hits and a largest Quake health
drop of 20: `/tmp/craftq3-knockback-ranged.{log,result}`. Each Gradle live task
completed successfully. These do not establish every projectile/explosion impulse
path; pushes bypassing the wrapped method and outgoing Quake-to-mob impulses remain
open, as do precise incoming attribution and status effects.

Both retail and modern original QVM combat audits pass the existing weapons,
splash/cover, names, health synchronization, armor, death/respawn and restart cases,
plus public impulse velocity, subsequent movement, host-wall collision, oversized
impulse rejection without mutation and no impulse mutation while dead.
Log: `/tmp/craftq3-knockback-qvm.log`. Original standalone qagame/cgame replay also
passes 140 movement/firing frames and viewport restart with unchanged digest
`d5310cda6d39abcf61f465c8958d3132f993f1e1374524a22713ae96750adbc6`:
423 views, 6,741 entities, 3,686 quads and 71 voices.
Log: `/tmp/craftq3-knockback-pure.log`.

The final package contains **855 Java 25 classes**, nine nested engine jars,
four GLSL sources, 1,798,772 bytes and no original/native assets. JAR:
`craftq3-fabric/build/libs/craftq3-0.1.0.jar`; SHA-256:
`90b55cb118c1709021ce8c7ac8b672d4285d13021d519e4f28760bf9e2bd2ec2`.
Build: `/tmp/craftq3-knockback-final-build.log`; inspection:
`/tmp/craftq3-knockback-final-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-knockback-final-runtime`. All nine final engine jars are byte-identical
to the trial runtime used by the original QVM audits above. The changed server
adapter and Fabric integration are both included in this checkpoint.


## Creeper explosion bridge checkpoint (2026-09-07)

The full build passes **1,257 tests**, zero failures/errors/skips, with formatting
and warnings-as-errors. The changed behavior is additionally exercised through
actual Minecraft creeper fuse/explosion code and the original Quake runtime.

A Fabric adapter captures the velocity change from `ServerExplosion`'s native
entity push for an active bridge player with a mob damage source. Native distance,
exposure, damage and explosion-knockback resistance calculations remain in
Minecraft. The wrapper restores host velocity even on failure, and sends the
captured impulse through the existing immutable combat snapshot and public Quake
velocity adapter. The native player explosion packet retains its effects with a
zero impulse, avoiding a second Minecraft motion path. Dead-player filtering and
original Quake movement/collision remain in the previously verified adapter.
Non-bridge entities and players follow the unchanged native calls.

The live Minecraft 26.2 / Java 25 Vulkan fixture ignites three actual creepers with
AI disabled to keep their positions fixed. Native fuse ticking causes each blast:

- Full native obsidian cover produces zero exposure, zero impulse and no movement.
- Removing cover and setting full native explosion-knockback resistance also
  produces zero impulse and no movement.
- Removing resistance produces native impulse
  (-0.3779651749, 0.1753657176, 0) blocks/tick. Original Quake movement carries the
  player approximately 1.027 blocks backward and 0.225 blocks upward; a rear
  obsidian wall stops the Quake hull.

The fixture checks zero native packet impulse, unchanged Minecraft health, Quake
health loss and restoration to Creative. It uses original `give all` armor to
survive all three blasts. Full cover suppresses the impulse; Minecraft's minimum
covered explosion damage is still handled by the existing incoming damage path.
Initial log/result: `/tmp/craftq3-bridge-explosion-live.{log,result}`.
The final reproduction flag is `-Pq3BridgeExplosionSmoke=true`, distinct from the
existing reverse-building flag `-Pq3BuildSmoke=true -Pq3ExplosionSmoke=true`.

The reverse building-mode explosion regression passes native entity cover,
exposed damage/knockback, shielding placed blocks, destruction of exposed blocks,
native-baseline behavior, immutable BSP geometry and return to Minecraft.
Log/result: `/tmp/craftq3-bridge-explosion-building.{log,result}`.

The final package contains **857 Java 25 classes**, nine nested engine jars,
four GLSL sources, 1,806,493 bytes and no original/native assets. JAR:
`craftq3-fabric/build/libs/craftq3-0.1.0.jar`; SHA-256:
`2e95a41e379240a5309e0557683df0f3e1afd2b181c2866b91600103661a8371`.
Build: `/tmp/craftq3-bridge-explosion-final-build.log`; inspection:
`/tmp/craftq3-bridge-explosion-final-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-bridge-explosion-final-runtime`. All nine engine jars are byte-identical
to the preceding knockback checkpoint with both original QVM combat profiles and
the unchanged original qagame/cgame replay digest verified. This checkpoint changes
Fabric integration only. Environmental/player-owned explosion sources, other
native push paths and outgoing original Quake-to-mob impulses remain open.

The final bridge launch with `-Pq3BridgeExplosionSmoke=true` also passes all three
creeper stages and Creative restoration, with the same measured displacement.
Log/result: `/tmp/craftq3-bridge-explosion-final-live.{log,result}`. Both final live
Gradle invocations completed successfully.


## Original Quake impulses on Minecraft mobs checkpoint (2026-09-07)

The full build passes **1,259 tests**, zero failures/errors/skips, with formatting
and warnings-as-errors. Two additional ledger tests cover immutable impulse/hit
pairing and single sends across stale acknowledgements, plus rejection of invalid
impulses without issuing a partial hit or sequence number.

After each original game frame, MinecraftCombat measures the hidden target's
public player-state velocity change and associates it with observed damage. A
native snapshot resets the proxy velocity and the host's comparison baseline.
The existing bounded sequence ledger carries damage and impulse together. Native
`hurtServer` must accept the hit before the converted impulse is applied; native
invulnerability therefore prevents both. All outgoing weapons currently use
Minecraft's general knockback-resistance attribute. The original Quake impulse
converts from units/second to blocks/tick with the existing coordinate mapping;
native mob physics performs subsequent movement. A merged `no_knockback` tag for
`craftq3:quake` suppresses Minecraft's extra source-position knockback, preserving
the original impact direction, including splash directed toward the shooter.

The live Minecraft 26.2 / Java 25 Vulkan fixture passes:

- Confirmed original bullet hits against an invulnerable native iron golem cause
  no accepted damage or movement.
- Full native knockback resistance accepts damage but suppresses movement despite
  original positive-X impulses of 35 Quake units/second per observed bullet.
- Removing resistance converts those impulses to 0.0546875 blocks/tick; native
  physics moves the golem 0.641765 blocks away from the shooter.
- A rocket passes above a short native chicken and explodes against the wall
  behind it. Original splash produces approximately (-286.1483, 0, -90.1063)
  Quake units/second, converted to (-0.447107, -0.140791, 0) blocks/tick. The chicken
  moves 0.980570 blocks toward the shooter. Native absorption keeps it available
  for the movement check; native physics stays enabled with goals removed.
- Every accepted fixture hit checks its actual immediate native velocity change
  against the converted impulse, rejecting duplicate native knockback. Exit
  restores Creative.

Log/result: `/tmp/craftq3-outgoing-impulse-final-live.{log,result}`; reproduction:
`-Pq3OutgoingImpulseSmoke=true`. The initial cow fixture was below the muzzle and
failed the resistance check; the corrected tall-target fixture additionally
requires original Quake hits before each native acceptance/resistance check.

Both retail and modern original QVM combat audits pass covered bullets and splash
with zero impulse, positive bullet/direct-rocket impulses, negative-X backstop
splash and removal of old velocity on proxy refresh. All existing weapon damage,
cover, names, health sync, armor, death/respawn, restart and incoming impulse checks
also pass: `/tmp/craftq3-outgoing-impulse-qvm.log`. The ordinary native outgoing
combat regression still passes cover, 15 accepted hits, one real iron-golem kill
and Creative restoration: `/tmp/craftq3-outgoing-impulse-combat.{log,result}`.
Both live Gradle tasks completed successfully.

The final package contains **858 Java 25 classes**, nine nested engine jars,
four GLSL sources, 1,813,836 bytes and no original/native assets. JAR:
`craftq3-fabric/build/libs/craftq3-0.1.0.jar`; SHA-256:
`aea5c9867082f81ba404d0a03cbfce90f49a9dcf732c735630b954857ad1a29f`.
Build: `/tmp/craftq3-outgoing-impulse-final-build.log`; inspection:
`/tmp/craftq3-outgoing-impulse-final-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-outgoing-impulse-final-runtime`. All nine engine jars are byte-identical
to the knockback runtime used by the expanded original QVM audits and its passing
original qagame/cgame replay with unchanged digest. This checkpoint changes the
Fabric combat adapter and damage-type tag, with no copied original game routine.
Weapon-specific native resistance policies, remaining native push paths, precise
incoming attribution and status effects remain open.


## Synchronized bridge cameras and imported-profile recovery (2026-09-07)

The full build passes **1,261 tests**, zero failures/errors/skips, with formatting
and warnings-as-errors. Two new recovery tests cover finding a uniquely marked
generation under a previous login UUID without consuming it, missing/ambiguous
rejection, canonical marker validation and the current owner's direct record.

Bridge simulation now runs at `GameRenderer.update` before native `Camera.update`,
and skips its previous extraction-time hook. The camera therefore uses the current
cgame frame instead of the preceding one. Native player-body yaw/pitch now come
from public original player-state aim; camera position, yaw/pitch, full quaternion
and direction vectors come independently from cgame. Native camera detachment is
suppressed while the bridge is active, keeping Minecraft's avatar out of the Quake
body rendering. The saved Minecraft camera preference is not changed by production
code. Pure Quake views retain their existing extraction-time simulation hook.

The final Minecraft 26.2 / Java 25 Vulkan camera fixture passes original first-person
weapon visibility and body masking, original third-person body visibility, camera
collision with a native rear wall, expansion after wall removal, orbiting without
changing native player aim, return to first person and Creative restoration. It
compares camera position, forward vector and quaternion with the current cgame view
throughout the checked frames, including mode changes. The native front-camera
preference is deliberately selected for the test and remains unchanged during play;
QA restores its prior preference after screen removal. Covered camera distance is
1.37109375 blocks; open camera distance is 4.0 blocks. The inspected capture shows
a complete original Quake body in the Minecraft world with no duplicate native body.
Capture: `/tmp/craftq3-bridge-camera-third-person.png`; log/result:
`/tmp/craftq3-bridge-camera-final-live.{log,result}`. Reproduction:
`-Pq3BridgeCameraSmoke=true`. The Gradle task completed successfully.

The initial mode-switch check exposed a real one-frame camera lag and motivated
the earlier simulation hook. Subsequent fixture corrections account for the
runtime's body-model representation (the complete body was visually verified) and
defer test preference restoration until after the asynchronous final capture.

The first crash also exposed an imported-singleplayer-data recovery case: the
launcher changed its generated login UUID while Minecraft retained a marker from
the previous profile. The record was intact under its original owner. Recovery now
uses the current profile record when present; otherwise it searches up to 4,096
canonical profile directories in the same world for that exact generation token.
It refuses missing or ambiguous matches without mutation. Valid unique records
restore normally and remain retained, with cleanup still confined to confirmed
unmarked joins for their owning profile. The final live run logs successful recovery
from a previous profile and proceeds through bridge entry and normal exit. Earlier
matching evidence is also in `/tmp/craftq3-bridge-camera-live3.log` and `live4.log`.

The final package contains **859 Java 25 classes**, nine nested engine jars,
four GLSL sources, 1,822,569 bytes and no original/native assets. JAR:
`craftq3-fabric/build/libs/craftq3-0.1.0.jar`; SHA-256:
`682303faab0c46e7359a8935b740db940ead211b17d7494f7156898f4d80fbe0`.
Build: `/tmp/craftq3-bridge-camera-final-build.log`; inspection:
`/tmp/craftq3-bridge-camera-final-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-bridge-camera-final-runtime`. All nine engine jars are byte-identical
to the outgoing-impulse checkpoint with original QVM combat audits and original
qagame/cgame replay evidence. This checkpoint changes Fabric integration only.
Broader camera/mod/character configurations, asymmetric viewports and translucent
ordering still need integration coverage; the full objective remains incomplete.

Final movement and shared-depth regressions also pass. Ordinary movement/jumping
travels 26.53917669 blocks and restores Creative, matching the prior result and
confirming the simulation does not advance twice. The GPU probe finds zero magenta
pixels behind the native wall and 289 in front, then restores Creative. Logs/results:
`/tmp/craftq3-bridge-camera-movement.{log,result}` and
`/tmp/craftq3-bridge-camera-depth.{log,result}`. Both Gradle tasks completed
successfully with the final package unchanged.


## Persistent bridge loadouts (2026-09-07)

The full build passes **1,265 tests**, zero failures/errors/skips, including four
new checkpoint/state tests. Formatting and `git diff --check` pass. The Java 25
archive contains 862 classes, nine engine jars and four GLSL resources, with no
original game assets or native engine binaries. The distributable is 1,833,913
bytes; SHA-256:
`590508af8a37992ad1aca3e59859801295e525a83b2956886391fc42e1e0684f`.
Build: `/tmp/craftq3-bridge-loadout-final-build.log`; inspection:
`/tmp/craftq3-bridge-loadout-final-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-bridge-loadout-final-runtime`.

Normal bridge exit atomically saves a bounded, versioned 148-byte base-game
checkpoint per login UUID and game home, bound to SHA-256 of qagame. Empty records
mean a dead-exit reset. UUID/module isolation, immutable state, invalid bounds,
truncated/extended records, invalid versions and preservation on read failure have
synthetic unit coverage. Restoration uses public player-state inventory fields;
original give/pickup and hurt-trigger commands keep private game health synchronized.
The original QVM validates supported holdable indices. Position, score, objectives,
native Minecraft inventory and full match/crash state are outside this checkpoint.

Five separate Minecraft 26.2 / Java 25 Vulkan launches pass, each restoring native
Creative mode on exit:

- `prepare`: original items, selected rocket launcher, one rocket fired, Quad Damage.
  Saved health 184, armor 171, weapons mask 1022, selected weapon 5, medkit 27,
  rocket ammo 998 and 27,280 milliseconds of Quad Damage.
- `verify`: exact equality of all saved fields before simulation, followed by an
  original rocket consuming one more round and the powerup timer decreasing.
- `fresh`: an existing rocket checkpoint is bypassed and original spawn defaults
  are observed before simulation.
- `dead`: original `kill` produces death and a zero-byte checkpoint on exit.
- `verify-dead`: another process observes the cleared checkpoint and original
  fresh defaults.

Logs/results are `/tmp/craftq3-bridge-loadout-prepare3.{log,result}`,
`/tmp/craftq3-bridge-loadout-verify2.{log,result}`, and
`/tmp/craftq3-bridge-loadout-{fresh,dead,verify-dead}.{log,result}`. Reproduce with
`./gradlew :craftq3-fabric:runBridgeSmokeClient -Pq3BridgeLoadoutSmoke=<stage>` in
that order. The fixture uses an isolated home and fixed QA profile. Its frame
counter waits for the first actual bridge frame, so startup readiness cannot skip
initial-state assertions. Earlier fixture failures exposed command/snapshot timing
and this skipped-check problem; they are not counted as passing evidence.

Both original qagame profiles pass `AuditBridgeGameplay.py --loadout` at restored
health 1, 73, 187 and 200, exact inventory equality, original rocket consumption,
timed-powerup decay, armor absorption, low-health death and original medkit use.
Retail medkit healing reaches 100 and the modern profile reaches 125, preserving
each game's behavior. Log: `/tmp/craftq3-bridge-loadout-verified-qvm.log`.
Expanded original combat regressions pass both profiles, including movement,
incoming armor/death/respawn and outgoing bullet/rocket splash impulses:
`/tmp/craftq3-bridge-loadout-combat-qvm.log`. The original standalone qagame/cgame
140-frame movement/firing and viewport-restart audit also passes:
`/tmp/craftq3-bridge-loadout-pure.log`, digest
`d5310cda6d39abcf61f465c8958d3132f993f1e1374524a22713ae96750adbc6`.
All nine final engine jars are byte-identical to the runtime used for those audits.

This remains a local base-game bridge feature. Full standalone-to-bridge loadout
transfer, pickup placement, multiplayer and broader mod inventories remain open.

A final live incoming-mob regression also passes on Vulkan: three accepted attacks,
original Quake death and respawn, final Quake health 122, unchanged native health
and restored Creative mode. Log/result:
`/tmp/craftq3-bridge-loadout-incoming.{log,result}`. The Gradle task completed
successfully, and the distributable hash remained unchanged.


## Live standalone Quake to Minecraft handoff (2026-09-07)

The Quake console command `minecraft` now exports the active local player's
base-game loadout and opens the bridge in the already loaded Minecraft world.
Export reads public player state without changing the source. It rejects menus,
demo/remote sessions, inactive players and a host with connected remote clients;
the exact destination qagame bytes must match. Destination preparation happens
before source-screen removal. The live inventory takes precedence over an older
bridge checkpoint, and the source match closes after successful preparation.

Minecraft audio now supports deferred ownership: the destination registers bounded
PCM assets without acquiring native voices/listener, then acquires the engine after
the source's cleanup future completes. Bridge simulation waits for both audio and
native player setup. Discarding an unactivated backend leaves the source owner
intact. The first live attempt correctly left the source match open when the old
one-owner constructor rejected destination preparation; that failure motivated
this resource handoff and is not counted as passing validation.

The final Minecraft 26.2 / Java 25 Vulkan fixture starts original `q3dm17`, obtains
items through the original game, selects a rocket launcher, and enters `minecraft`
through the same command buffer as console input. It checks the exact exported
inventory before the first bridge simulation frame, original source shutdown,
a subsequent original rocket consuming one round, continuing timed powerups and
restoration of native Creative mode. It also rejects a mismatched module without
mutating the live source, rejects premature audio acquisition, and discards that
prepared backend while verifying the source still owns its listener.
Destination audio reports 100 registered sounds, 55 started voices and zero
failures. The inspected capture shows Minecraft terrain/mobs with the Quake rocket
launcher, HUD, carried medkit and active Quad Damage.

Reproduce: `./gradlew :craftq3-fabric:runBridgeSmokeClient -Pq3BridgeTransferSmoke=true`.
Log/result: `/tmp/craftq3-bridge-transfer-final-live.{log,result}`; capture:
`/tmp/craftq3-bridge-transfer-final.png`. Both original QVM profiles additionally
pass a CPU application audit of menu/dead/module rejection, read-only export,
exactly-once command delivery, source preservation until host admission, exact
restoration into an external world and original rocket/powerup behavior.
Reproduce: `python3 scripts/AuditBridgeTransfer.py --runtime <inspected-runtime>`.
Log: `/tmp/craftq3-bridge-transfer-final-application.log`. The initial CPU fixture
sampled during original weapon lowering; the final fixture waits for the switch
to complete before checking a ready rocket launcher.

The final full build passes **1,265 tests**, zero failures/errors/skips, with
formatting and `git diff --check`. The 1,840,689-byte distributable contains 863
Java 25 classes, nine engine jars and four GLSL resources, with no original assets
or native engine binaries. SHA-256:
`1e1dd7f13a8d5efc9185d791d900f3205ab03a3a0afedaeb60a78450dffd7113`.
Build: `/tmp/craftq3-bridge-transfer-final-build.log`; inspection:
`/tmp/craftq3-bridge-transfer-final-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-bridge-transfer-final-runtime`. All nine engine jars are unchanged
from the previous original-QVM combat, loadout and standalone regression checkpoint.

This handoff requires an open local Minecraft world. It closes the source match;
map/score/objectives, a return transfer into standalone play, world selection from
the title screen, arbitrary mod inventory and seamless portals remain incomplete.

The final packaged Fabric runtime also passes both original Create Server menu /
hosted-client application regressions, including restart, map change, background
host ticking, dedicated hosting and shutdown return to the menu. While a remote
player is connected, both host and remote session reject bridge loadout export.
Both profiles report 918 views, 222 first-map frames and 93 second-map frames.
Log: `/tmp/craftq3-bridge-transfer-hosted.log`; reproduce with
`python3 scripts/AuditHostedApplication.py --runtime <inspected-runtime>`.

An ordinary direct bridge entry also passes the final live incoming-combat
regression: `PASS incomingHits=2 death=true respawn=true health=121 moved=0.0 restoredMode=CREATIVE`. Log/result:
`/tmp/craftq3-bridge-transfer-incoming.{log,result}`. This exercises immediate audio
ownership alongside original mob damage, death/respawn and native-mode restoration.
The task completed successfully and the final distributable hash is unchanged.


## Quake / Minecraft inventory round trips (2026-09-07)

The bridge console now accepts `quake <map>` to start a new local Quake match with
its current base-game inventory. Destination assets, original QVMs and the loadout
are prepared before source-screen removal. Missing-map or incompatible-module
preparation leaves the source running; a dead bridge player must respawn first.
After source removal, the Quake screen waits for both native player restoration
and deferred audio acquisition. The effective qagame module is checked after any
cached archives have mounted. Later ordinary map changes do not replay the initial
loadout. Standalone storage used by the transfer QA fixture is now isolated under
`run/craftq3-transfer-qa`, including settings and demo storage.

`Q3Server` has an explicit local-map admission constructor. When carried health
requires subtraction from the original 100/200 healing baseline, immutable server
metadata adds exactly one empty inline-model descriptor and an original
`trigger_hurt` entity. Original BSP brushes, planes, vertices, faces, visibility
and lightmaps remain unchanged. Only the trusted admission object authorizes that
contact; an ordinary BSP cannot activate it by supplying entity metadata. It is
inert outside the single restoration command. Original give/pickup/hurt behavior
keeps private entity health synchronized with public player state. The original
cheat setting is restored and the QVM observes it before inventory publication.
Admissions reject replay; the ordinary external-damage API remains unavailable in
local BSP matches. Maps with no spare inline-model slot cannot use the hurt path.

Two added unit tests cover unchanged geometry, explicit contact authorization,
inert/disarmed contact behavior, and natural health baselines needing no additional
entity/model. Both original QVM profiles pass `AuditMapLoadout.py` at health
1, 73, 100, 187 and 200. The original map geometry is retained while private QA
spawn points position a second original player for a controlled machinegun shot.
After exact inventory restoration, original cheat commands remain rejected; an
original seven-point bullet kills the one-health player or removes two health and
five armor from the protected player. This checks subsequent private-health and
armor behavior, not just immediate player-state bytes. The initial fixture aimed
horizontally while the two players had fallen different distances; the final check
aims at the target's actual current body position.
Log: `/tmp/craftq3-roundtrip-map-qvm.log`; reproduce:
`python3 scripts/AuditMapLoadout.py --classpath '<inspected-runtime>/*'`.
The final engine jars are byte-identical to those used by that audit.

The packaged CPU application audit passes both original QVM profiles through
standalone export, original external-world rocket fire, admission into a real
`q3dm17` QuakeSession, original cgame rocket fire, re-export of updated ammo and a
later ordinary `q3dm1` map change with fresh inventory. It also checks wrong-module
rejection in both directions, menus/dead players, read-only export, single command
delivery and original cheat enforcement. Log:
`/tmp/craftq3-roundtrip-final-application.log`; reproduce:
`python3 scripts/AuditBridgeTransfer.py --runtime <inspected-runtime>`.

The full build passes **1,267 tests**, zero failures/errors/skips, with formatting
and `git diff --check`. The 1,849,532-byte distributable contains 864 Java 25
classes, nine engine jars and four GLSL resources, with no original game assets or
native engine binaries. SHA-256:
`4781888ecddc1b4b2262b4d5a880eb667b61e07dcef1e9e9dfbc591412dd1f98`.
Build: `/tmp/craftq3-roundtrip-final-build.log`; inspection:
`/tmp/craftq3-roundtrip-final-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-roundtrip-final-runtime`.

Expanded original bridge combat passes both profiles, including incoming
armor/death/respawn and outgoing bullet/rocket splash impulses:
`/tmp/craftq3-roundtrip-combat-qvm.log`. The standalone original qagame/cgame
140-frame movement/firing and viewport-restart audit remains deterministic:
`/tmp/craftq3-roundtrip-pure.log`, digest
`d5310cda6d39abcf61f465c8958d3132f993f1e1374524a22713ae96750adbc6`.

The source match closes on a successful transfer; this starts a new destination
match, rather than resuming old maps/bots/scores. Open-world selection, seamless
portals, broader mod inventories and the remaining standalone compatibility work
remain incomplete.

The final Minecraft 26.2 / Java 25 Vulkan round trip passes through the real console
commands in both directions. The first transfer carries health 199, armor 199,
rocket ammo 999, a medkit and 29,000 milliseconds of Quad Damage into Minecraft.
After a rocket and elapsed gameplay, the return transfer carries health 160,
armor 123, rocket ammo 998, the medkit and 26,920 milliseconds of Quad Damage into
`q3dm17`. Exact inventory is checked before the returned match's first simulation
frame, then original cgame fires another rocket. A deliberately missing map first
verifies that failed preparation preserves source inventory and live audio.
The restored native player is Creative before the Quake screen advances. Return
audio reports 123 registered sounds, 18 started voices and zero failures.

Log/result: `/tmp/craftq3-roundtrip-final-live.{log,result}`; capture:
`/tmp/craftq3-roundtrip-final.png`; reproduce:
`./gradlew :craftq3-fabric:runBridgeSmokeClient -Pq3BridgeRoundtripSmoke=true`.
The earlier inspected capture `/tmp/craftq3-roundtrip-live.png` shows original
`q3dm17`, the carried rocket launcher/medkit/Quad Damage and no native world pixels.
Both live tasks completed successfully.

The final engine also passes both original profiles' existing loadout/medkit
regression: `/tmp/craftq3-roundtrip-loadout-qvm.log`. Both original Create Server
menus and host/remote sessions pass restart, map changes, background ticks,
dedicated hosting and shutdown return to menus. Each profile reports 918 views,
222 first-map frames and 93 second-map frames, with both host and remote rejecting
bridge export while connected: `/tmp/craftq3-roundtrip-hosted.log`.

The final ordinary bridge-entry Vulkan regression also passes three native mob
attacks, original Quake death/respawn, final Quake health 123, unchanged native
health and restored Creative mode. Log/result:
`/tmp/craftq3-roundtrip-incoming.{log,result}`. The task completed successfully;
the final distributable hash remained unchanged after all application checks.

## World-persistent original Quake pickups in Minecraft

`/q3 pickup add <classname>`, `list` and `remove <id>` edit per-world/game/dimension
placements at native player positions. Bridge admission supplies only original
item classnames and transformed origins to qagame. Original QVM code owns touch,
health/armor/ammo grants, weapon selection and item respawn; cgame uses original
PK3 models and sounds. The host persists no item quantities or respawn rules.

Two separate Minecraft 26.2 / Java 25 Vulkan processes pass actual add/remove
commands, exact saved-record comparison on relaunch, world-model visibility before
weapon ownership, weapon/ammo/armor collection, firing the collected launcher,
weapon respawn and return to Creative. Reproduce with sequential
`./gradlew :craftq3-fabric:runBridgeSmokeClient -Pq3PickupSmoke=prepare` and
`-Pq3PickupSmoke=verify`. Logs/results:
`/tmp/craftq3-pickups-{prepare,verify}.{log,result}`. Inspected captures:
`/tmp/craftq3-pickups-visible.png` and `/tmp/craftq3-pickups-final.png`.
The initial capture includes a bright spawn effect; the final view shows the
collected rocket launcher, 15 rockets and 50 armor on native stone terrain.
Other development smoke fixtures ignore these private QA placements to preserve
their independently controlled inventory; normal gameplay loads saved placements.

The final packaged original-QVM audit passes both retail and modern profiles:
world model before weapon possession, original damage/healing, rocket pickup
(10 ammo), ammo pickup (15), original weapon respawn (16), armor and rocket fire.
Log: `/tmp/craftq3-pickups-final-qvm.log`; reproduce:
`python3 scripts/AuditBridgePickups.py --classpath '<inspected-runtime>/*'`.
The packaged application also passes both profiles' full inventory round trip,
original rockets in both worlds, failed-module/menu/dead-player rejection and a
fresh subsequent map: `/tmp/craftq3-pickups-final-transfer.log`.

The full build passes **1,270 tests**, zero failures/errors/skips, and formatting.
Three new tests cover bounded original entity descriptors, isolated durable
placement records, removal/ID reuse and malformed-file rejection without mutation.
The 1,871,716-byte distributable contains 869 Java 25 classes, nine engine jars and
four GLSL resources. Recursive inspection finds no original assets or native
engine binaries. SHA-256:
`3c32248f01373dde3092187514447135ca4c3a46bb3ea5fe644fa69bb4aaf044`.
Build: `/tmp/craftq3-pickups-final-build.log`; report:
`/tmp/craftq3-pickups-final-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-pickups-final-runtime`.

Placements are limited to 256 per dimension and loaded only from currently loaded
chunks within 256 blocks at entry. Editing requires re-entry; collected/respawning
item state resets with the bridge session. Streaming pickup entities, natural item
generation and multiplayer synchronization remain unimplemented.

The final native Vulkan regression passes the Quake → Minecraft → Quake inventory
round trip, original rocket firing in both worlds, missing-map failure preservation,
audio handoff and restored Creative mode:
`/tmp/craftq3-pickups-final-roundtrip.{log,result}`. Both original QVM profiles also
pass the existing expanded mob-combat audit, including incoming armor/death/respawn,
cover and outgoing bullet/rocket/splash impulses:
`/tmp/craftq3-pickups-final-combat.log`. All processes completed successfully;
`git diff --check` passes and the distributable hash remains unchanged.


## Bridge travel and background simulation

The bridge previously passed zero elapsed time to Quake when the window lost
focus, although its non-pausing Minecraft world continued ticking. It now releases
held controls and mouse capture while continuing the Quake clock alongside the
native world. Pure Quake retains its existing separate focus-pause behavior.

The final Minecraft 26.2 / Java 25 Vulkan fixture passes a 399.027-block traversal
beyond the initially loaded client chunk window, arrival on the prepared native
floor, original wall collision, native server chunk tracking and exactly zero
sampled position error between Quake and the server. It fires the original rocket
launcher after arrival (999 → 998 ammo) and restores Creative on exit. The position
check runs before rocket splash can move the player. The deterministic focus-loss
interval checks released movement and exactly 1,280 ms of both original simulation
time and Quad Damage decay, followed by resumed forward movement.

Reproduce: `./gradlew :craftq3-fabric:runBridgeSmokeClient -Pq3TravelSmoke=true`.
Log/result: `/tmp/craftq3-travel-final-live.{log,result}`; inspected capture:
`/tmp/craftq3-travel-final.png` (upward view at the far wall with the rocket HUD).
The earlier travel-only run also passed:
`/tmp/craftq3-travel-live3.{log,result}`. Initial fixture iterations selected the
weapon before its inventory snapshot and sampled travel distance after rocket
splash; correcting those sequences preserved the gameplay assertions.

The final full build passes **1,270 tests**, zero failures/errors/skips, formatting
and `git diff --check`. The 1,876,977-byte distributable has 870 Java 25 classes,
nine nested engine jars and four GLSL resources. Recursive inspection finds no
original game assets or native engine binaries. SHA-256:
`1d1d9e942447ecd50fd01a860ebd626ee544100cc8ee84ea2efaf9d37ac7f204`.
Build: `/tmp/craftq3-travel-final-build.log`; report:
`/tmp/craftq3-travel-final-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-travel-final-runtime`. All nine engine jars are byte-identical to the
preceding pickup/combat/transfer audit runtime; this change is in the Fabric screen
and its native QA fixture. The final live process completed successfully.

This verifies traversal across the initial chunk window, not unbounded-distance
precision or dynamic pickup streaming. The broader interoperability goal remains
incomplete.

## Native Minecraft buckets and fluid spread over BSP geometry

Building mode now includes original solid BSP geometry in native bucket raycasts,
fluid-cell admission and neighboring-cell flow queries. Bucket rays preserve nearer
native blocks and fluid sources; source water formation uses coplanar full BSP
support. Geometry checks run outside the native block-state-pair cache. Original
map data stays immutable, and native fluid scheduling, levels, items and sounds
remain in Minecraft.

The final Minecraft 26.2 / Java 25 Vulkan run passes actual client water/lava bucket
use, scheduled spreading over an original q3dm17 floor, both source pickups,
rejection of fluid placement inside BSP solids, native two-source water conversion,
slab waterlogging and retrieval, and native downward flow outside BSP geometry.
Every cell below the pool stays native air. The screenshot shows native lava and
stone basin sides over the original Quake floor; the sides are ordinary placed
blocks and the foundation is BSP only. Exit returns from the building dimension.
Log/result: `/tmp/craftq3-fluids-final-live.{log,result}`; capture:
`/tmp/craftq3-fluids-final.png`. Reproduce:
`./gradlew :craftq3-fabric:runBridgeSmokeClient -Pq3BuildSmoke=true -Pq3FluidSmoke=true`.
The earlier run also passed bucket/spread/source checks:
`/tmp/craftq3-fluid-live2.{log,result}` and inspected
`/tmp/craftq3-fluid-live2.png`. The first fixture compared the exact source-fluid
instance with a flowing-fluid instance; it now checks Minecraft's fluid-family
identity instead.

Two new tests verify clear bucket destinations on all six BSP face orientations,
fractional floors and translated regions, nearer native-source priority and
player-only clip exclusion. The full build passes **1,272 tests**, zero
failures/errors/skips, with formatting. The 1,890,836-byte distributable contains
874 Java 25 classes, nine engine jars and four GLSL resources, with no original
assets or native engine binaries. SHA-256:
`4e72f3f994ddaf35e125028c83f004ab28aa9a2df0607bf64e5a4119e6617bbf`.
Build: `/tmp/craftq3-fluids-final-build.log`; report:
`/tmp/craftq3-fluids-final-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-fluids-final-runtime`. All nine engine jars remain byte-identical to
the prior travel/pickup/combat/transfer checkpoints.

Fluid cells remain grid-aligned and cannot partially occupy solid BSP volume.
Fractional floors can leave a gap, and source formation requires support at the
grid boundary. Complex basins, waterfalls, fluid/fire interactions, swimming and
broader material rendering remain incomplete.

The final ordinary building Vulkan regression also passes real placement and
breaking, native grounded stepping over a 0.125-block BSP ledge, airborne step
rejection and return to the original dimension:
`/tmp/craftq3-fluids-building-regression.{log,result}`. This run creates a new
placement cell and reports `persisted=false`; it is not a new cross-launch
persistence claim. Both final native tasks completed successfully. `git diff
--check` passes and the packaged JAR hash is unchanged after validation.

## Native hanging decorations on BSP walls

Building mode now adjusts outside BSP placement cells for the native hanging-item
path. Painting and item-frame support checks accept coplanar full BSP faces;
shared hanging-entity clearance rejects solid BSP overlap. Native entity overlap,
painting selection, item insertion, rotation, damage/drop rules and survival ticks
remain active. The checks use saved server geometry after session closure and
leave ordinary Minecraft support results intact.

The first Minecraft 26.2 / Java 25 Vulkan run passes native item-frame placement on
an original q3dm17 wall, diamond insertion, rotation and breaking, followed by
painting placement, survival through native ticks and support after leaving the
building session. Log/result: `/tmp/craftq3-decorations-live2.{log,result}`;
inspected capture: `/tmp/craftq3-decorations-live2.png`. The painting is visible on
the original wall but dark under native lighting; lightmap/native-light integration
remains unfinished. Reproduce with
`./gradlew :craftq3-fabric:runBridgeSmokeClient -Pq3BuildSmoke=true -Pq3DecorationSmoke=true`.

The full build passes **1,272 tests**, zero failures/errors/skips, with formatting.
The 1,901,661-byte distributable contains 879 Java 25 classes, nine engine jars and
four GLSL resources, with no original game assets or native engine binaries.
SHA-256:
`62dbf53e318c2880889eeef6323e7b6c546997901174d3de482ed0ee084ccd1b`.
Build: `/tmp/craftq3-decorations-final-build.log`; inspection:
`/tmp/craftq3-decorations-final-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-decorations-final-runtime`. All nine engine jars are unchanged from
the prior validated checkpoints. This work remains confined to Fabric integration.

Attachment requires support meeting the Minecraft grid boundary. Fractional gaps,
sloped attachment, lighting fidelity and broader decoration/world persistence
coverage remain open; the existing post-leave check is not a cross-launch claim.

The final Vulkan run also passes explicit rejection checks for a duplicate frame,
a frame one cell away from its support and a frame buried behind the BSP wall,
then repeats the full placement/interaction/painting/leave sequence successfully.
Log/result: `/tmp/craftq3-decorations-final-live.{log,result}`; capture:
`/tmp/craftq3-decorations-final.png`. Both native runs completed successfully;
`git diff --check` passes and the packaged JAR hash remains unchanged.

## Original light-grid brightness for native entities

The active build session now samples the original BSP light grid for native entity
renderers and level-based light-coordinate queries, including paintings and held
items. The adapter maps ambient light plus half the directed contribution to
luminance, then inverts Minecraft 26.2's light-map brightness curve before
quantization. Stronger native block light and native sky values are retained.
Missing/solid samples and positions outside map bounds keep native lighting.
No native light data or Quake lightmaps are changed; this is a render-only
brightness approximation, not colored/directional lighting or terrain relighting.

The final Minecraft 26.2 / Java 25 Vulkan run captures the same painting with the
adapter disabled and enabled. Its sampled packed light changes from 10485760 to
10485984: block light 0 → 14, sky light stays 10. It checks a native cow renderer
and verifies burning-entity brightness remains 15. The complete frame/painting
placement, insertion, rotation, breaking, invalid-placement, native survival and
post-leave support checks also pass. Log/result:
`/tmp/craftq3-lighting-final-live.{log,result}`; captures:
`/tmp/craftq3-lighting-final-{before,after}.png`. Reproduce:
`./gradlew :craftq3-fabric:runBridgeSmokeClient -Pq3BuildSmoke=true -Pq3LightingSmoke=true`.
The preceding live run also passed; the inspected paired captures
`/tmp/craftq3-lighting-{before,after}.png` show the same painting changing from dark
to clearly visible while its Quake wall stays lightmapped.

Two new tests verify optional light-grid sampling without changing the original
renderer's fallback, and brightness merging that preserves native sky/emission,
keeps dark samples unchanged and stays within Minecraft's light range. The full
build passes **1,274 tests**, zero failures/errors/skips, plus formatting and
`git diff --check`. The 1,907,487-byte distributable contains 882 Java 25 classes,
nine engine jars and four GLSL resources, with no original assets or native
engine binaries. SHA-256:
`d7f301cb394bab0a186f2d9fd5221e11fed751252ea44f876f573bc92e031d4c`.
Build: `/tmp/craftq3-lighting-final-build.log`; inspection:
`/tmp/craftq3-lighting-final-aggregate-report.json`; immutable runtime:
`/tmp/craftq3-lighting-final-runtime`.

The final packaged original qagame/cgame audit passes 140 movement/firing frames
and viewport restart with the unchanged digest
`d5310cda6d39abcf61f465c8958d3132f993f1e1374524a22713ae96750adbc6`:
423 views, 6,741 entities, 3,686 quads and 71 voices. Log:
`/tmp/craftq3-lighting-pure.log`. Both final validation processes completed
successfully and the distributable hash remains unchanged.

Colored/directional illumination of native models, native terrain lighting,
placed-block shadows on BSP and broader rendering fidelity remain unfinished.
