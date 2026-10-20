package dev.bluevista.craftq3.engine.formats.bsp;

import dev.bluevista.craftq3.engine.common.hash.Md4;
import dev.bluevista.craftq3.engine.common.io.Ranges;
import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.Binary;
import dev.bluevista.craftq3.engine.formats.Diagnostics;
import dev.bluevista.craftq3.engine.formats.entity.EntityFormatException;
import dev.bluevista.craftq3.engine.formats.entity.EntityParser;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded little-endian IBSP reader. It accepts what the original engine accepts: versions 46 and
 * 47, overlapping lumps, oversized visibility data and partial lightmap or light-grid records. It
 * rejects offsets outside the file, partial records elsewhere ("funny lump size"), resource
 * exhaustion, non-finite planes and positions, and references a consumer would follow out of bounds
 * (see {@link BspValidator}).
 */
public final class BspReader {
  public static final int MAX_BYTES = 128 * 1024 * 1024;
  public static final long MAX_RECORDS = 4_000_000;
  private static final Log LOG = Log.get(BspReader.class);

  private BspReader() {}

  public static BspMap read(byte[] data) {
    return read(data, "bsp");
  }

  /** Reads {@code data}; {@code source} names the file in diagnostics. */
  public static BspMap read(byte[] data, String source) {
    if (data == null || data.length < BspLump.HEADER_BYTES || data.length > MAX_BYTES)
      throw new BspFormatException("Invalid BSP size");
    Diagnostics notes = new Diagnostics(LOG, source);
    int version = header(data);
    if (version == BspMap.VERSION_EXTENDED)
      notes.note("bsp-v47", "IBSP version 47 header accepted as an extension");
    ByteBuffer[] lumps = directory(data, notes);
    BspMap.Parts parts = new BspMap.Parts();
    parts.version = version;
    parts.checksum = Md4.blockChecksum(data);
    readWorld(lumps, parts);
    readGeometry(lumps, parts, notes);
    readLighting(lumps, parts, notes);
    parts.diagnostics = notes.notes();
    BspMap map = new BspMap(parts);
    BspValidator.validate(map);
    return map;
  }

  private static int header(byte[] data) {
    ByteBuffer header = Binary.littleEndian(data);
    if (header.getInt(0) != BspMap.IDENT) throw new BspFormatException("Missing IBSP identifier");
    int version = header.getInt(4);
    if (version != BspMap.VERSION_Q3 && version != BspMap.VERSION_EXTENDED)
      throw new BspFormatException("Unsupported IBSP version " + version + " (expected 46 or 47)");
    return version;
  }

  private static ByteBuffer[] directory(byte[] data, Diagnostics notes) {
    ByteBuffer header = Binary.littleEndian(data);
    BspLump[] kinds = BspLump.values();
    ByteBuffer[] lumps = new ByteBuffer[kinds.length];
    int[] offsets = new int[kinds.length];
    int[] lengths = new int[kinds.length];
    long records = 0;
    for (BspLump lump : kinds) {
      int offset = header.getInt(lump.headerOffset());
      int length = header.getInt(lump.headerOffset() + 4);
      if (!Ranges.contains(data.length, offset, length))
        throw new BspFormatException(
            "Lump " + lump + " range [" + offset + ", +" + length + ") lies outside the file");
      if (lump.requiresWholeRecords() && length % lump.stride() != 0)
        throw new BspFormatException("Lump " + lump + " has a partial record (funny lump size)");
      if (lump.stride() > 1) records += length / lump.stride();
      if (records > MAX_RECORDS) throw new BspFormatException("BSP record budget exceeded");
      if (length > 0 && offset < BspLump.HEADER_BYTES)
        notes.note("bsp-overlap", "Lump " + lump + " overlaps the header");
      offsets[lump.index()] = offset;
      lengths[lump.index()] = length;
      lumps[lump.index()] = Binary.littleEndian(data, offset, length);
    }
    noteOverlaps(offsets, lengths, notes);
    return lumps;
  }

  private static void noteOverlaps(int[] offsets, int[] lengths, Diagnostics notes) {
    BspLump[] kinds = BspLump.values();
    for (int i = 0; i < offsets.length; i++) {
      for (int j = 0; j < i; j++) {
        if (lengths[i] > 0
            && lengths[j] > 0
            && offsets[i] < (long) offsets[j] + lengths[j]
            && offsets[j] < (long) offsets[i] + lengths[i])
          notes.note("bsp-overlap", "Lumps " + kinds[j] + " and " + kinds[i] + " overlap");
      }
    }
  }

  private static ByteBuffer lump(ByteBuffer[] lumps, BspLump kind) {
    return lumps[kind.index()].duplicate().order(lumps[kind.index()].order());
  }

