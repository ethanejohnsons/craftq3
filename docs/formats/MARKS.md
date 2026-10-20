# Mark fragments

`trap_CM_MarkFragments` (cgame import 27, `CG_CM_MARKFRAGMENTS`) clips the world's triangles to the
prism a decal polygon sweeps along a projection vector. The cgame owns everything else about a
mark (shader, texture coordinates, colour, fade, lifetime) and submits the returned polygons
through its usual renderer imports.

Code:
[`MarkFragments`](../../engine/render-ir/src/main/java/dev/bluevista/craftq3/engine/render/MarkFragments.java)
(geometry),
[`ClientWorld.markFragments`](../../engine/client/src/main/java/dev/bluevista/craftq3/engine/client/host/ClientWorld.java)
(guest binding, registered in
[`CgameHost`](../../engine/client/src/main/java/dev/bluevista/craftq3/engine/client/host/CgameHost.java)),
[`MarkFragmentLayout`](../../engine/vm-abi/src/main/java/dev/bluevista/craftq3/engine/vmabi/layout/MarkFragmentLayout.java).
Import policy: [SYSCALLS.md](../SYSCALLS.md).

## Guest interface

Arguments: point count, pointer to that many `vec3_t` corners, pointer to the projection `vec3_t`,
output point capacity, output point buffer (`vec3_t` array), output fragment capacity, output
fragment buffer. Each `markFragment_t` is 8 bytes: first point index and point count (two
little-endian ints). The return value is the number of fragments written.

The binding returns 0 without work for fewer than 3 or more than 64 corners and for a zero or
non-finite projection. Capacities are clamped to 65,536 points and 8,192 fragments. Every guest
read and write is range-checked as it happens; an out-of-range buffer faults the access, and the
import then follows its policy (degrades to 0, or a VM fault under `vm_strictSyscalls 1`).

## Geometry

- Candidates are the triangles of world (model 0) surfaces, as built for rendering, including
  patches tessellated to the `r_subdivisions` error bound as for rendering (`PatchTessellator`).
  Surfaces flagged `SURF_SKY`, `SURF_NOIMPACT` or `SURF_NOMARKS` never receive marks. Inline
  models (doors, platforms) receive none.
- The prism has one side plane per polygon edge plus two caps along the projection, each cap
  widened by 0.5 units to absorb the trace contact epsilon. Surfaces whose bounds miss the swept
  box (grown by 0.55 units) are skipped.
- A triangle whose facing normal does not point against the projection is skipped (back faces and
  surfaces parallel to it).
- Each remaining triangle is clipped (Sutherland-Hodgman, 0.05 unit epsilon) against every prism
  plane; fragments keep the world triangle's winding. Degenerate results are dropped.
- Projection longer than 131,072 units is refused; at most 1,000,000 triangles are visited per
  call. Output stops before the point or fragment capacity would be exceeded.

The collision model is not consulted; marks follow render geometry. Fragment order follows surface
and triangle order and is not guaranteed to match the original engine's.

Independent implementation; see [LICENSE-NOTES.md](../../LICENSE-NOTES.md). Tests:
`MarkFragmentsTest`.
