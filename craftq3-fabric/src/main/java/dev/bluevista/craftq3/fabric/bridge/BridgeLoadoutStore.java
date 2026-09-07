package dev.bluevista.craftq3.fabric.bridge;

import dev.bluevista.craftq3.core.fs.*;
import dev.bluevista.craftq3.server.PlayerLoadout;
import java.io.*;
import java.util.*;

/** Versioned, bounded checkpoint tied to the exact qagame module and login profile. */
final class BridgeLoadoutStore {
  private static final int MAGIC = 0x4351334c;
  private final WritableFiles files;
  private final VirtualPath path;
  private final byte[] moduleHash;

  BridgeLoadoutStore(WritableFiles files, UUID player, byte[] moduleHash) {
    if (moduleHash.length != 32) throw new IllegalArgumentException("Expected SHA-256");
    this.files = Objects.requireNonNull(files);
    this.moduleHash = moduleHash.clone();
    path = new VirtualPath("loadout-" + Objects.requireNonNull(player) + ".dat");
  }

  static byte[] hash(byte[] module) {
    try {
      return java.security.MessageDigest.getInstance("SHA-256").digest(module);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  Optional<PlayerLoadout> load() throws IOException {
    var saved = files.read(path);
    if (saved.isEmpty() || saved.get().length == 0) return Optional.empty();
    byte[] bytes = saved.get();
    if (bytes.length != 148) throw new IOException("Invalid bridge loadout size");
    try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (in.readInt() != MAGIC || in.readInt() != 1)
        throw new IOException("Invalid bridge loadout version");
      if (!Arrays.equals(in.readNBytes(32), moduleHash)) return Optional.empty();
      int health = in.readInt(),
          armor = in.readInt(),
          weapons = in.readInt(),
          weapon = in.readInt(),
          holdable = in.readInt();
      var ammo = new ArrayList<Integer>();
      var powerups = new ArrayList<Integer>();
      for (int i = 0; i < 16; i++) ammo.add(in.readInt());
      for (int i = 0; i < 6; i++) powerups.add(in.readInt());
      return Optional.of(
          new PlayerLoadout(health, armor, weapons, weapon, holdable, ammo, powerups));
    } catch (IllegalArgumentException invalid) {
      throw new IOException("Invalid bridge loadout", invalid);
    }
  }

  void save(PlayerLoadout state) throws IOException {
    if (state == null) {
      files.write(path, new byte[0]);
      return;
    }
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(bytes)) {
      out.writeInt(MAGIC);
      out.writeInt(1);
      out.write(moduleHash);
      out.writeInt(state.health());
      out.writeInt(state.armor());
      out.writeInt(state.weapons());
      out.writeInt(state.weapon());
      out.writeInt(state.holdable());
      for (int value : state.ammo()) out.writeInt(value);
      for (int value : state.powerupMillis()) out.writeInt(value);
    }
    files.write(path, bytes.toByteArray());
  }
}
