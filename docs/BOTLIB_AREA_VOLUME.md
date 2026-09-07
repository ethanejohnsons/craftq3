# AAS area volume

`AasAreaVolume` provides the native-observed area-volume value used when choosing among jump-pad areas. It derives the value from boundary faces, edges, vertices, and planes. Stored area bounds and center do not determine this value.

```java
var volumes = new AasAreaVolume(map);
float volume = volumes.volume(areaNumber);
```

The provider holds lazy per-map caches for face areas and area volumes. Queries are synchronized so the caches are safe to share. An optional constructor argument limits geometry visits per uncached query; the default is 10,000,000, counting boundary faces and uncached face triangles. Exceeding that limit throws an explicit exception. Allocation is bounded by the existing AAS area and face limits. Invalid indices, malformed geometry ranges, and non-finite float calculations fail explicitly. Empty areas return zero.

## Observed geometry and numeric behavior

Face area is the sum of triangle-fan cross-product lengths, multiplied by one half. Signed edge indices determine each triangle's vertex order. Reversing a complete boundary preserves its area; reversing edge directions without reversing the boundary order can describe different or degenerate geometry.

Area volume uses a boundary vertex as the common pyramid reference. The reference is endpoint zero of the **absolute** first edge index of the first boundary face; its signed direction is ignored for this particular reference. Each face contributes its area times the distance from that reference to the face's outward plane. When the queried area is the face's front area, the opposite plane in the paired plane records is used. Face-index signs do not choose that plane. The contributions are accumulated before one final division by three.

Arithmetic preserves separate float32 operations, including triangle lengths, plane distances, accumulation, and final division. There is no determinant threshold, bounding-box approximation, or absolute-value clamp. Inconsistent plane metadata can produce negative volume, and that signed result is retained. A native fixture with one altered box plane returns -384; an inverted face returns 8 instead of the ordinary box's 24. A separate reversed-first-edge fixture isolates the reference endpoint and returns exactly 660.609375.

## Validation and provenance

The independent geometry implementation was tested against calls to unchanged native `AAS_AreaVolume` and, during initial investigation, `AAS_FaceArea`. Native routine bodies were not read, copied, or translated. The mathematical decomposition was checked through authored geometry inputs and returned values.

The reference is ioquake3 commit `588393618dbc82e7207c21c6ddecca229944a03a`. [BuildAreaVolumeOracle.py](../scripts/BuildAreaVolumeOracle.py) compiles unchanged `be_aas_reach.c` and `q_math.c` with `-ffp-contract=off`, then links only reachable code with [AreaVolumeOracle.c](../scripts/AreaVolumeOracle.c). The host supplies declared `aasworld` metadata through stdin and calls the exported operations. It does not load botlib, open a device, run a VM, or access the network.

[AuditAreaVolumes.java](../scripts/AuditAreaVolumes.java) reads the user-supplied PK3 without extracting files, passes its parsed geometry to the observer, and compares float bits against the production provider. The completed corpus covers:

- **96,758 area queries across all 30 original maps**, including their empty area-zero records.
- **10,000 authored meshes**, varying affine geometry, translations, reversed edge loops, face order and signs, front/back plane representation, degeneracy, and deliberately inconsistent normal metadata.
- **106,758 exact comparisons, zero differences.**

Five focused tests cover known native fixture results, stored metadata independence, signed and degenerate results, caching, work limits, index bounds, and numeric overflow. The shared provider handles geometry only; jump-pad candidate ordering, prediction, zero-volume ties, and selection policy belong to the separate item-placement service.

```sh
python3 scripts/BuildAreaVolumeOracle.py
java -cp craftq3-core/build/classes/java/main:craftq3-assets/build/classes/java/main:craftq3-botlib/build/classes/java/main scripts/AuditAreaVolumes.java /absolute/path/pak0.pk3 .tools/area-volume-oracle/probe
```

The scripts and tests contain only authored fixtures. Native source, native objects, and original media remain development inputs and are not bundled.