  private static int records(ByteBuffer lump, BspLump kind) {
    return lump.remaining() / kind.stride();
  }

  // ---- entities, textures, models, effects ------------------------------------------------

  private static void readWorld(ByteBuffer[] lumps, BspMap.Parts parts) {
    ByteBuffer entities = lump(lumps, BspLump.ENTITIES);
    byte[] text = new byte[entities.remaining()];
    entities.get(text);
    parts.entityText = Binary.latin1UntilNul(text);
    try {
      parts.entities = EntityParser.parse(parts.entityText);
    } catch (EntityFormatException e) {
      throw new BspFormatException("Entity lump: " + e.getMessage(), e);
    }
    parts.textures = textures(lump(lumps, BspLump.TEXTURES));
    parts.models = models(lump(lumps, BspLump.MODELS));
    parts.effects = effects(lump(lumps, BspLump.EFFECTS));
  }

  private static List<BspMap.Texture> textures(ByteBuffer b) {
    List<BspMap.Texture> result = new ArrayList<>(records(b, BspLump.TEXTURES));
    while (b.hasRemaining())
      result.add(new BspMap.Texture(Binary.fixedString(b, 64), b.getInt(), b.getInt()));
    return result;
  }

  private static List<BspMap.Model> models(ByteBuffer b) {
    List<BspMap.Model> result = new ArrayList<>(records(b, BspLump.MODELS));
    while (b.hasRemaining()) {
      Vec3f mins = finiteVector(b, "model bounds");
      Vec3f maxs = finiteVector(b, "model bounds");
      result.add(new BspMap.Model(mins, maxs, b.getInt(), b.getInt(), b.getInt(), b.getInt()));
    }
    return result;
  }

  private static List<BspMap.Effect> effects(ByteBuffer b) {
    List<BspMap.Effect> result = new ArrayList<>(records(b, BspLump.EFFECTS));
    while (b.hasRemaining())
      result.add(new BspMap.Effect(Binary.fixedString(b, 64), b.getInt(), b.getInt()));
    return result;
  }

  // ---- tree, brushes and draw geometry ----------------------------------------------------

  private static void readGeometry(ByteBuffer[] lumps, BspMap.Parts parts, Diagnostics notes) {
    parts.planes = new BspMap.Planes(planes(lump(lumps, BspLump.PLANES)));
    parts.nodes = new BspMap.Nodes(ints(lump(lumps, BspLump.NODES)));
    parts.leaves = new BspMap.Leaves(ints(lump(lumps, BspLump.LEAVES)));
    parts.leafFaces = new BspMap.IndexList(ints(lump(lumps, BspLump.LEAF_FACES)));
    parts.leafBrushes = new BspMap.IndexList(ints(lump(lumps, BspLump.LEAF_BRUSHES)));
    parts.brushes = new BspMap.Brushes(ints(lump(lumps, BspLump.BRUSHES)));
    parts.brushSides = new BspMap.BrushSides(ints(lump(lumps, BspLump.BRUSH_SIDES)));
    parts.vertices = vertices(lump(lumps, BspLump.VERTICES), notes);
    parts.meshVertices = new BspMap.IndexList(ints(lump(lumps, BspLump.MESH_VERTICES)));
    parts.faces = faces(lump(lumps, BspLump.FACES), parts.effects.size(), notes);
  }

  private static float[] planes(ByteBuffer b) {
    float[] result = new float[b.remaining() / 4];
    for (int i = 0; i < result.length; i++) result[i] = finite(b.getFloat(), "plane");
    return result;
  }

  private static int[] ints(ByteBuffer b) {
    int[] result = new int[b.remaining() / 4];
    b.asIntBuffer().get(result);
    return result;
  }

  private static BspMap.Vertices vertices(ByteBuffer b, Diagnostics notes) {
    int count = records(b, BspLump.VERTICES);
    float[] positions = new float[count * 3];
    float[] texCoords = new float[count * 2];
    float[] lightmapCoords = new float[count * 2];
    float[] normals = new float[count * 3];
    byte[] colors = new byte[count * 4];
    Sanitizer sanitizer = new Sanitizer(notes, "vertex attributes");
    for (int i = 0; i < count; i++) {
      for (int axis = 0; axis < 3; axis++)
        positions[i * 3 + axis] = finite(b.getFloat(), "vertex position");
      for (int c = 0; c < 2; c++) texCoords[i * 2 + c] = sanitizer.apply(b.getFloat(), i);
      for (int c = 0; c < 2; c++) lightmapCoords[i * 2 + c] = sanitizer.apply(b.getFloat(), i);
      for (int axis = 0; axis < 3; axis++) normals[i * 3 + axis] = sanitizer.apply(b.getFloat(), i);
      b.get(colors, i * 4, 4);
    }
    return new BspMap.Vertices(positions, texCoords, lightmapCoords, normals, colors);
  }

