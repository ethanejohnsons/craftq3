package dev.bluevista.craftq3.assets.bsp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.IntBinaryOperator;

/**
 * Original shader regression scene and procedurally authored pixels. No Quake assets are copied.
 * Camera: (0,-480,190), yaw90. Panels are ordered left-to-right, top row then bottom row.
 */
public final class ShaderFixture {
  private static final String PREFIX = "textures/craftq3_shaderlab/";
  private static final List<String> NAMES =
      List.of(
          "implicit",
          "layered",
          "cutout",
          "scroll",
          "rotate",
          "animation",
          "patch",
          "mirror",
          "backplate",
          "reflection",
          "sky_left",
          "sky_right",
          "fog");

  private ShaderFixture() {}

  private record Vertex(float x, float y, float z, float u, float v, float normalY) {}

  private record Face(
      int texture,
      int vertex,
      int count,
      int mesh,
      int meshCount,
      boolean patch,
      int lightmap,
      int effect,
      float normalY) {}

  public static byte[] map() {
    List<Vertex> vertices = new ArrayList<>();
    List<Integer> meshes = new ArrayList<>();
    List<Face> faces = new ArrayList<>();
    for (int panel = 0; panel < 8; panel++) {
      float x = (panel % 4) * 180 - 270;
      float z = panel < 4 ? 260 : 80;
      if (panel == 6) {
        int start = vertices.size();
        for (int row = 0; row < 3; row++) {
          for (int column = 0; column < 3; column++) {
            vertices.add(
                new Vertex(
                    x + (column - 1) * 72,
                    row == 1 && column == 1 ? -70 : 0,
                    z + (row - 1) * 72,
                    column * .5f,
                    1 - row * .5f,
                    -1));
          }
        }
        faces.add(new Face(panel, start, 9, meshes.size(), 0, true, 0, 0, -1));
      } else {
        quad(vertices, meshes, faces, panel, x, 0, z, 144, 144, -1, panel <= 2 ? 0 : -1);
      }
    }
    // Blue is visible through the holes in panel3. It is not part of the alpha-tested surface.
    quad(vertices, meshes, faces, 8, 90, 20, 260, 144, 144, -1, -1);
    // Outside the initial camera frustum; its orange/white diagonals must appear only in the
    // mirror.
    quad(vertices, meshes, faces, 9, 450, -320, 6, 220, 144, 1, -1);
    // Separate sky openings must retain their own material coverage despite sharing the view.
    quad(vertices, meshes, faces, 10, -275, 80, 170, 550, 600, -1, -1);
    quad(vertices, meshes, faces, 11, 275, 80, 170, 550, 600, -1, -1);

    byte[][] lumps = new byte[17][];
    Arrays.setAll(lumps, ignored -> new byte[0]);
    lumps[0] =
        ("""
        {
          "classname" "worldspawn"
          "message" "CraftQ3 original shader regression fixture"
        }
        {
          "classname" "info_player_deathmatch"
          "origin" "0 -480 164"
          "angle" "90"
        }
        {
          "classname" "misc_portal_surface"
          "origin" "270 -16 80"
        }
        """
                + '\0')
            .getBytes(StandardCharsets.ISO_8859_1);
    ByteBuffer textures = buffer(NAMES.size() * 72);
    for (String name : NAMES) {
      name(textures, PREFIX + name, 64);
      textures.putInt(name.startsWith("sky_") ? 4 : 0).putInt(name.equals("fog") ? 64 : 1);
    }
    lumps[1] = textures.array();
    // Plane0 is the harmless BSP split; the remaining six enclose panel7's pale-blue fog volume.
    ByteBuffer planes = buffer(7 * 16);
    plane(planes, 0, 1, 0, 0);
    plane(planes, 1, 0, 0, 162);
    plane(planes, -1, 0, 0, -18);
    plane(planes, 0, 1, 0, 16);
    plane(planes, 0, -1, 0, 100);
    plane(planes, 0, 0, 1, 152);
    plane(planes, 0, 0, -1, -8);
    lumps[2] = planes.array();
    ByteBuffer node = buffer(36).putInt(0).putInt(-1).putInt(-1);
    integerBounds(node);
    lumps[3] = node.array();
    ByteBuffer leaf = buffer(48).putInt(0).putInt(0);
    integerBounds(leaf);
    leaf.putInt(0).putInt(faces.size()).putInt(0).putInt(1);
    lumps[4] = leaf.array();
    ByteBuffer leafFaces = buffer(faces.size() * 4);
    for (int i = 0; i < faces.size(); i++) leafFaces.putInt(i);
    lumps[5] = leafFaces.array();
    lumps[6] = buffer(4).putInt(0).array();
    ByteBuffer model = buffer(40);
    for (int i = 0; i < 3; i++) model.putFloat(-1000);
    for (int i = 0; i < 3; i++) model.putFloat(1000);
    model.putInt(0).putInt(faces.size()).putInt(0).putInt(1);
    lumps[7] = model.array();
    lumps[8] = buffer(12).putInt(0).putInt(6).putInt(12).array();
    ByteBuffer brushSides = buffer(6 * 8);
    for (int i = 1; i <= 6; i++) brushSides.putInt(i).putInt(12);
    lumps[9] = brushSides.array();
    ByteBuffer vertexData = buffer(vertices.size() * 44);
    for (Vertex v : vertices) {
      vertexData
          .putFloat(v.x)
          .putFloat(v.y)
          .putFloat(v.z)
          .putFloat(v.u)
          .putFloat(v.v)
          .putFloat(.01f + v.u * .98f)
          .putFloat(.01f + v.v * .98f)
          .putFloat(0)
          .putFloat(v.normalY)
          .putFloat(0);
      // BSP colors have two bits of lighting headroom; the restored value is full white.
      vertexData.put((byte) 63).put((byte) 63).put((byte) 63).put((byte) 255);
    }
    lumps[10] = vertexData.array();
    ByteBuffer meshData = buffer(meshes.size() * 4);
    for (int index : meshes) meshData.putInt(index);
    lumps[11] = meshData.array();
    ByteBuffer effect = buffer(72);
    name(effect, PREFIX + "fog", 64);
    effect.putInt(0).putInt(-1);
    lumps[12] = effect.array();
    ByteBuffer faceData = buffer(faces.size() * 104);
    for (Face f : faces) {
      int start = faceData.position();
      faceData
          .putInt(f.texture)
          .putInt(f.effect)
          .putInt(f.patch ? 2 : 1)
          .putInt(f.vertex)
          .putInt(f.count)
          .putInt(f.mesh)
          .putInt(f.meshCount)
          .putInt(f.lightmap)
          .putInt(0)
          .putInt(0)
          .putInt(128)
          .putInt(128);
      // Origin and lightmap S/T projection are not used when explicit lightmap UVs are present.
      faceData.position(start + 84);
      faceData
          .putFloat(0)
          .putFloat(f.normalY)
          .putFloat(0)
          .putInt(f.patch ? 3 : 0)
          .putInt(f.patch ? 3 : 0);
    }
    lumps[13] = faceData.array();
    lumps[14] = lightmap();
    lumps[16] = buffer(9).putInt(1).putInt(1).put((byte) 1).array();
    int length = 144 + Arrays.stream(lumps).mapToInt(bytes -> bytes.length).sum();
    ByteBuffer file = buffer(length).putInt(0x50534249).putInt(46);
    int offset = 144;
    for (byte[] lump : lumps) {
      file.putInt(offset).putInt(lump.length);
      offset += lump.length;
    }
    for (byte[] lump : lumps) file.put(lump);
    return file.array();
  }

