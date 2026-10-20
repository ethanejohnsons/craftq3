package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.botlib.BspEntities;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * The movement library variables of the original {@code BotInitMoveAI}: step and barrier heights,
 * gravity for air control, the weapon numbers used for rocket and BFG jumps and the grapple, the
 * missile entity type, and the off-hand grapple commands.
 */
public record MovementSettings(
    float step,
    float maxBarrier,
    float gravity,
    int rocketLauncher,
    int bfg,
    int grapple,
    int missileType,
    boolean offhandGrapple,
    String grappleOn,
    String grappleOff) {
  public static final MovementSettings DEFAULT = from(name -> null);

  public MovementSettings {
    Objects.requireNonNull(grappleOn);
    Objects.requireNonNull(grappleOff);
    if (!Float.isFinite(step) || step < 0 || step > 100_000)
      throw new IllegalArgumentException("Invalid sv_step");
    if (!Float.isFinite(maxBarrier) || maxBarrier < 0 || maxBarrier > 100_000)
      throw new IllegalArgumentException("Invalid sv_maxbarrier");
    if (!Float.isFinite(gravity) || gravity < 0 || gravity > 100_000)
      throw new IllegalArgumentException("Invalid sv_gravity");
  }

  /** Reads the variables through {@code libvar}, which returns null for unset names. */
  public static MovementSettings from(UnaryOperator<String> libvar) {
    return new MovementSettings(
        number(libvar, "sv_step", 18, 0, 100_000),
        number(libvar, "sv_maxbarrier", 32, 0, 100_000),
        number(libvar, "sv_gravity", 800, 0, 100_000),
        (int) number(libvar, "weapindex_rocketlauncher", 5, 0, 255),
        (int) number(libvar, "weapindex_bfg10k", 9, 0, 255),
        (int) number(libvar, "weapindex_grapple", 10, 0, 255),
        (int) number(libvar, "entitytypemissile", 3, 0, 255),
        number(libvar, "offhandgrapple", 0, -1e9f, 1e9f) != 0,
        text(libvar, "cmd_grappleon", "grappleon"),
        text(libvar, "cmd_grappleoff", "grappleoff"));
  }

  private static float number(
      UnaryOperator<String> libvar, String name, float fallback, float min, float max) {
    String value = libvar.apply(name);
    if (value == null) return fallback;
    float parsed = BspEntities.atof(value);
    return Float.isFinite(parsed) ? Math.clamp(parsed, min, max) : fallback;
  }

  private static String text(UnaryOperator<String> libvar, String name, String fallback) {
    String value = libvar.apply(name);
    return value == null ? fallback : value;
  }
}
