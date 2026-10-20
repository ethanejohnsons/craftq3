package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.botlib.BspEntities;
import dev.bluevista.craftq3.engine.botlib.Contents;
import dev.bluevista.craftq3.engine.botlib.aas.AasAreaTrace;
import dev.bluevista.craftq3.engine.botlib.aas.AasPresenceTrace;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Measured AAS prediction for dry and swimming planar contacts, with bounded collision and event
 * handling.
 */
public final class AasMovementPredictor {
  public static final int HIT_GROUND = 1,
      LEAVE_GROUND = 2,
      ENTER_WATER = 4,
      ENTER_SLIME = 8,
      ENTER_LAVA = 16,
      HIT_GROUND_DAMAGE = 32,
      GAP = 64,
      TOUCH_JUMPPAD = 128,
      TOUCH_TELEPORTER = 256,
      ENTER_AREA = 512,
      HIT_GROUND_AREA = 1024,
      HIT_BOUNDING_BOX = 2048,
      TOUCH_CLUSTERPORTAL = 4096;

  /** Events the ordinary prediction stops at; the bounding-box event has its own entry point. */
  private static final int SUPPORTED_EVENTS = 8191 & ~HIT_BOUNDING_BOX;

  /** Area-trace events, checked along each frame's movement. */
  private static final int AREA_EVENTS =
      TOUCH_JUMPPAD | TOUCH_TELEPORTER | ENTER_AREA | TOUCH_CLUSTERPORTAL;

  /** Areas one frame's area trace reports at most. */
  private static final int MAX_FRAME_AREAS = 20;

  private static final AasPresenceTrace.Result CLEARED_TRACE =
      new AasPresenceTrace.Result(false, 0, Vec3f.ZERO, 0, 0, 0, 0, 0);

  /** Implementations retain ownership of geometry, entities and query budgets. */
  public interface World {
    AasPresenceTrace.Result trace(Vec3f start, Vec3f end, int presence, int ignoreEntity);

    Vec3f planeNormal(int plane);

    int area(Vec3f point);

    int presence(Vec3f point);

    int contents(Vec3f point);

    /** AAS area flags use different fluid bits from BSP point contents. */
    default int areaContents(int area) {
      return 0;
    }

    /**
     * The areas a point segment passes through with their entry points ({@code AAS_TraceAreas}).
     */
    default List<AasAreaTrace.Entry> traceAreas(Vec3f start, Vec3f end, int maxAreas) {
      return List.of();
    }
  }

  public record Settings(
      float friction,
      float stopSpeed,
      float gravity,
      float maxWalkSpeed,
      float maxCrouchSpeed,
      float walkAcceleration,
      float airAcceleration,
      float jumpVelocity,
      float maxSteepness,
      float maxStep,
      float maxBarrier,
      float waterFriction,
      float waterGravity,
      float maxSwimSpeed,
      float swimAcceleration) {
    public Settings {
      for (float value :
          new float[] {
            friction,
            stopSpeed,
            gravity,
            maxWalkSpeed,
            maxCrouchSpeed,
            walkAcceleration,
            airAcceleration,
            jumpVelocity,
            maxSteepness,
            maxStep,
            maxBarrier,
            waterFriction,
            waterGravity,
            maxSwimSpeed,
            swimAcceleration
          })
        if (!Float.isFinite(value) || value < 0 || value > 100_000)
          throw new IllegalArgumentException("Invalid movement setting");
      if (maxSteepness > 1) throw new IllegalArgumentException("Invalid ground steepness");
    }

    /** Preserves the dry predictor settings constructor with native water defaults. */
    public Settings(
        float friction,
        float stopSpeed,
        float gravity,
        float maxWalkSpeed,
        float maxCrouchSpeed,
        float walkAcceleration,
        float airAcceleration,
        float jumpVelocity,
        float maxSteepness,
        float maxStep,
        float maxBarrier) {
      this(
          friction,
          stopSpeed,
          gravity,
          maxWalkSpeed,
          maxCrouchSpeed,
          walkAcceleration,
          airAcceleration,
          jumpVelocity,
          maxSteepness,
          maxStep,
          maxBarrier,
          1,
          400,
          150,
          4);
    }

    public Settings(
        float friction,
        float stopSpeed,
        float gravity,
        float maxWalkSpeed,
        float maxCrouchSpeed,
        float walkAcceleration,
        float airAcceleration,
        float jumpVelocity,
        float maxSteepness,
        float maxStep) {
      this(
          friction,
          stopSpeed,
          gravity,
          maxWalkSpeed,
          maxCrouchSpeed,
          walkAcceleration,
          airAcceleration,
          jumpVelocity,
          maxSteepness,
          maxStep,
          33,
          1,
          400,
          150,
          4);
    }

    public static Settings defaults() {
      return from(Map.of());
    }

    public static Settings from(Map<String, String> variables) {
      Objects.requireNonNull(variables);
      return from(variables::get);
    }

    /** Reads the {@code phys_*} library variables; {@code variables} returns null when unset. */
    public static Settings from(UnaryOperator<String> variables) {
      Objects.requireNonNull(variables);
      return new Settings(
          value(variables, "phys_friction", 6),
          value(variables, "phys_stopspeed", 100),
          value(variables, "phys_gravity", 800),
          value(variables, "phys_maxwalkvelocity", 320),
          value(variables, "phys_maxcrouchvelocity", 100),
          value(variables, "phys_walkaccelerate", 10),
          value(variables, "phys_airaccelerate", 1),
          value(variables, "phys_jumpvel", 270),
          value(variables, "phys_maxsteepness", .7f),
          value(variables, "phys_maxstep", 19),
          value(variables, "phys_maxbarrier", 33),
          value(variables, "phys_waterfriction", 1),
          value(variables, "phys_watergravity", 400),
          value(variables, "phys_maxswimvelocity", 150),
          value(variables, "phys_swimaccelerate", 4));
    }

    private static final java.util.regex.Pattern NUMBER =
        java.util.regex.Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)");

