# IBSP versions 46 and 47

Quake III maps (`maps/*.bsp`). `BspReader` turns one file into an immutable `BspMap`, accepting
what the original engine accepts and rejecting what a consumer would follow out of bounds.
Collision over the map is described in [COLLISION.md](COLLISION.md); shader names in the texture
lump resolve as in [SHADERS.md](SHADERS.md). Independent implementation; see
[LICENSE-NOTES.md](../../LICENSE-NOTES.md).

Code (`engine/formats/.../formats/bsp` and `.../formats/entity`):
[`BspReader`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/bsp/BspReader.java),
[`BspValidator`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/bsp/BspValidator.java),
[`BspLump`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/bsp/BspLump.java),
[`BspMap`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/bsp/BspMap.java),
[`SurfaceType`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/bsp/SurfaceType.java),
[`BspBoxLeaves`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/bsp/BspBoxLeaves.java),
[`EntityParser`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/entity/EntityParser.java),
[`BspEntity`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/entity/BspEntity.java).

## Header

All integers and floats are little-endian. The 144-byte header is the ident `IBSP` (`0x50534249`),
the version, then 17 `(offset, length)` int pairs in the lump order below.

Version 46 is Quake III. Version 47 (the RTCW header version, used by some community maps such as
`almostlost.bsp`) is accepted as an extension with a diagnostic: the code reads it with exactly the
same 17-lump layout and record sizes, so only the version number differs. Quake III 1.32 refuses
version 47. Any other version is refused.

## Lumps

| # | Lump | Record bytes | Contents |
| ---: | --- | ---: | --- |
| 0 | Entities | text | Latin-1 text up to the first NUL |
| 1 | Textures | 72 | 64-byte name, surface flags, contents |
| 2 | Planes | 16 | normal (3 floats), distance |
| 3 | Nodes | 36 | plane, front child, back child, int mins[3], maxs[3] |
| 4 | Leaves | 48 | cluster, area, int mins[3], maxs[3], first/count leaf face, first/count leaf brush |
| 5 | Leaf faces | 4 | face index |
| 6 | Leaf brushes | 4 | brush index |
| 7 | Models | 40 | float mins[3], maxs[3], first/count face, first/count brush |
| 8 | Brushes | 12 | first side, side count, texture |
| 9 | Brush sides | 8 | plane, texture |
| 10 | Vertices | 44 | position[3], texture st[2], lightmap st[2], normal[3], RGBA bytes |
| 11 | Mesh vertices | 4 | index relative to the face's first vertex |
| 12 | Effects (fogs) | 72 | 64-byte shader name, brush, visible side (-1 for none) |
| 13 | Faces | 104 | see below |
| 14 | Lightmaps | 49,152 | 128 x 128 RGB page |
| 15 | Light grid | 8 | ambient RGB, directed RGB, latitude byte, longitude byte |
| 16 | Visibility | variable | cluster count, bytes per cluster, then one bit row per cluster |

A face is 12 ints (texture, effect, surface type, first vertex, vertex count, first mesh vertex,
mesh vertex count, lightmap index, lightmap x, y, width, height), 12 floats (lightmap origin, the
`s` and `t` lightmap vectors, normal; flares keep their colour in the vectors), then patch width
and height. Surface types are 1 planar, 2 patch, 3 triangle soup, 4 flare; 0 and unknown values
are `BAD` and skipped by renderers.

## Limits and rejections

`BspFormatException` (unchecked, a `FormatException`) is thrown for:

- files smaller than the header or larger than 128 MiB; a missing ident or unsupported version;
- a lump range outside the file, or a partial trailing record in any lump except lightmaps and the
  light grid (the original's "funny lump size");
- more than 4,000,000 records in total, or more than 16,000,000 mesh-index checks;
- a non-finite plane, model bound or vertex position;
- node planes or children, leaf face/brush ranges and indices, brush side ranges, planes and
  textures, model face/brush ranges, fog brushes and face textures out of range; a cyclic node tree
  (checked iteratively over every node);
- for planar and soup faces, a vertex range or mesh-vertex range out of bounds, or a relative index
  outside the face's own vertex count;
- for patches, a control grid that is not odd, at least 3 and at most 32 (`MAX_PATCH_SIZE`) in each
  direction, or larger than the face's vertex count;
- a visibility lump shorter than 8 bytes, with negative dimensions, a row shorter than
  `(clusters + 7) / 8` bytes, or fewer than `clusters * rowBytes` bytes;
- entity text that is structurally damaged or over the parser's limits (below).

## Leniency

These are accepted, each recorded in `BspMap.diagnostics()`:

- lumps overlapping each other or the header;
- trailing partial lightmap or light-grid records (ignored), extra visibility bytes (ignored);
- non-finite texture coordinates, lightmap coordinates, vertex normals and face lightmap
  origin/vectors/normal: replaced with 0 (compilers leave uninitialised bytes in fields unused by,
  for example, vertex-lit surfaces). Positions, planes and model bounds are never sanitised;
- a face fog index that is neither -1 nor a valid effect: treated as no fog (silently for a flare
  with fog 0);
- patch mesh-vertex offset and count, which are unused and may hold any value;
- leaf clusters outside the visibility data, lightmap indices outside the lightmap lump (-3 is
  `LIGHTMAP_BY_VERTEX`, -1 none), bad surface types and incomplete triangles. These are listed
  only by `BspValidator.strictIssues` for validation tooling.

An empty visibility lump means everything is visible; a negative source cluster also sees every
cluster. Node children below zero are leaves `-child - 1`. `BspMap.checksum()` is the MD4 block
checksum of the whole file, compared against an AAS header (see [AAS.md](AAS.md)).

`BspBoxLeaves` enumerates the leaves a box touches in the original leaf query's order (front before
back, duplicates kept, last non-solid leaf reported past the capacity), with a bounded work budget.

## Entity text

`EntityParser` reads `{ "key" "value" ... }` blocks with the original tokenizer's rules: any
character up to space is whitespace, `//` and `/* */` comments are skipped (an unterminated block
comment ends the text), quoted strings have no escapes, unquoted words are accepted, and text ends
at the first NUL. Only a missing brace or value is an error. Limits: 4 MiB of text, 65,536 entities,
4,096 pairs per entity, 8,192 characters per token. `BspEntity` keeps duplicate keys in order;
lookups are case-insensitive and return the first match.

Tests: `BspReaderTest`, `BspBoxLeavesTest`, `EntityParserTest`. Format references:
[ioquake3 qfiles.h](https://github.com/ioquake/ioq3/blob/main/code/qcommon/qfiles.h),
[Unofficial Quake 3 Map Specs](https://www.mralligator.com/q3/).
