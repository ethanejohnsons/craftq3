package dev.bluevista.craftq3.mc.hybrid.building;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/**
 * The per-world record of building regions: a small, versioned, line-oriented properties file.
 *
 * <pre>
 * craftq3.building=1
 * region.0.game=baseq3
 * region.0.map=maps/q3dm17.bsp
 * region.0.sha256=...
 * region.0.origin=0 64 0
 * region.0.scale=32
 * </pre>
 *
 * Unknown keys are ignored so later versions can add fields; a newer major version, a malformed
 * value or a duplicate slot is rejected as a whole with {@link IOException}, leaving the caller's
 * file untouched.
 */
public final class BuildingRegionCodec {
  /** The current format version. */
  public static final int VERSION = 1;

  /** Files larger than this are rejected before parsing. */
  public static final int MAX_BYTES = 1 << 20;

  private static final String VERSION_KEY = "craftq3.building";

  private BuildingRegionCodec() {}

  public static byte[] encode(List<BuildingRegion> regions) {
    List<BuildingRegion> sorted = new ArrayList<>(regions);
    sorted.sort(Comparator.comparingInt(BuildingRegion::slot));
    BitSet seen = new BitSet();
    StringBuilder out = new StringBuilder();
    out.append("# CraftQ3 building regions. Each names a BSP by path and SHA-256.\n");
    out.append(VERSION_KEY).append('=').append(VERSION).append('\n');
    for (BuildingRegion r : sorted) {
      if (seen.get(r.slot())) throw new IllegalArgumentException("Duplicate slot " + r.slot());
      seen.set(r.slot());
      String p = "region." + r.slot() + ".";
      out.append(p).append("game=").append(escape(r.game())).append('\n');
      out.append(p).append("map=").append(escape(r.map())).append('\n');
      out.append(p).append("sha256=").append(r.sha256()).append('\n');
      out.append(p)
          .append("origin=")
          .append(r.originX())
          .append(' ')
          .append(r.originY())
          .append(' ')
          .append(r.originZ())
          .append('\n');
      out.append(p).append("scale=").append(r.unitsPerBlock()).append('\n');
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  public static List<BuildingRegion> decode(byte[] data) throws IOException {
    if (data.length > MAX_BYTES) throw new IOException("Building region file is too large");
    if (data.length == 0) return List.of();
    Properties properties = new Properties();
    try {
      properties.load(new StringReader(new String(data, StandardCharsets.UTF_8)));
    } catch (IllegalArgumentException malformed) {
      throw new IOException("Malformed building region file", malformed);
    }
    String version = properties.getProperty(VERSION_KEY);
    if (version == null) throw new IOException("Missing " + VERSION_KEY + " version");
    int major;
    try {
      major = Integer.parseInt(version.trim());
    } catch (NumberFormatException e) {
      throw new IOException("Bad building region version " + version, e);
    }
    if (major < 1 || major > VERSION)
      throw new IOException("Unsupported building region version " + major);
    BitSet slots = new BitSet();
    for (String key : properties.stringPropertyNames()) {
      if (!key.startsWith("region.")) continue;
      int dot = key.indexOf('.', 7);
      if (dot < 0) throw new IOException("Bad key " + key);
      int slot;
      try {
        slot = Integer.parseInt(key.substring(7, dot));
      } catch (NumberFormatException e) {
        throw new IOException("Bad region slot in " + key, e);
      }
      if (slot < 0 || slot >= BuildingRegion.MAX_SLOTS) throw new IOException("Bad slot " + slot);
      slots.set(slot);
    }
    List<BuildingRegion> regions = new ArrayList<>();
    for (int slot = slots.nextSetBit(0); slot >= 0; slot = slots.nextSetBit(slot + 1)) {
      String p = "region." + slot + ".";
      try {
        String[] origin = required(properties, p + "origin").trim().split("\\s+");
        if (origin.length != 3) throw new IOException("Region " + slot + " origin needs 3 values");
        regions.add(
            new BuildingRegion(
                slot,
                required(properties, p + "game"),
                required(properties, p + "map"),
                required(properties, p + "sha256").trim(),
                Integer.parseInt(origin[0]),
                Integer.parseInt(origin[1]),
                Integer.parseInt(origin[2]),
                Integer.parseInt(required(properties, p + "scale").trim())));
      } catch (IllegalArgumentException invalid) {
        throw new IOException(
            "Invalid building region " + slot + ": " + invalid.getMessage(), invalid);
      }
    }
    return List.copyOf(regions);
  }

  private static String required(Properties properties, String key) throws IOException {
    String value = properties.getProperty(key);
    if (value == null) throw new IOException("Missing " + key);
    return value;
  }

  private static String escape(String value) {
    StringBuilder out = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\' -> out.append("\\\\");
        case ' ' -> out.append(i == 0 ? "\\ " : " ");
        case ':', '=', '#', '!' -> out.append('\\').append(c);
        default -> {
          if (c < 0x20 || c > 0x7e) out.append(String.format("\\u%04x", (int) c));
          else out.append(c);
        }
      }
    }
    return out.toString();
  }
}
