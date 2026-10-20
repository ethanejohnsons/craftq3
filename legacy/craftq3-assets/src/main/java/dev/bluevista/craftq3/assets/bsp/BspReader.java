package dev.bluevista.craftq3.assets.bsp;

import dev.bluevista.craftq3.assets.bsp.BspMap.*;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Bounded little-endian IBSP46 parser. All references are checked before publication. */
public final class BspReader {
  public static final int MAX_BYTES = 64 * 1024 * 1024;
  private static final int HEADER_BYTES = 144;
  private static final int[] STRIDES = {
    1, 72, 16, 36, 48, 4, 4, 40, 12, 8, 44, 4, 72, 104, 49152, 8, 1
  };

  private BspReader() {}

  @FunctionalInterface
  private interface RecordReader<T> {
    T read(ByteBuffer buffer) throws BspFormatException;
  }

  public static BspMap read(byte[] data) throws BspFormatException {
    if (data.length < HEADER_BYTES || data.length > MAX_BYTES) throw bad("Invalid BSP size");
    ByteBuffer header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    if (header.getInt() != 0x50534249 || header.getInt() != 46)
      throw bad("Expected IBSP version 46");
    ByteBuffer[] lumps = new ByteBuffer[17];
    int[] offsets = new int[17];
    int[] lengths = new int[17];
    long records = 0;
    for (int i = 0; i < 17; i++) {
      int offset = header.getInt(), length = header.getInt();
      if (offset < 0
          || length < 0
          || (long) offset + length > data.length
          || (length > 0 && offset < HEADER_BYTES))
        throw bad("Lump " + i + " lies outside BSP payload");
      if (length % STRIDES[i] != 0) throw bad("Lump " + i + " has a partial record");
      if (i != 0 && i != 16) records += length / STRIDES[i];
      if (records > 2_000_000) throw bad("BSP record budget exceeded");
      offsets[i] = offset;
      lengths[i] = length;
      for (int j = 0; j < i; j++) {
        if (length > 0
            && lengths[j] > 0
            && offset < (long) offsets[j] + lengths[j]
            && offsets[j] < (long) offset + length)
          throw bad("Overlapping BSP lumps " + j + " and " + i);
      }
      lumps[i] = header.slice(offset, length).order(ByteOrder.LITTLE_ENDIAN);
    }
    List<Face> faces = records(lumps[13], BspReader::face);
    List<Vertex> vertices = vertices(lumps[10], faces);
    BspMap map =
        new BspMap(
            EntityParser.parse(new String(bytes(lumps[0]), StandardCharsets.ISO_8859_1)),
            records(lumps[1], b -> new Texture(name(b), b.getInt(), b.getInt())),
            records(lumps[2], b -> new Plane(vec(b), finite(b))),
            records(lumps[3], b -> new Node(b.getInt(), b.getInt(), b.getInt(), bounds(b, true))),
            records(
                lumps[4],
                b ->
                    new Leaf(
                        b.getInt(),
                        b.getInt(),
                        bounds(b, true),
                        b.getInt(),
                        b.getInt(),
                        b.getInt(),
                        b.getInt())),
            records(lumps[5], ByteBuffer::getInt),
            records(lumps[6], ByteBuffer::getInt),
            records(
                lumps[7],
                b -> new Model(bounds(b, false), b.getInt(), b.getInt(), b.getInt(), b.getInt())),
            records(lumps[8], b -> new Brush(b.getInt(), b.getInt(), b.getInt())),
            records(lumps[9], b -> new BrushSide(b.getInt(), b.getInt())),
            vertices,
            records(lumps[11], ByteBuffer::getInt),
            records(lumps[12], b -> new Effect(name(b), b.getInt(), b.getInt())),
            faces,
            records(
                lumps[14],
                b -> {
                  byte[] rgb = new byte[49152];
                  b.get(rgb);
                  return new Bytes(rgb);
                }),
            records(
                lumps[15],
                b ->
                    new LightVolume(
                        rgb(b), rgb(b), Byte.toUnsignedInt(b.get()), Byte.toUnsignedInt(b.get()))),
            visibility(lumps[16]));
    BspValidator.validate(map);
    return map;
  }

  private static Face face(ByteBuffer b) throws BspFormatException {
    return new Face(
        b.getInt(),
        b.getInt(),
        b.getInt(),
        b.getInt(),
        b.getInt(),
        b.getInt(),
        b.getInt(),
        b.getInt(),
        b.getInt(),
        b.getInt(),
        b.getInt(),
        b.getInt(),
        vec(b),
        vec(b),
        vec(b),
        vec(b),
        b.getInt(),
        b.getInt());
  }

