package dev.bluevista.craftq3.engine.formats.md3;

import static dev.bluevista.craftq3.engine.formats.md3.AnimationConfig.PlayerAnimation.BOTH_DEATH1;
import static dev.bluevista.craftq3.engine.formats.md3.AnimationConfig.PlayerAnimation.LEGS_BACKCR;
import static dev.bluevista.craftq3.engine.formats.md3.AnimationConfig.PlayerAnimation.LEGS_BACKWALK;
import static dev.bluevista.craftq3.engine.formats.md3.AnimationConfig.PlayerAnimation.LEGS_TURN;
import static dev.bluevista.craftq3.engine.formats.md3.AnimationConfig.PlayerAnimation.LEGS_WALKCR;
import static dev.bluevista.craftq3.engine.formats.md3.AnimationConfig.PlayerAnimation.TORSO_GESTURE;
import static dev.bluevista.craftq3.engine.formats.md3.AnimationConfig.PlayerAnimation.TORSO_GETFLAG;
import static dev.bluevista.craftq3.engine.formats.md3.AnimationConfig.PlayerAnimation.TORSO_NEGATIVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import org.junit.jupiter.api.Test;

class Md3PlayerConfigTest {
  @Test
  void readsSkinCommentsTagsQuotesLodNamesAndFirstMatchingSurface() {
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
    assertTrue(skin.diagnostics().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> skin.surfaces().clear());
  }

  @Test
  void skinsFollowTheRendererCommaTokenizerWithoutRejectingAnything() {
    assertTrue(SkinParser.parse("body").surfaces().isEmpty());
    assertFalse(SkinParser.parse("body").diagnostics().isEmpty());
    assertEquals("a", SkinParser.parse("body,a,b").surfaces().get("body"));
    assertEquals("../secret", SkinParser.parse("body,../secret").surfaces().get("body"));
    assertEquals("oops", SkinParser.parse("body,\"oops").surfaces().get("body"));
    assertTrue(SkinParser.parse("/* oops").surfaces().isEmpty());
    // A skipped tag line leaves its shader token to be read as the next surface name, as in Q3.
    assertEquals(
        "legs", SkinParser.parse("h_tag_x,models/x\nlegs,models/legs").surfaces().get("models/x"));
  }

  @Test
  void normalizesLegExportOffsetAndFillsOptionalTeamArenaAnimations() {
    var config =
        AnimationConfig.parse(
            "sex f\nheadoffset 1 2 -3\nfootsteps boot\nfixedlegs\nfixedtorso\n"
                + Md3Fixture.config(25));
    assertEquals(AnimationConfig.Sex.FEMALE, config.sex());
    assertEquals(AnimationConfig.Footsteps.BOOT, config.footsteps());
    assertEquals(new Vec3f(1, 2, -3), config.headOffset());
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
  void handlesReverseFramesZeroFpsUnknownTokensAndExtraRows() {
    String rows = Md3Fixture.config(32).replaceFirst("0 10 5 20", "0 -10 5 0");
    var config =
        AnimationConfig.parse("/* comment\n comment */\nfootsteps unknown\nfuture value\n" + rows);
    assertTrue(config.animations().get(BOTH_DEATH1).reversed());
    assertEquals(10, config.animations().get(BOTH_DEATH1).frameCount());
    assertEquals(1, config.animations().get(BOTH_DEATH1).framesPerSecond());
    assertEquals(300, config.animations().get(TORSO_NEGATIVE).firstFrame());
    assertEquals(3, config.diagnostics().size(), config.diagnostics().toString());
  }

  @Test
  void numbersFollowAtoiAndAtof() {
    var config =
        AnimationConfig.parse(Md3Fixture.config(25).replaceFirst("0 10 5 20", "0 10x 5 2.5fps"));
    assertEquals(10, config.animations().get(BOTH_DEATH1).frameCount());
    assertEquals(2.5f, config.animations().get(BOTH_DEATH1).framesPerSecond());
  }

  @Test
  void rejectsConfigsTheGameRejects() {
    assertThrows(Md3FormatException.class, () -> AnimationConfig.parse(Md3Fixture.config(24)));
    assertThrows(
        Md3FormatException.class,
        () -> AnimationConfig.parse(Md3Fixture.config(25).replaceFirst("\n$", "") + " 1 2"));
    assertThrows(
        Md3FormatException.class,
        () -> AnimationConfig.parse("x".repeat(AnimationConfig.MAX_CHARS + 1)));
  }
}
