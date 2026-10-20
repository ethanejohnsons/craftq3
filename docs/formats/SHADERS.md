# Shader scripts, images and material programs

Quake III material scripts (`.shader` files in the `scripts` directory), the TGA and JPEG images
they name, and the compiled form the renderer consumes. Parsing and image decoding live in
`engine:formats`; compilation into GPU-ready data lives in `engine:render-ir`. Neither depends on
Minecraft or a graphics API; the Fabric backend (`Q3RenderBackend`) evaluates the compiled programs
in GLSL.

Code:

- [`ShaderLibrary`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/shader/ShaderLibrary.java),
  [`ShaderParser`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/shader/ShaderParser.java),
  [`ShaderDefinition`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/shader/ShaderDefinition.java),
  [`ShaderDiagnostic`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/shader/ShaderDiagnostic.java)
- [`ImageLoader`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/image/ImageLoader.java),
  [`TgaDecoder`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/image/TgaDecoder.java),
  [`JpegDecoder`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/image/JpegDecoder.java),
  [`Mipmaps`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/image/Mipmaps.java),
  [`Q3Image`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/image/Q3Image.java)
- [`material/`](../../engine/render-ir/src/main/java/dev/bluevista/craftq3/engine/render/material/)
  (`MaterialCompiler`, `MaterialProgram`, `StageProgram`, `DeformProgram`, `ProgramLayout`,
  `StageEvaluator`, `VertexDeformer`, `Waveforms`),
  [`SceneTextures`](../../engine/render-ir/src/main/java/dev/bluevista/craftq3/engine/render/SceneTextures.java),
  [`SkyGeometry`](../../engine/render-ir/src/main/java/dev/bluevista/craftq3/engine/render/SkyGeometry.java),
  [`FogVolumes`](../../engine/render-ir/src/main/java/dev/bluevista/craftq3/engine/render/FogVolumes.java),
  [`PortalView`](../../engine/render-ir/src/main/java/dev/bluevista/craftq3/engine/render/PortalView.java),
  [`PortalViews`](../../engine/render-ir/src/main/java/dev/bluevista/craftq3/engine/render/PortalViews.java)