  private static List<Vertex> vertices(ByteBuffer b, List<Face> faces) throws BspFormatException {
    int count = b.remaining() / 44;
    // Difference arrays keep overlapping face ranges linear in faces plus vertices.
    int[] vertexLit = new int[count + 1];
    int[] other = new int[count + 1];
    for (Face face : faces) {
      int first = face.firstVertex(), length = face.vertexCount();
      if (first < 0 || length < 0 || (long) first + length > count)
        throw bad("Invalid face vertices range");
      int[] ranges =
          face.lightmap() == -3 && face.type() >= 1 && face.type() <= 3 ? vertexLit : other;
      ranges[first]++;
      ranges[first + length]--;
    }
    List<Vertex> result = new ArrayList<>(count);
    int vertexLitUses = 0, otherUses = 0;
    for (int i = 0; i < count; i++) {
      vertexLitUses += vertexLit[i];
      otherUses += other[i];
      Vec3 position = vec(b);
      Uv texture = uv(b);
      float lightmapU = b.getFloat(), lightmapV = b.getFloat();
      if (!Float.isFinite(lightmapU) || !Float.isFinite(lightmapV)) {
        if (vertexLitUses == 0 || otherUses != 0)
          throw bad("Non-finite lightmap UV at BSP vertex " + i);
        // LIGHTMAP_BY_VERTEX surfaces do not have baked lightmap coordinates. Some compilers
        // leave these bytes uninitialized. Keep published geometry finite, without touching
        // positions, texture UVs, normals, or coordinates shared by a lightmapped surface.
        if (!Float.isFinite(lightmapU)) lightmapU = 0;
        if (!Float.isFinite(lightmapV)) lightmapV = 0;
      }
      result.add(new Vertex(position, texture, new Uv(lightmapU, lightmapV), vec(b), rgba(b)));
    }
    return List.copyOf(result);
  }

  private static <T> List<T> records(ByteBuffer b, RecordReader<T> reader)
      throws BspFormatException {
    List<T> result = new ArrayList<>();
    while (b.hasRemaining()) result.add(reader.read(b));
    return List.copyOf(result);
  }

  private static byte[] bytes(ByteBuffer b) {
    byte[] result = new byte[b.remaining()];
    b.get(result);
    return result;
  }

  private static String name(ByteBuffer b) {
    byte[] value = new byte[64];
    b.get(value);
    int length = 0;
    while (length < value.length && value[length] != 0) length++;
    return new String(value, 0, length, StandardCharsets.ISO_8859_1);
  }

  private static float finite(ByteBuffer b) throws BspFormatException {
    float value = b.getFloat();
    if (!Float.isFinite(value)) throw bad("Non-finite BSP float");
    return value;
  }

  private static Vec3 vec(ByteBuffer b) throws BspFormatException {
    return new Vec3(finite(b), finite(b), finite(b));
  }

  private static Vec3 ivec(ByteBuffer b) {
    return new Vec3(b.getInt(), b.getInt(), b.getInt());
  }

  private static Uv uv(ByteBuffer b) throws BspFormatException {
    return new Uv(finite(b), finite(b));
  }

  private static Bounds bounds(ByteBuffer b, boolean integer) throws BspFormatException {
    Vec3 min = integer ? ivec(b) : vec(b), max = integer ? ivec(b) : vec(b);
    // Empty solid leaves in compiler output can have inverted sentinel bounds; preserve them.
    return new Bounds(min, max);
  }

  private static int rgb(ByteBuffer b) {
    return (Byte.toUnsignedInt(b.get()) << 16)
        | (Byte.toUnsignedInt(b.get()) << 8)
        | Byte.toUnsignedInt(b.get());
  }

  private static int rgba(ByteBuffer b) {
    return (rgb(b) << 8) | Byte.toUnsignedInt(b.get());
  }

  private static Visibility visibility(ByteBuffer b) throws BspFormatException {
    if (!b.hasRemaining()) return new Visibility(0, 0, new Bytes(new byte[0]));
    if (b.remaining() < 8) throw bad("Truncated visibility header");
    int clusters = b.getInt(), stride = b.getInt();
    if (clusters < 0
        || stride < 0
        || stride < (clusters + 7L) / 8
        || (long) clusters * stride != b.remaining()) throw bad("Invalid visibility dimensions");
    return new Visibility(clusters, stride, new Bytes(bytes(b)));
  }

  static BspFormatException bad(String message) {
    return new BspFormatException(message);
  }
}
