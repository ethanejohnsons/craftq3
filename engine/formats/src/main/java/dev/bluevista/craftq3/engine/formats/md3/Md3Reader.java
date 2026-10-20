package dev.bluevista.craftq3.engine.formats.md3;

import dev.bluevista.craftq3.engine.common.io.Ranges;
import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.Binary;
import dev.bluevista.craftq3.engine.formats.Diagnostics;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bounded little-endian reader for the MD3 version 15 layout. Like the original loader it requires
 * the identifier, version and at least one frame and otherwise trusts the exporter (inverted
 * bounds, duplicate tag names and aliased blocks are accepted); it rejects blocks outside the file,
 * triangle corners naming absent vertices and resource exhaustion. Non-finite frame, tag and
 * texture-coordinate floats (left by some exporters) are replaced with zero and noted.
 */
public final class Md3Reader {
  public static final int IDENT = 0x33504449; // "IDP3"
  public static final int VERSION = 15;
  public static final int MAX_BYTES = 64 * 1024 * 1024;
  public static final int MAX_FRAMES = 1024;
  public static final int MAX_TAGS = 256;
  public static final int MAX_SURFACES = 256;
  public static final int MAX_SHADERS = 256;
  public static final int MAX_VERTICES = 4096;
  public static final int MAX_TRIANGLES = 8192;
  public static final int MAX_VERTEX_SAMPLES = 4_000_000;

  /** {@code MD3_XYZ_SCALE}: stored coordinates are in 1/64 units. */
  public static final float XYZ_SCALE = 1.0f / 64;

  static final int HEADER_BYTES = 108;
  static final int SURFACE_HEADER_BYTES = 108;
  static final int FRAME_BYTES = 56;
  static final int TAG_BYTES = 112;
  private static final Log LOG = Log.get(Md3Reader.class);

  private Md3Reader() {}

  public static Md3Model read(byte[] data) {
    return read(data, "md3");
  }

  /** Reads {@code data}; {@code source} names the file in diagnostics. */
  public static Md3Model read(byte[] data, String source) {
    if (data == null || data.length < HEADER_BYTES || data.length > MAX_BYTES)
      throw new Md3FormatException("Invalid MD3 size");
    ByteBuffer file = Binary.littleEndian(data);
    if (file.getInt() != IDENT) throw new Md3FormatException("Missing IDP3 identifier");
    int version = file.getInt();
    if (version != VERSION)
      throw new Md3FormatException("Unsupported MD3 version " + version + " (expected 15)");
    Diagnostics notes = new Diagnostics(LOG, source);
    String name = Binary.fixedString(file, 64);
    int flags = file.getInt();
    int frameCount = count(file.getInt(), 1, MAX_FRAMES, "frames");
    int tagCount = count(file.getInt(), 0, MAX_TAGS, "tags");
    int surfaceCount = count(file.getInt(), 0, MAX_SURFACES, "surfaces");
    file.getInt(); // numSkins: unused by the format's consumers
    int frameOffset = file.getInt();
    int tagOffset = file.getInt();
    int surfaceOffset = file.getInt();
    int endOffset = file.getInt();
    if (endOffset > data.length)
      throw new Md3FormatException("MD3 end offset " + endOffset + " lies outside the file");
    List<Md3Model.Frame> frames =
        frames(
            block(data, frameOffset, (long) frameCount * FRAME_BYTES, "frames"), frameCount, notes);
    String[] tagNames = new String[frameCount * tagCount];
    float[] tagData = new float[frameCount * tagCount * 12];
    tags(
        block(data, tagOffset, (long) frameCount * tagCount * TAG_BYTES, "tags"),
        tagNames,
        tagData,
        notes);
    List<Md3Model.Surface> surfaces =
        surfaces(data, surfaceOffset, surfaceCount, frameCount, notes);
    if (surfaceCount > 0) noteInvertedBounds(frames, notes);
    return new Md3Model(name, flags, frames, tagCount, tagNames, tagData, surfaces, notes.notes());
  }

