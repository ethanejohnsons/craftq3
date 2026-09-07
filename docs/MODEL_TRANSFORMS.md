# Native model-transform observations

The original retail cgame can submit a model with every axis component exactly
zero. The first bot-render reproduction is a rocket launcher,
`models/weapons2/rocketl/rocketl.md3`, at client time 8,952 and server time 8,950.
Its frame, old frame and backlerp are all zero; its origin and old origin are
`(294.5761413574219, 664.5830078125, 350.9553527832031)`. Its three axes are
`(-0,0,-0)`, `(-0,-0,0)` and `(0,0,0)`, with render flags 1, no custom shader and
RGBA zero. This is an exactly collapsed transform, not a nearly singular one.

`scripts/AuditBotRendering.java` reproduces it with the original retail server
and client QVMs, original pak0 q3dm17, seed 42, stationary human client 0 and
Sarge/Visor/Anarki at skill 3. Cgame frames advance by 16 ms and the server by
50 ms. Production `SubmittedGeometry` builds every submitted view without
opening a GPU device. The inspected 830-test runtime fails at that entity after
1,417 views, 26,635 model submissions and 99,972 constructed surfaces. The
same first failure was reproduced twice. Sprite submissions may carry a model
reference and zero axes; the diagnostic deliberately distinguishes those from
actual model submissions.

With only the corrected render-module classes prepended to the same inspected
830 runtime jars, the full 20-second replay passes: 5,241 views, 63,959 model
submissions and 437,654 surfaces. Four exact-zero model transforms are observed:
rocket launchers at 8,952 and 9,560, a shotgun at 18,456, and a railgun at 19,112.
No other nearly singular or nonzero singular model transform appears in this
bounded replay. The server, client and botlib classes are unchanged from the
inspected snapshot, isolating the renderer correction. Its report is
`/tmp/craftq3-bot-render-cpu-cofactor.log`.

## Unchanged native CPU probe

The authored `ModelTransformOracle.c` fixture calls header-declared native
`R_RotateForEntity`, the `SF_MD3` entry of `rb_surfaceTable`, and
`R_LocalPointToWorld`. It supplies one authored MD3 triangle with local vertices
`(0,0,0)`, `(1,0,0)` and `(0,1,1)`, a view whose world matrix is identity, and
explicit entity axes/origin. The native tessellator emits three vertices and
three indices for every tested transform.

`AuditModelTransforms.py` checks 64 requests: eight axis fixtures, four origins
and both values of `nonNormalizedAxes`. All pass the independently calculated
affine-position and model-matrix checks:

- Zero and signed-zero axes return normally with a finite matrix. All three
  world vertices coincide exactly with the entity origin.
- Rank-two axes `diag(1,1,0)` preserve a planar triangle of nonzero area. A
  dependent nonzero third axis is also accepted.
- Rank-one axes produce collinear vertices.
- An invertible scale of `1e-5` is accepted. At origin zero its transformed
  vertices remain distinct, despite its determinant being below `1e-12`.
- Identity and reflection fixtures also retain their expected positions.

Thus native CPU submission does not require an invertible model transform.
Discarding every singular model would lose the measured rank-two geometry.
Exactly zero axes collapse the triangle geometrically; the probe does not
initialize a GPU or measure fragment coverage. Its normal output is the local
MD3 tessellation output, so these results establish position/geometry behavior,
not equivalence of transformed lighting, arbitrary shader deformation or GPU
normal handling.

## Provenance and reproduction

`BuildModelTransformOracle.py` relinks the existing unchanged OpenGL 1 renderer
objects from official ioquake3 commit
`588393618dbc82e7207c21c6ddecca229944a03a` with the authored metadata adapter.
The adapter uses public format and renderer-internal header declarations. No
native function body was read, copied or mechanically translated. The normal
renderer objects remain untouched, and the new library and SDL dependency stay
under the ignored `.tools/model-transform-oracle` directory.

```sh
python3 scripts/BuildModelTransformOracle.py
python3 scripts/AuditModelTransforms.py
java -cp '/tmp/craftq3-platform-parity-runtime/*' \
  scripts/AuditBotRendering.java .tools/pak0-audit/games q3dm17
```

The native command transcript, raw output and summary are
`.tools/model-transform-oracle/audit.commands`, `audit.raw.log` and `report.log`.
The original failing Java capture is `/tmp/craftq3-bot-render-cpu-models.log`.
All fixtures are authored metadata; no model, texture or other original asset
is copied into the native fixture or distributed with CraftQ3.