  private static void quad(
      List<Vertex> vertices,
      List<Integer> meshes,
      List<Face> faces,
      int texture,
      float x,
      float y,
      float z,
      float width,
      float height,
      float normalY,
      int lightmap) {
    int vertex = vertices.size();
    int mesh = meshes.size();
    vertices.add(new Vertex(x - width / 2, y, z - height / 2, 0, 1, normalY));
    vertices.add(new Vertex(x + width / 2, y, z - height / 2, 1, 1, normalY));
    vertices.add(new Vertex(x + width / 2, y, z + height / 2, 1, 0, normalY));
    vertices.add(new Vertex(x - width / 2, y, z + height / 2, 0, 0, normalY));
    // Indexed Q3 faces use clockwise winding viewed from the front: cross(edge1,edge2) opposes
    // the authored surface normal. Match original BSPs so native culling is meaningfully tested.
    meshes.addAll(normalY < 0 ? List.of(0, 2, 1, 0, 3, 2) : List.of(0, 1, 2, 0, 2, 3));
    faces.add(new Face(texture, vertex, 4, mesh, 6, false, lightmap, -1, normalY));
  }

  public static String shaderScript() {
    return """
        // All artwork and materials in this file are generated from original CraftQ3 fixture code.
        textures/craftq3_shaderlab/layered {
          {
            map textures/craftq3_shaderlab/checker.tga
            rgbGen identity
          }
          { map $lightmap blendFunc filter rgbGen identity depthFunc equal }
          {
            map textures/craftq3_shaderlab/stripes.tga
            blendFunc add
            rgbGen wave sin .2 .15 0 .25
          }
        }
        textures/craftq3_shaderlab/cutout {
          cull none
          {
            map textures/craftq3_shaderlab/cutout.tga
            alphaFunc GE128
            rgbGen identity
            depthWrite
          }
          { map $lightmap blendFunc filter rgbGen identity depthFunc equal }
        }
        textures/craftq3_shaderlab/scroll {
          { map textures/craftq3_shaderlab/checker.tga rgbGen identity }
          {
            map textures/craftq3_shaderlab/scroll.tga
            blendFunc blend
            rgbGen identity
            tcMod scroll .35 .12
          }
        }
        textures/craftq3_shaderlab/rotate {
          { map $whiteimage rgbGen const ( .1 .15 .22 ) }
          {
            clampmap textures/craftq3_shaderlab/arrow.tga
            blendFunc blend
            rgbGen identity
            tcMod rotate 37
          }
        }
        textures/craftq3_shaderlab/animation {
          {
            animMap 1 textures/craftq3_shaderlab/frame0.tga textures/craftq3_shaderlab/frame1.tga
            rgbGen identity
          }
        }
        textures/craftq3_shaderlab/patch {
          cull none
          deformVertexes wave 40 sin 0 10 0 .5
          { map textures/craftq3_shaderlab/checker.tga rgbGen identity tcMod scale 2 2 }
          { map $lightmap blendFunc filter rgbGen identity }
        }
        textures/craftq3_shaderlab/mirror {
          portal
          surfaceparm nolightmap
        }
        textures/craftq3_shaderlab/backplate {
          { map $whiteimage rgbGen const ( .12 .25 .95 ) }
        }
        textures/craftq3_shaderlab/reflection {
          cull none
          { map textures/craftq3_shaderlab/reflection.tga rgbGen identity }
        }
        textures/craftq3_shaderlab/sky_left {
          cull none
          surfaceparm sky
          surfaceparm nolightmap
          skyParms - 512 -
          { map $whiteimage rgbGen const ( .2 .025 .04 ) }
        }
        textures/craftq3_shaderlab/sky_right {
          cull none
          surfaceparm sky
          surfaceparm nolightmap
          skyParms - 512 -
          { map $whiteimage rgbGen const ( .025 .05 .2 ) }
        }
        textures/craftq3_shaderlab/fog {
          surfaceparm fog
          surfaceparm nonsolid
          fogParms ( .25 .65 .85 ) 200
        }
        """;
  }