The grammar follows the public
[Q3Radiant Shader Manual](https://icculus.org/gtkradiant/documentation/Q3AShader_Manual/index.htm).
Independent implementation; see [LICENSE-NOTES.md](../../LICENSE-NOTES.md).

## Discovery and precedence

`ShaderLibrary.load` lists every `.shader` file directly in the `scripts` directory, like
`FS_ListFiles` (each name once, highest-priority source first); `shaderlist.txt` is not used.
As in the original renderer, the files are processed in reverse of that order, so when two
*differently named* files define the same shader, the file listed **last** wins (a `pak0` script
beats a later pak or mod). To override a shader, ship a script with the same file name: the
search path then replaces the whole file. Within one file the first definition wins. Ignored
duplicates produce a diagnostic naming the winning script.

Names are canonicalised for lookup: lower case, `/` separators, extension stripped. Names
from BSP faces, MD3 surfaces and skins compare the same way. `resolve(name, lightmapped)`
returns the definition, or the implicit material for an undefined name:

| Surface | Implicit stages |
| --- | --- |
| Lightmapped | image (`rgbGen identity`, opaque, depth write), then `$lightmap` with `blendFunc filter`, `depthFunc equal` |
| Not lightmapped | image with `rgbGen vertex`, opaque |

The image name is the shader name; extensions are resolved by `ImageLoader` (below). A defined
shader with zero stages stays empty (sky, fog and portal metadata may still apply).

## Grammar

`ShaderParser.parse` reads ISO-8859-1 text: `//` and `/* */` comments (an unterminated block
comment runs to the end), quoted tokens, `{ } ( )` as separate tokens, case-insensitive keywords.
Arguments are line-delimited: a directive takes the remaining tokens on its own line, so an
`animMap` frame list must stay on one line.

Stage defaults:

| Field | Default |
| --- | --- |
| `blendFunc` | opaque (`GL_ONE GL_ZERO`) |
| `depthWrite` | on for opaque stages; blended stages need an explicit `depthWrite` |
| `rgbGen` | `identityLighting` when opaque or the source factor is `ONE` or `SRC_ALPHA`; else `identity` |
| `alphaGen` | `vertex` after `rgbGen vertex`; else `identity` |
| `tcGen` | `lightmap` for a `$lightmap` stage; else `base` |
| texture | `$whiteimage` with a diagnostic when a stage has no map |

Material defaults: `cull front`; `sort` is 1 for `portal`, 2 for sky, 4 with `polygonOffset`,
5 or 9 when the first stage blends (with or without `depthWrite`), 7 for a stageless fog
shader, 3 otherwise. Named sorts: portal 1, sky/environment 2, opaque 3, decal 4, seeThrough 5,
banner 6, fog 7, underwater 8, additive 9, nearest 16. `nomipmaps` implies `nopicmip`.

| Area | Keywords |
| --- | --- |
| Images | `map`, `clampMap`, `animMap`, `clampAnimMap` (up to 64 frames), `videoMap`, `$lightmap`, `$whiteimage`, `*white` |
| Blending | `add`, `filter`, `blend`, or two factors (`GL_` prefix optional), including `SRC_ALPHA_SATURATE` |
| Alpha test | `GT0`, `LT128`, `GE128` |
| `rgbGen` | `identity`, `identityLighting`, `vertex`, `exactVertex`, `oneMinusVertex`, `entity`, `oneMinusEntity`, `lightingDiffuse`, `wave`, `const`, `fog` |
| `alphaGen` | `identity`, `vertex`, `oneMinusVertex`, `entity`, `oneMinusEntity`, `lightingSpecular`, `wave`, `const`, `portal [range]` (default 256) |
| `tcGen` | `base`/`texture`, `lightmap`, `environment`, `vector`, `identity`, `fog` |
| `tcMod` | `scroll`, `scale`, `rotate`, `stretch`, `transform`, `turb`, `entityTranslate` (applied in order) |
| Waves | `sin`, `triangle`, `square`, `sawtooth`, `inverseSawtooth`, `noise` |
| Stage state | `depthFunc lequal\|equal\|disable`, `depthWrite`, `detail` |
| Material | `cull`, `sort`, `polygonOffset`, `nomipmaps`, `nopicmip`, `portal`, `clampTime`, `surfaceparm`, `skyParms`, `fogParms` |
| `deformVertexes` | `wave`, `normal`, `bulge`, `move`, `autosprite`, `autosprite2`, `projectionShadow`, `text0`..`text7` |

`videoMap name` plays a RoQ file; a bare file name is looked up in `video/` (see
[ROQ.md](ROQ.md)). `skyParms far height near` takes box prefixes or `-`; a height of `-` or 0
becomes 512. Box images are `<prefix>_rt`, `_lf`, `_bk`, `_ft`, `_up`, `_dn`.

`q3map_*` and `qer_*` directives, `tessSize`, `light` and `entityMergable` are compiler or editor
directives; they are skipped silently (their results are already baked into the BSP).

## Limits and recovery

| Resource | Limit |
| --- | --- |
| Script size | 4 MiB (4,194,304 characters) |
| Tokens per script / characters per token | 500,000 / 4,096 |
| Definitions per script / whole library | 16,384 / 65,536 |
| Stages per shader / `tcMod`s per stage / deforms per shader | 64 / 32 / 32 |
| Script files / total script bytes | 4,096 / 64 MiB |
| Diagnostics per script / whole library | 4,096 / 16,384 |

Unknown directives, unknown values and non-finite numbers give a diagnostic and skip the rest of
that line. Unknown `surfaceparm`s are kept with a warning. A structurally damaged shader
(unterminated, nested stages, over a stage or modifier limit) is skipped to its closing brace and
the rest of the file still loads. Only a script over the size or token limits is skipped whole;
past the library-wide file, byte or definition limits the remaining scripts are ignored.

## Images

`ImageLoader.candidates` gives the files tried for a name:

| Requested | Tried in order |
| --- | --- |
| `x.tga` | `x.tga`, `x.jpg` |
| `x.jpg` | `x.jpg`, `x.tga` |
| `x.jpeg` | `x.jpeg`, `x.tga`, `x.jpg` |
| `x` (no extension) | `x.tga`, `x.jpg` |
| other extension (`.png`, `.dds`, ...) | stem `.tga`, stem `.jpg`, then the full name plus `.tga`, `.jpg` |

A candidate that exists but fails to decode is reported and the next one is tried. When none
decodes, the result is a 16×16 magenta/black checker (`$missing`) with diagnostics.

TGA (Truevision 2.0): types 2 and 10 (true colour, 24 or 32 bits) and 3 and 11 (grayscale,
8 bits, or 16 bits with alpha), uncompressed or RLE. Colour-mapped types 1 and 9 are refused; a
colour map attached to a true-colour image is skipped. Both origin bits are honoured; interleave
bits are ignored. The fourth byte of a 32-bit pixel is always alpha, even when the descriptor's
attribute count is 0. Truncated headers, pixels and RLE packets, and packets that overrun the
image, are errors; a short RLE payload is rejected before the output is allocated.

JPEG uses the JDK's ImageIO reader (baseline and progressive) after checking the start-of-image
marker and declared dimensions. Grayscale expands to RGB and the result is always opaque.
Recoverable damage (such as a missing end marker) yields the image plus a warning; CMYK/YCCK
files are refused.

`Q3Image` is owned, immutable RGBA8, top row first, with no colour-space conversion. `Mipmaps`
builds a full chain by area filtering in encoded space (odd sizes keep their last row and
column). Limits: 64 MiB encoded per file, 16,777,216 pixels per image, and 256 MiB of decoded
images per map (`SceneTextures`) and for cgame-registered images (`SceneAssets`). Lightmap pages
are converted separately with the map's two overbright bits restored, scaling all three channels
together when one saturates.

## Compiled materials (`engine:render-ir`)

`MaterialCompiler.compile` turns a `ShaderDefinition` into a `MaterialProgram`: whole-material
state (sort, cull, polygon offset, sky and fog parameters, portal, `clampTime`, whether a fog
pass and a dynamic-light pass apply) plus `StageProgram`s (texture bundle, blend and depth state,
alpha test, fog mode, and the rgbGen/alphaGen/tcGen/tcMod shading as data) and `DeformProgram`s.
Compilation is pure. `MaterialRegistry` assigns stable indices: world materials first, cgame
materials appended as they appear.

`ProgramLayout` packs programs into fixed uniform blocks and keeps at most 8 stages, 8 tcMods per
stage and 4 deforms per material; anything beyond is dropped and listed in
`MaterialProgram.unexpressed()`. The backend evaluates stage shading, the per-vertex deforms
(`wave`, `normal`, `bulge`, `move`), fog and dynamic lights in GLSL. `autosprite` and
`autosprite2` need whole quads and the view axes and run on the CPU through `VertexDeformer`;
`text*` and `projectionShadow` deforms leave vertices unchanged (cgame `RF_SHADOW_PLANE`
shadows are drawn separately). `StageEvaluator` is the reference CPU evaluation of a stage
(used by tests and as the backend fallback). Waveforms follow the manual; `noise` uses a 32-bit
hash that GLSL reproduces exactly instead of the original noise table.

Related scene data:

- `SkyGeometry` builds the view-centred box (faces in `rt lf bk ft up dn` order) and the cloud
  layer (five faces, no bottom, 8 subdivisions) from `cloudHeight`. The near box is not drawn by
  default.
- `FogVolumes` resolves BSP fog effects to convex brush volumes (up to 256 volumes and 32,768
  planes) and clips the eye-to-point segment against them; the packed planes let the GPU do the
  same per vertex.
- `PortalView` maps a portal or mirror surface to the view it shows, either from the cgame's
  `RT_PORTALSURFACE` entity (during play) or from the map's `misc_portal_surface` and
  `misc_portal_camera` entities; mirrors reflect across the surface plane. `PortalViews` derives
  one view per visible portal plane and destination, one level deep by default.

Differences from the original renderer (noise, uniform patch tessellation, the sky near box,
`videoMap` timing) are listed in [DIVERGENCES.md](../DIVERGENCES.md).

Tests: `ShaderParserTest`, `ShaderLibraryTest`, `ImageLoaderTest`, `MipmapsTest`,
`MaterialCompilerTest`, `StageEvaluatorTest`, `SkyGeometryTest`, `FogVolumesTest`,
`PortalViewTest`.
