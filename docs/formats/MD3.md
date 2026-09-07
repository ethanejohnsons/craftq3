# MD3 version 15 and multipart player assets

`craftq3-assets` reads MD3 data without Minecraft, rendering, native, or engine-code dependencies.
The implementation is independently written against the published format, with authored synthetic
fixtures. No commercial model bytes or copied/transliterated engine routines are included.

The primary layout reference is id Software's
[qfiles.h](https://github.com/id-Software/Quake-III-Arena/blob/master/code/qcommon/qfiles.h).
The little-endian model header is 108 bytes, begins with ASCII `IDP3` and version 15, and supplies
counts plus offsets for frames, per-frame tags, chained surfaces, and the declared end. Frame records
are 56 bytes; tags are 112 bytes. Each 108-byte surface header has relative offsets for 12-byte
triangles, 68-byte shaders, 8-byte UVs, and 8-byte packed vertices per frame. Signed XYZ shorts are
scaled by 1/64 into Q3 units. UVs and original triangle order are preserved.

`Md3Reader.read(byte[])` returns an immutable `Md3Model` or a checked `Md3FormatException`.
Limits are 64 MiB input, 1,024 frames, 16 tags, 32 surfaces, 256 shaders per surface, 4,096 vertices
and 8,192 triangles per surface, and two million decoded vertex samples per model. Counts, offsets,
nonempty overlapping blocks, triangle references, finite floats, frame agreement, and stable tag
names/order are checked before dependent access. Empty blocks and trailing file padding are allowed.

The model has immutable frame bounds, origins, tags, material names, triangles, UVs, and decoded
position/normal samples. `Frame.hasBounds()` is false for inverted empty boxes in tag-only exports;
geometry-bearing models must have ordered bounds. `Tag.transformPoint`, `transformDirection`, and
`compose` operate on Q3's Z-up coordinates using the stored basis axes.

Normals use two spherical angles (high-byte azimuth, low-byte polar), each multiplied by `2π/255`,
following the [published MD3 normal description](https://icculus.org/~phaethon/q3a/formats/md3format.html).
This is analytical decoding, not a reproduction of an engine's quantized sine lookup table.
`Md3Animation.interpolateSurface` blends positions and normalized normals.
`interpolateTag` blends origins and independently normalizes blended axes; it does not substitute
quaternion interpolation. Degenerate opposite directions choose an endpoint. Fractions weight the
destination frame, so a cgame-provided `backlerp` becomes `1-backlerp`.

`SkinParser.parse` supplies immutable surface/material mappings, ignores attachment entries, supports
comments and quoted fields, and canonicalizes material paths. Lookup ignores case and can strip a
surface's terminal `_digit` LOD suffix. The first duplicate mapping wins. File conventions follow
id's [player model manual](https://icculus.org/projects/gtkradiant/documentation/Model_Manual/model_manual.htm).

`AnimationConfig.parse` reads the 25 classic player rows and six optional Team Arena torso rows,
plus sex, footsteps, head offset, fixed legs, and fixed torso metadata. Missing optional torso clips
use the gesture clip. Lower-body frame indices subtract the export offset between `LEGS_WALKCR`
and `TORSO_GESTURE`; shared death frames retain their indices. Negative frame counts specify reverse
playback; reverse crouch/walk clips are synthesized. Zero FPS becomes one with a diagnostic. See id's
[Team Arena character documentation](https://icculus.org/gtkradiant/documentation/New_Teams_For_Q3TA/).

`Md3Animation.sample` offers deterministic preview clip timing, including loop tails, reverse,
flip-flop, and final-frame hold. The original game VM remains responsible for gameplay animation
selection and timing; its renderer frames can be interpolated directly.

Validation on the user-provided, ignored `pak0.pk3` loaded all **354 MD3s, 267 skins, and 23 player
animation configurations**, with zero failures. Models contained 642 surfaces, 24,734 frames,
6,162,695 vertex samples, and at most 247 frames each. Ten weapon-hand files had no surfaces and
inverted exporter bounds, motivating the explicit empty-box support. Thirteen synthetic tests cover
decoded data, immutable ownership, attachments, playback, config behavior, malformed bounds/offsets,
all truncations of a fixture, and 1,000 seeded header mutations.
