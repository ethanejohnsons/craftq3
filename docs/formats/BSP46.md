# IBSP version 46

Binary integers and IEEE floats are little-endian. The 144-byte header contains `IBSP`, version 46 and 17 offset/length pairs. Bounds use long arithmetic; nonempty lumps may not overlap the header or one another. Record lengths must match their declared stride. Empty lumps and compiler padding outside declared lengths are accepted. Non-finite active geometry attributes, invalid cross-references, cyclic trees and oversized work/allocation requests fail with `BspFormatException`. The reader limits files to 64 MiB, records to two million, and indexed-face validation to eight million references.

| Lump | Record bytes | Interpretation |
| --- | ---: | --- |
| Entities | variable | Quoted key/value blocks, comments and NUL termination |
| Textures | 72 | 64-byte name, surface flags, contents |
| Planes | 16 | Normal and distance in Q3 coordinates |
| Nodes | 36 | Plane, two children, integer bounds |
| Leaves | 48 | Cluster/area, bounds and face/brush ranges |
| Leaf faces | 4 | Face indices |
| Leaf brushes | 4 | Brush indices |
| Models | 40 | Bounds and face/brush ranges |
| Brushes | 12 | Side range, texture index |
| Brush sides | 8 | Plane and texture indices |
| Vertices | 44 | Position, texture UV, lightmap UV, normal, RGBA bytes |
| Meshverts | 4 | Vertex offset **relative to the face's first vertex** |
| Effects | 72 | Name, brush, visible side |
| Faces | 104 | Material/effect/type, vertex/index ranges, lightmap data, normal, patch dimensions |
| Lightmaps | 49,152 | 128×128 RGB bytes |
| Light volumes | 8 | Ambient RGB, directional RGB, two direction bytes |
| Visibility | variable | Cluster count, row bytes and bitset rows |

Negative node children encode leaf `-child-1`; validation handles integer overflow before use. Absent visibility conservatively exposes all clusters. Empty compiler sentinel bounds are retained. Lightmap negative sentinels -1/-2/-3 are preserved rather than treated as array indices.

Surface types 1/3 consume the stored triangle indices. Their ranges, triangle counts and every relative index remain strictly checked. Type 2 uses odd control-grid dimensions and overlapping 3×3 quadratic patches: its mesh-index offset, count and referenced values are unused and may contain arbitrary compiler output. Its vertex range and control-grid dimensions remain strictly checked. The scene builder evaluates tensor-product Bernstein weights at a fixed subdivision count and retains vertex attributes and BSP surface identities. Type 4 flare metadata is retained. The existing narrow allowance for effect zero on a flare with an empty effects lump is unchanged.

## Vertex-lit lightmap coordinates

`LIGHTMAP_BY_VERTEX` (-3) selects vertex lighting. Some compiler/exporter output leaves non-finite bytes in the unused lightmap UV components of those surfaces. The reader changes a non-finite lightmap component to zero only when **every** face referencing that vertex is a type 1/2/3 surface with lightmap -3. Finite components are preserved exactly. A vertex shared with a lightmapped face or another surface policy remains strict; unreferenced non-finite coordinates are also rejected. Position, texture UV and normal components always require finite values.

Faces are read before vertices so this decision uses actual ownership. Two bounded difference arrays classify overlapping ranges in linear time in the number of faces plus vertices. Invalid or overflowing vertex ranges fail before any array indexing. The immutable published map therefore has finite vertex attributes without changing active geometry or modifying the source file. Zero is a deterministic replacement for unavailable data; it does not recover intended lightmap coordinates for an unusual custom shader that reads this unused field.

## Compatibility evidence

An authored memory-only observer called the unchanged native renderer's `RE_LoadWorldMap`, linking its BSP loader and patch tessellator with supplied allocation, shader-registration and GPU-image callbacks. No engine routine bodies were consulted or translated. These observations concern loading and shader selection, not an end-to-end native GPU rendering comparison.

- The installed `reqbath` resolved to `reqbath.pk3!MAPS/reqbath.bsp`, with no loose override. Native loading accepted all 6,996 surfaces (6,695 planar, 301 patches). Its 175 invalid relative-index references all belonged to patches. Replacing every patch's index offset/count with extreme integer sentinels in memory left the native load result unchanged.
- Ten installed loose/converted maps contained 603 non-finite lightmap components on 343 vertices, exclusively referenced by -3 surfaces. Native loading accepted all ten, requested vertex-lit shaders for every affected surface, and created zero lightmap images. Other floating-point fields were finite.
- After the narrow changes, `scripts/AuditAssets.java` passed all 71 maps in that installation, including scene construction, patch tessellation and material loading. The isolated original `pak0.pk3` corpus also passed all 31 maps. This is a loading/material audit; missing community textures remain reported separately, and it does not assert gameplay or visual parity for every community map.

The local observer source and executable are under `.tools/bsp-loader-oracle/`; original inputs remain in their archives or existing loose locations. No asset was extracted, rewritten or bundled. Logs for this checkpoint are `/tmp/craftq3-bsp-current-fixed.log` and `/tmp/craftq3-bsp-original-fixed.log`.

The authored `BspReaderTest` regressions cover unused patch fields, strict planar/triangle-soup indices, invalid patch grids, non-finite UV component replacement, active attributes, unreferenced vertices and shared lightmapped vertices. Existing cases cover all records, offsets, truncation, cycles, malformed entity text and deterministic header mutations.

Primary format metadata: [ioquake3 qfiles.h](https://github.com/ioquake/ioq3/blob/main/code/qcommon/qfiles.h) and [renderer lightmap sentinels](https://github.com/ioquake/ioq3/blob/main/code/renderercommon/tr_common.h). Additional reference: [Unofficial Quake 3 Map Specs](https://www.mralligator.com/q3/). The implementation is independent Java and includes no native loader code.
