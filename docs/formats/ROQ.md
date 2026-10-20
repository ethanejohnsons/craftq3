# RoQ cinematics

RoQ is Quake III's movie format: vector-quantised YCbCr video with DPCM audio. It is used by the
`cinematic` console command, the menu and cgame cinematic imports (`trap_CIN_*`) and `videoMap`
shader stages.

Code:
[`RoqDecoder`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/roq/RoqDecoder.java)
(streaming decoder),
[`Cinematics`](../../engine/client/src/main/java/dev/bluevista/craftq3/engine/client/video/Cinematics.java)
(cinematic handles for the ui and cgame),
[`Q3VideoMaps`](../../mc/fabric/src/client/java/dev/bluevista/craftq3/mc/fabric/client/render/Q3VideoMaps.java)
(`videoMap` textures in the Fabric renderer). Format reference: Tim Ferguson's
[RoQ description](https://multimedia.cx/mirror/idroq.txt).

## Stream layout

All values are little-endian. The file starts with an 8-byte header: `0x1084`, `0xFFFFFFFF` and
the frame rate (1 to 120). Then come chunks, each with an 8-byte header (16-bit id, 32-bit length,
16-bit argument) followed by `length` bytes:

| Id | Chunk | Notes |
| --- | --- | --- |
| `0x1001` | Info | width, height, then 8 and 4 (block sizes); dimensions are multiples of 16, at least 16, at most 4,194,304 pixels, and may not change later |
| `0x1002` | Codebook | argument high byte: 2x2 cells (0 means 256), low byte: 4x4 quads (0 means 256 when data remains); a cell is 4 Y bytes plus Cb and Cr, a quad is four cell indices |
| `0x1011` | Video frame | argument is the signed motion offset (high byte x, low byte y); 16-bit code words of eight 2-bit codes describe each 8x8 block |
| `0x1020` | Mono audio | argument is the initial predictor |
| `0x1021` | Stereo audio | argument high and low bytes are the left and right predictors (shifted into the high byte) |

Block codes: 0 keeps the block from two frames back, 1 copies a motion-compensated block from the
previous frame, 2 paints a codebook quad, 3 subdivides (into 4x4 blocks, then into cells).
Motion vectors pointing outside the frame are rejected. When a frame ends exactly as its last code
word is used up, original encoders may append one unused 2-byte code word; exactly that is
accepted, and any other trailing data is an error.

Audio is 22,050 Hz signed 16-bit. Each byte is a delta: magnitude `(b & 127)^2`, negative when
bit 7 is set, added to the channel's predictor with 16-bit wrap. Audio positions count sample
frames from the start of the movie, independent of video chunks.

Decoded video frames are planar full-range Y, Cb and Cr at full resolution; `Video.image()`
converts to RGBA with the JFIF coefficients.

## Limits

A stream may be at most 512 MiB (PK3 entries ending in `.roq` may expand that far; other entries
are limited to 64 MiB, see [PK3.md](PK3.md)), a chunk at most 8 MiB, and a movie 216,000 frames.
Unknown chunk ids, bad lengths, undefined codebook entries and channel-count changes raise
`RoqFormatException` (unchecked); either kind of failure closes the decoder.

## Playback

`Cinematics` implements the `CIN_*` imports with 16 handles (`MAX_VIDEO_HANDLES`). A name without
a directory is opened as `video/<name>.roq`. Flags: `LOOP` restarts at the end, `HOLD` keeps the
last frame (status `FMV_IDLE`), otherwise the movie ends with `FMV_EOF`; `SILENT` drops the
soundtrack, which otherwise goes to the sound system's raw-sample stream at `s_volume`. Each
`run` decodes up to the movie's clock, at most 512 events, so a stall cannot block a frame.
Drawn extents are in the 640x480 virtual screen, scaled to the framebuffer.

`videoMap` stages are played by the Fabric renderer: each file is one looping, silent stream shared
by every stage that names it, decoded on a worker thread from the whole file in memory. The frame
shown is `floor(shaderTime x frameRate)` wrapped at the file's length, uploaded at the movie's own
size; see [DIVERGENCES.md](../DIVERGENCES.md) and [SHADERS.md](SHADERS.md).

Independent implementation; see [LICENSE-NOTES.md](../../LICENSE-NOTES.md). Tests:
`RoqDecoderTest`.
