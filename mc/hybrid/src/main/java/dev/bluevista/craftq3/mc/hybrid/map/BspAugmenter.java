package dev.bluevista.craftq3.mc.hybrid.map;

import dev.bluevista.craftq3.engine.formats.bsp.BspLump;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.bsp.BspReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Rewrites a parsed map for the server with extra inline box models appended (host trigger volumes)
 * and, optionally, a different entity string. Every other lump is copied unchanged, so the server's
 * collision, visibility and model numbering of the original map are kept; clients still load the
 * original file (the server keeps publishing the original checksum), and the extra models are
 * server-only because triggers are never sent to clients.
 */
public final class BspAugmenter {
  private record Box(float[] mins, float[] maxs, String shader, int contents) {}

  private final BspMap source;
  private final List<Box> boxes = new ArrayList<>();
  private String entityText;

  public BspAugmenter(BspMap source) {
    this.source = Objects.requireNonNull(source);
    this.entityText = source.entityText();
  }

  /** The number of the next model {@link #boxModel} adds. */
  public int nextModel() {
    return source.models().size() + boxes.size();
  }

  /** True when {@code count} more inline models fit under {@code MAX_SUBMODELS}. */
  public boolean fits(int count) {
    return nextModel() + count <= SyntheticBsp.MAX_MODELS;
  }

  /** Appends inline model {@code *N} made of one box brush and returns {@code N}. */
  public int boxModel(float[] mins, float[] maxs, String shader, int contents) {
    if (!fits(1)) throw new IllegalStateException("No free inline model slot");
    for (int axis = 0; axis < 3; axis++)
      if (!(mins[axis] < maxs[axis])) throw new IllegalArgumentException("Empty box model");
    boxes.add(new Box(mins.clone(), maxs.clone(), shader, contents));
    return nextModel() - 1;
  }

  public BspAugmenter entityText(String text) {
    entityText = Objects.requireNonNull(text);
    return this;
  }

  public String entityTextValue() {
    return entityText;
  }

  public BspMap build(String sourceName) {
    return BspReader.read(write(), sourceName);
  }

