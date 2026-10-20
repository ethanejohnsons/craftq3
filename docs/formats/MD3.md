# MD3 models, skins and animation.cfg

MD3 (`IDP3`, version 15) holds vertex-animated models: frames, per-frame attachment tags and
surfaces. Player models add `.skin` files and an `animation.cfg`. The readers produce immutable
CPU data; `engine/render-ir` uploads and draws it, and the cgame chooses frames and `backlerp`.

Code (`engine/formats`, package `formats.md3`):
[`Md3Reader`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/md3/Md3Reader.java),
[`Md3Model`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/md3/Md3Model.java),
[`Md3Animation`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/md3/Md3Animation.java),
[`SkinParser`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/md3/SkinParser.java),
[`AnimationConfig`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/md3/AnimationConfig.java).
Layout reference: id Software's public
[qfiles.h](https://github.com/id-Software/Quake-III-Arena/blob/master/code/qcommon/qfiles.h).

## Layout

All fields are little-endian.

| Block | Bytes | Contents |
| --- | ---: | --- |
| Header | 108 | `IDP3`, version 15, 64-byte name, flags, frame/tag/surface/skin counts, frame, tag and surface offsets, end offset |
| Frame | 56 | mins, maxs, local origin, radius, 16-byte name |
| Tag (per frame) | 112 | 64-byte name, origin, three axis vectors |
| Surface header | 108 | `IDP3`, 64-byte name, flags, frame/shader/vertex/triangle counts, triangle, shader, texture-coordinate and vertex offsets (relative to the surface), end offset (to the next surface) |
| Shader | 68 | 64-byte name, index |
| Triangle | 12 | three vertex indices |
| Texture coordinate | 8 | s, t |
| Vertex (per frame) | 8 | x, y, z as signed shorts in 1/64 units, packed normal |

The packed normal's high byte is latitude and its low byte longitude. It is decoded as the original
renderer does, through a 1,024-entry sine table with step 2pi/1023
(`Md3Reader.decodeNormal`), so results are unit length only to within about 0.5%.

## Limits and leniency

Like the original loader, the reader requires the ident, version 15 and at least one frame and
otherwise trusts the exporter: inverted frame bounds (tag-only models; see `Frame.hasBounds`),
duplicate tag names, aliased blocks and surfaces whose frame count differs from the model's are
accepted and noted. Non-finite frame, tag and texture-coordinate floats are replaced by zero and
noted. It rejects blocks outside the file, an end offset past the file, triangles naming absent
vertices, surfaces that do not advance, and these limits:

| Limit | Value |
| --- | ---: |
| File size | 64 MiB |
| Frames | 1,024 |
| Tags per frame | 256 |
| Surfaces | 256 |
| Shaders per surface | 256 |
| Vertices per surface | 4,096 |
| Triangles per surface | 8,192 |
| Vertex samples (frames x vertices, whole model) | 4,000,000 |

Surface names are lower-cased. Errors are `Md3FormatException` (unchecked).

## Interpolation

`Md3Animation` is a helper for consumers and previews; the original cgame supplies its own timing.
Fractions weight the destination frame, so a cgame `backlerp` becomes `1 - backlerp`.
`interpolateSurface` blends positions and renormalises blended normals; `interpolateTag` blends the
origin and normalises each blended axis (no quaternion interpolation); an antipodal blend picks the
nearer endpoint. `Md3Model.Tag` offers `transformPoint`, `transformDirection` and `compose`.

## Skins

`SkinParser` reads `surface,shader` pairs with the renderer's comma tokenizer (whitespace and commas
separate words, quotes group, `//` and `/* */` comments are skipped; text ends at a NUL). Surface
names are lower-cased, any name containing `tag_` is skipped, the first pair for a surface wins and
a missing shader is noted. Lookup falls back from `name_N` to `name` (level-of-detail suffix).
Limits: 1 MiB of text, 4,096 surfaces.

## animation.cfg

`AnimationConfig` follows the game's parser. Optional directives (`sex`, `footsteps`, `headoffset`,
`fixedlegs`, `fixedtorso`) come first; unknown tokens are noted. The first token starting with a
digit begins the rows: first frame, frame count, looping frames and frames per second, parsed like
C `atoi`/`atof`. Rules:

- At least 25 rows (death, torso and legs animations) are required; rows past the 31st are
  ignored. The six Team Arena torso gestures default to `TORSO_GESTURE` when absent.
- Lower-body first frames (from `LEGS_WALKCR` to `LEGS_TURN`) are made relative to `lower.md3` by
  subtracting the difference between the `LEGS_WALKCR` and `TORSO_GESTURE` first frames.
- A negative frame count plays the clip in reverse; a zero frame rate becomes one.
- `LEGS_BACKCR` and `LEGS_BACKWALK` are synthesized as reversed `LEGS_WALKCR` and `LEGS_WALK`.

Limit: 64 KiB of text. References: id's
[player model manual](https://icculus.org/gtkradiant/documentation/Model_Manual/model_manual.htm).

Independent implementation; see [LICENSE-NOTES.md](../../LICENSE-NOTES.md). Tests:
`Md3ReaderTest`, `Md3AnimationTest`, `Md3PlayerConfigTest`.
