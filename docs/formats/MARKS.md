# BSP mark fragments

`MarkFragments` supplies geometry for the original cgame mark-fragment service.
It accepts a convex polygon and finite projection vector in Q3 coordinates,
clips world triangles against the resulting prism, and returns bounded polygon
fragments. Cgame continues to choose the mark texture, UVs, color, lifetime and
fade, and submits the resulting polygons through its usual renderer traps.

The implementation caches model-zero triangles, including patches tessellated
at eight subdivisions. Surface bounds reject distant candidates. Sky,
no-impact and no-marks surfaces are excluded; a facing test excludes geometry
pointing away from the projection. A half-unit cap margin accommodates trace
contact epsilon. Triangle clipping retains the surface plane and winding.

Input is capped at 64 polygon points and 131072 projection units. Caller output
capacities are bounded by 65536 points and 8192 fragments; the implementation
stops before either capacity is exceeded. Each query has a one-million-triangle
work bound. Numeric inputs use checked finite `Vec3` values.

The VM ABI takes input point count/vec3 pointer, projection vec3 pointer, output
point capacity/pointer, and output fragment capacity/pointer. Each fragment is
two little-endian integers: first output point and point count. The host checks
complete guest output ranges before invoking the geometry service.

Synthetic tests cover floor projection, triangle and map-edge clipping,
backfaces, projection depth, misses, zero direction and output capacities.
This is an independent convex clipping implementation, not a translated native
renderer function. Moving inline-model marks, adaptive patch marks, edge
quantization and exact native fragment ordering remain fidelity work.
