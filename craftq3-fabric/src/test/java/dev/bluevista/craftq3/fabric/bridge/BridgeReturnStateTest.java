package dev.bluevista.craftq3.fabric.bridge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgeReturnStateTest {
  @TempDir Path directory;

  private BridgeReturnState.Journal journal() {
    return new BridgeReturnState.Journal(
        UUID.randomUUID().toString(),
        new BridgeReturnState.State(
            GameType.ADVENTURE,
            new Abilities.Packed(true, false, true, false, true, .073f, .113f),
            true,
            false));
  }

  @Test
  void everyRestorationFieldRoundTripsWithoutConsumingTheRecord() throws Exception {
    var path = directory.resolve("return.properties");
    var journal = journal();
    BridgeReturnState.write(path, journal);
    var bytes = Files.readAllBytes(path);
    assertEquals(journal, BridgeReturnState.read(path));
    assertEquals(journal, BridgeReturnState.read(path));
    assertArrayEquals(bytes, Files.readAllBytes(path));
  }

  @Test
  void invalidStateIsRejectedWithFileIntact() throws Exception {
    var path = directory.resolve("return.properties");
    BridgeReturnState.write(path, journal());
    String valid = Files.readString(path);
    for (String invalid :
        new String[] {
          valid.replace("flying=false", "flying=maybe"),
          valid.replace("flightSpeed=0.073", "flightSpeed=NaN"),
          valid.replace("walkSpeed=0.113", "walkSpeed=-1"),
          valid.replace("mode=adventure", "mode=unknown"),
          valid.replace("version=1", "version=2"),
          valid + "unexpected=true\n"
        }) {
      Files.writeString(path, invalid);
      assertThrows(IOException.class, () -> BridgeReturnState.read(path));
      assertEquals(invalid, Files.readString(path));
    }
  }

  @Test
  void malformedAndOversizedRecordsFailWithoutDeletion() throws Exception {
    var path = directory.resolve("return.properties");
    for (String value : new String[] {"version=1\n", "bad=\\uinvalid", "x".repeat(8193)}) {
      Files.writeString(path, value);
      assertThrows(IOException.class, () -> BridgeReturnState.read(path));
      assertEquals(value, Files.readString(path));
    }
  }

  @Test
  void onlyCanonicalMarkersCanSelectRecoveryFiles() throws Exception {
    var id = "16dd3146-7c62-40ee-a630-fb937aa481bc";
    assertEquals(id, BridgeReturnState.token(id));
    for (String value : new String[] {"../" + id, "", "1-1-1-1-1", id.toUpperCase(), id + "/extra"})
      assertThrows(IOException.class, () -> BridgeReturnState.token(value));
  }

  @Test
  void importedSingleplayerMarkerFindsItsPreviousProfileWithoutConsumingIt() throws Exception {
    var oldOwner = Files.createDirectory(directory.resolve("bridge-return-" + UUID.randomUUID()));
    var newOwner = directory.resolve("bridge-return-" + UUID.randomUUID());
    var journal = journal();
    var file = oldOwner.resolve(journal.token() + ".properties");
    BridgeReturnState.write(file, journal);
    var bytes = Files.readAllBytes(file);
    assertEquals(file, BridgeReturnState.find(newOwner, journal.token()));
    assertEquals(
        journal, BridgeReturnState.read(BridgeReturnState.find(newOwner, journal.token())));
    assertArrayEquals(bytes, Files.readAllBytes(file));
    assertFalse(Files.exists(newOwner));
  }

  @Test
  void importedMarkersRejectMissingOrAmbiguousGenerations() throws Exception {
    var newOwner = directory.resolve("bridge-return-" + UUID.randomUUID());
    var journal = journal();
    assertThrows(IOException.class, () -> BridgeReturnState.find(newOwner, journal.token()));
    var first = Files.createDirectory(directory.resolve("bridge-return-" + UUID.randomUUID()));
    var second = Files.createDirectory(directory.resolve("bridge-return-" + UUID.randomUUID()));
    var file = first.resolve(journal.token() + ".properties");
    var other = second.resolve(journal.token() + ".properties");
    BridgeReturnState.write(file, journal);
    Files.copy(file, other);
    assertThrows(IOException.class, () -> BridgeReturnState.find(newOwner, journal.token()));
    assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(other));
    assertThrows(
        IOException.class, () -> BridgeReturnState.find(newOwner, "../" + journal.token()));
    assertEquals(file, BridgeReturnState.find(first, journal.token()));
  }
}
