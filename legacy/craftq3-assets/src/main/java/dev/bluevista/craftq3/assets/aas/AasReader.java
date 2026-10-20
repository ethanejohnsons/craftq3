package dev.bluevista.craftq3.assets.aas;

import dev.bluevista.craftq3.assets.aas.AasMap.*;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Bounded independent reader of the little-endian EAAS version 4/5 file layout. */
public final class AasReader {
  public static final int HEADER_BYTES = 124,
      MAX_BYTES = 64 * 1024 * 1024,
      MAX_TOTAL_ELEMENTS = 4_000_000;
  private static final int IDENT = 0x53414145;

  private AasReader() {}

  public static AasMap read(byte[] data) throws AasFormatException {
    if (data == null || data.length < HEADER_BYTES || data.length > MAX_BYTES)
      throw bad("Invalid AAS file size");
    byte[] decodedHeader = Arrays.copyOf(data, HEADER_BYTES);
    ByteBuffer header = ByteBuffer.wrap(decodedHeader).order(ByteOrder.LITTLE_ENDIAN);
    if (header.getInt() != IDENT) throw bad("Expected EAAS identifier");
    int version = header.getInt();
    if (version != 4 && version != 5) throw bad("Unsupported AAS version " + version);
    // Version 5 stores header byte 8+n XOR the low byte of 119*n; lump payloads are unchanged.
    if (version == 5) {
      for (int position = 8; position < HEADER_BYTES; position++)
        decodedHeader[position] ^= (byte) ((position - 8) * 119);
    }
    int checksum = header.getInt();
    List<Lump> lumps = new ArrayList<>();
    long total = 0;
    for (LumpKind kind : LumpKind.values()) {
      int offset = header.getInt(), length = header.getInt();
      if (offset < 0
          || length < 0
          || (long) offset + length > data.length
          || (length > 0 && offset < HEADER_BYTES)
          || length % kind.stride() != 0) {
        throw bad("Invalid AAS " + kind + " lump range/alignment");
      }
      int count = length / kind.stride();
      total += count;
      if (count > kind.maxElements() || total > MAX_TOTAL_ELEMENTS)
        throw bad("AAS element budget exceeded at " + kind);
      if (length > 0) {
        for (Lump previous : lumps) {
          if (previous.length() > 0
              && offset < (long) previous.offset() + previous.length()
              && previous.offset() < (long) offset + length) throw bad("Overlapping AAS lumps");
        }
      }
      lumps.add(new Lump(kind, offset, length, count));
    }
    List<BoundingBox> boxes =
        read(
            data, lumps.get(0), b -> new BoundingBox(b.getInt(), b.getInt(), vector(b), vector(b)));
    List<Vec3> vertices = read(data, lumps.get(1), AasReader::vector);
    List<Plane> planes = read(data, lumps.get(2), b -> new Plane(vector(b), finite(b), b.getInt()));
    List<Edge> edges = read(data, lumps.get(3), b -> new Edge(b.getInt(), b.getInt()));
    Indices edgeIndices = indices(data, lumps.get(4));
    List<Face> faces =
        read(
            data,
            lumps.get(5),
            b -> new Face(b.getInt(), b.getInt(), b.getInt(), b.getInt(), b.getInt(), b.getInt()));
    Indices faceIndices = indices(data, lumps.get(6));
    List<Area> areas =
        read(
            data,
            lumps.get(7),
            b -> new Area(b.getInt(), b.getInt(), b.getInt(), vector(b), vector(b), vector(b)));
    List<AreaSettings> settings =
        read(
            data,
            lumps.get(8),
            b ->
                new AreaSettings(
                    b.getInt(),
                    b.getInt(),
                    b.getInt(),
                    b.getInt(),
                    b.getInt(),
                    b.getInt(),
                    b.getInt()));
    List<Reachability> reachabilities =
        read(
            data,
            lumps.get(9),
            b ->
                new Reachability(
                    b.getInt(),
                    b.getInt(),
                    b.getInt(),
                    vector(b),
                    vector(b),
                    b.getInt(),
                    Short.toUnsignedInt(b.getShort()),
                    Short.toUnsignedInt(b.getShort())));
    List<Node> nodes = read(data, lumps.get(10), b -> new Node(b.getInt(), b.getInt(), b.getInt()));
    List<Portal> portals =
        read(
            data,
            lumps.get(11),
            b -> new Portal(b.getInt(), b.getInt(), b.getInt(), b.getInt(), b.getInt()));
    Indices portalIndices = indices(data, lumps.get(12));
    List<Cluster> clusters =
        read(data, lumps.get(13), b -> new Cluster(b.getInt(), b.getInt(), b.getInt(), b.getInt()));
    AasMap result =
        new AasMap(
            version,
            checksum,
            lumps,
            boxes,
            vertices,
            planes,
            edges,
            edgeIndices,
            faces,
            faceIndices,
            areas,
            settings,
            reachabilities,
            nodes,
            portals,
            portalIndices,
            clusters);
    AasValidator.validate(result);
    return result;
  }

  /** The expected BSP checksum is an opaque 32-bit value supplied by the engine's BSP loader. */
  public static AasMap read(byte[] data, int expectedBspChecksum) throws AasFormatException {
    AasMap result = read(data);
    if (result.bspChecksum() != expectedBspChecksum) throw bad("AAS BSP checksum mismatch");
    return result;
  }

  private interface Decoder<T> {
    T read(ByteBuffer input) throws AasFormatException;
  }

  private static <T> List<T> read(byte[] data, Lump lump, Decoder<T> decoder)
      throws AasFormatException {
    ByteBuffer bytes = bytes(data, lump);
    List<T> result = new ArrayList<>(lump.count());
    while (bytes.hasRemaining()) result.add(decoder.read(bytes));
    return List.copyOf(result);
  }

  private static Indices indices(byte[] data, Lump lump) {
    int[] result = new int[lump.count()];
    bytes(data, lump).asIntBuffer().get(result);
    return new Indices(result);
  }

  private static ByteBuffer bytes(byte[] data, Lump lump) {
    return ByteBuffer.wrap(data, lump.offset(), lump.length())
        .slice()
        .order(ByteOrder.LITTLE_ENDIAN);
  }

  private static Vec3 vector(ByteBuffer input) throws AasFormatException {
    return new Vec3(finite(input), finite(input), finite(input));
  }

  private static float finite(ByteBuffer input) throws AasFormatException {
    float value = input.getFloat();
    if (!Float.isFinite(value)) throw bad("Non-finite AAS geometry");
    return value;
  }

  private static AasFormatException bad(String message) {
    return new AasFormatException(message);
  }
}
