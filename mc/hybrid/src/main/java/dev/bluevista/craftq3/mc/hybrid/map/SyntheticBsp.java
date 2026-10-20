package dev.bluevista.craftq3.mc.hybrid.map;

import dev.bluevista.craftq3.engine.formats.bsp.BspLump;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.bsp.BspReader;
import dev.bluevista.craftq3.engine.formats.entity.BspEntity;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes a geometry-free IBSP 46 map: the world model is one empty leaf (cluster 0, area 0, no
 * visibility data, so everything sees everything) spanning {@link #WORLD_EXTENT}, followed by
 * inline models that are single axis-aligned box brushes (trigger volumes). The entity lump comes
 * from the caller. World collision is normally supplied separately as a {@code CollisionSource};
 * world brushes can be added for tests and small fixtures.
 *
 * <p>The result goes through the normal {@link BspReader}, so it is validated like any map.
 */
public final class SyntheticBsp {
  /** Half the extent of the world model and its leaf, in Quake units. */
  public static final float WORLD_EXTENT = 65536;

  /** {@code MAX_SUBMODELS}: the world plus inline models. */
  public static final int MAX_MODELS = 256;

  private static final int TEXTURE_BYTES = 72;
  private static final int PLANE_BYTES = 16;
  private static final int LEAF_BYTES = 48;
  private static final int MODEL_BYTES = 40;
  private static final int BRUSH_BYTES = 12;
  private static final int SIDE_BYTES = 8;

  private record Texture(String name, int flags, int contents) {}

  /** A convex brush: planes {@code [nx, ny, nz, dist]} with the solid behind each. */
  private record Brush(float[][] planes, int texture) {}

  private final List<BspEntity> entities = new ArrayList<>();
  private final List<Texture> textures = new ArrayList<>();
  private final List<Brush> world = new ArrayList<>();
  private final List<Brush> boxes = new ArrayList<>();

  /** Appends an entity block; {@code worldspawn} must come first. */
  public SyntheticBsp entity(BspEntity entity) {
    entities.add(Objects.requireNonNull(entity));
    return this;
  }

  public SyntheticBsp entities(List<BspEntity> list) {
    list.forEach(this::entity);
    return this;
  }

  /**
   * Adds inline model {@code *N} made of one box brush in model space and returns {@code N}.
   *
   * @param shader the brush's shader name, e.g. {@code textures/common/trigger}
   * @param contents the brush contents, e.g. {@code CONTENTS_TRIGGER}
   */
  public int boxModel(float[] mins, float[] maxs, String shader, int contents) {
    for (int axis = 0; axis < 3; axis++)
      if (!(mins[axis] < maxs[axis]) || !Float.isFinite(mins[axis]) || !Float.isFinite(maxs[axis]))
        throw new IllegalArgumentException("Box model needs finite mins below maxs");
    if (boxes.size() + 1 >= MAX_MODELS)
      throw new IllegalStateException("At most " + (MAX_MODELS - 1) + " inline models");
    boxes.add(new Brush(boxPlanes(mins, maxs), texture(shader, 0, contents)));
    return boxes.size();
  }

  /** Adds an axis-aligned world brush, e.g. a solid floor. */
  public SyntheticBsp worldBox(
      float[] mins, float[] maxs, String shader, int contents, int surfaceFlags) {
    for (int axis = 0; axis < 3; axis++)
      if (!(mins[axis] < maxs[axis]))
        throw new IllegalArgumentException("World box needs mins below maxs");
    world.add(new Brush(boxPlanes(mins, maxs), texture(shader, surfaceFlags, contents)));
    return this;
  }

  /**
   * Adds a convex world brush bounded by {@code planes} ({@code [nx, ny, nz, dist]}, unit normals
   * pointing out of the solid).
   */
  public SyntheticBsp worldBrush(String shader, int contents, int surfaceFlags, float[]... planes) {
    if (planes.length < 4) throw new IllegalArgumentException("A brush needs at least 4 planes");
    float[][] copy = new float[planes.length][];
    for (int i = 0; i < planes.length; i++) {
      if (planes[i].length != 4) throw new IllegalArgumentException("Planes are [nx, ny, nz, d]");
      copy[i] = planes[i].clone();
    }
    world.add(new Brush(copy, texture(shader, surfaceFlags, contents)));
    return this;
  }

  /** Sides in the compiler's order: -x, +x, -y, +y, -z, +z. */
  private static float[][] boxPlanes(float[] mins, float[] maxs) {
    float[][] planes = new float[6][];
    for (int axis = 0; axis < 3; axis++)
      for (int sign = 0; sign < 2; sign++) {
        float[] n = {0, 0, 0, 0};
        n[axis] = sign == 0 ? -1 : 1;
        n[3] = sign == 0 ? -mins[axis] : maxs[axis];
        planes[axis * 2 + sign] = n;
      }
    return planes;
  }

  /** The number of models including the world. */
  public int modelCount() {
    return boxes.size() + 1;
  }

  private int texture(String name, int flags, int contents) {
    Texture t = new Texture(Objects.requireNonNull(name), flags, contents);
    int index = textures.indexOf(t);
    if (index >= 0) return index;
    if (name.getBytes(StandardCharsets.US_ASCII).length >= 64)
      throw new IllegalArgumentException("Shader name too long: " + name);
    textures.add(t);
    return textures.size() - 1;
  }

  /** The file bytes. */
  public byte[] write() {
    if (entities.isEmpty()
        || !"worldspawn".equalsIgnoreCase(entities.getFirst().classname().orElse("")))
      throw new IllegalStateException("The first entity must be worldspawn");
    Map<BspLump, byte[]> lumps = new EnumMap<>(BspLump.class);
    byte[] text = (EntityText.write(entities) + '\0').getBytes(StandardCharsets.ISO_8859_1);
    lumps.put(BspLump.ENTITIES, text);

    ByteBuffer tex = buffer(TEXTURE_BYTES * Math.max(1, textures.size()));
    if (textures.isEmpty()) {
      name(tex, "noshader", 64);
      tex.putInt(0).putInt(0);
    }
    for (Texture t : textures) {
      name(tex, t.name, 64);
      tex.putInt(t.flags).putInt(t.contents);
    }
    lumps.put(BspLump.TEXTURES, tex.array());

    List<Brush> all = new ArrayList<>(world);
    all.addAll(boxes);
    int sideCount = 0;
    for (Brush brush : all) sideCount += brush.planes.length;
    ByteBuffer planes = buffer(PLANE_BYTES * sideCount);
    ByteBuffer brushes = buffer(BRUSH_BYTES * all.size());
    ByteBuffer sides = buffer(SIDE_BYTES * sideCount);
    int side = 0;
    for (Brush brush : all) {
      brushes.putInt(side).putInt(brush.planes.length).putInt(brush.texture);
      for (float[] plane : brush.planes) {
        for (float value : plane) planes.putFloat(value == 0 ? 0 : value);
        sides.putInt(side++).putInt(brush.texture);
      }
    }
    ByteBuffer models = buffer(MODEL_BYTES * (boxes.size() + 1));
    float[] bounds = worldBounds();
    for (float value : bounds) models.putFloat(value);
    models.putInt(0).putInt(0).putInt(0).putInt(world.size());
    for (int i = 0; i < boxes.size(); i++) {
      float[][] p = boxes.get(i).planes;
      // Box planes: mins are -dist of the negative sides, maxs the dist of the positive ones.
      models.putFloat(-p[0][3]).putFloat(-p[2][3]).putFloat(-p[4][3]);
      models.putFloat(p[1][3]).putFloat(p[3][3]).putFloat(p[5][3]);
      models.putInt(0).putInt(0).putInt(world.size() + i).putInt(1);
    }
    lumps.put(BspLump.PLANES, planes.array());
    lumps.put(BspLump.BRUSHES, brushes.array());
    lumps.put(BspLump.BRUSH_SIDES, sides.array());
    lumps.put(BspLump.MODELS, models.array());
    ByteBuffer leafBrushes = buffer(4 * world.size());
    for (int i = 0; i < world.size(); i++) leafBrushes.putInt(i);
    lumps.put(BspLump.LEAF_BRUSHES, leafBrushes.array());

    ByteBuffer leaf = buffer(LEAF_BYTES);
    int extent = (int) WORLD_EXTENT;
    leaf.putInt(0).putInt(0);
    leaf.putInt(-extent).putInt(-extent).putInt(-extent);
    leaf.putInt(extent).putInt(extent).putInt(extent);
    leaf.putInt(0).putInt(0).putInt(0).putInt(world.size());
    lumps.put(BspLump.LEAVES, leaf.array());
    return assemble(lumps);
  }

  /**
   * The world model's bounds: the axial extent of the world brushes, or {@link #WORLD_EXTENT} on
   * every side without them (and on any side a brush leaves unbounded).
   */
  private float[] worldBounds() {
    if (world.isEmpty())
      return new float[] {
        -WORLD_EXTENT, -WORLD_EXTENT, -WORLD_EXTENT, WORLD_EXTENT, WORLD_EXTENT, WORLD_EXTENT
      };
    float[] out = {
      Float.POSITIVE_INFINITY,
      Float.POSITIVE_INFINITY,
      Float.POSITIVE_INFINITY,
      Float.NEGATIVE_INFINITY,
      Float.NEGATIVE_INFINITY,
      Float.NEGATIVE_INFINITY
    };
    for (Brush brush : world) {
      float[] lo = {Float.NaN, Float.NaN, Float.NaN};
      float[] hi = {Float.NaN, Float.NaN, Float.NaN};
      for (float[] p : brush.planes)
        for (int axis = 0; axis < 3; axis++) {
          if (p[axis] == 1 && p[(axis + 1) % 3] == 0 && p[(axis + 2) % 3] == 0)
            hi[axis] = Float.isNaN(hi[axis]) ? p[3] : Math.min(hi[axis], p[3]);
          if (p[axis] == -1 && p[(axis + 1) % 3] == 0 && p[(axis + 2) % 3] == 0)
            lo[axis] = Float.isNaN(lo[axis]) ? -p[3] : Math.max(lo[axis], -p[3]);
        }
      for (int axis = 0; axis < 3; axis++) {
        out[axis] = Math.min(out[axis], Float.isNaN(lo[axis]) ? -WORLD_EXTENT : lo[axis]);
        out[axis + 3] = Math.max(out[axis + 3], Float.isNaN(hi[axis]) ? WORLD_EXTENT : hi[axis]);
      }
    }
    return out;
  }

  /** The parsed map. */
  public BspMap build(String source) {
    return BspReader.read(write(), source);
  }

  static byte[] assemble(Map<BspLump, byte[]> lumps) {
    int size = BspLump.HEADER_BYTES;
    for (BspLump kind : BspLump.values()) size += padded(lumps.getOrDefault(kind, new byte[0]));
    ByteBuffer file = buffer(size).putInt(BspMap.IDENT).putInt(BspMap.VERSION_Q3);
    int offset = BspLump.HEADER_BYTES;
    for (BspLump kind : BspLump.values()) {
      byte[] payload = lumps.getOrDefault(kind, new byte[0]);
      file.putInt(kind.headerOffset(), offset).putInt(kind.headerOffset() + 4, payload.length);
      file.put(offset, payload);
      offset += padded(payload);
    }
    return file.array();
  }

  private static int padded(byte[] payload) {
    return (payload.length + 3) & ~3;
  }

  static ByteBuffer buffer(int size) {
    return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
  }

  static void name(ByteBuffer into, String value, int width) {
    byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
    into.put(bytes);
    for (int i = bytes.length; i < width; i++) into.put((byte) 0);
  }
}
