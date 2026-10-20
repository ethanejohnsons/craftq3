package dev.bluevista.craftq3.engine.render;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.bsp.SurfaceType;
import dev.bluevista.craftq3.engine.formats.entity.BspEntity;
import java.util.List;
import java.util.Optional;

/**
 * The rigid or reflected transform from a portal surface to the view it shows. A point is expressed
 * in the source frame (origin and three axes on the portal surface) and re-emitted in the
 * destination frame (the portal camera); a mirror reflects across the surface plane instead.
 *
 * <p>Two constructions exist. {@link #find} links a portal surface to {@code misc_portal_surface}
 * and {@code misc_portal_camera} entities of the BSP, for static viewers without a cgame. {@link
 * #fromEntity} follows the cgame's {@code RT_PORTALSURFACE} entity, as the original renderer does
 * during play.
 */
public final class PortalView {
  /** Entities farther than this from a portal's plane or extent are not linked to it. */
  public static final float LINK_DISTANCE = 64;

  private static final float PLANAR_TOLERANCE = 0.125f;
  private static final float ROLL_UPRIGHT = 180;

  private final Plane sourcePlane;
  private final Plane destinationPlane;
  private final Vec3f sourceOrigin;
  private final Vec3f destinationOrigin;
  private final Vec3f[] sourceAxes;
  private final Vec3f[] destinationAxes;
  private final boolean mirrored;
  private final boolean animatedCamera;

  private PortalView(
      Plane sourcePlane,
      Plane destinationPlane,
      Vec3f sourceOrigin,
      Vec3f destinationOrigin,
      Vec3f[] sourceAxes,
      Vec3f[] destinationAxes,
      boolean mirrored,
      boolean animatedCamera) {
    this.sourcePlane = sourcePlane;
    this.destinationPlane = destinationPlane;
    this.sourceOrigin = sourceOrigin;
    this.destinationOrigin = destinationOrigin;
    this.sourceAxes = sourceAxes;
    this.destinationAxes = destinationAxes;
    this.mirrored = mirrored;
    this.animatedCamera = animatedCamera;
  }

  /** The portal surface plane; its normal faces the viewer. */
  public Plane sourcePlane() {
    return sourcePlane;
  }

  /** The clip plane of the derived view: geometry with negative distance is removed. */
  public Plane destinationPlane() {
    return destinationPlane;
  }

  public Vec3f sourceOrigin() {
    return sourceOrigin;
  }

  public Vec3f destinationOrigin() {
    return destinationOrigin;
  }

  public boolean mirrored() {
    return mirrored;
  }

  /** True when the map asks for a rotating portal camera (spawnflags 1 or 2). */
  public boolean animatedCamera() {
    return animatedCamera;
  }

  /** A mirror across {@code plane}, anchored at {@code origin} projected onto the plane. */
  public static PortalView mirror(Plane plane, Vec3f origin) {
    Vec3f onPlane = origin.multiplyAdd(-plane.signedDistance(origin), plane.normal());
    Vec3f[] axes = basisAxes(oriented(plane.normal().negate(), 0));
    return new PortalView(plane, plane, onPlane, onPlane, axes, axes, true, false);
  }