    /**
     * The variable read like {@code atof}; a value the predictor cannot use (negative, beyond
     * 100000, or a steepness above 1) keeps the default rather than disabling prediction.
     */
    private static float value(UnaryOperator<String> variables, String name, float fallback) {
      String text = variables.apply(name);
      if (text == null || !NUMBER.matcher(text.strip()).lookingAt()) return fallback;
      float value = BspEntities.atof(text);
      float max = name.equals("phys_maxsteepness") ? 1 : 100_000;
      return Float.isFinite(value) && value >= 0 && value <= max ? value : fallback;
    }
  }

  public record Request(
      int entity,
      Vec3f origin,
      int presence,
      boolean onGround,
      Vec3f velocity,
      Vec3f commandMove,
      int commandFrames,
      int maxFrames,
      float frameTime,
      int stopEvents,
      int stopArea) {
    /** A request without an area stop event. */
    public Request(
        int entity,
        Vec3f origin,
        int presence,
        boolean onGround,
        Vec3f velocity,
        Vec3f commandMove,
        int commandFrames,
        int maxFrames,
        float frameTime,
        int stopEvents) {
      this(
          entity,
          origin,
          presence,
          onGround,
          velocity,
          commandMove,
          commandFrames,
          maxFrames,
          frameTime,
          stopEvents,
          0);
    }

    public Request {
      origin = Objects.requireNonNull(origin);
      velocity = Objects.requireNonNull(velocity);
      commandMove = Objects.requireNonNull(commandMove);
      if (presence != 2 && presence != 4)
        throw new IllegalArgumentException("Unknown movement presence");
      if (commandFrames < 0
          || commandFrames > 4096
          || maxFrames < 0
          || maxFrames > 4096
          || !Float.isFinite(frameTime)
          || frameTime <= 0
          || frameTime > 1)
        throw new IllegalArgumentException("Movement prediction budget out of range");
    }
  }

  /** Successful prediction supplies either the main movement trace or a cleared native trace. */
  public record Prediction(
      Vec3f endPosition,
      int endArea,
      Vec3f velocity,
      int presence,
      int stopEvent,
      int endContents,
      float time,
      int frames,
      Optional<AasPresenceTrace.Result> trace) {
    public Prediction {
      trace = Objects.requireNonNull(trace);
    }
  }

  private record Point(float x, float y, float z) {
    Point {
      if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
        throw new IllegalArgumentException("Movement arithmetic overflow");
    }

    static Point from(Vec3f value) {
      return new Point(value.x(), value.y(), value.z());
    }

    Point add(Point other) {
      return new Point(x + other.x, y + other.y, z + other.z);
    }

    Point scale(float amount) {
      return new Point(x * amount, y * amount, z * amount);
    }

    Point subtract(Point other) {
      return new Point(x - other.x, y - other.y, z - other.z);
    }

    float dot(Point other) {
      return x * other.x + y * other.y + z * other.z;
    }

    Point slide(Point normal) {
      // Call-boundary observations show the projection is recalculated after each component.
      Point value = new Point(x - normal.x() * dot(normal), y, z);
      value = new Point(value.x(), value.y() - normal.y() * value.dot(normal), value.z());
      return new Point(value.x(), value.y(), value.z() - normal.z() * value.dot(normal));
    }

    Point vertical(float value) {
      return new Point(x, y, value);
    }

    float horizontalLength() {
      return (float) Math.sqrt(x * x + y * y);
    }

    Vec3f vector() {
      return new Vec3f(x, y, z);
    }
  }

  private final World world;
  private final Settings settings;

  public AasMovementPredictor(World world, Settings settings) {
    this.world = Objects.requireNonNull(world);
    this.settings = Objects.requireNonNull(settings);
  }

  /**
   * Empty means the bounded collision loop could not resolve movement. Unsupported step planes and
   * other stop bits are not approximated.
   */
  public Optional<Prediction> predict(Request request) {
    return predict(request, null);
  }

  /**
   * Predicts target-box entry using the native mode's distinct collision behavior. Solid traces
   * bound each attempted segment, while this mode omits ordinary contact sliding. The request must
   * have no other stop events; the ordinary prediction API does not accept this event bit.
   */
  public Optional<Prediction> predictHitBox(Request request, Vec3f minimum, Vec3f maximum) {
    Objects.requireNonNull(request);
    if (request.stopEvents() != 0)
      throw new IllegalArgumentException("Target-box prediction does not combine stop events");
    return predict(request, new AasTargetBox(minimum, maximum));
  }

  private Optional<Prediction> predict(Request request, AasTargetBox target) {
    Objects.requireNonNull(request);
    // Unknown event bits are ignored, as the original tests only the bits it knows.
    int events = request.stopEvents() & SUPPORTED_EVENTS;
    Point position = Point.from(request.origin());
    position = position.vertical(position.z() + .25f);
    Point velocity = Point.from(request.velocity()), command = Point.from(request.commandMove());
    int presence = request.presence();
    boolean ground = request.onGround();
    int stepSuppressedUntil = 0;
    float dt = request.frameTime();
    float inverseTime = 1 / dt;
    velocity = velocity.scale(dt);
    for (int frame = 0; frame < request.maxFrames(); frame++) {
      Point frameStart = position;
      boolean swimming = swimming(position.vector());
      boolean commanded = frame < request.commandFrames();
      boolean impactGround = ground;
      Point move = commanded ? command : new Point(0, 0, 0);
      boolean crouching = ground && commanded && move.z() < -300;
      if (crouching) presence = 4;
      else if (presence == 4 && (world.presence(position.vector()) & 2) != 0) presence = 2;
      // The native predictor retains velocity as frame displacement between service calls.
      velocity =
          velocity.vertical(
              (float)
                  (velocity.z()
                      - (swimming ? settings.waterGravity() : settings.gravity()) * .1 * dt));
      if (ground || swimming) {
        Point speedVector = velocity.scale(inverseTime);
        float speed = speedVector.horizontalLength();
        if (speed != 0) {
          float kept =
              Math.max(
                      0,
                      speed
                          - Math.max(speed, settings.stopSpeed())
                              * dt
                              * (swimming ? settings.waterFriction() : settings.friction()))
                  / speed;
          speedVector = new Point(speedVector.x() * kept, speedVector.y() * kept, speedVector.z());
        }
        velocity = speedVector.scale(dt);
      }
      if (commanded && ground && !swimming && move.z() > 1) {
        velocity =
            velocity.vertical(
                (float) (settings.jumpVelocity() * dt + 5 - settings.gravity() * .1 * dt));
        ground = false;
        stepSuppressedUntil = frame + 3;
      }
      if (commanded) {
        Point speedVector = velocity.scale(inverseTime);
        float lengthSquared = swimming ? move.dot(move) : move.x() * move.x() + move.y() * move.y();
        float desired = (float) Math.sqrt(lengthSquared);
        if (desired != 0) {
          float inverseLength = 1 / desired;
          Point direction =
              new Point(move.x(), move.y(), swimming ? move.z() : 0).scale(inverseLength);
          // The native normalizer returns squared length times its rounded inverse root.
          desired = lengthSquared * inverseLength;
          desired =
              Math.min(
                  desired,
                  swimming
                      ? settings.maxSwimSpeed()
                      : crouching ? settings.maxCrouchSpeed() : settings.maxWalkSpeed());
          float remaining =
              desired
                  - (swimming
                      ? speedVector.dot(direction)
                      : speedVector.x() * direction.x() + speedVector.y() * direction.y());
          if (remaining > 0) {
            float acceleration =
                swimming
                    ? settings.swimAcceleration()
                    : ground ? settings.walkAcceleration() : settings.airAcceleration();
            speedVector =
                speedVector.add(direction.scale(Math.min(remaining, acceleration * dt * desired)));
          }
        }
        velocity = speedVector.scale(dt);
      }
      Point displacement = velocity;
      AasPresenceTrace.Result last;
      int contacts = 0;
      while (true) {
        Point destination = position.add(displacement);
        last = trace(position, destination, presence, request.entity());
        if (target != null) {
          var hit = target.clip(position.vector(), last.endPosition(), presence);
          if (hit.isPresent())
            return Optional.of(
                result(
                    Point.from(hit.get().endPosition()),
                    velocity.scale(inverseTime),
                    presence,
                    HIT_BOUNDING_BOX,
                    frame * dt,
                    frame,
                    hit));
          position = Point.from(last.endPosition());
          break;
        }
        position = Point.from(last.endPosition());
        contacts++;
        if (last.fraction() == 1) {
          if (contacts > 20) return Optional.empty();
          break;
        }
        Point normal = Point.from(world.planeNormal(last.planeNumber()));
        boolean floor =
            normal.x() == 0 && normal.y() == 0 && normal.z() == 1
                || normal.z() > settings.maxSteepness()
                    && Math.abs(normal.dot(normal) - 1) <= .0001f;
        boolean wall =
            normal.z() == 0
                && Math.abs(normal.x() * normal.x() + normal.y() * normal.y() - 1) <= .0001f;
        // A plane outside these classes (a non-unit normal in a damaged file) is still clipped
        // against below; it simply cannot be a floor, wall or step.
        if (wall && frame >= stepSuppressedUntil) {
          Point inside = position.subtract(normal.scale(.25f));
          var step =
              trace(
                  inside.vertical(inside.z() + settings.maxStep()),
                  inside,
                  presence,
                  request.entity());
          if (!step.startSolid()) {
            Vec3f stepNormal = world.planeNormal(step.planeNumber());
            Point stepPlane = Point.from(stepNormal);
            boolean upwardNormal =
                stepPlane.z() > 0 && Math.abs(stepPlane.dot(stepPlane) - 1) <= .0001f;
            // A downward or non-unit step plane cannot be stepped onto: keep the wall.
            if (upwardNormal && stepPlane.z() > settings.maxSteepness()) {
              position = position.vertical(step.endPosition().z());
              velocity = velocity.vertical(0);
              displacement = destination.subtract(inside).vertical(0);
              if (contacts > 20) return Optional.empty();
              continue;
            }
            // At/below the strict slope threshold, keep the original wall for clipping.
            // A clear trace can retain a horizontal default normal and is also rejected.
          }
        }
        // Native collision sliding clips the full current attempt in component order.
        if (floor) impactGround = true;
        float previousVertical = velocity.z();
        velocity = velocity.slide(normal);
        displacement = displacement.slide(normal);
        // Airborne contacts charge the negative pre-clip velocity when clipping slows
        // the fall. A start-solid AAS trace can retain a downward-facing raw plane.
        // Strict increases include float-rounding changes after repeated slides.
        float verticalImpact =
            impactGround
                ? velocity.z() - previousVertical
                : previousVertical < 0 && velocity.z() > previousVertical ? previousVertical : 0;
        if (!swimming
            && normal.z() != 0
            && (events & HIT_GROUND_DAMAGE) != 0
            && verticalImpact * verticalImpact > 4000) {
          // This early result retains frame displacement, unlike ordinary prediction results.
          return Optional.of(
              result(
                  position,
                  velocity,
                  presence,
                  HIT_GROUND_DAMAGE,
                  frame * dt,
                  frame,
                  Optional.of(last)));
        }
        if (contacts > 20) return Optional.empty();
      }
      if ((events & AREA_EVENTS) != 0) {
        var areaStop = areaEvent(request, events, frame, frameStart, position);
        if (areaStop != null)
          return Optional.of(
              new Prediction(
                  areaStop.point(),
                  areaStop.area(),
                  velocity.scale(inverseTime).vector(),
                  presence,
                  areaStop.event(),
                  0,
                  frame * dt,
                  frame,
                  Optional.of(last)));
      }
      int contents = 0, areaContents = 0, entryArea = 0;
      if (velocity.z() <= 10) {
        contents = world.contents(position.vertical(position.z() - 22).vector());
        entryArea = world.area(position.vector());
        areaContents = world.areaContents(entryArea);
      }
      int entryEvents =
          ((contents & 32) != 0 || (areaContents & 1) != 0 ? ENTER_WATER : 0)
              | ((contents & 16) != 0 || (areaContents & 4) != 0 ? ENTER_SLIME : 0)
              | ((contents & 8) != 0 || (areaContents & 2) != 0 ? ENTER_LAVA : 0);
      entryEvents &= events;
      if (entryEvents != 0) {
        return Optional.of(
            new Prediction(
                position.vector(),
                entryArea,
                velocity.scale(inverseTime).vector(),
                presence,
                entryEvents,
                contents,
                frame * dt,
                frame,
                Optional.of(CLEARED_TRACE)));
      }
      ground = onGround(position.vector(), presence, request.entity());
      if (ground
          && (events & HIT_GROUND_AREA) != 0
          && request.stopArea() != 0
          && world.area(position.vector()) == request.stopArea())
        return Optional.of(
            result(
                position,
                velocity.scale(inverseTime),
                presence,
                HIT_GROUND_AREA,
                frame * dt,
                frame,
                Optional.of(last)));
      int event = ground ? HIT_GROUND : LEAVE_GROUND;
      if ((events & event) != 0)
        return Optional.of(
            result(
                position,
                velocity.scale(inverseTime),
                presence,
                event,
                frame * dt,
                frame,
                Optional.of(last)));
      if (!ground && (events & GAP) != 0) {
        Point bottom = position.vertical(position.z() - (settings.maxBarrier() + 48));
        var gap = trace(position, bottom, 4, -1);
        if (!gap.startSolid()
            && gap.endPosition().z() < position.z() - settings.maxStep() - 1
            && (world.contents(bottom.vector()) & 32) == 0)
          return Optional.of(
              result(
                  frameStart,
                  velocity.scale(inverseTime),
                  presence,
                  GAP,
                  frame * dt,
                  frame,
                  Optional.of(last)));
      }
    }
    return Optional.of(
        result(
            position,
            velocity.scale(inverseTime),
            presence,
            0,
            request.maxFrames() * dt,
            request.maxFrames(),
            Optional.of(CLEARED_TRACE)));
  }

  private record AreaStop(int event, int area, Vec3f point) {}

  /**
   * The first area along this frame's movement that ends the prediction: the requested stop area, a
   * jump pad (not in the first frame), a teleporter or a cluster portal.
   */
  private AreaStop areaEvent(Request request, int events, int frame, Point from, Point to) {
    for (var entry : world.traceAreas(from.vector(), to.vector(), MAX_FRAME_AREAS)) {
      int area = entry.area();
      int contents = world.areaContents(area);
      if ((events & ENTER_AREA) != 0 && area == request.stopArea())
        return new AreaStop(ENTER_AREA, area, entry.point());
      if ((events & TOUCH_JUMPPAD) != 0 && frame != 0 && (contents & Contents.AREA_JUMPPAD) != 0)
        return new AreaStop(TOUCH_JUMPPAD, area, entry.point());
      if ((events & TOUCH_TELEPORTER) != 0 && (contents & Contents.AREA_TELEPORTER) != 0)
        return new AreaStop(TOUCH_TELEPORTER, area, entry.point());
      if ((events & TOUCH_CLUSTERPORTAL) != 0 && (contents & Contents.AREA_CLUSTERPORTAL) != 0)
        return new AreaStop(TOUCH_CLUSTERPORTAL, area, entry.point());
    }
    return null;
  }

  public boolean onGround(Vec3f origin, int presence, int ignoreEntity) {
    Point point = Point.from(Objects.requireNonNull(origin));
    var contact = trace(point, point.vertical(point.z() - 10), presence, ignoreEntity);
    return !contact.startSolid()
        && contact.fraction() < 1
        && world.planeNormal(contact.planeNumber()).z() >= settings.maxSteepness();
  }

  /** The engine's swimming predicate samples two units below the supplied origin. */
  public boolean swimming(Vec3f origin) {
    Point point = Point.from(Objects.requireNonNull(origin));
    return (world.contents(point.vertical(point.z() - 2).vector()) & (8 | 16 | 32)) != 0;
  }

  private Prediction result(
      Point point,
      Point velocity,
      int presence,
      int event,
      float time,
      int frames,
      Optional<AasPresenceTrace.Result> trace) {
    return new Prediction(
        point.vector(),
        world.area(point.vector()),
        velocity.vector(),
        presence,
        event,
        0,
        time,
        frames,
        trace);
  }

  private AasPresenceTrace.Result trace(Point start, Point end, int presence, int entity) {
    var result =
        Objects.requireNonNull(world.trace(start.vector(), end.vector(), presence, entity));
    if (!Float.isFinite(result.fraction()) || result.fraction() < 0 || result.fraction() > 1)
      throw new IllegalArgumentException("Invalid movement collision fraction");
    return result;
  }
}
