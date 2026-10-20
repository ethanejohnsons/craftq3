package dev.bluevista.craftq3.engine.formats.aas;

import dev.bluevista.craftq3.engine.common.io.Ranges;
import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.Binary;
import dev.bluevista.craftq3.engine.formats.Diagnostics;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Bounded reader of the little-endian EAAS version 4/5 layout. Like the original loader it checks
 * the identifier, version and lump ranges and accepts overlapping lumps; in addition every index
 * botlib follows is range-checked ({@link AasValidator#validate}).
 */
public final class AasReader {
  public static final int MAX_BYTES = 64 * 1024 * 1024;
  public static final int MAX_TOTAL_ELEMENTS = 4_000_000;
  private static final Log LOG = Log.get(AasReader.class);

  private AasReader() {}

  public static AasMap read(byte[] data) {
    return read(data, "aas");
  }

  /** Reads {@code data}; {@code source} names the file in diagnostics. */
  public static AasMap read(byte[] data, String source) {
    if (data == null || data.length < AasLump.HEADER_BYTES || data.length > MAX_BYTES)
      throw new AasFormatException("Invalid AAS file size");
    Diagnostics notes = new Diagnostics(LOG, source);
    ByteBuffer header = Binary.littleEndian(decodeHeader(data));
    AasMap.Parts parts = new AasMap.Parts();
    parts.version = header.getInt(4);
    parts.bspChecksum = header.getInt(8);
    parts.lumps = directory(header, data.length, notes);
    readGeometry(data, parts);
    readTopology(data, parts);
    parts.diagnostics = notes.notes();
    AasMap map = new AasMap(parts);
    AasValidator.validate(map);
    return map;
  }

  /**
   * Reads {@code data} and refuses it unless it was compiled from the BSP whose {@code
   * Com_BlockChecksum} is {@code expectedBspChecksum}, as the original loader does ("out of date").
   */
  public static AasMap read(byte[] data, int expectedBspChecksum) {
    AasMap result = read(data);
    if (result.bspChecksum() != expectedBspChecksum)
      throw new AasFormatException(
          "AAS BSP checksum "
              + Integer.toHexString(result.bspChecksum())
              + " does not match the map's "
              + Integer.toHexString(expectedBspChecksum));
    return result;
  }

  /**
   * The header with version 5 obfuscation removed: byte {@code 8+n} is stored XOR the low byte of
   * {@code 119*n}. Lump payloads are never obfuscated, and the input is not modified.
   */
  private static byte[] decodeHeader(byte[] data) {
    byte[] header = Arrays.copyOf(data, AasLump.HEADER_BYTES);
    ByteBuffer view = Binary.littleEndian(header);
    if (view.getInt(0) != AasMap.IDENT) throw new AasFormatException("Missing EAAS identifier");
    int version = view.getInt(4);
    if (version != AasMap.VERSION_OLD && version != AasMap.VERSION)
      throw new AasFormatException("Unsupported AAS version " + version + " (expected 4 or 5)");
    if (version == AasMap.VERSION) {
      for (int position = 8; position < AasLump.HEADER_BYTES; position++)
        header[position] ^= (byte) ((position - 8) * 119);
    }
    return header;
  }

  private static List<AasMap.LumpInfo> directory(ByteBuffer header, int size, Diagnostics notes) {
    List<AasMap.LumpInfo> lumps = new ArrayList<>();
    long total = 0;
    for (AasLump kind : AasLump.values()) {
      int offset = header.getInt(kind.headerOffset());
      int length = header.getInt(kind.headerOffset() + 4);
      if (!Ranges.contains(size, offset, length))
        throw new AasFormatException(
            "AAS " + kind + " range [" + offset + ", +" + length + ") lies outside the file");
      if (length % kind.stride() != 0)
        throw new AasFormatException("AAS " + kind + " has a partial record");
      int count = length / kind.stride();
      total += count;
      if (count > kind.maxElements() || total > MAX_TOTAL_ELEMENTS)
        throw new AasFormatException("AAS element budget exceeded at " + kind);
      if (length > 0 && offset < AasLump.HEADER_BYTES)
        notes.note("aas-overlap", "AAS " + kind + " overlaps the header");
      for (AasMap.LumpInfo previous : lumps) {
        if (length > 0
            && previous.length() > 0
            && offset < (long) previous.offset() + previous.length()
            && previous.offset() < (long) offset + length)
          notes.note("aas-overlap", "AAS lumps " + previous.kind() + " and " + kind + " overlap");
      }
      lumps.add(new AasMap.LumpInfo(kind, offset, length, count));
    }
    return lumps;
  }

  private static ByteBuffer lump(byte[] data, AasMap.Parts parts, AasLump kind) {
    AasMap.LumpInfo info = parts.lumps.get(kind.index());
    return Binary.littleEndian(data, info.offset(), info.length());
  }

  private static void readGeometry(byte[] data, AasMap.Parts parts) {
    parts.boundingBoxes = boundingBoxes(lump(data, parts, AasLump.BOUNDING_BOXES));
    parts.vertices = new AasMap.Vectors(floats(lump(data, parts, AasLump.VERTICES), "vertex"));
    parts.planes = planes(lump(data, parts, AasLump.PLANES));
    parts.edges = records(data, parts, AasLump.EDGES);
    parts.edgeIndices = records(data, parts, AasLump.EDGE_INDICES);
    parts.faces = records(data, parts, AasLump.FACES);
    parts.faceIndices = records(data, parts, AasLump.FACE_INDICES);
    parts.areas = areas(lump(data, parts, AasLump.AREAS));
  }

  private static void readTopology(byte[] data, AasMap.Parts parts) {
    parts.areaSettings = records(data, parts, AasLump.AREA_SETTINGS);
    parts.reachabilities = reachabilities(lump(data, parts, AasLump.REACHABILITIES));
    parts.nodes = records(data, parts, AasLump.NODES);
    parts.portals = records(data, parts, AasLump.PORTALS);
    parts.portalIndices = records(data, parts, AasLump.PORTAL_INDICES);
    parts.clusters = records(data, parts, AasLump.CLUSTERS);
  }

  private static AasMap.Records records(byte[] data, AasMap.Parts parts, AasLump kind) {
    ByteBuffer b = lump(data, parts, kind);
    int[] values = new int[b.remaining() / 4];
    b.asIntBuffer().get(values);
    return new AasMap.Records(values, kind.stride() / 4);
  }

  private static List<AasMap.BoundingBox> boundingBoxes(ByteBuffer b) {
    List<AasMap.BoundingBox> result = new ArrayList<>();
    while (b.hasRemaining()) {
      int presence = b.getInt();
      int flags = b.getInt();
      result.add(new AasMap.BoundingBox(presence, flags, vector(b), vector(b)));
    }
    return result;
  }

  private static AasMap.Planes planes(ByteBuffer b) {
    int count = b.remaining() / AasLump.PLANES.stride();
    float[] values = new float[count * 4];
    int[] types = new int[count];
    for (int i = 0; i < count; i++) {
      for (int j = 0; j < 4; j++) values[i * 4 + j] = finite(b.getFloat(), "plane");
      types[i] = b.getInt();
    }
    return new AasMap.Planes(values, types);
  }

  private static AasMap.Areas areas(ByteBuffer b) {
    int count = b.remaining() / AasLump.AREAS.stride();
    int[] ints = new int[count * 3];
    float[] floats = new float[count * 9];
    for (int i = 0; i < count; i++) {
      for (int j = 0; j < 3; j++) ints[i * 3 + j] = b.getInt();
      for (int j = 0; j < 9; j++) floats[i * 9 + j] = finite(b.getFloat(), "area bounds");
    }
    return new AasMap.Areas(ints, floats);
  }

  private static AasMap.Reachabilities reachabilities(ByteBuffer b) {
    int count = b.remaining() / AasLump.REACHABILITIES.stride();
    int[] ints = new int[count * AasMap.Reachabilities.INTS];
    float[] floats = new float[count * 6];
    for (int i = 0; i < count; i++) {
      int base = i * AasMap.Reachabilities.INTS;
      ints[base] = b.getInt();
      ints[base + 1] = b.getInt();
      ints[base + 2] = b.getInt();
      for (int j = 0; j < 6; j++) floats[i * 6 + j] = finite(b.getFloat(), "reachability point");
      ints[base + 3] = b.getInt();
      ints[base + 4] = Short.toUnsignedInt(b.getShort());
      ints[base + 5] = Short.toUnsignedInt(b.getShort());
    }
    return new AasMap.Reachabilities(ints, floats);
  }

  private static float[] floats(ByteBuffer b, String what) {
    float[] values = new float[b.remaining() / 4];
    for (int i = 0; i < values.length; i++) values[i] = finite(b.getFloat(), what);
    return values;
  }

  private static Vec3f vector(ByteBuffer b) {
    return new Vec3f(
        finite(b.getFloat(), "bounding box"),
        finite(b.getFloat(), "bounding box"),
        finite(b.getFloat(), "bounding box"));
  }

  private static float finite(float value, String what) {
    if (!Float.isFinite(value)) throw new AasFormatException("Non-finite AAS " + what);
    return value;
  }
}
