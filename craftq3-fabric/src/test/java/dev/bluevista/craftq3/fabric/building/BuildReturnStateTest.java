package dev.bluevista.craftq3.fabric.building;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildReturnStateTest {
  @TempDir Path directory;

  private Properties valid() {
    var values = new Properties();
    values.setProperty("dimension", "minecraft:the_nether");
    values.setProperty("x", "23.25");
    values.setProperty("y", "-42.5");
    values.setProperty("z", "-73.75");
    values.setProperty("yaw", "63.5");
    values.setProperty("pitch", "-17.25");
    values.setProperty("mode", "adventure");
    values.setProperty("invulnerable", "true");
    values.setProperty("flying", "true");
    values.setProperty("mayfly", "true");
    values.setProperty("instabuild", "false");
    values.setProperty("mayBuild", "false");
    values.setProperty("flightSpeed", "0.073");
    values.setProperty("walkSpeed", "0.113");
    return values;
  }

  private Path write(Properties values) throws IOException {
    var path = directory.resolve("return.properties");
    try (var writer = Files.newBufferedWriter(path)) {
      values.store(writer, "test");
    }
    return path;
  }

  @Test
  void completeReturnStateIsParsedWithoutConsumingTheJournal() throws IOException {
    var path = write(valid());
    var bytes = Files.readAllBytes(path);
    var point = BuildReturnState.read(path);
    assertEquals("minecraft:the_nether", point.dimension().identifier().toString());
    assertEquals(23.25, point.x());
    assertEquals(-42.5, point.y());
    assertEquals(-73.75, point.z());
    assertEquals(63.5f, point.yaw());
    assertEquals(-17.25f, point.pitch());
    assertEquals(GameType.ADVENTURE, point.mode());
    assertEquals(
        new Abilities.Packed(true, true, true, false, false, .073f, .113f), point.abilities());
    assertArrayEquals(bytes, Files.readAllBytes(path));
    assertEquals(point, BuildReturnState.read(path));
  }

  @Test
  void everyRequiredFieldMustBePresent() throws IOException {
    for (String key : valid().stringPropertyNames()) {
      var values = valid();
      values.remove(key);
      var path = write(values);
      assertThrows(IOException.class, () -> BuildReturnState.read(path), key);
      assertTrue(Files.exists(path));
    }
  }

  @Test
  void invalidNumericFlagsDimensionsAndModesAreRejectedWithoutDeletingEvidence()
      throws IOException {
    for (var change :
        List.of(
            Map.entry("x", "NaN"),
            Map.entry("z", "30000001"),
            Map.entry("y", "20000001"),
            Map.entry("yaw", "1e99"),
            Map.entry("pitch", "Infinity"),
            Map.entry("flightSpeed", "-1"),
            Map.entry("walkSpeed", "-0.5"),
            Map.entry("flying", "TRUE"),
            Map.entry("mayBuild", "1"),
            Map.entry("mode", "bogus"),
            Map.entry("dimension", "Bad namespace:world"),
            Map.entry("dimension", "craftq3:build"))) {
      var values = valid();
      values.setProperty(change.getKey(), change.getValue());
      var path = write(values);
      var bytes = Files.readAllBytes(path);
      assertThrows(IOException.class, () -> BuildReturnState.read(path), change.toString());
      assertArrayEquals(bytes, Files.readAllBytes(path));
    }
  }

  @Test
  void malformedPropertiesAndOversizedJournalsBecomeCheckedRecoveryFailures() throws IOException {
    var path = directory.resolve("return.properties");
    for (String content : List.of("x=\\uZZZZ\n", "x".repeat(8193))) {
      Files.writeString(path, content);
      assertThrows(IOException.class, () -> BuildReturnState.read(path));
      assertEquals(content, Files.readString(path));
    }
  }
}
