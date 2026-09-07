# Audio backend

`AudioBackend` is a session capability in `craftq3-platform`. It accepts immutable decoded
`PcmSound` objects, canonical Q3 positions and integer entity/channel identifiers. The client owns
filesystem lookup and WAV decoding. No Minecraft sound event or resource identifier crosses the
platform contract.

Create `MinecraftAudioBackend(Minecraft)` on the client thread before starting the loading worker.
`register(name, pcm)` is synchronized and CPU-only, so cgame media registration can run on that worker.
The default boundary transform is 32 Q3 units per Minecraft block, with `(x,y,z)` mapped to
`(x,z,-y)`. A constructor overload accepts a different `CoordinateTransform`.

`play(Playback)` supports local, fixed-position and entity-following sources. A nonzero channel
replaces an earlier one-shot voice for the same entity; channel zero permits overlap. Return value
zero means the request could not be scheduled. `updateEntity` supplies following-source positions.
Use `beginFrame`, submit the current frame's `Loop` records, and `endFrame(Listener)` to update the
listener and reconcile loops. A loop omitted in the next frame stops. `clearLoops`, `stop`, and
`stopAll` allow explicit cancellation. `volume` multiplies request gain by the current Minecraft
master volume; category sliders do not control the separate Q3 session.

The adapter uses Minecraft 26.2's existing `SoundEngine`, `SoundEngineExecutor`, `ChannelAccess`,
`Channel` and `SoundBuffer` APIs. It creates no device or context and calls no raw OpenAL functions.
Positional audio is downmixed to mono 16-bit PCM; local audio preserves supported mono/stereo,
8/16-bit PCM. Direct input buffers are GC-owned and uploaded by Minecraft on its sound executor.
The adapter retains immutable PCM so registered handles survive engine reloads.

Listener ownership begins at the first `endFrame`. Narrow mixins suppress Minecraft camera updates
while this session owns the listener, including already queued camera callbacks. Closing restores
the prior listener transform; subsequent Minecraft camera updates proceed normally. A Q3 session
can continue playing while its screen pauses the underlying Minecraft world.

Cleanup follows Minecraft's channel ownership. Stopping a source uses `Channel.stop`; the shared
`ChannelAccess` tick releases and removes its handle. Calling `ChannelHandle.release` independently
would leave a released entry in Minecraft's shared set, so the adapter never does so. On `stopAll`,
the lifecycle hook discards Q3 buffers only after the sound executor has stopped and Minecraft has
cleared its channels. Device teardown therefore cannot race a queued buffer deletion. Emergency
shutdown invalidates IDs and lets Minecraft destroy its context. `closeCompletion()` completes
after asynchronous native cleanup; the host must close the backend after its client VM stops.

Registration is limited to 4,096 sounds and 64 MiB PCM. The native cache is limited to 128 MiB and
the session to 128 active/pending voices. Minecraft's shared source pool may impose a lower limit;
allocation failure is explicit in `diagnostics()` and bounded log messages. A stopped/unavailable
engine does not silently open a fallback device.

Current spatial attenuation uses Minecraft's linear-distance wrapper with a 1,250-Q3-unit range.
It is an approximation of Q3 attenuation. Doppler, reverb, underwater filtering, streaming music,
voice priority stealing and sample-accurate loop synchronization are not implemented. The listener
contract retains underwater state for a future effect implementation.

Validation includes platform source/basis checks and a development-only silent smoke harness.
Set `-Dcraftq3.audioSmoke=true` with a development BSP viewer launch to exercise real source
allocation, channel replacement/overlap, positional/entity updates, loop omission, Minecraft
`stopAll`/resume, and final buffer cleanup. It uses authored silent PCM, changes no audio options,
and logs `CraftQ3 audio smoke PLAY`, `RESUME`, then `PASS` or an explicit `FAIL`. When combined
with capture mode, capture waits for the smoke result.

The real Minecraft 26.2 / Java 25 Vulkan client passed this smoke on September 5, 2026. Six voices
played simultaneously, including one loop, with two shared PCM buffers. After Minecraft `stopAll`,
two sources resumed with recreated buffers. Final close had zero voices, loops, buffers, or failures;
eight native channel starts were confirmed by `Channel.playing()`. The client captured the shader
fixture and exited automatically. The ignored run log is `run/audio-smoke-vulkan.log`.
This proves silent PCM playback commands and resource lifetime, not an audible listening assessment
of spatial balance or exact Q3 attenuation.