  /**
   * The view of a cgame portal entity ({@code RT_PORTALSURFACE}) near a portal surface. When the
   * entity's origin equals its old origin the surface is a mirror; otherwise the old origin is the
   * camera position and the entity axes its orientation (forward, left, up). A nonzero {@code
   * oldFrame} rotates the camera about its forward axis: by {@code frame} degrees per second, or by
   * {@code skinNumber} degrees when {@code frame} is zero.
   */
  public static Optional<PortalView> fromEntity(
      Plane plane,
      Vec3f origin,
      Vec3f oldOrigin,
      Vec3f forward,
      Vec3f left,
      Vec3f up,
      int frame,
      int oldFrame,
      int skinNumber,
      double seconds) {
    if (Math.abs(plane.signedDistance(origin)) > LINK_DISTANCE) return Optional.empty();
    if (origin.equals(oldOrigin)) return Optional.of(mirror(plane, origin));
    Vec3f normal = plane.normal();
    Vec3f surfaceOrigin = normal.scale(plane.distance());
    Vec3f side = perpendicular(normal);
    Vec3f[] source = {normal, side, normal.cross(side)};
    Vec3f cameraLeft = left.negate();
    Vec3f cameraForward = forward.negate();
    if (oldFrame != 0) {
      double degrees = frame != 0 ? seconds * frame : skinNumber;
      cameraLeft = rotate(cameraLeft, cameraForward, degrees);
    }
    Vec3f[] destination = {cameraForward, cameraLeft, cameraForward.cross(cameraLeft)};
    if (!up.isFinite() || destination[2].lengthSquared() < 0.5f) return Optional.empty();
    Plane clip = new Plane(forward, forward.dot(oldOrigin));
    return Optional.of(
        new PortalView(
            plane, clip, surfaceOrigin, oldOrigin, source, destination, false, oldFrame != 0));
  }

  /**
   * Links surface {@code surface} of {@code world} to the nearest {@code misc_portal_surface}
   * within {@link #LINK_DISTANCE} and, through its target, a {@code misc_portal_camera}. Without a
   * target the surface is a mirror. Malformed or missing entities give no portal.
   */
  public static Optional<PortalView> find(BspMap map, WorldGeometry world, int surface) {
    Optional<Plane> found = surfacePlane(map, world, surface);
    if (found.isEmpty()) return Optional.empty();
    Plane source = found.get();
    float[] bounds = new float[6];
    for (int k = 0; k < 6; k++) bounds[k] = world.surfaceBound(surface, k);
    Vec3f center =
        new Vec3f(
            (bounds[0] + bounds[3]) / 2, (bounds[1] + bounds[4]) / 2, (bounds[2] + bounds[5]) / 2);
    BspEntity nearest = null;
    Vec3f entityOrigin = null;
    double nearestRank = Double.POSITIVE_INFINITY;
    for (BspEntity entity : map.entities()) {
      if (!entity.getOrDefault("classname", "").equalsIgnoreCase("misc_portal_surface")) continue;
      Optional<Vec3f> parsed = vector(entity.getOrDefault("origin", "0 0 0"));
      if (parsed.isEmpty()) continue;
      Vec3f origin = parsed.get();
      double distance = Math.abs(source.signedDistance(origin));
      if (distance > LINK_DISTANCE
          || boundsDistanceSquared(origin, bounds) > LINK_DISTANCE * LINK_DISTANCE) continue;
      double rank = distance * distance + origin.subtract(center).lengthSquared();
      if (rank < nearestRank) {
        nearestRank = rank;
        nearest = entity;
        entityOrigin = origin;
      }
    }
    if (nearest == null) return Optional.empty();
    String targetName = nearest.getOrDefault("target", "");
    if (targetName.isBlank()) return Optional.of(mirror(source, entityOrigin));
    Optional<BspEntity> target =
        target(map.entities(), targetName)
            .filter(e -> e.getOrDefault("classname", "").equalsIgnoreCase("misc_portal_camera"));
    if (target.isEmpty()) return Optional.empty();
    BspEntity camera = target.get();
    Optional<Vec3f> destination = vector(camera.getOrDefault("origin", "0 0 0"));
    if (destination.isEmpty()) return Optional.empty();
    Vec3f forward;
    Optional<BspEntity> aimedAt = target(map.entities(), camera.getOrDefault("target", ""));
    if (aimedAt.isPresent()) {
      Optional<Vec3f> aim = vector(aimedAt.get().getOrDefault("origin", "0 0 0"));
      if (aim.isEmpty()) return Optional.empty();
      forward = aim.get().subtract(destination.get()).normalize();
      if (forward.lengthSquared() < 0.5f) return Optional.empty();
    } else {
      Optional<Vec3f> facing = entityForward(camera);
      if (facing.isEmpty()) return Optional.empty();
      forward = facing.get();
    }
    Optional<Float> roll = number(camera.getOrDefault("roll", "0"));
    int flags;
    try {
      flags = Integer.parseInt(camera.getOrDefault("spawnflags", "0").trim());
    } catch (NumberFormatException malformed) {
      return Optional.empty(); // An unreadable optional entity cannot invent a camera.
    }
    if (roll.isEmpty()) return Optional.empty();
    // Radiant's published camera convention: roll 0 is upside down; roll 180 is upright.
    ViewBasis destinationBasis = oriented(forward, roll.get() - ROLL_UPRIGHT);
    Vec3f sourceOrigin =
        entityOrigin.multiplyAdd(-source.signedDistance(entityOrigin), source.normal());
    return Optional.of(
        new PortalView(
            source,
            new Plane(forward, forward.dot(destination.get())),
            sourceOrigin,
            destination.get(),
            basisAxes(oriented(source.normal().negate(), 0)),
            basisAxes(destinationBasis),
            false,
            (flags & 3) != 0));
  }

