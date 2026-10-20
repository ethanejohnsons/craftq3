package dev.bluevista.craftq3.mc.hybrid.bridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Binary file of one dimension's pickup placements: magic {@code CQ3P}, version, count, then {@code
 * id, classname, x, y, z} per placement. Malformed files are rejected whole.
 */
public final class PickupPlacementCodec {
  private static final int MAGIC = 0x43513350;

  /** The current version. */
  public static final int VERSION = 1;

  /** Largest accepted file. */
  public static final int MAX_BYTES = 64 * 1024;

  private PickupPlacementCodec() {}

  public static byte[] encode(List<PickupPlacement> placements) {
    if (placements.size() > PickupPlacement.MAX_PLACEMENTS)
      throw new IllegalArgumentException("Too many placements");
    boolean[] ids = new boolean[PickupPlacement.MAX_PLACEMENTS + 1];
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeInt(MAGIC);
      out.writeInt(VERSION);
      out.writeInt(placements.size());
      for (PickupPlacement p : placements) {
        if (ids[p.id()]) throw new IllegalArgumentException("Duplicate pickup id " + p.id());
        ids[p.id()] = true;
        out.writeInt(p.id());
        out.writeUTF(p.classname());
        out.writeDouble(p.x());
        out.writeDouble(p.y());
        out.writeDouble(p.z());
      }
    } catch (IOException impossible) {
      throw new UncheckedIOException(impossible);
    }
    return bytes.toByteArray();
  }

  public static List<PickupPlacement> decode(byte[] data) throws IOException {
    if (data.length == 0) return List.of();
    if (data.length > MAX_BYTES) throw new IOException("Oversized pickup placements");
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
      if (in.readInt() != MAGIC) throw new IOException("Not a pickup placement file");
      int version = in.readInt();
      if (version != VERSION)
        throw new IOException("Unsupported pickup placement version " + version);
      int count = in.readInt();
      if (count < 0 || count > PickupPlacement.MAX_PLACEMENTS)
        throw new IOException("Bad pickup placement count " + count);
      boolean[] ids = new boolean[PickupPlacement.MAX_PLACEMENTS + 1];
      List<PickupPlacement> out = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
        PickupPlacement p =
            new PickupPlacement(
                in.readInt(), in.readUTF(), in.readDouble(), in.readDouble(), in.readDouble());
        if (ids[p.id()]) throw new IOException("Duplicate pickup id " + p.id());
        ids[p.id()] = true;
        out.add(p);
      }
      if (in.available() != 0) throw new IOException("Trailing pickup placement data");
      return List.copyOf(out);
    } catch (IllegalArgumentException invalid) {
      throw new IOException("Invalid pickup placement: " + invalid.getMessage(), invalid);
    }
  }
}
