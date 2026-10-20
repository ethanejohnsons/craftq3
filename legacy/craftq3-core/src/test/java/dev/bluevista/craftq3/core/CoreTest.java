package dev.bluevista.craftq3.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.config.CraftQ3Config;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CoreTest {
  @TempDir Path temp;

  @Test
  void canonicalizesQ3Paths() {
    assertEquals("textures/base/wall.tga", new VirtualPath("Textures\\Base/Wall.TGA").value());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "/etc/passwd",
        "../x",
        "x/../y",
        "x//y",
        "C:/x",
        "./x",
        "x/",
        "x\\..\\y",
        "x\n"
      })
  void rejectsUnsafePaths(String path) {
    assertThrows(IllegalArgumentException.class, () -> new VirtualPath(path));
  }

  @Test
  void configRoundTripsPathsWithSpacesAndUnicode() throws Exception {
    Path file = temp.resolve("config/craftq3.properties");
    var initial = CraftQ3Config.load(file, temp.resolve("games"));
    assertTrue(Files.isDirectory(initial.installation().resolve("baseq3")));
    var next = new CraftQ3Config(temp.resolve("Q3 data é"), "AlternateFire");
    next.save(file);
    assertEquals(next, CraftQ3Config.load(file, temp));
  }

  @Test
  void invalidConfigIsNotOverwritten() throws Exception {
    Path file = temp.resolve("bad.properties");
    Files.writeString(file, "game=../escape");
    assertThrows(java.io.IOException.class, () -> CraftQ3Config.load(file, temp));
    assertEquals("game=../escape", Files.readString(file));
  }
}
