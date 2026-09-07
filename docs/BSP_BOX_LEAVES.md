# BSP box-to-leaf queries

`BspBoxLeaves.query(map, bounds, capacity)` returns an immutable ordered leaf list
and `lastLeaf`, following independently observed `CM_BoxLeafnums` behavior. The
overload accepting a work budget bounds traversal independently of list capacity.
The query uses BSP node planes; stored leaf bounding boxes are not a substitute
for the leaf partition.

The list preserves front-child-before-back-child traversal and includes solid
leaves. `lastLeaf` is the final encountered leaf whose cluster is not -1, or zero
if none is encountered. Filling the output list does not stop traversal or the
`lastLeaf` update. Capacity zero is valid and returns an empty list while retaining
the same `lastLeaf` result as an unrestricted query.

## Plane contacts

The native plane type uses the first normal component equal to positive one,
checking X, Y, then Z. Other normal components do not need to be zero for that
metadata classification. For this positive-axis case, a minimum coordinate at or
above the plane selects only the front child; otherwise a maximum at or below the
plane selects only the back child. A point exactly on the plane therefore selects
the front child.

Other planes, including negative-axis normals, compare the extreme box-corner dot
products using float32 arithmetic. Front is included when the high dot product is
at least the plane distance, and back when the low dot product is strictly less.
Consequently, a nonaxial box whose high corner just touches the plane can visit
both children, whereas a positive-axis box touching from behind visits only back.
These contact conventions are preserved without an epsilon.

## Original door case

For original q3tourney4 bounds `(-1,-329,255)` through `(81,-311,401)`, the native
query returns 37 leaves at capacity 128. The final returned leaf is 1486, which is
solid; `lastLeaf` is 1432. Capacity one retains only leaf 614 but still reports
`lastLeaf=1432`; capacity zero retains no leaves and reports the same last leaf.

Touched leaf areas include -1, 0 and 1. The -1 area is an actual native sentinel:
leaf 631 has nonnegative cluster 311 and area -1. Area association must handle
that sentinel separately; filtering only solid clusters does not remove it. Raw
box-to-leaf enumeration deliberately retains all these leaves. Native entity
linking and area association are separate services.

## Native verification

`scripts/BoxLeafOracle.c` wraps only completion of public `CM_LoadMap`, then calls
the original `CM_BoxLeafnums`, `CM_LeafCluster` and `CM_LeafArea` operations. It
exits before game initialization. `scripts/BuildBoxLeafOracle.py` builds this
observer against the existing dedicated-server CMake build under
`.tools/server-startup-build`, using the unchanged ioquake3 checkout at commit
`588393618dbc82e7207c21c6ddecca229944a03a`. Collision classification and shared math
reference objects use `-ffp-contract=off`.

The authored query file contains one record per line:

```text
id capacity minX minY minZ maxX maxY maxZ
```

`CRAFTQ3_BOX_QUERIES` selects that file. Native output contains
`CMBOX id count lastLeaf leafIds...` and one `CMLEAF id cluster area` record per
touched or last leaf. Optional `CRAFTQ3_BOX_FIXTURES` records temporarily supply
authored one-plane nodes and leaf metadata, then restore the loaded native map.
These fixtures contain no original engine routines.

Seventeen controlled native cases established positive-axis, negative-axis and
oblique contact equality, traversal order, solid inclusion, empty last-leaf
initialization and capacity behavior. Three additional cases with normals
`(1,.2,0)`, `(1,1,0)` and `(0,1,.2)` confirmed the first-positive-one plane-type
rule even for noncanonical finite normals.

`scripts/AuditBoxLeaves.java` compared 310,000 production queries against the
native observer: 10,000 on each of the 31 BSP files in the user-supplied original
PK3, comprising 30 playable maps and `test_bigbox`. Every ordered leaf ID,
`lastLeaf`, capacity result, touched cluster and area matched exactly (5.539
seconds locally). Cases include random boxes near original vertices, leaf and
node bounds, exact points, plane contacts, neighboring float values, whole-map
boxes, the concrete door bounds, and capacities from zero through the complete
leaf count.

```text
python3 scripts/BuildBoxLeafOracle.py
java -cp <compiled-core-and-assets-classpath> scripts/AuditBoxLeaves.java \
  <user-pak0.pk3> .tools/box-leaf-oracle/probe 10000 all
```

The audit creates an isolated temporary home and a symlink to the supplied PK3
inside the ignored oracle directory. It does not modify the installation or
archive. Passing runs remove their temporary query files and logs; failures keep
reproduction records. Invalid query domains and exhausted work limits fail
explicitly. Native oracle binaries, source checkouts and original assets remain
outside distributable artifacts. Only public declaration/layout metadata and
independent native observations guided implementation; native routine bodies were
not copied or mechanically translated.
