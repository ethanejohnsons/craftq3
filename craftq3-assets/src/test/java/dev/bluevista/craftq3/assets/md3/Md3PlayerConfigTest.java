package dev.bluevista.craftq3.assets.md3;

import static dev.bluevista.craftq3.assets.md3.AnimationConfig.PlayerAnimation.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.math.Vec3;
import org.junit.jupiter.api.Test;

class Md3PlayerConfigTest {
  @Test
  void readsSkinCommentsTagsQuotesLodNamesAndFirstMatchingSurface() throws Exception {
    var skin =
        SkinParser.parse(
            """
        // authored test skin
        tag_torso,
        BODY, "Models\\Players\\Test\\Body.tga" /* comment */
        body, models/ignored.tga
        head,models/players/test/head
        "boots", "models/players/test/boots"
        """);
    assertEquals(3, skin.surfaces().size());
    assertEquals("models/players/test/body.tga", skin.shaderForSurface("BODY_2").orElseThrow());
    assertEquals("models/players/test/head", skin.shaderForSurface("head").orElseThrow());
    assertTrue(skin.shaderForSurface("tag_torso").isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> skin.surfaces().clear());
    for (String malformed :
        new String[] {"body", "body,", "body,a,b", "body,../secret", "body,\"oops", "/* oops"}) {
      assertThrows(Md3FormatException.class, () -> SkinParser.parse(malformed), malformed);
    }
  }

  @Test
  void normalizesLegExportOffsetAndFillsOptionalTeamArenaAnimations() throws Exception {
    var config =
        AnimationConfig.parse(
            "sex f\nheadoffset 1 2 -3\nfootsteps boot\nfixedlegs\nfixedtorso\n"
                + Md3Fixture.config(25));
    assertEquals(AnimationConfig.Sex.FEMALE, config.sex());
    assertEquals(AnimationConfig.Footsteps.BOOT, config.footsteps());
    assertEquals(new Vec3(1, 2, -3), config.headOffset());
    assertTrue(config.fixedLegs());
    assertTrue(config.fixedTorso());
    assertEquals(60, config.animations().get(TORSO_GESTURE).firstFrame());
    assertEquals(60, config.animations().get(LEGS_WALKCR).firstFrame());
    assertEquals(170, config.animations().get(LEGS_TURN).firstFrame());
    assertEquals(config.animations().get(TORSO_GESTURE), config.animations().get(TORSO_GETFLAG));
    assertTrue(config.animations().get(LEGS_BACKCR).reversed());
    assertEquals(70, config.animations().get(LEGS_BACKWALK).firstFrame());
    assertEquals(33, config.animations().size());
    assertTrue(config.diagnostics().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> config.animations().clear());
  }

  @Test
  void handlesReverseFramesZeroFpsOptionalDirectivesAndExtendedRows() throws Exception {
    String rows = Md3Fixture.config(31).replaceFirst("0 10 5 20", "0 -10 5 0");
    var config =
        AnimationConfig.parse("/* comment\n comment */\nfootsteps unknown\nfuture value\n" + rows);
    assertTrue(config.animations().get(BOTH_DEATH1).reversed());
    assertEquals(1, config.animations().get(BOTH_DEATH1).framesPerSecond());
    assertEquals(300, config.animations().get(TORSO_NEGATIVE).firstFrame());
    assertEquals(3, config.diagnostics().size());
  }

  @Test
  void rejectsIncompleteOrInvalidAnimationRanges() {
    assertThrows(Md3FormatException.class, () -> AnimationConfig.parse(Md3Fixture.config(24)));
    assertThrows(Md3FormatException.class, () -> AnimationConfig.parse(Md3Fixture.config(32)));
    for (String badRow :
        new String[] {
          "0 0 0 20",
          "-1 10 5 20",
          "0 10 11 20",
          "0 10 0 NaN",
          "0 10 0 -1",
          "0 10 0",
          "0 -2147483648 0 10"
        }) {
      assertThrows(
          Md3FormatException.class,
          () -> AnimationConfig.parse(Md3Fixture.config(25).replaceFirst("0 10 5 20", badRow)),
          badRow);
    }
    assertThrows(
        Md3FormatException.class,
        () -> AnimationConfig.parse("headoffset 0 1\n" + Md3Fixture.config(25)));
  }
}