  private static List<Md3Model.Frame> frames(ByteBuffer b, int count, Diagnostics notes) {
    List<Md3Model.Frame> frames = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      Vec3f mins = vector(b, notes);
      Vec3f maxs = vector(b, notes);
      Vec3f origin = vector(b, notes);
      float radius = finite(b.getFloat(), notes);
      frames.add(new Md3Model.Frame(mins, maxs, origin, radius, Binary.fixedString(b, 16)));
    }
    return frames;
  }

  private static void noteInvertedBounds(List<Md3Model.Frame> frames, Diagnostics notes) {
    for (int i = 0; i < frames.size(); i++) {
      if (!frames.get(i).hasBounds()) {
        notes.note("md3-bounds", "Frame " + i + " has inverted bounds");
        return;
      }
    }
  }

  private static void tags(ByteBuffer b, String[] names, float[] data, Diagnostics notes) {
    for (int i = 0; i < names.length; i++) {
      names[i] = Binary.fixedString(b, 64);
      for (int j = 0; j < 12; j++) data[i * 12 + j] = finite(b.getFloat(), notes);
    }
  }

  private static List<Md3Model.Surface> surfaces(
      byte[] data, int firstOffset, int count, int modelFrames, Diagnostics notes) {
    List<Md3Model.Surface> surfaces = new ArrayList<>(count);
    long offset = firstOffset;
    long samples = 0;
    for (int i = 0; i < count; i++) {
      if (!Ranges.contains(data.length, offset, SURFACE_HEADER_BYTES))
        throw new Md3FormatException("MD3 surface " + i + " header lies outside the file");
      SurfaceHeader header = SurfaceHeader.read(data, (int) offset);
      samples += (long) header.frames * header.vertices;
      if (samples > MAX_VERTEX_SAMPLES)
        throw new Md3FormatException("MD3 vertex-sample budget exceeded");
      if (header.frames != modelFrames)
        notes.note(
            "md3-frames",
            "Surface " + header.name + " has " + header.frames + " frames, model " + modelFrames);
      surfaces.add(surface(data, (int) offset, header, notes));
      if (header.end <= 0) throw new Md3FormatException("MD3 surface " + i + " does not advance");
      offset += header.end;
    }
    return surfaces;
  }

  private static Md3Model.Surface surface(
      byte[] data, int base, SurfaceHeader h, Diagnostics notes) {
    ByteBuffer shaders = block(data, base + (long) h.shaderOffset, h.shaders * 68L, "shaders");
    ByteBuffer triangles =
        block(data, base + (long) h.triangleOffset, h.triangles * 12L, "triangles");
    ByteBuffer coords = block(data, base + (long) h.texCoordOffset, h.vertices * 8L, "texcoords");
    ByteBuffer vertices =
        block(data, base + (long) h.vertexOffset, (long) h.frames * h.vertices * 8, "vertices");
    List<Md3Model.Shader> shaderList = new ArrayList<>(h.shaders);
    for (int j = 0; j < h.shaders; j++)
      shaderList.add(new Md3Model.Shader(Binary.fixedString(shaders, 64), shaders.getInt()));
    int[] corners = new int[h.triangles * 3];
    for (int j = 0; j < corners.length; j++) {
      corners[j] = triangles.getInt();
      if (corners[j] < 0 || corners[j] >= h.vertices)
        throw new Md3FormatException("MD3 surface " + h.name + " triangle names absent vertex");
    }
    float[] st = new float[h.vertices * 2];
    for (int j = 0; j < st.length; j++) st[j] = finite(coords.getFloat(), notes);
    float[] positions = new float[h.frames * h.vertices * 3];
    float[] normals = new float[positions.length];
    float[] normal = new float[3];
    for (int v = 0; v < h.frames * h.vertices; v++) {
      positions[v * 3] = vertices.getShort() * XYZ_SCALE;
      positions[v * 3 + 1] = vertices.getShort() * XYZ_SCALE;
      positions[v * 3 + 2] = vertices.getShort() * XYZ_SCALE;
      decodeNormal(Short.toUnsignedInt(vertices.getShort()), normal);
      System.arraycopy(normal, 0, normals, v * 3, 3);
    }
    return new Md3Model.Surface(
        h.name, h.flags, shaderList, corners, st, positions, normals, h.frames);
  }

  /** The fixed part of a surface record. */
  private record SurfaceHeader(
      String name,
      int flags,
      int frames,
      int shaders,
      int vertices,
      int triangles,
      int triangleOffset,
      int shaderOffset,
      int texCoordOffset,
      int vertexOffset,
      int end) {
    static SurfaceHeader read(byte[] data, int offset) {
      ByteBuffer b = Binary.littleEndian(data, offset, SURFACE_HEADER_BYTES);
      if (b.getInt() != IDENT) throw new Md3FormatException("Invalid MD3 surface identifier");
      String name = Binary.fixedString(b, 64).toLowerCase(Locale.ROOT);
      int flags = b.getInt();
      int frames = count(b.getInt(), 0, MAX_FRAMES, "surface frames");
      int shaders = count(b.getInt(), 0, MAX_SHADERS, "surface shaders");
      int vertices = count(b.getInt(), 0, MAX_VERTICES, "surface vertices");
      int triangles = count(b.getInt(), 0, MAX_TRIANGLES, "surface triangles");
      return new SurfaceHeader(
          name,
          flags,
          frames,
          shaders,
          vertices,
          triangles,
          b.getInt(),
          b.getInt(),
          b.getInt(),
          b.getInt(),
          b.getInt());
    }
  }

  /**
   * Decodes the packed normal (high byte latitude, low byte longitude) exactly as the original
   * renderer does: through its 1024-entry sine table, whose step is {@code 2*pi/1023}, so the
   * result is unit length only to within about 0.5%.
   */
  public static void decodeNormal(int packed, float[] out) {
    int lat = ((packed >>> 8) & 255) * 4;
    int lng = (packed & 255) * 4;
    out[0] = SINE[(lat + 256) & 1023] * SINE[lng];
    out[1] = SINE[lat] * SINE[lng];
    out[2] = SINE[(lng + 256) & 1023];
  }

  public static Vec3f decodeNormal(int packed) {
    float[] out = new float[3];
    decodeNormal(packed, out);
    return Vec3f.of(out);
  }

  private static final float[] SINE = sineTable();

  private static float[] sineTable() {
    float[] table = new float[1024];
    for (int i = 0; i < table.length; i++)
      table[i] = (float) Math.sin(Math.toRadians(i * 360.0f / 1023f));
    return table;
  }

  private static ByteBuffer block(byte[] data, long offset, long length, String label) {
    if (!Ranges.contains(data.length, offset, length))
      throw new Md3FormatException(
          "MD3 " + label + " block [" + offset + ", +" + length + ") lies outside the file");
    return Binary.littleEndian(data, (int) offset, (int) length);
  }

  private static int count(int count, int minimum, int maximum, String label) {
    if (count < minimum || count > maximum)
      throw new Md3FormatException("Invalid MD3 " + label + " count " + count);
    return count;
  }

  private static float finite(float value, Diagnostics notes) {
    if (Float.isFinite(value)) return value;
    notes.note("md3-nonfinite", "Non-finite MD3 float replaced with 0");
    return 0;
  }

  private static Vec3f vector(ByteBuffer b, Diagnostics notes) {
    return new Vec3f(
        finite(b.getFloat(), notes), finite(b.getFloat(), notes), finite(b.getFloat(), notes));
  }
}