  public Vec3f transformPoint(Vec3f point) {
    if (mirrored)
      return point.multiplyAdd(-2 * sourcePlane.signedDistance(point), sourcePlane.normal());
    return destinationOrigin.add(transformDirection(point.subtract(sourceOrigin)));
  }

  public Vec3f transformDirection(Vec3f direction) {
    if (mirrored)
      return direction.multiplyAdd(-2 * direction.dot(sourcePlane.normal()), sourcePlane.normal());
    return destinationAxes[0]
        .scale(direction.dot(sourceAxes[0]))
        .add(destinationAxes[1].scale(direction.dot(sourceAxes[1])))
        .add(destinationAxes[2].scale(direction.dot(sourceAxes[2])));
  }

  /** The camera seen through the portal; yaw/pitch cannot express roll or reflection. */
  public Camera camera(Camera original) {
    Vec3f forward = transformDirection(original.basis().forward());
    float yaw = (float) Math.toDegrees(Math.atan2(forward.y(), forward.x()));
    float pitch = (float) Math.toDegrees(Math.asin(Math.clamp(-forward.z(), -1, 1)));
    return new Camera(transformPoint(original.origin()), yaw, pitch, original.horizontalFov());
  }

  /** The full transformed basis, including roll and reflection. */
  public ViewBasis basis(ViewBasis original) {
    return new ViewBasis(
        transformDirection(original.forward()),
        transformDirection(original.right()),
        transformDirection(original.up()));
  }

  public ViewBasis basis(Camera camera) {
    return basis(camera.basis());
  }

  /** The plane of a planar portal surface, if its vertices lie within 1/8 unit of it. */
  public static Optional<Plane> surfacePlane(BspMap map, WorldGeometry world, int surface) {
    int first = world.surfaceFirstVertex(surface), count = world.surfaceVertexCount(surface);
    if (world.surfaceType(surface) == SurfaceType.PATCH || count < 3) return Optional.empty();
    float[] p = world.vertexArrays().positions();
    Vec3f point = new Vec3f(p[first * 3], p[first * 3 + 1], p[first * 3 + 2]);
    Vec3f normal =
        surface < map.faces().count()
            ? map.faces().normal(surface)
            : new Vec3f(
                world.vertexArrays().normals()[first * 3],
                world.vertexArrays().normals()[first * 3 + 1],
                world.vertexArrays().normals()[first * 3 + 2]);
    normal = normal.normalize();
    if (normal.lengthSquared() < 0.5f) return Optional.empty();
    Plane plane = new Plane(normal, normal.dot(point));
    for (int v = first; v < first + count; v++)
      if (Math.abs(plane.signedDistance(p[v * 3], p[v * 3 + 1], p[v * 3 + 2])) > PLANAR_TOLERANCE)
        return Optional.empty();
    return Optional.of(plane);
  }

  private static Vec3f[] basisAxes(ViewBasis basis) {
    return new Vec3f[] {basis.forward(), basis.right(), basis.up()};
  }

