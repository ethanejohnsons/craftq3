package dev.bluevista.craftq3.assets.md3;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Q3 player animation.cfg with lower-body frame offsets normalized for lower.md3. */
public record AnimationConfig(
    Map<PlayerAnimation, Animation> animations,
    Vec3 headOffset,
    Sex sex,
    Footsteps footsteps,
    boolean fixedLegs,
    boolean fixedTorso,
    List<String> diagnostics) {
  public AnimationConfig {
    animations = Map.copyOf(animations);
    diagnostics = List.copyOf(diagnostics);
  }

  public enum Sex {
    MALE,
    FEMALE,
    NEUTER
  }

  public enum Footsteps {
    NORMAL,
    BOOT,
    FLESH,
    MECH,
    ENERGY
  }

  /** Canonical numeric order; the final two entries are synthesized reverse-walk animations. */
  public enum PlayerAnimation {
    BOTH_DEATH1,
    BOTH_DEAD1,
    BOTH_DEATH2,
    BOTH_DEAD2,
    BOTH_DEATH3,
    BOTH_DEAD3,
    TORSO_GESTURE,
    TORSO_ATTACK,
    TORSO_ATTACK2,
    TORSO_DROP,
    TORSO_RAISE,
    TORSO_STAND,
    TORSO_STAND2,
    LEGS_WALKCR,
    LEGS_WALK,
    LEGS_RUN,
    LEGS_BACK,
    LEGS_SWIM,
    LEGS_JUMP,
    LEGS_LAND,
    LEGS_JUMPB,
    LEGS_LANDB,
    LEGS_IDLE,
    LEGS_IDLECR,
    LEGS_TURN,
    TORSO_GETFLAG,
    TORSO_GUARDBASE,
    TORSO_PATROL,
    TORSO_FOLLOWME,
    TORSO_AFFIRMATIVE,
    TORSO_NEGATIVE,
    LEGS_BACKCR,
    LEGS_BACKWALK
  }

  public record Animation(
      int firstFrame,
      int frameCount,
      int loopFrames,
      float framesPerSecond,
      boolean reversed,
      boolean flipFlop) {
    public Animation {
      if (firstFrame < 0
          || frameCount < 1
          || loopFrames < 0
          || loopFrames > frameCount
          || !Float.isFinite(framesPerSecond)
          || framesPerSecond <= 0
          || (long) firstFrame + frameCount > Integer.MAX_VALUE) {
        throw new IllegalArgumentException("Invalid player animation range");
      }
    }
  }

  public static AnimationConfig parse(String text) throws Md3FormatException {
    if (text == null || text.length() > 64 * 1024)
      throw bad("Animation config exceeds 64KiB limit");
    List<Animation> rows = new ArrayList<>();
    List<String> diagnostics = new ArrayList<>();
    Vec3 headOffset = new Vec3(0, 0, 0);
    Sex sex = Sex.MALE;
    Footsteps footsteps = Footsteps.NORMAL;
    boolean fixedLegs = false, fixedTorso = false;
    int lineNumber = 0;
    for (String line : ModelText.withoutComments(text).split("\\R")) {
      lineNumber++;
      line = line.strip();
      if (line.isEmpty()) continue;
      String[] fields = line.split("\\s+");
      if (fields[0].matches("[+-]?\\d+")) {
        if (fields.length != 4 || rows.size() >= 31)
          throw bad("Expected at most 31 animation rows of four values at line " + lineNumber);
        int first = integer(fields[0], lineNumber);
        int signedCount = integer(fields[1], lineNumber);
        int loops = integer(fields[2], lineNumber);
        float fps = decimal(fields[3], lineNumber);
        if (signedCount == Integer.MIN_VALUE
            || Math.abs(signedCount) > 1_000_000
            || first > 1_000_000) throw bad("Animation frame budget exceeded");
        if (fps == 0) {
          fps = 1;
          diagnostics.add("Zero FPS replaced with one at animation line " + lineNumber);
        }
        try {
          rows.add(new Animation(first, Math.abs(signedCount), loops, fps, signedCount < 0, false));
        } catch (IllegalArgumentException invalid) {
          throw bad("Invalid animation range at line " + lineNumber);
        }
        continue;
      }
      if (!rows.isEmpty())
        throw bad("Animation directive appears after frame rows at line " + lineNumber);
      String directive = fields[0].toLowerCase(Locale.ROOT);
      switch (directive) {
        case "sex" -> {
          arity(fields, 2, lineNumber);
          sex =
              switch (fields[1].toLowerCase(Locale.ROOT)) {
                case "m" -> Sex.MALE;
                case "f" -> Sex.FEMALE;
                case "n" -> Sex.NEUTER;
                default -> throw bad("Unknown animation sex at line " + lineNumber);
              };
        }
        case "footsteps" -> {
          arity(fields, 2, lineNumber);
          try {
            footsteps =
                Footsteps.valueOf(
                    fields[1].equalsIgnoreCase("default")
                        ? "NORMAL"
                        : fields[1].toUpperCase(Locale.ROOT));
          } catch (IllegalArgumentException unknown) {
            diagnostics.add("Unknown footsteps at animation line " + lineNumber + "; using normal");
            footsteps = Footsteps.NORMAL;
          }
        }
        case "headoffset" -> {
          arity(fields, 4, lineNumber);
          headOffset =
              new Vec3(
                  decimal(fields[1], lineNumber),
                  decimal(fields[2], lineNumber),
                  decimal(fields[3], lineNumber));
        }
        case "fixedlegs" -> {
          arity(fields, 1, lineNumber);
          fixedLegs = true;
        }
        case "fixedtorso" -> {
          arity(fields, 1, lineNumber);
          fixedTorso = true;
        }
        default -> {
          if (diagnostics.size() < 256)
            diagnostics.add("Unknown animation directive " + directive + " at line " + lineNumber);
        }
      }
    }
    if (rows.size() < 25) throw bad("Player animation config requires at least 25 animation rows");
    Map<PlayerAnimation, Animation> animations = new EnumMap<>(PlayerAnimation.class);
    int legOffset =
        rows.get(PlayerAnimation.LEGS_WALKCR.ordinal()).firstFrame()
            - rows.get(PlayerAnimation.TORSO_GESTURE.ordinal()).firstFrame();
    for (int i = 0; i < 31; i++) {
      Animation animation =
          i < rows.size() ? rows.get(i) : rows.get(PlayerAnimation.TORSO_GESTURE.ordinal());
      if (i >= PlayerAnimation.LEGS_WALKCR.ordinal() && i <= PlayerAnimation.LEGS_TURN.ordinal()) {
        int first = animation.firstFrame() - legOffset;
        if (first < 0) throw bad("Lower-body animation offset produces a negative frame");
        animation =
            new Animation(
                first,
                animation.frameCount(),
                animation.loopFrames(),
                animation.framesPerSecond(),
                animation.reversed(),
                animation.flipFlop());
      }
      animations.put(PlayerAnimation.values()[i], animation);
    }
    animations.put(
        PlayerAnimation.LEGS_BACKCR, reversed(animations.get(PlayerAnimation.LEGS_WALKCR)));
    animations.put(
        PlayerAnimation.LEGS_BACKWALK, reversed(animations.get(PlayerAnimation.LEGS_WALK)));
    return new AnimationConfig(
        animations, headOffset, sex, footsteps, fixedLegs, fixedTorso, diagnostics);
  }

  private static Animation reversed(Animation source) {
    return new Animation(
        source.firstFrame(),
        source.frameCount(),
        source.loopFrames(),
        source.framesPerSecond(),
        true,
        source.flipFlop());
  }

  private static int integer(String value, int line) throws Md3FormatException {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException invalid) {
      throw bad("Invalid animation integer at line " + line);
    }
  }

  private static float decimal(String value, int line) throws Md3FormatException {
    try {
      float result = Float.parseFloat(value);
      if (!Float.isFinite(result)) throw new NumberFormatException();
      return result;
    } catch (NumberFormatException invalid) {
      throw bad("Invalid animation float at line " + line);
    }
  }

  private static void arity(String[] fields, int expected, int line) throws Md3FormatException {
    if (fields.length != expected) throw bad("Wrong animation directive arity at line " + line);
  }

  private static Md3FormatException bad(String message) {
    return new Md3FormatException(message);
  }
}
