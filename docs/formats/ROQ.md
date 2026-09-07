# RoQ decoder

`RoqDecoder` independently reads Q3 information/codebook/VQ and mono/stereo DPCM
chunks from a bounded stream, retaining two image buffers and producing owned
planar frames and PCM. The format reference is [Tim Ferguson's published
description](https://multimedia.cx/mirror/idroq.txt). No native engine routine was
read or translated. Eleven synthetic tests cover buffering, block modes, audio
predictors, bounds, malformed input and an unused terminal mode word.

Original movies exposed a two-byte unused next mode word when a frame exhausts
its final eight-mode codeword. The decoder permits exactly that word at that
boundary; partial or longer trailing data, and trailing words before that boundary,
remain errors. A regression checks both arbitrary word bytes and the following
chunk boundary. This exception was established by original-asset observations and
differential validation, rather than specified by the format reference.

The packaged decoder now matches FFmpeg for every planar video frame and all PCM
bytes across all 11 RoQ entries in the locally supplied retail `pak0.pk3`:
7,715 video frames and 5,444,948 audio sample frames. All are 512×256 at 30 fps.
`scripts/AuditRoq.py` drives `AuditRoq.java`, comparing per-frame SHA-256 and the
aggregate PCM SHA-256. A memory-only loopback range server gives the native
reference seekable input without extracting or packaging game assets.

FFmpeg emits a terminal-header demux diagnostic at EOF on these inputs despite
zero exit status and complete output. The runner retains it and permits only that
exact diagnostic; matching frame counts, every frame hash and the PCM hash remain
required. Other diagnostics or nonzero exit status fail the audit. Results are in
`/tmp/craftq3-gameplay-final-roq/report.json`; see [validation](../VALIDATION.md).

`RoqPlayback` now supplies the platform-independent presentation timeline. It streams
through `VirtualFileSystem.open`, retains the current and one future frame, and
submits ordered immutable PCM with sample positions to an audio sink. The clock
uses integer units shared by the frame rate and 22050 Hz audio, so looping does
not accumulate millisecond rounding drift. ONCE releases the final frame at the
end, HOLD retains it, and LOOP reopens the virtual stream. Movie duration includes
both the final video-frame interval and any audio tail. A silent movie invokes no
audio callbacks. Stop, EOF and failures release the decoder and audio queue.

Each advance processes at most 256 decoded events; after a long stall the caller
can repeat the same monotonic timestamp while `catchingUp()` is true. Already-known
whole loop cycles are skipped without repeatedly reopening the input. The clock
accepts up to 24 hours per playback. The sink receives exact cycle origins and
sample indices; a native implementation must queue PCM against those positions,
not start unrelated sound voices for individual chunks.

PK3 streams retain normal override/pure selection, mark pack references at open,
check declared size and CRC at EOF, and permit early cancellation without draining
the entry. Loose streams recheck containment/type and reject size changes. RoQ
entries may stream up to 512 MiB; buffered reads and nonmovie entries retain their
64 MiB limit. Eight playback tests and four streaming tests cover timing, modes,
audio continuity, bounded catch-up, cleanup errors, malformed/reopened input,
overrides, CRC/size rejection, early close and large movies.

`scripts/AuditRoqPlayback.java` runs all 11 supplied movies at 16 ms presentation
steps against the saved, FFmpeg-validated frame/PCM hashes. All 7,715 frames and
5,444,948 audio sample frames match through the final packaged playback layer.
It also requires ordered presentation, contiguous samples and clean EOF/audio
lifecycle. Evidence: `/tmp/craftq3-roq-playback-final-audit.log`.

The native PCM queue/backend now passes original movie audio and resource-lifecycle
checks (see [audio](../AUDIO.md)). Synchronizing the presentation clock with its
prefetch/start time, CIN syscalls, fullscreen cinematic commands and original menu
integration remain unfinished. The playback-layer audit
does not establish in-game movie display or audible playback.


## Movie image submission

`CgameFrame.Image` carries an immutable RGBA snapshot, a stream identity and a
framebuffer rectangle. Frames allow up to 32 distinct streams and 64 MiB of active
movie pixels; conflicting snapshots for one stream in a single frame are rejected.
Repeated draws of the same snapshot count once against this budget. Submitted
movie quads use opaque replacement, clamped linear sampling and no world depth or
lighting, while retaining their position among ordinary HUD/view commands.

The Minecraft backend keeps one texture/view per submitted stream, writes new
frames into the same GPU allocation, and skips uploads of the same immutable
snapshot. Size changes replace that allocation. Images absent from the next
submitted frame, a return to ordinary world rendering, and backend close release
retained movie resources. Movie pixels use a private cache rather than replacing
registered static textures. Uploads use the same Blaze3D APIs as other assets and
therefore retain Vulkan compatibility.

The live `-Pq3VideoInstallation=<installation>` fixture on `runSmokeClient` silently
plays original `video/idlogo.roq` into an inset framebuffer rectangle. GPU readback
at frames 90 and 180 matches 129,284 interior pixels per frame with zero RGB error
against the decoder's RGBA conversion. It checks background/movie/HUD ordering,
30 held renders without repeated uploads, a 2×2 replacement scaled to 64×64, all
four clamped corner colors, removal and close with another image still active.
181 original frames share one allocation; resize uses a second, and the final
close probe uses a third. Logs: `/tmp/craftq3-video-render-final-live.log`.
Inspected captures: `/tmp/craftq3-movie-render-{90,180,resize}.png`.

This establishes movie pixel presentation through the native backend. It does not
establish original cinematic syscall behavior, fullscreen layout, audio/video
synchronization or a native-Q3 RGB conversion comparison. The decoder's planar
output was compared with FFmpeg separately; this GPU check compares against the
production Java RGBA conversion. Audio remains intentionally silent in this fixture.
