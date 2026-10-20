# AAS navigation files, versions 4 and 5

Bot navigation data (`maps/<name>.aas`) compiled from a BSP. `AasReader` turns one file into an
immutable `AasMap`; routing, reachability use and movement live in `engine:botlib`
(`BotLib.loadMap`, the `botlib.aas` package such as `AasWorld`, `AasRouter`, `AasNavigation`).
Independent implementation; see [LICENSE-NOTES.md](../../LICENSE-NOTES.md).

Code (`engine/formats/.../formats/aas`):
[`AasReader`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/aas/AasReader.java),
[`AasValidator`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/aas/AasValidator.java),
[`AasLump`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/aas/AasLump.java),
[`AasMap`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/aas/AasMap.java),
[`TravelType`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/aas/TravelType.java);
botlib entry point
[`BotLib`](../../engine/botlib/src/main/java/dev/bluevista/craftq3/engine/botlib/BotLib.java).
Layout reference: id Software's
[aasfile.h](https://github.com/id-Software/Quake-III-Arena/blob/master/code/botlib/aasfile.h).

## Header

All fields are little-endian; floats must be finite. The 124-byte header is the ident `EAAS`
(`0x53414145`), the version, the BSP checksum, then 14 `(offset, length)` int pairs.

- Version 4 stores the header plainly.
- Version 5 XOR-masks header bytes 8..123: byte `8 + n` is stored XOR the low byte of `119 * n`.
  The ident, version and all lump payloads are plain. The input array is not modified.
- Any other version is refused.

## Lumps

| # | Lump | Record bytes | Fields | Element limit |
| ---: | --- | ---: | --- | ---: |
| 0 | Bounding boxes | 32 | presence type, flags, mins[3], maxs[3] | 64 |
| 1 | Vertices | 12 | x, y, z | 262,144 |
| 2 | Planes | 20 | normal[3], dist, type | 131,072 |
| 3 | Edges | 8 | vertex, vertex | 524,288 |
| 4 | Edge indices | 4 | signed edge | 2,097,152 |
| 5 | Faces | 24 | plane, flags, edge count, first edge, front area, back area | 262,144 |
| 6 | Face indices | 4 | signed face | 1,048,576 |
| 7 | Areas | 48 | number, face count, first face, mins[3], maxs[3], center[3] | 65,536 |
| 8 | Area settings | 28 | contents, flags, presence, cluster, cluster area, reach count, first reach | 65,536 |
| 9 | Reachabilities | 44 | area, face, edge, start[3], end[3], travel, time (u16), reserved (u16) | 1,048,576 |
| 10 | Nodes | 12 | plane, front child, back child | 262,144 |
| 11 | Portals | 20 | area, front cluster, back cluster, front/back cluster area | 65,536 |
| 12 | Portal indices | 4 | portal | 131,072 |
| 13 | Clusters | 16 | area count, reachability area count, portal count, first portal | 65,536 |

Files are limited to 64 MiB and 4,000,000 records in total.

## Validation

`AasFormatException` (unchecked, a `FormatException`) is thrown for a bad size, ident or version,
a lump range outside the file, a partial record, an element budget overrun, a non-finite float,
an area count that differs from the area-settings count, or any index botlib follows that is out
of range: edge vertices, edge/face/portal indices, face planes and areas, area face and
reachability ranges, area clusters or (negative) portals, reachability areas, node planes and
children, portal areas and clusters, cluster portal ranges. Node graphs are checked for cycles
iteratively. Like the original loader, overlapping lumps (with each other or the header) are
accepted and recorded in `AasMap.diagnostics()`.

Conventions kept as stored: record 0 of edges and faces is a dummy whose fields are not checked
(portal 0's cluster-area fields are not checked either); signed edge and face indices keep their
orientation; node children above zero are nodes, below zero are areas `-child`, and zero is solid; a
negative area cluster names a portal.

`AasValidator.strictIssues` additionally lists redundant fields that disagree (inverted bounds,
invalid planes, aliased ranges, area/face side mismatches, portal back-references); the original
loader accepts all of these, so they are only for validation tooling.

## Reachabilities

The travel field's low 24 bits are the base type (`TravelType`, 1 invalid through 19 func_bob);
the high byte holds travel flags. Travel time is in hundredths of a second. For elevators, jump
pads and bobbing platforms (and unknown types) the face and edge fields carry packed travel data,
so they are validated as geometry references only for walk through strafe jump, excluding
elevators (`TravelType.hasGeometryReferences`).

## BSP checksum

`AasReader.read(data, expectedBspChecksum)` refuses a file whose stored checksum differs from the
map's (the original's "out of date"). `BotLib.loadMap` makes the same comparison against
`sv_mapChecksum`; the BSP side is `BspMap.checksum()`, the MD4 block checksum of the whole file
(see [BSP46.md](BSP46.md)).

Tests: `AasReaderTest`.
