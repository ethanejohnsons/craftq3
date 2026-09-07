package dev.bluevista.craftq3.fabric.bridge;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.fs.*;
import dev.bluevista.craftq3.server.PlayerLoadout;
import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgeLoadoutStoreTest {
  @TempDir Path directory;

  private PlayerLoadout state() {
    var ammo = new ArrayList<Integer>(Collections.nCopies(16, 0));
    ammo.set(1, -1);
    ammo.set(5, 7);
    return new PlayerLoadout(187, 57, 38, 5, 27, ammo, List.of(24000, 12000, 0, 0, 0, 0));
  }

  @Test
  void roundTripsAndSeparatesModulesProfilesAndDeadReset() throws Exception {
    try (var files = new GameFileStore(directory)) {
      var id = UUID.randomUUID();
      var hash = new byte[32];
      var store = new BridgeLoadoutStore(files, id, hash);
      assertTrue(store.load().isEmpty());
      store.save(state());
      assertEquals(state(), store.load().orElseThrow());
      assertTrue(new BridgeLoadoutStore(files, UUID.randomUUID(), hash).load().isEmpty());
      hash[0] = 1;
      assertTrue(new BridgeLoadoutStore(files, id, hash).load().isEmpty());
      assertEquals(state(), store.load().orElseThrow());
      store.save(null);
      assertTrue(store.load().isEmpty());
    }
  }

  @Test
  void malformedCheckpointsRemainUntouched() throws Exception {
    try (var files = new GameFileStore(directory)) {
      var id = UUID.randomUUID();
      var store = new BridgeLoadoutStore(files, id, new byte[32]);
      store.save(state());
      var path = new VirtualPath("loadout-" + id + ".dat");
      var valid = files.read(path).orElseThrow();
      assertEquals(148, valid.length);
      var badHealth = valid.clone();
      ByteBuffer.wrap(badHealth).putInt(40, 0);
      var badVersion = valid.clone();
      ByteBuffer.wrap(badVersion).putInt(4, 2);
      for (var bad :
          List.of(Arrays.copyOf(valid, 147), Arrays.copyOf(valid, 149), badHealth, badVersion)) {
        files.write(path, bad);
        assertThrows(IOException.class, store::load);
        assertArrayEquals(bad, files.read(path).orElseThrow());
      }
    }
  }
}