  public byte[] write() {
    BspMap m = source;
    Map<BspLump, byte[]> lumps = new EnumMap<>(BspLump.class);
    lumps.put(BspLump.ENTITIES, (entityText + '\0').getBytes(StandardCharsets.ISO_8859_1));

    List<BspMap.Texture> textures = new ArrayList<>(m.textures());
    int[] boxTexture = new int[boxes.size()];
    for (int i = 0; i < boxes.size(); i++) {
      Box b = boxes.get(i);
      BspMap.Texture t = new BspMap.Texture(b.shader, 0, b.contents);
      int index = textures.indexOf(t);
      if (index < 0) {
        textures.add(t);
        index = textures.size() - 1;
      }
      boxTexture[i] = index;
    }
    ByteBuffer tex = SyntheticBsp.buffer(72 * textures.size());
    for (BspMap.Texture t : textures) {
      SyntheticBsp.name(tex, t.name(), 64);
      tex.putInt(t.surfaceFlags()).putInt(t.contents());
    }
    lumps.put(BspLump.TEXTURES, tex.array());

    BspMap.Planes planes = m.planes();
    int planeCount = planes.count();
    ByteBuffer pl = SyntheticBsp.buffer(16 * (planeCount + 6 * boxes.size()));
    for (int p = 0; p < planeCount; p++)
      pl.putFloat(planes.normal(p, 0))
          .putFloat(planes.normal(p, 1))
          .putFloat(planes.normal(p, 2))
          .putFloat(planes.distance(p));
    for (Box b : boxes)
      for (int axis = 0; axis < 3; axis++)
        for (int sign = 0; sign < 2; sign++) {
          for (int k = 0; k < 3; k++) pl.putFloat(k == axis ? (sign == 0 ? -1 : 1) : 0);
          float d = sign == 0 ? -b.mins[axis] : b.maxs[axis];
          pl.putFloat(d == 0 ? 0 : d);
        }
    lumps.put(BspLump.PLANES, pl.array());

    BspMap.Nodes nodes = m.nodes();
    ByteBuffer nd = SyntheticBsp.buffer(36 * nodes.count());
    for (int n = 0; n < nodes.count(); n++) {
      nd.putInt(nodes.plane(n)).putInt(nodes.front(n)).putInt(nodes.back(n));
      for (int a = 0; a < 3; a++) nd.putInt(nodes.mins(n, a));
      for (int a = 0; a < 3; a++) nd.putInt(nodes.maxs(n, a));
    }
    lumps.put(BspLump.NODES, nd.array());

    BspMap.Leaves leaves = m.leaves();
    ByteBuffer lf = SyntheticBsp.buffer(48 * leaves.count());
    for (int l = 0; l < leaves.count(); l++) {
      lf.putInt(leaves.cluster(l)).putInt(leaves.area(l));
      for (int a = 0; a < 3; a++) lf.putInt(leaves.mins(l, a));
      for (int a = 0; a < 3; a++) lf.putInt(leaves.maxs(l, a));
      lf.putInt(leaves.firstLeafFace(l)).putInt(leaves.leafFaceCount(l));
      lf.putInt(leaves.firstLeafBrush(l)).putInt(leaves.leafBrushCount(l));
    }
    lumps.put(BspLump.LEAVES, lf.array());
    lumps.put(BspLump.LEAF_FACES, ints(m.leafFaces().toArray()));
    lumps.put(BspLump.LEAF_BRUSHES, ints(m.leafBrushes().toArray()));

    BspMap.Brushes brushes = m.brushes();
    BspMap.BrushSides sides = m.brushSides();
    int brushCount = brushes.count();
    int sideCount = sides.count();
    ByteBuffer br = SyntheticBsp.buffer(12 * (brushCount + boxes.size()));
    for (int b = 0; b < brushCount; b++)
      br.putInt(brushes.firstSide(b)).putInt(brushes.sideCount(b)).putInt(brushes.texture(b));
    for (int i = 0; i < boxes.size(); i++)
      br.putInt(sideCount + i * 6).putInt(6).putInt(boxTexture[i]);
    lumps.put(BspLump.BRUSHES, br.array());
    ByteBuffer sd = SyntheticBsp.buffer(8 * (sideCount + 6 * boxes.size()));
    for (int s = 0; s < sideCount; s++) sd.putInt(sides.plane(s)).putInt(sides.texture(s));
    for (int i = 0; i < boxes.size(); i++)
      for (int k = 0; k < 6; k++) sd.putInt(planeCount + i * 6 + k).putInt(boxTexture[i]);
    lumps.put(BspLump.BRUSH_SIDES, sd.array());

    List<BspMap.Model> models = m.models();
    ByteBuffer md = SyntheticBsp.buffer(40 * (models.size() + boxes.size()));
    for (BspMap.Model model : models) {
      md.putFloat(model.mins().x()).putFloat(model.mins().y()).putFloat(model.mins().z());
      md.putFloat(model.maxs().x()).putFloat(model.maxs().y()).putFloat(model.maxs().z());
      md.putInt(model.firstFace()).putInt(model.faceCount());
      md.putInt(model.firstBrush()).putInt(model.brushCount());
    }
    for (int i = 0; i < boxes.size(); i++) {
      Box b = boxes.get(i);
      for (float v : b.mins) md.putFloat(v);
      for (float v : b.maxs) md.putFloat(v);
      md.putInt(0).putInt(0).putInt(brushCount + i).putInt(1);
    }
    lumps.put(BspLump.MODELS, md.array());

    BspMap.Vertices vertices = m.vertices();
    ByteBuffer vx = SyntheticBsp.buffer(44 * vertices.count());
    for (int v = 0; v < vertices.count(); v++) {
      for (int a = 0; a < 3; a++) vx.putFloat(vertices.position(v, a));
      for (int c = 0; c < 2; c++) vx.putFloat(vertices.texCoord(v, c));
      for (int c = 0; c < 2; c++) vx.putFloat(vertices.lightmapCoord(v, c));
      for (int a = 0; a < 3; a++) vx.putFloat(vertices.normal(v, a));
      vx.putInt(Integer.reverseBytes(vertices.rgba(v)));
    }
    lumps.put(BspLump.VERTICES, vx.array());
    lumps.put(BspLump.MESH_VERTICES, ints(m.meshVertices().toArray()));

    ByteBuffer ef = SyntheticBsp.buffer(72 * m.effects().size());
    for (BspMap.Effect e : m.effects()) {
      SyntheticBsp.name(ef, e.name(), 64);
      ef.putInt(e.brush()).putInt(e.visibleSide());
    }
    lumps.put(BspLump.EFFECTS, ef.array());

    BspMap.Faces faces = m.faces();
    ByteBuffer fc = SyntheticBsp.buffer(104 * faces.count());
    for (int f = 0; f < faces.count(); f++) {
      fc.putInt(faces.texture(f)).putInt(faces.effect(f)).putInt(faces.type(f));
      fc.putInt(faces.firstVertex(f)).putInt(faces.vertexCount(f));
      fc.putInt(faces.firstMeshVertex(f)).putInt(faces.meshVertexCount(f));
      fc.putInt(faces.lightmap(f));
      fc.putInt(faces.lightmapX(f)).putInt(faces.lightmapY(f));
      fc.putInt(faces.lightmapWidth(f)).putInt(faces.lightmapHeight(f));
      for (int a = 0; a < 3; a++) fc.putFloat(faces.lightmapOrigin(f, a));
      for (int vec = 0; vec < 2; vec++)
        for (int a = 0; a < 3; a++) fc.putFloat(faces.lightmapVector(f, vec, a));
      for (int a = 0; a < 3; a++) fc.putFloat(faces.normal(f, a));
      fc.putInt(faces.patchWidth(f)).putInt(faces.patchHeight(f));
    }
    lumps.put(BspLump.FACES, fc.array());

    BspMap.Lightmaps lightmaps = m.lightmaps();
    byte[] lm = new byte[lightmaps.count() * BspMap.LIGHTMAP_BYTES];
    for (int i = 0; i < lightmaps.count(); i++)
      System.arraycopy(lightmaps.page(i), 0, lm, i * BspMap.LIGHTMAP_BYTES, BspMap.LIGHTMAP_BYTES);
    lumps.put(BspLump.LIGHTMAPS, lm);
    lumps.put(BspLump.LIGHT_GRID, m.lightGrid().toArray());

    BspMap.Visibility vis = m.visibility();
    if (!vis.absent()) {
      ByteBuffer vb = SyntheticBsp.buffer(8 + vis.clusters() * vis.bytesPerCluster());
      vb.putInt(vis.clusters()).putInt(vis.bytesPerCluster());
      for (int c = 0; c < vis.clusters(); c++) vb.put(vis.row(c));
      lumps.put(BspLump.VISIBILITY, vb.array());
    }
    return SyntheticBsp.assemble(lumps);
  }

  private static byte[] ints(int[] values) {
    ByteBuffer b = SyntheticBsp.buffer(4 * values.length);
    for (int v : values) b.putInt(v);
    return b.array();
  }
}
