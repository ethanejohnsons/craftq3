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
 * from the caller. World collision is supplied separately as a {@code CollisionSource}.
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

  private record Box(float[] mins, float[] maxs, int texture) {}

  private final List<BspEntity> entities = new ArrayList<>();
  private final List<Texture> textures = new ArrayList<>();
  private final List<Box> boxes = new ArrayList<>();

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
    boxes.add(new Box(mins.clone(), maxs.clone(), texture(shader, 0, contents)));
    return boxes.size();
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

    ByteBuffer planes = buffer(PLANE_BYTES * 6 * boxes.size());
    ByteBuffer brushes = buffer(BRUSH_BYTES * boxes.size());
    ByteBuffer sides = buffer(SIDE_BYTES * 6 * boxes.size());
    ByteBuffer models = buffer(MODEL_BYTES * (boxes.size() + 1));
    models.putFloat(-WORLD_EXTENT).putFloat(-WORLD_EXTENT).putFloat(-WORLD_EXTENT);
    models.putFloat(WORLD_EXTENT).putFloat(WORLD_EXTENT).putFloat(WORLD_EXTENT);
    models.putInt(0).putInt(0).putInt(0).putInt(0);
    for (int i = 0; i < boxes.size(); i++) {
      Box box = boxes.get(i);
      // Sides in the compiler's order: -x, +x, -y, +y, -z, +z.
      for (int axis = 0; axis < 3; axis++) {
        for (int sign = 0; sign < 2; sign++) {
          float[] n = new float[3];
          n[axis] = sign == 0 ? -1 : 1;
          float dist = sign == 0 ? -box.mins[axis] : box.maxs[axis];
          planes.putFloat(n[0] == 0 ? 0 : n[0]).putFloat(n[1] == 0 ? 0 : n[1]);
          planes.putFloat(n[2] == 0 ? 0 : n[2]).putFloat(dist == 0 ? 0 : dist);
          sides.putInt(i * 6 + axis * 2 + sign).putInt(box.texture);
        }
      }
      brushes.putInt(i * 6).putInt(6).putInt(box.texture);
      for (int axis = 0; axis < 3; axis++) models.putFloat(box.mins[axis]);
      for (int axis = 0; axis < 3; axis++) models.putFloat(box.maxs[axis]);
      models.putInt(0).putInt(0).putInt(i).putInt(1);
    }
    lumps.put(BspLump.PLANES, planes.array());
    lumps.put(BspLump.BRUSHES, brushes.array());
    lumps.put(BspLump.BRUSH_SIDES, sides.array());
    lumps.put(BspLump.MODELS, models.array());

    ByteBuffer leaf = buffer(LEAF_BYTES);
    int extent = (int) WORLD_EXTENT;
    leaf.putInt(0).putInt(0);
    leaf.putInt(-extent).putInt(-extent).putInt(-extent);
    leaf.putInt(extent).putInt(extent).putInt(extent);
    leaf.putInt(0).putInt(0).putInt(0).putInt(0);
    lumps.put(BspLump.LEAVES, leaf.array());
    return assemble(lumps);
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
