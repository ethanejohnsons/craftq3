# Q3 shader scripts and images

CraftQ3 reads original Q3 material scripts and texture files through the mounted virtual filesystem. It builds an immutable Java material description and evaluates that description against BSP geometry. Q3 scripts are material instructions, not GLSL programs. Parsing, image decoding, and stage evaluation have no Minecraft, Fabric, or graphics-driver dependency; the Fabric backend supplies the GPU passes.

The implementation is independently written from the public formats and manuals. It does not embed or call a Quake renderer. Original PK3 contents remain user-supplied files; they are neither extracted into resource packs nor included in the mod. The shader regression scene and its generated TGA images are original synthetic fixtures.

## Discovery and defaults

`ShaderLibrary.load(VirtualFileSystem)` discovers direct `scripts/*.shader` children. It does not require an editor `shaderlist.txt`. Files are ordered by their resolved VFS search priority, then canonical filename. The first definition of a name wins; duplicate definitions produce diagnostics. This preserves mod/PK3 precedence even when competing definitions live in differently named scripts.

Shader names use lowercase, slash-separated virtual paths with the final extension removed. Texture references retain their extensions. Host paths and traversal components remain subject to `VirtualPath` validation. `find(name)` returns an explicit definition; `resolve(name, lightmapped)` also supplies a missing-name fallback:

- A lightmapped surface receives an opaque base-texture stage followed by a `$lightmap` filter stage with equal-depth testing.
- A surface without a lightmap receives one base-texture stage using vertex color.
- An existing zero-stage definition stays empty. It is not replaced with an implicit material. Portal and fog metadata can still be meaningful without ordinary texture stages.

Within explicit stages, opaque blending is the default. Opaque stages write depth; blended stages require `depthWrite` to do so. `rgbGen` defaults to `identityLighting` for opaque, `ONE`-source, and `SRC_ALPHA`-source blending, and to `identity` otherwise. An omitted `alphaGen` follows `rgbGen vertex` with vertex alpha and otherwise uses identity alpha. `$lightmap` selects lightmap texture coordinates unless `tcGen` overrides them. A stage missing its texture map receives `$whiteimage` and a diagnostic.

An original example, equivalent in structure to the generated cutout fixture:

```text
textures/example/grate
{
    cull none
    {
        map textures/example/grate.tga
        alphaFunc GE128
        rgbGen identity
        depthWrite
    }
    {
        map $lightmap
        blendFunc filter
        rgbGen identity
        depthFunc equal
    }
}
```

## Grammar and material data

`ShaderParser.parse(sourceName, text)` returns definitions and source/line diagnostics. It handles braces, parenthesized vectors, quoted tokens, `//` comments, `/* ... */` comments, and case-insensitive keywords. Arguments are line-delimited; put an entire `animMap` frame list on the same line. Multiple fixed-argument directives may share a line. Script bytes are decoded as ISO-8859-1.

`ShaderDefinition` owns immutable stages, surface parameters, sky/fog descriptions, and ordered deformations. Nested records and enums represent the following instructions:

| Area | Represented instructions |
| --- | --- |
| Images | `map`, `clampMap`, `animMap`, `clampAnimMap`, `$lightmap`, `$whiteimage` |
| Blending | `add`, `filter`, `blend`, and explicit source/destination factors, including alpha and color inverses |
| Alpha testing | `GT0`, `LT128`, `GE128` |
| RGB | identity, identity lighting, vertex/exact vertex, inverse vertex, entity/inverse entity, diffuse lighting, wave, constant, fog |
| Alpha | identity, vertex/inverse vertex, entity/inverse entity, specular lighting, wave, constant, portal distance |
| Coordinates | base/texture, lightmap, environment, vector projection, identity, fog |
| Coordinate modifiers | ordered scroll, scale, rotate, stretch, affine transform, turbulence, entity translation |
| Raster state | `depthFunc`, `depthWrite`, `cull`, numeric/named `sort`, `polygonOffset`, `detail` |
| Global metadata | `surfaceparm`, `skyParms`, `fogParms`, `portal`, `noMipmaps`, `noPicmip`, `clampTime` |
| Deformations | wave, normal, bulge, move, autosprite, autosprite2, projection shadow, text0–text7 |

Compiler/editor instructions beginning with `q3map_` or `qer_`, plus `tessSize`, `light`, and `entityMergable`, are consumed without runtime evaluation. Their compiled results, where applicable, are already in the BSP. Surface/content parameter spellings remain available in the AST; gameplay meanings such as player clipping and damage require later runtime systems. BSP texture flags and contents are retained separately by the BSP reader.

## Evaluation and rendering boundary

`StageEvaluator` calculates final per-stage UVs and RGBA values. Texture modifiers execute in script order; animations select `floor(max(0,time) * fps)` modulo the frame count. Sine, triangle, square, sawtooth, inverse sawtooth, and noise waves are represented. Vertex colors and lightmaps restore the BSP's two bits of lighting headroom with ratio-preserving saturation. The viewer uses identity light 1 rather than changing Minecraft's display gamma.