  private static byte[] lightmap() {
    byte[] rgb = new byte[128 * 128 * 3];
    for (int y = 0; y < 128; y++) {
      for (int x = 0; x < 128; x++) {
        int i = (y * 128 + x) * 3;
        double shade = .25 + .75 * (x / 127.0);
        rgb[i] = (byte) Math.round(63 * shade);
        rgb[i + 1] = (byte) Math.round(55 * shade);
        rgb[i + 2] = (byte) Math.round(42 * shade);
      }
    }
    return rgb;
  }

  private static byte[] tga(IntBinaryOperator pixel) {
    ByteBuffer image = buffer(18 + 64 * 64 * 4);
    image.put((byte) 0).put((byte) 0).put((byte) 2);
    image.position(12);
    image.putShort((short) 64).putShort((short) 64).put((byte) 32).put((byte) 0x28);
    for (int y = 0; y < 64; y++) {
      for (int x = 0; x < 64; x++) {
        int rgba = pixel.applyAsInt(x, y);
        image
            .put((byte) (rgba >>> 8))
            .put((byte) (rgba >>> 16))
            .put((byte) (rgba >>> 24))
            .put((byte) rgba);
      }
    }
    return image.array();
  }

  public static Map<String, byte[]> images() {
    IntBinaryOperator checker = (x, y) -> ((x / 8 + y / 8) & 1) == 0 ? 0xe0ceacff : 0x344351ff;
    return Map.of(
        "implicit", tga(checker),
        "checker", tga(checker),
        "stripes", tga((x, y) -> ((x + y) % 24 < 5) ? 0x3060ffff : 0x000000ff),
        "cutout", tga((x, y) -> (x % 16 < 6 || y % 16 < 6) ? 0x68da78ff : 0x68da7800),
        "scroll", tga((x, y) -> (x + 2 * y) % 24 < 8 ? 0xe84060ce : 0x10305000),
        "arrow",
            tga(
                (x, y) ->
                    ((y >= 28 && y <= 36 && x >= 12 && x <= 48)
                            || (x >= 36 && x <= 51 && Math.abs(y - 32) <= 51 - x))
                        ? 0xffb630ff
                        : 0x00000000),
        "frame0", tga((x, y) -> ((x / 16 + y / 16) & 1) == 0 ? 0xf04770ff : 0x681f42ff),
        "frame1", tga((x, y) -> ((x / 16 + y / 16) & 1) == 0 ? 0x65e89aff : 0x214a72ff),
        "reflection", tga((x, y) -> (x + y) % 24 < 12 ? 0xff891eff : 0xfff6d8ff));
  }

