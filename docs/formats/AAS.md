# AAS navigation assets, versions 4 and 5

`craftq3-assets` supplies an independent, pure Java AAS reader. This is a navigation-data foundation;
loading an AAS file does not implement bot AI, routing, movement prediction, or the botlib VM bridge.
The original game VM remains responsible for game behavior. No commercial assets or copied or
mechanically translated engine functions are included.

The primary binary-layout reference is id Software's
[aasfile.h](https://github.com/id-Software/Quake-III-Arena/blob/master/code/botlib/aasfile.h).
An AAS file starts with little-endian ASCII `EAAS`, a version, an opaque BSP checksum, and fourteen
offset/length pairs. The header occupies 124 bytes. Version 4 stores it plainly. In version 5,
header bytes at positions 8 through 123 are XOR-masked with the low byte of `119 * (position - 8)`;
the first eight bytes and all lump payloads remain plain. The mask was independently checked against
the supplied corpus, including its encoded first-lump offset. All numeric payload fields are
little-endian, with finite IEEE-754 floats for positions, planes, and bounds.

| Lump, in header order | Record bytes | Reader element limit |
| --- | ---: | ---: |
| Bounding boxes | 32 | 64 |
| Vertices | 12 | 262,144 |
| Planes | 20 | 131,072 |
| Edges | 8 | 524,288 |
| Edge indices | 4 | 2,097,152 |
| Faces | 24 | 262,144 |
| Face indices | 4 | 1,048,576 |
| Areas | 48 | 65,536 |
| Area settings | 28 | 65,536 |
| Reachabilities | 44 | 1,048,576 |
| Nodes | 12 | 262,144 |
| Portals | 20 | 65,536 |
| Portal indices | 4 | 131,072 |
| Clusters | 16 | 65,536 |

`AasReader.read(byte[])` returns an immutable `AasMap` or throws a checked `AasFormatException`.
The overload accepting an expected BSP checksum compares its exact 32 bits. Lists are copied and
compact `Indices` wrappers offer `size()`, `get()`, and a copying `toArray()`. Q3 coordinates, flags,
plane types, cluster assignments, and travel metadata are preserved without renderer conversion.

The input budget is 64 MiB with at most four million combined decoded records. Each lump must have
a valid, stride-divisible range; nonempty lumps cannot overlap each other or the header. Validation
checks finite values, ordered bounds, nonzero plane normals, references, sentinel conventions,
oriented face/area agreement, matching area/settings counts, portal/cluster relationships, and node
cycles. Iterative graph checks avoid recursive stack exhaustion. Claimed ranges cannot alias within
index/reachability arrays, keeping dependent checks linear in the decoded data. Empty blocks and
trailing padding are accepted.

Signed edge and face indices retain their orientation. Positive node children reference nodes,
negative children reference areas, and zero denotes solid space. A negative area cluster references
a portal. The model retains all dummy zero records, so file references need no renumbering.

Reachability time is an unsigned 16-bit value; the final two padding bytes are also retained.
`baseTravelType()` separates the low 24 bits from additional team restrictions in `travelFlags()`.
The raw face/edge fields carry packed travel parameters for elevators, jump pads, and bobbing movers,
so those fields are deliberately not treated as geometry references for these travel kinds. Unknown
travel kinds remain opaque for future support. `hasGeometryReferences()` identifies the known kinds
for which signed face/edge bounds are checked.

The read-only `scripts/AuditAas.java` audit loaded all **30** AAS files from the user-provided, ignored
`pak0.pk3`: **26 version 5 and four version 4, with zero failures**. The older files were `q3dm17`,
`q3dm19`, `q3tourney1`, and `q3tourney5`. Totals included 96,758 areas, 125,731 reachabilities, 336,570
nodes, 722 portals, and 177 clusters, including each file's dummy entries. The 52 bobbing-mover
reachabilities in `q3dm14` confirmed why packed face/edge fields must be preserved. No pack was
modified and no commercial bytes are stored in tests.

Eight authored synthetic tests cover both headers, immutable ownership, signed geometry, clusters,
unsigned time and padding, packed travel values, malformed references/ranges, cycles, all fixture
truncations, and 1,000 seeded header mutations. These establish reader robustness and corpus
compatibility; they do not establish original botlib behavioral compatibility.
