package dev.bluevista.craftq3.mc.hybrid.bridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Optional;

/**
 * A saved loadout: magic {@code CQ3L}, version, the SHA-256 of the qagame module it belongs to,
 * then the fields (188 bytes in all). A loadout saved by a different module reads as empty (the
 * player starts fresh); a malformed file is rejected with {@link IOException} and left alone.
 * Writing an empty array clears a checkpoint (e.g. after leaving dead).
 */
public final class LoadoutCodec {
  private static final int MAGIC = 0x4351334c;

  public static final int VERSION = 1;

  /** The encoded size. */
  public static final int BYTES = 4 + 4 + 32 + 5 * 4 + Loadout.SLOTS * 8;

  private LoadoutCodec() {}

  public static byte[] encode(Loadout loadout, byte[] moduleSha256) {
    checkHash(moduleSha256);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(BYTES);
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeInt(MAGIC);
      out.writeInt(VERSION);
      out.write(moduleSha256);
      out.writeInt(loadout.health());
      out.writeInt(loadout.armor());
      out.writeInt(loadout.weapons());
      out.writeInt(loadout.weapon());
      out.writeInt(loadout.holdable());
      for (int i = 0; i < Loadout.SLOTS; i++) out.writeInt(loadout.ammo(i));
      for (int i = 0; i < Loadout.SLOTS; i++) out.writeInt(loadout.powerupMillis(i));
    } catch (IOException impossible) {
      throw new UncheckedIOException(impossible);
    }
    return bytes.toByteArray();
  }

  /** The loadout saved for {@code moduleSha256}, or empty for a cleared file or another module. */
  public static Optional<Loadout> decode(byte[] data, byte[] moduleSha256) throws IOException {
    checkHash(moduleSha256);
    if (data.length == 0) return Optional.empty();
    if (data.length != BYTES) throw new IOException("Saved loadout is " + data.length + " bytes");
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
      if (in.readInt() != MAGIC) throw new IOException("Not a saved loadout");
      int version = in.readInt();
      if (version != VERSION) throw new IOException("Unsupported loadout version " + version);
      byte[] module = in.readNBytes(32);
      if (!Arrays.equals(module, moduleSha256)) return Optional.empty();
      int health = in.readInt();
      int armor = in.readInt();
      int weapons = in.readInt();
      int weapon = in.readInt();
      int holdable = in.readInt();
      int[] ammo = new int[Loadout.SLOTS];
      int[] powerups = new int[Loadout.SLOTS];
      for (int i = 0; i < Loadout.SLOTS; i++) ammo[i] = in.readInt();
      for (int i = 0; i < Loadout.SLOTS; i++) powerups[i] = in.readInt();
      return Optional.of(new Loadout(health, armor, weapons, weapon, holdable, ammo, powerups));
    } catch (IllegalArgumentException invalid) {
      throw new IOException("Invalid saved loadout: " + invalid.getMessage(), invalid);
    }
  }

  private static void checkHash(byte[] hash) {
    if (hash == null || hash.length != 32)
      throw new IllegalArgumentException("Module identity must be a SHA-256");
  }
}