  public static void write(Path gameDirectory) throws Exception {
    Path map = gameDirectory.resolve("maps/craftq3_shaderlab.bsp");
    Files.createDirectories(map.getParent());
    byte[] data = map();
    BspReader.read(data); // Fail before writing if the fixture ever becomes structurally invalid.
    Files.write(map, data);
    Path script = gameDirectory.resolve("scripts/craftq3_shaderlab.shader");
    Files.createDirectories(script.getParent());
    Files.writeString(script, shaderScript(), StandardCharsets.ISO_8859_1);
    Path textureDirectory = gameDirectory.resolve(PREFIX);
    Files.createDirectories(textureDirectory);
    for (var entry : images().entrySet()) {
      Files.write(textureDirectory.resolve(entry.getKey() + ".tga"), entry.getValue());
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Expected baseq3 output directory");
    write(Path.of(args[0]));
  }

  private static void plane(ByteBuffer into, float x, float y, float z, float distance) {
    into.putFloat(x).putFloat(y).putFloat(z).putFloat(distance);
  }

  private static void integerBounds(ByteBuffer into) {
    for (int i = 0; i < 3; i++) into.putInt(-1000);
    for (int i = 0; i < 3; i++) into.putInt(1000);
  }

  private static void name(ByteBuffer into, String value, int width) {
    byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
    if (bytes.length >= width) throw new IllegalArgumentException("Fixture name too long");
    into.put(bytes);
    for (int i = bytes.length; i < width; i++) into.put((byte) 0);
  }

  private static ByteBuffer buffer(int length) {
    return ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
  }
}
