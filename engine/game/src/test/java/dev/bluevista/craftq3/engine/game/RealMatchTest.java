package dev.bluevista.craftq3.engine.game;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Headless dedicated matches with four bots on every game directory's effective qagame: two minutes
 * of game time under {@code vm_strictSyscalls 1}, requiring no degradation, no level drop, moving
 * bots and changing scores.
 */
@RealData
final class RealMatchTest {
  private static final int MATCH_MILLIS = 120_000;
  private static final List<String> BASE_BOTS = List.of("Sarge", "Major", "Visor", "Grunt");

  @TempDir static Path pak0Root;

  static Stream<Arguments> games() {
    return Stream.of(
        Arguments.of("baseq3", "q3dm17", 0),
        Arguments.of("cpma", "q3dm17", 0),
        Arguments.of("jailbreak", "q3jbeven1", 3),
        Arguments.of("truecombat", "tc_harbour", 0),
        Arguments.of("combat", "combatctf_01", 4),
        Arguments.of("q3rally", "q3r_country01", 0),
        Arguments.of("alternatefire", "q3dm17", 0),
        Arguments.of("sha", "q3dm17", 0),
        Arguments.of("q3pinball", "q3dm17", 0),
        Arguments.of("CorkScrew", "q3dm17", 0),
        Arguments.of("baseq3", "q3ctf1", 4));
  }

  @ParameterizedTest(name = "{0} {1} gametype {2}")
  @MethodSource("games")
  void match(String game, String map, int gametype) throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(selected(game + ":" + map), "not selected");
    var result =
        RealMatch.run(RealDataRoot.require(), game, map, gametype, MATCH_MILLIS, BASE_BOTS);
    report(result);
  }

  /** The retail pak0 qagame alone (an installation of only pak0.pk3). */
  @org.junit.jupiter.api.Test
  void retailPak0() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(selected("pak0:q3dm17"), "not selected");
    Path base = pak0Root.resolve("baseq3");
    Files.createDirectories(base);
    Path pak0 = RealDataRoot.require().resolve("baseq3/pak0.pk3");
    try {
      Files.createSymbolicLink(base.resolve("pak0.pk3"), pak0);
    } catch (IOException | UnsupportedOperationException noLinks) {
      Files.copy(pak0, base.resolve("pak0.pk3"));
    }
    // Chatting bots exercise the retail chat imports (BotSetChatName without a client).
    var result = RealMatch.run(pak0Root, "baseq3", "q3dm17", 0, MATCH_MILLIS, BASE_BOTS, true);
    assertEquals("RETAIL_1999", result.profile(), result.line());
    report(result);
  }

  /**
   * {@code CRAFTQ3_MATCHES} (comma-separated {@code game:map}) narrows the run, e.g. for a
   * re-check.
   */
  private static boolean selected(String key) {
    String only = System.getenv("CRAFTQ3_MATCHES");
    return only == null || only.isBlank() || List.of(only.split(",")).contains(key);
  }

  private static void report(RealMatch.Result result) {
    System.out.println("MATCH " + result.line());
    boolean ok =
        result.drop().isEmpty()
            && result.degradations() == 0
            && result.botsActive() == 4
            && result.botsMoved() >= 3
            && result.scoreChanges() > 0;
    if (!ok) System.out.println("LOG " + result.game() + "\n" + result.log());
    assertEquals("", result.drop(), result.line());
    assertEquals(0, result.degradations(), result.line());
    assertEquals(4, result.botsActive(), result.line());
    assertTrue(result.botsMoved() >= 3, result.line());
    assertTrue(result.scoreChanges() > 0, result.line());
  }
}
