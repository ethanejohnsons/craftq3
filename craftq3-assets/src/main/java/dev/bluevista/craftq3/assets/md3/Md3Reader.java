package dev.bluevista.craftq3.assets.md3;

import dev.bluevista.craftq3.assets.md3.Md3Model.*;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Independent, bounded little-endian reader for the published MD3 version 15 layout. */
public final class Md3Reader {
  public static final int MAX_BYTES = 64 * 1024 * 1024;
  public static final int MAX_VERTEX_SAMPLES = 2_000_000;
  private static final int IDENT = 0x33504449;
  private static final int HEADER_BYTES = 108;
  private static final int FRAME_BYTES = 56;
  private static final int TAG_BYTES = 112;

  private Md3Reader() {}

  public static Md3Model read(byte[] data) throws Md3FormatException {
    if (data == null || data.length < HEADER_BYTES || data.length > MAX_BYTES) {
      throw bad("Invalid MD3 size");
    }
    ByteBuffer file = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    if (file.getInt() != IDENT || file.getInt() != 15) throw bad("Expected IDP3 version 15");
    String name = name(file, 64);
    int flags = file.getInt();
    int frameCount = count(file.getInt(), 1, 1024, "frames");
    int tagCount = count(file.getInt(), 0, 16, "tags");
    int surfaceCount = count(file.getInt(), 0, 32, "surfaces");
    count(file.getInt(), 0, 256, "legacy skins");
    int frameOffset = file.getInt();
    int tagOffset = file.getInt();
    int surfaceOffset = file.getInt();
    int endOffset = file.getInt();
    if (endOffset < HEADER_BYTES || endOffset > data.length) throw bad("MD3 end lies outside file");
    file.limit(endOffset);
    List<Range> ranges = new ArrayList<>();
    ByteBuffer frameBytes =
        block(file, frameOffset, (long) frameCount * FRAME_BYTES, ranges, "frames");
    ByteBuffer tagBytes =
        block(file, tagOffset, (long) frameCount * tagCount * TAG_BYTES, ranges, "tags");
    List<Frame> frames = new ArrayList<>(frameCount);
    for (int i = 0; i < frameCount; i++) {
      Vec3 min = vector(frameBytes), max = vector(frameBytes), origin = vector(frameBytes);
      float radius = finite(frameBytes);
      String frameName = name(frameBytes, 16);
      // Original weapon-hand files contain tags only and retain the exporter's inverted empty box.
      if ((surfaceCount > 0 && (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()))
          || radius < 0) {
        throw bad("Invalid MD3 frame bounds or radius at frame " + i);
      }
      frames.add(new Frame(min, max, origin, radius, frameName));
    }
    List<List<Tag>> tagFrames = new ArrayList<>(frameCount);
    for (int i = 0; i < frameCount; i++) {
      List<Tag> tags = new ArrayList<>(tagCount);
      Set<String> tagNames = new HashSet<>();
      for (int j = 0; j < tagCount; j++) {
        String tagName = name(tagBytes, 64);
        if (tagName.isEmpty() || !tagNames.add(tagName))
          throw bad("Empty or duplicate MD3 tag name");
        if (i > 0 && !tagFrames.getFirst().get(j).name().equals(tagName)) {
          throw bad("MD3 tag order/name changes between frames");
        }
        tags.add(
            new Tag(
                tagName, vector(tagBytes), vector(tagBytes), vector(tagBytes), vector(tagBytes)));
      }
      tagFrames.add(List.copyOf(tags));
    }
    List<Surface> surfaces = new ArrayList<>(surfaceCount);
    int offset = surfaceOffset;
    long samples = 0;
    for (int i = 0; i < surfaceCount; i++) {
      if (offset < HEADER_BYTES || (long) offset + HEADER_BYTES > file.limit()) {
        throw bad("MD3 surface header lies outside model");
      }
      ByteBuffer header = file.slice(offset, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
      if (header.getInt() != IDENT) throw bad("Invalid MD3 surface identifier");
      String surfaceName = name(header, 64);
      int surfaceFlags = header.getInt();
      if (header.getInt() != frameCount) throw bad("MD3 surface frame count differs from model");
      int shaderCount = count(header.getInt(), 0, 256, "surface shaders");
      int vertexCount = count(header.getInt(), 0, 4096, "surface vertices");
      int triangleCount = count(header.getInt(), 0, 8192, "surface triangles");
      int triangleOffset = header.getInt();
      int shaderOffset = header.getInt();
      int texCoordOffset = header.getInt();
      int vertexOffset = header.getInt();
      int surfaceEnd = header.getInt();
      samples += (long) frameCount * vertexCount;
      if (samples > MAX_VERTEX_SAMPLES) throw bad("MD3 vertex-sample budget exceeded");
      if (surfaceEnd < HEADER_BYTES) throw bad("MD3 surface does not advance to a next record");
      ByteBuffer surface = block(file, offset, surfaceEnd, ranges, "surface " + i);
      List<Range> localRanges = new ArrayList<>();
      ByteBuffer shaderBytes =
          block(surface, shaderOffset, (long) shaderCount * 68, localRanges, "shaders");
      ByteBuffer triangleBytes =
          block(surface, triangleOffset, (long) triangleCount * 12, localRanges, "triangles");
      ByteBuffer coordBytes =
          block(
              surface, texCoordOffset, (long) vertexCount * 8, localRanges, "texture coordinates");
      ByteBuffer vertexBytes =
          block(
              surface, vertexOffset, (long) frameCount * vertexCount * 8, localRanges, "vertices");
      List<Shader> shaders = new ArrayList<>(shaderCount);
      for (int j = 0; j < shaderCount; j++)
        shaders.add(new Shader(name(shaderBytes, 64), shaderBytes.getInt()));
      List<Triangle> triangles = new ArrayList<>(triangleCount);
      for (int j = 0; j < triangleCount; j++) {
        triangles.add(
            new Triangle(
                index(triangleBytes, vertexCount),
                index(triangleBytes, vertexCount),
                index(triangleBytes, vertexCount)));
      }
      List<TexCoord> coordinates = new ArrayList<>(vertexCount);
      for (int j = 0; j < vertexCount; j++)
        coordinates.add(new TexCoord(finite(coordBytes), finite(coordBytes)));
      List<List<Vertex>> vertices = new ArrayList<>(frameCount);
      for (int frame = 0; frame < frameCount; frame++) {
        List<Vertex> frameVertices = new ArrayList<>(vertexCount);
        for (int j = 0; j < vertexCount; j++) {
          Vec3 position =
              new Vec3(
                  vertexBytes.getShort() / 64.0,
                  vertexBytes.getShort() / 64.0,
                  vertexBytes.getShort() / 64.0);
          frameVertices.add(
              new Vertex(position, decodeNormal(Short.toUnsignedInt(vertexBytes.getShort()))));
        }
        vertices.add(List.copyOf(frameVertices));
      }
      surfaces.add(
          new Surface(surfaceName, surfaceFlags, shaders, triangles, coordinates, vertices));
      offset = Math.toIntExact((long) offset + surfaceEnd);
    }
    if (surfaceCount == 0 && (surfaceOffset < 0 || surfaceOffset > endOffset)) {
      throw bad("Invalid empty MD3 surface offset");
    }
    return new Md3Model(name, flags, frames, tagFrames, surfaces);
  }

  /** Spherical normal encoding: high byte azimuth, low byte polar angle, both in 255 steps. */
  public static Vec3 decodeNormal(int packed) {
    double azimuth = ((packed >>> 8) & 255) * (Math.TAU / 255);
    double polar = (packed & 255) * (Math.TAU / 255);
    double radial = Math.sin(polar);
    return new Vec3(Math.cos(azimuth) * radial, Math.sin(azimuth) * radial, Math.cos(polar));
  }

  private static ByteBuffer block(
      ByteBuffer parent, int offset, long length, List<Range> ranges, String label)
      throws Md3FormatException {
    if (offset < 0
        || length < 0
        || (long) offset + length > parent.limit()
        || (length > 0 && offset < HEADER_BYTES))
      throw bad("MD3 " + label + " lies outside its owner");
    if (length > 0) {
      for (Range range : ranges) {
        if (offset < range.end && range.start < (long) offset + length) {
          throw bad("Overlapping MD3 " + label + " and " + range.label);
        }
      }
      ranges.add(new Range(offset, offset + length, label));
    }
    return parent.slice(offset, (int) length).order(ByteOrder.LITTLE_ENDIAN);
  }

  private static int count(int count, int minimum, int maximum, String label)
      throws Md3FormatException {
    if (count < minimum || count > maximum) throw bad("Invalid MD3 " + label + " count: " + count);
    return count;
  }

  private static int index(ByteBuffer input, int vertices) throws Md3FormatException {
    int index = input.getInt();
    if (index < 0 || index >= vertices) throw bad("MD3 triangle references an absent vertex");
    return index;
  }

  private static String name(ByteBuffer input, int length) throws Md3FormatException {
    byte[] bytes = new byte[length];
    input.get(bytes);
    int end = 0;
    while (end < bytes.length && bytes[end] != 0) {
      if (Byte.toUnsignedInt(bytes[end]) < 32 || bytes[end] == 127)
        throw bad("Control character in MD3 name");
      end++;
    }
    return new String(bytes, 0, end, StandardCharsets.ISO_8859_1);
  }

  private static float finite(ByteBuffer input) throws Md3FormatException {
    float value = input.getFloat();
    if (!Float.isFinite(value)) throw bad("Non-finite MD3 float");
    return value;
  }

  private static Vec3 vector(ByteBuffer input) throws Md3FormatException {
    return new Vec3(finite(input), finite(input), finite(input));
  }

  private static Md3FormatException bad(String message) {
    return new Md3FormatException(message);
  }

  private record Range(long start, long end, String label) {}
}
