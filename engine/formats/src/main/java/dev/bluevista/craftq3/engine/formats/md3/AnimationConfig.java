package dev.bluevista.craftq3.engine.formats.md3;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.TextTokenizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A player model's {@code animation.cfg}, read with the original game's token rules: optional
 * directives ({@code sex}, {@code footsteps}, {@code headoffset}, {@code fixedlegs}, {@code
 * fixedtorso}) until the first token starting with a digit, then four numbers per animation parsed
 * like C {@code atoi}/{@code atof}. Lower-body first frames are made relative to {@code lower.md3}.
 * The six Team Arena gestures default to {@code TORSO_GESTURE}; fewer than 25 rows is an error, as
 * in the game, and rows past the 31st are ignored.
 */
public record AnimationConfig(
    Map<PlayerAnimation, Animation> animations,
    Vec3f headOffset,
    Sex sex,
    Footsteps footsteps,
    boolean fixedLegs,
    boolean fixedTorso,
    List<String> diagnostics) {
  public static final int MAX_CHARS = 64 * 1024;

  /** Rows read from the file ({@code MAX_ANIMATIONS}). */
  public static final int FILE_ANIMATIONS = 31;

  private static final int MAX_DIAGNOSTICS = 256;

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

  /**
   * One clip. Values are stored as the game stores them; a zero frame rate was replaced by one.
   * {@link Md3Animation#sample} requires at least one frame and a positive rate.
   */
  public record Animation(
      int firstFrame,
      int frameCount,
      int loopFrames,
      float framesPerSecond,
      boolean reversed,
      boolean flipFlop) {
    Animation reverse() {
      return new Animation(firstFrame, frameCount, loopFrames, framesPerSecond, true, flipFlop);
    }
  }

  public static AnimationConfig parse(String text) {
    if (text == null || text.length() > MAX_CHARS)
      throw new Md3FormatException("Animation config exceeds the 64 KiB limit");
    return new Parser(new TextTokenizer(text)).parse();
  }

  private static final class Parser {
    private final TextTokenizer tokens;
    private final List<String> diagnostics = new ArrayList<>();
    private Vec3f headOffset = Vec3f.ZERO;
    private Sex sex = Sex.MALE;
    private Footsteps footsteps = Footsteps.NORMAL;
    private boolean fixedLegs;
    private boolean fixedTorso;

    Parser(TextTokenizer tokens) {
      this.tokens = tokens;
    }

    AnimationConfig parse() {
      directives();
      Map<PlayerAnimation, Animation> animations = rows();
      animations.put(
          PlayerAnimation.LEGS_BACKCR, animations.get(PlayerAnimation.LEGS_WALKCR).reverse());
      animations.put(
          PlayerAnimation.LEGS_BACKWALK, animations.get(PlayerAnimation.LEGS_WALK).reverse());
      return new AnimationConfig(
          animations, headOffset, sex, footsteps, fixedLegs, fixedTorso, diagnostics);
    }

    private void directives() {
      while (true) {
        int position = tokens.position();
        int line = tokens.line();
        String token = tokens.next();
        if (token.isEmpty()) return;
        switch (token.toLowerCase(Locale.ROOT)) {
          case "footsteps" -> footsteps(tokens.next());
          case "headoffset" -> headOffset();
          case "sex" -> sex(tokens.next());
          case "fixedlegs" -> fixedLegs = true;
          case "fixedtorso" -> fixedTorso = true;
          default -> {
            if (Character.isDigit(token.charAt(0))) {
              tokens.reset(position, line);
              return;
            }
            note("Unknown animation token '" + token + "' at line " + line);
          }
        }
      }
    }

    private void footsteps(String value) {
      switch (value.toLowerCase(Locale.ROOT)) {
        case "default", "normal" -> footsteps = Footsteps.NORMAL;
        case "boot" -> footsteps = Footsteps.BOOT;
        case "flesh" -> footsteps = Footsteps.FLESH;
        case "mech" -> footsteps = Footsteps.MECH;
        case "energy" -> footsteps = Footsteps.ENERGY;
        default -> note("Bad footsteps parm '" + value + "'");
      }
    }

    private void headOffset() {
      float[] offset = new float[3];
      for (int i = 0; i < 3; i++) offset[i] = TextTokenizer.atof(tokens.next());
      headOffset = Vec3f.of(offset);
    }

    private void sex(String value) {
      char c = value.isEmpty() ? 'm' : Character.toLowerCase(value.charAt(0));
      sex = c == 'f' ? Sex.FEMALE : c == 'n' ? Sex.NEUTER : Sex.MALE;
    }

    private Map<PlayerAnimation, Animation> rows() {
      Map<PlayerAnimation, Animation> animations = new EnumMap<>(PlayerAnimation.class);
      PlayerAnimation[] kinds = PlayerAnimation.values();
      int skip = 0;
      for (int i = 0; i < FILE_ANIMATIONS; i++) {
        PlayerAnimation kind = kinds[i];
        String first = tokens.next();
        if (first.isEmpty()) {
          if (kind.ordinal() < PlayerAnimation.TORSO_GETFLAG.ordinal())
            throw new Md3FormatException(
                "Animation config ends after " + i + " of at least 25 animations");
          Animation gesture = animations.get(PlayerAnimation.TORSO_GESTURE);
          animations.put(
              kind,
              new Animation(
                  gesture.firstFrame(),
                  gesture.frameCount(),
                  gesture.loopFrames(),
                  gesture.framesPerSecond(),
                  false,
                  false));
          continue;
        }
        int firstFrame = TextTokenizer.atoi(first);
        if (kind == PlayerAnimation.LEGS_WALKCR)
          skip = firstFrame - animations.get(PlayerAnimation.TORSO_GESTURE).firstFrame();
        if (kind.ordinal() >= PlayerAnimation.LEGS_WALKCR.ordinal()
            && kind.ordinal() < PlayerAnimation.TORSO_GETFLAG.ordinal()) firstFrame -= skip;
        animations.put(kind, row(firstFrame, i));
      }
      return animations;
    }

    private Animation row(int firstFrame, int index) {
      String count = tokens.next();
      String loops = tokens.next();
      String rate = tokens.next();
      if (rate.isEmpty())
        throw new Md3FormatException("Animation " + index + " has fewer than four values");
      int frames = TextTokenizer.atoi(count);
      boolean reversed = frames < 0;
      float fps = TextTokenizer.atof(rate);
      if (fps == 0) fps = 1;
      if (!Float.isFinite(fps)) throw new Md3FormatException("Non-finite animation frame rate");
      return new Animation(
          firstFrame,
          reversed ? (frames == Integer.MIN_VALUE ? Integer.MAX_VALUE : -frames) : frames,
          TextTokenizer.atoi(loops),
          fps,
          reversed,
          false);
    }

    private void note(String message) {
      if (diagnostics.size() < MAX_DIAGNOSTICS) diagnostics.add(message);
    }
  }
}