`VertexDeformer` evaluates ordered wave, move, bulge, and normal changes. Autosprite uses complete triangle-pair quads and the current camera basis; autosprite2 keeps a quad's major axis fixed. Incomplete/non-quad groups retain their source geometry. Camera bases can include portal roll and mirror reflection.

`SkyGeometry` provides camera-centered environment faces and tessellated cloud geometry. Environment suffixes are `rt`, `lf`, `bk`, `ft`, `up`, and `dn`; cloud UVs depend on spherical projection and `cloudHeight`. Cloud geometry omits the bottom face. Distinct sky materials remain distinct: each sky uses a mask from its own visible BSP surfaces rather than taking over all sky openings. `nearBox` is retained and its images can be resolved, but the world sky renderer does not draw a near box. GPU pass details and current limitations are recorded in [COMPATIBILITY.md](../../COMPATIBILITY.md).

`FogVolumes` resolves BSP effects to convex brush volumes and evaluates the eye-to-surface segment through them. `PortalView` resolves nearby `misc_portal_surface` entities, mirrors, targeted cameras, camera aim/roll, and source/destination clipping planes. Its full basis preserves reflection handedness; yaw and pitch alone cannot represent a mirror. The original entity conventions are documented in the [GtkRadiant entity manual](https://icculus.org/gtkradiant/documentation/q3radiant_manual/appndx/appn_b_6.htm).

Parsing a directive is not a claim of full entity simulation. `projectionShadow` and text deformations are retained but currently leave geometry unchanged because their entity inputs are not implemented. Entity color, alpha, texture translation, diffuse lighting, and specular inputs exist in the evaluator context; the BSP viewer supplies the context available to it. Portal cameras use static authored aim and roll; slow/fast rotation flags are reported, and cgame-timed camera animation remains future work. Noise uses CraftQ3's deterministic interpolated noise rather than Quake's random table, so noise effects are not expected to match the original renderer pixel for pixel. The standalone `tcGen fog` coordinate approximation is separate from the actual brush-fog pass.

## Texture files

`ImageLoader` resolves an explicit `.tga` first and then the corresponding `.jpg`, or an explicit `.jpg`/`.jpeg` first and then `.tga`. Extensionless names try `.tga` then `.jpg`. A missing image becomes the diagnostic checker texture. A malformed existing image raises a format error instead of silently falling through to another file.

TGA support covers true-color 24/32-bit and grayscale 8/16-bit images, both uncompressed and RLE. Origin bits are normalized to top-left rows. The fourth channel of a 32-bit Q3 texture remains alpha even when an older exporter left the descriptor's attribute count at zero. Palette images, 15/16-bit true-color images, and interleaved rows are not supported. Truncated data, overflowing packets, and invalid dimensions are rejected. The byte layout follows the [Truevision TGA 2.0 specification](https://www.ludorg.net/amnesia/TGA_File_Format_Spec.html).

JPEG decoding uses the JDK JPEG reader after validating declared dimensions. Decoder warnings, inconsistent dimensions, and damaged input are errors. Output is opaque RGBA8. The format reference is [ITU-T T.81](https://www.itu.int/rec/T-REC-T.81-199209-I/en). `Q3Image` owns its pixels and returns defensive copies; neither decoder owns GPU resources.

## Bounds and recovery

| Resource | Limit |
| --- | --- |
| One script | 4 MiB/4,194,304 characters |
| Tokens per script / characters per token | 500,000 / 4,096 |
| Definitions per script / whole library | 16,384 / 65,536 |
| Stages per material | 64 |
| Texture modifiers per stage / deformations per material | 32 / 32 |
| Animation frames per stage | 64 |
| Script files / cumulative encoded script data | 4,096 / 64 MiB |
| Diagnostics per script / whole library | 4,096 / 16,384 |
| Encoded image / decoded image | 64 MiB / 16,777,216 pixels |
| Loaded map image budget | 256 MiB, with BSP lightmaps accounted separately |

Unknown directives and invalid numeric arguments produce bounded diagnostics and skip the rest of that line without consuming structural braces. Unknown surface parameters are retained with a warning. Non-finite numbers are rejected. Structural damage or per-file resource exhaustion rejects the script; `ShaderLibrary` reports that file and continues with other scripts. Collection-level byte, file, and definition limits fail the library load. These limits supplement the VFS's own archive and entry limits.

The parser and evaluator are tested with original synthetic scripts, malformed input, cross-file VFS precedence, deterministic image data, texture modifiers, billboards, sky geometry, and portal transforms. `generateShaderFixture` writes the independently authored `craftq3_shaderlab` BSP, script, and TGA files only to the ignored development game directory. Current capture evidence belongs in [VALIDATION.md](../VALIDATION.md).

The primary material reference is id Software's [Q3Radiant Shader Manual](https://icculus.org/gtkradiant/documentation/Q3AShader_Manual/index.htm), especially its [general keywords](https://icculus.org/gtkradiant/documentation/Q3AShader_Manual/ch02/pg2_1.htm) and [stage keywords](https://icculus.org/gtkradiant/documentation/Q3AShader_Manual/ch05/pg5_1.htm). The descriptions above document CraftQ3's current implementation; they do not imply support for every Q3Map2 extension or legacy experimental keyword.