Implementation references are the installed Minecraft 26.2 public method signatures and lifecycle
behavior inspected with `javap`; no Minecraft or ioquake3 routines were copied or translated.
Java's [AudioFormat contract](https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/javax/sound/sampled/AudioFormat.html)
defines PCM signedness, channel layout and endian metadata used at the adapter boundary.

Pure Quake views stop existing Minecraft SoundInstances by category and reject new immediate, delayed and ticking host playback. The shared source pool is preserved, so original cgame PCM continues normally. Returning to a Minecraft screen removes the playback gate; preferences are unchanged. Local gameplay captures passed on Vulkan and OpenGL with 25 starts, ten persistent map loops and zero backend failures in the fixed input script. The smoke includes a host UI sound rejection probe. Actual audible spatial balance still requires listening tests.

## Filesystem-view changes

`AudioBackend.resetAssets()` is an explicit asset-lifetime boundary. The host first closes the old
cgame and UI, then resets audio before loading assets from a different filesystem view. It uses
this sequence on remote gamestates and on return to the local view. Old sound handles must no
longer be used. Stateless adapters inherit `stopAll()`; the Minecraft backend also clears its
name registrations and decoded PCM, so a same-name sound from an approved pack replaces any
previous local copy.

The Minecraft reset increments the playback generation, preventing pending old voice callbacks
from attaching or playing new-generation buffers. It stops active sources through their existing
handles and queues native-buffer disposal after the shared channel-release tick. Each queued
cleanup owns a specific retired batch: a second reset cannot make the first cleanup delete buffers
whose source release is still pending. Retired native bytes remain charged against the budget
until disposal. A host sound-engine teardown reclaims both active and retired batches; emergency
context destruction forgets their IDs so queued cleanup becomes harmless. The borrowed engine
and listener remain owned by the open audio session.

Six CPU-only tests exercise the production `AudioAssetCache`: same-name PCM replacement and
registration-budget recovery, repeated deferred resets, pending native-byte accounting, host
reload with retained current PCM, emergency-context invalidation, and failed buffer allocation.
All six tests and the four existing platform audio tests pass. This checks asset ownership and
deferred cleanup without opening an audio device; the new reset path has not received a separate
OpenAL playback smoke in this checkpoint.


## Continuous PCM streams (2026-09-07)

`AudioBackend.stream` now transfers ownership of a `PcmStream` to a local native
streaming voice. `PcmQueue` provides a synchronized, bounded producer/consumer
implementation with a stable mono/stereo 8/16-bit format, exact sample positions,
explicit backpressure and EOF. It buffers a quarter second before starting, or
accepts a shorter completed clip. Reads are frame-aligned and capped at 50 ms;
Minecraft fills/refills its four streaming buffers without blocking for new data.
An underrun is an explicit failure rather than inserted silence or discarded PCM.
The queue is limited to 16 MiB and must have capacity for its startup threshold.

Streaming sources share session gain, master gain, listener ownership, voice limits
and sound-executor lifecycle with existing voices. They use Minecraft's streaming
source pool and native buffer management, without registering PCM chunks as static
sound assets or calling raw OpenAL APIs. Pending streams wait for enough data;
stop, sound-engine suspension, filesystem asset reset and session close reclaim
the queue. A refill queued before cancellation checks that its voice still belongs
to the session. Short nonblocking reads serialize with cancellation, fixing a live
race where the host requested data after the producer queue had been closed.

Four CPU tests cover chunk boundaries, sample continuity, frame alignment, bounded
backpressure/retry, startup readiness, short EOF, underrun, cancellation and invalid
formats/capacities. The live development fixture is:
`./gradlew :craftq3-fabric:runSmokeClient -Pq3AudioSmoke=true -Pq3AudioMovie=<installation>`.
It decodes the supplied original `video/idlogo.roq` and feeds its 129,150 sample
frames incrementally through the production queue. Unlike the default silent audio
fixture, this explicit movie fixture plays the first stream at 0.3 gain. It verifies
pending/prebuffer behavior, more than the initial four buffers of native playback,
complete EOF, active cancellation, host stopAll, asset reset and close. The run
finished the movie in about 5.925 seconds and released all voices with zero failures
and zero registered static sounds. Log: `/tmp/craftq3-stream-fixed-live.log`.

The default silent regression also passes source replacement/overlap, entity and
position sounds, loops, host stopAll/resume and close:
`/tmp/craftq3-stream-static-regression.log`. Both native processes exited normally.
These checks prove host submission and resource lifetime; they do not establish
movie audio/video synchronization, a subjective listening assessment or completed
cinematic/menu integration. The RoQ presentation clock still needs to coordinate
prefetch and native start time before those services can be connected.