  private static BspMap.Faces faces(ByteBuffer b, int effectCount, Diagnostics notes) {
    int count = records(b, BspLump.FACES);
    int[] ints = new int[count * BspMap.Faces.INTS];
    float[] floats = new float[count * BspMap.Faces.FLOATS];
    Sanitizer sanitizer = new Sanitizer(notes, "face lightmap vectors");
    for (int face = 0; face < count; face++) {
      int base = face * BspMap.Faces.INTS;
      for (int i = 0; i < 12; i++) ints[base + i] = b.getInt();
      for (int i = 0; i < BspMap.Faces.FLOATS; i++)
        floats[face * BspMap.Faces.FLOATS + i] = sanitizer.apply(b.getFloat(), face);
      ints[base + 12] = b.getInt();
      ints[base + 13] = b.getInt();
      int effect = ints[base + 1];
      if (effect != -1 && (effect < 0 || effect >= effectCount)) {
        // The renderer indexes fogs with this field unchecked; an unusable value means no fog.
        if (!(SurfaceType.of(ints[base + 2]) == SurfaceType.FLARE && effect == 0))
          notes.note("bsp-effect", "Face " + face + " fog " + effect + " ignored");
        ints[base + 1] = -1;
      }
    }
    return new BspMap.Faces(ints, floats);
  }

  // ---- lightmaps, light grid, visibility --------------------------------------------------

  private static void readLighting(ByteBuffer[] lumps, BspMap.Parts parts, Diagnostics notes) {
    parts.lightmaps = new BspMap.Lightmaps(wholeRecords(lumps, BspLump.LIGHTMAPS, notes));
    parts.lightGrid = new BspMap.LightGrid(wholeRecords(lumps, BspLump.LIGHT_GRID, notes));
    parts.visibility = visibility(lump(lumps, BspLump.VISIBILITY), notes);
  }

  private static byte[] wholeRecords(ByteBuffer[] lumps, BspLump kind, Diagnostics notes) {
    ByteBuffer b = lump(lumps, kind);
    int remainder = b.remaining() % kind.stride();
    if (remainder != 0)
      notes.note("bsp-partial", "Ignored " + remainder + " trailing bytes of lump " + kind);
    byte[] result = new byte[b.remaining() - remainder];
    b.get(result);
    return result;
  }

  private static BspMap.Visibility visibility(ByteBuffer b, Diagnostics notes) {
    if (!b.hasRemaining()) return BspMap.Visibility.NONE;
    if (b.remaining() < 8) throw new BspFormatException("Truncated visibility header");
    int clusters = b.getInt();
    int stride = b.getInt();
    if (clusters < 0 || stride < 0 || stride < (clusters + 7L) / 8)
      throw new BspFormatException(
          "Invalid visibility dimensions: " + clusters + " clusters, " + stride + " bytes each");
    long needed = (long) clusters * stride;
    if (needed > b.remaining())
      throw new BspFormatException(
          "Visibility lump holds " + b.remaining() + " bytes but needs " + needed);
    if (needed < b.remaining())
      notes.note("bsp-vis", "Ignored " + (b.remaining() - needed) + " extra visibility bytes");
    byte[] bits = new byte[(int) needed];
    b.get(bits);
    return new BspMap.Visibility(clusters, stride, bits);
  }

  // ---- floats -----------------------------------------------------------------------------

  private static float finite(float value, String what) {
    if (!Float.isFinite(value)) throw new BspFormatException("Non-finite " + what);
    return value;
  }

  private static Vec3f finiteVector(ByteBuffer b, String what) {
    return new Vec3f(
        finite(b.getFloat(), what), finite(b.getFloat(), what), finite(b.getFloat(), what));
  }

  /**
   * Replaces non-finite rendering attributes with zero. Compilers leave uninitialised bytes in
   * fields that are unused for some surfaces (for example lightmap coordinates of vertex-lit
   * surfaces); positions and planes are never sanitised.
   */
  private static final class Sanitizer {
    private final Diagnostics notes;
    private final String what;
    private int replaced;

    Sanitizer(Diagnostics notes, String what) {
      this.notes = notes;
      this.what = what;
    }

    float apply(float value, int record) {
      if (Float.isFinite(value)) return value;
      if (replaced++ == 0)
        notes.note(
            "bsp-nonfinite", "Non-finite " + what + " replaced with 0 (first at " + record + ")");
      return 0;
    }
  }
}