  /** A basis looking along {@code forward}, rolled clockwise by {@code rollDegrees}. */
  static ViewBasis oriented(Vec3f forward, double rollDegrees) {
    Vec3f referenceUp = Math.abs(forward.z()) > .999f ? new Vec3f(0, 1, 0) : new Vec3f(0, 0, 1);
    Vec3f right = forward.cross(referenceUp).normalize();
    Vec3f up = right.cross(forward);
    double angle = Math.toRadians(rollDegrees);
    float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
    return new ViewBasis(
        forward, right.scale(cos).add(up.scale(sin)), up.scale(cos).add(right.scale(-sin)));
  }

  /** A unit vector perpendicular to {@code normal}, from the axis it has least of. */
  static Vec3f perpendicular(Vec3f normal) {
    int axis = 0;
    float least = 1;
    for (int i = 0; i < 3; i++) {
      float magnitude = Math.abs(normal.get(i));
      if (magnitude < least) {
        least = magnitude;
        axis = i;
      }
    }
    Vec3f unit = new Vec3f(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0);
    float inverse = 1 / normal.lengthSquared();
    return unit.multiplyAdd(-normal.dot(unit) * inverse, normal).normalize();
  }

  /** Rotates {@code v} around unit {@code axis} by {@code degrees} (right-handed). */
  private static Vec3f rotate(Vec3f v, Vec3f axis, double degrees) {
    double angle = Math.toRadians(degrees);
    float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
    return v.scale(cos).add(axis.cross(v).scale(sin)).add(axis.scale(axis.dot(v) * (1 - cos)));
  }

  private static double boundsDistanceSquared(Vec3f point, float[] b) {
    double dx = Math.max(0, Math.max(b[0] - point.x(), point.x() - b[3]));
    double dy = Math.max(0, Math.max(b[1] - point.y(), point.y() - b[4]));
    double dz = Math.max(0, Math.max(b[2] - point.z(), point.z() - b[5]));
    return dx * dx + dy * dy + dz * dz;
  }

  private static Optional<BspEntity> target(List<BspEntity> entities, String name) {
    if (name == null || name.isBlank()) return Optional.empty();
    for (BspEntity entity : entities)
      if (name.equals(entity.getOrDefault("targetname", ""))) return Optional.of(entity);
    return Optional.empty();
  }

  private static Optional<Vec3f> entityForward(BspEntity entity) {
    float pitch = 0, yaw = 0;
    Optional<String> angles = entity.get("angles");
    if (angles.isPresent()) {
      String[] parts = angles.get().trim().split("\\s+");
      if (parts.length < 2 || parts.length > 3) return Optional.empty();
      Optional<Float> p = number(parts[0]), y = number(parts[1]);
      if (p.isEmpty() || y.isEmpty()) return Optional.empty();
      pitch = p.get();
      yaw = y.get();
    } else if (entity.get("angle").isPresent()) {
      Optional<Float> y = number(entity.get("angle").get());
      if (y.isEmpty()) return Optional.empty();
      yaw = y.get();
      if (yaw == -1) return Optional.of(new Vec3f(0, 0, 1));
      if (yaw == -2) return Optional.of(new Vec3f(0, 0, -1));
    }
    double p = Math.toRadians(pitch), y = Math.toRadians(yaw);
    return Optional.of(
        new Vec3f(
            (float) (Math.cos(y) * Math.cos(p)),
            (float) (Math.sin(y) * Math.cos(p)),
            (float) -Math.sin(p)));
  }

  private static Optional<Vec3f> vector(String text) {
    String[] parts = text.trim().split("\\s+");
    if (parts.length != 3) return Optional.empty();
    Optional<Float> x = number(parts[0]), y = number(parts[1]), z = number(parts[2]);
    if (x.isEmpty() || y.isEmpty() || z.isEmpty()) return Optional.empty();
    return Optional.of(new Vec3f(x.get(), y.get(), z.get()));
  }

  private static Optional<Float> number(String text) {
    try {
      float value = Float.parseFloat(text.trim());
      return Float.isFinite(value) ? Optional.of(value) : Optional.empty();
    } catch (NumberFormatException malformed) {
      return Optional.empty(); // Optional entity metadata: an unreadable value links nothing.
    }
  }
}
