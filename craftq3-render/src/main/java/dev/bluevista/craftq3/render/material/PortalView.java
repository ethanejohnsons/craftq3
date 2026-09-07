package dev.bluevista.craftq3.render.material;

import static dev.bluevista.craftq3.render.material.ShaderMath.*;

import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.render.RenderScene;
import dev.bluevista.craftq3.render.RenderScene.Camera;
import dev.bluevista.craftq3.render.RenderScene.Surface;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Static misc_portal_surface/camera linkage, expressed as a rigid or reflected coordinate
 * transform. This does not render recursively or replace the future cgame entity system.
 */
public final class PortalView {
  private final Plane sourcePlane;
  private final Plane destinationPlane;
  private final Vec3 sourceOrigin;
  private final Vec3 destinationOrigin;
  private final Basis sourceBasis;
  private final Basis destinationBasis;
  private final boolean mirrored;
  private final boolean animatedCamera;

  private PortalView(
      Plane sourcePlane,
      Plane destinationPlane,
      Vec3 sourceOrigin,
      Vec3 destinationOrigin,
      Basis sourceBasis,
      Basis destinationBasis,
      boolean mirrored,
      boolean animatedCamera) {
    this.sourcePlane = sourcePlane;
    this.destinationPlane = destinationPlane;
    this.sourceOrigin = sourceOrigin;
    this.destinationOrigin = destinationOrigin;
    this.sourceBasis = sourceBasis;
    this.destinationBasis = destinationBasis;
    this.mirrored = mirrored;
    this.animatedCamera = animatedCamera;
  }

  /** Points with a nonnegative signed distance are on the retained side of a clipping plane. */
  public record Plane(Vec3 normal, double distance) {
    public double signedDistance(Vec3 point) {
      return dot(normal, point) - distance;
    }
  }

  /**
   * Right is the screen-right axis, not Q3's left axis. Reflected bases retain their handedness.
   */
  public record Basis(Vec3 forward, Vec3 right, Vec3 up) {}

  public Plane sourcePlane() {
    return sourcePlane;
  }

  public Plane destinationPlane() {
    return destinationPlane;
  }

  public Vec3 sourceOrigin() {
    return sourceOrigin;
  }

  public Vec3 destinationOrigin() {
    return destinationOrigin;
  }

  public boolean mirrored() {
    return mirrored;
  }

  /** True when a map requests camera rotation that the static BSP viewer cannot time like cgame. */
  public boolean animatedCamera() {
    return animatedCamera;
  }

  /**
   * Resolves an entity within 64 units of a planar surface. The caller selects portal materials;
   * arbitrary surfaces are not independently promoted to portals by this helper.
   */
  public static Optional<PortalView> find(RenderScene scene, Surface surface) {
    Optional<Plane> plane = surfacePlane(scene, surface);
    if (scene.bsp() == null || plane.isEmpty()) return Optional.empty();
    Plane source = plane.orElseThrow();
    Map<String, String> nearest = null;
    Vec3 entityOrigin = null;
    double nearestDistance = Double.POSITIVE_INFINITY;
    for (Map<String, String> entity : scene.bsp().entities()) {
      if (!entity.getOrDefault("classname", "").equalsIgnoreCase("misc_portal_surface")) continue;
      try {
        Vec3 origin = vector(entity.getOrDefault("origin", "0 0 0"));
        double distance = Math.abs(source.signedDistance(origin));
        // Limit both plane distance and finite face extent, avoiding a remote coplanar portal.
        if (distance > 64 || boundsDistanceSquared(origin, surface) > 64 * 64) continue;
        Vec3 center = surface.bounds().min().add(surface.bounds().max()).scale(.5);
        double rank = distance * distance + dot(subtract(origin, center), subtract(origin, center));
        if (rank < nearestDistance) {
          nearestDistance = rank;
          nearest = entity;
          entityOrigin = origin;
        }
      } catch (IllegalArgumentException ignored) {
        // An invalid optional entity cannot make the otherwise valid BSP viewer fail.
      }
    }
    if (nearest == null) return Optional.empty();
    Vec3 sourceOrigin =
        entityOrigin.add(source.normal().scale(-source.signedDistance(entityOrigin)));
    Basis sourceBasis = oriented(source.normal().scale(-1), 0);
    String targetName = nearest.getOrDefault("target", "");
    if (targetName.isBlank()) {
      return Optional.of(
          new PortalView(
              source, source, sourceOrigin, sourceOrigin, sourceBasis, sourceBasis, true, false));
    }
    Optional<Map<String, String>> target =
        target(scene.bsp().entities(), targetName)
            .filter(
                entity ->
                    entity.getOrDefault("classname", "").equalsIgnoreCase("misc_portal_camera"));
    if (target.isEmpty()) return Optional.empty();
    try {
      Map<String, String> entity = target.orElseThrow();
      Vec3 destination = vector(entity.getOrDefault("origin", "0 0 0"));
      Vec3 forward;
      Optional<Map<String, String>> aimedAt = target(scene.bsp().entities(), entity.get("target"));
      if (aimedAt.isPresent()) {
        forward =
            normalize(
                subtract(
                    vector(aimedAt.orElseThrow().getOrDefault("origin", "0 0 0")), destination));
        if (dot(forward, forward) < .5) return Optional.empty();
      } else {
        forward = entityForward(entity);
      }
      // Radiant's published camera convention: roll 0 is upside down; roll 180 is upright.
      double roll = number(entity.getOrDefault("roll", "0")) - 180;
      Basis destinationBasis = oriented(forward, roll);
      Plane destinationPlane = new Plane(forward, dot(forward, destination));
      int flags = Integer.parseInt(entity.getOrDefault("spawnflags", "0"));
      return Optional.of(
          new PortalView(
              source,
              destinationPlane,
              sourceOrigin,
              destination,
              sourceBasis,
              destinationBasis,
              false,
              (flags & 3) != 0));
    } catch (IllegalArgumentException ignored) {
      return Optional.empty();
    }
  }

  public Vec3 transformPoint(Vec3 point) {
    if (mirrored)
      return point.add(sourcePlane.normal().scale(-2 * sourcePlane.signedDistance(point)));
    return destinationOrigin.add(transformDirection(subtract(point, sourceOrigin)));
  }

  public Vec3 transformDirection(Vec3 direction) {
    if (mirrored)
      return direction.add(sourcePlane.normal().scale(-2 * dot(direction, sourcePlane.normal())));
    return destinationBasis
        .forward()
        .scale(dot(direction, sourceBasis.forward()))
        .add(destinationBasis.right().scale(dot(direction, sourceBasis.right())))
        .add(destinationBasis.up().scale(dot(direction, sourceBasis.up())));
  }

  /**
   * Camera angles are useful for visibility and diagnostics. Rendering must use basis(camera) too:
   * yaw/pitch alone cannot represent a mirrored basis or a rolled camera.
   */
  public Camera camera(Camera original) {
    Vec3 forward = forward(original);
    float yaw = (float) Math.toDegrees(Math.atan2(forward.y(), forward.x()));
    float pitch = (float) Math.toDegrees(Math.asin(Math.clamp(-forward.z(), -1, 1)));
    return new Camera(transformPoint(original.origin()), yaw, pitch, original.horizontalFov());
  }

  public Basis basis(Camera camera) {
    Basis original = cameraBasis(camera);
    return new Basis(
        transformDirection(original.forward()),
        transformDirection(original.right()),
        transformDirection(original.up()));
  }

  public Vec3 forward(Camera camera) {
    return transformDirection(cameraBasis(camera).forward());
  }

  public Vec3 right(Camera camera) {
    return transformDirection(cameraBasis(camera).right());
  }

  public Vec3 up(Camera camera) {
    return transformDirection(cameraBasis(camera).up());
  }

  public static Basis cameraBasis(Camera camera) {
    double yaw = Math.toRadians(camera.yaw());
    double pitch = Math.toRadians(camera.pitch());
    Vec3 forward =
        new Vec3(
            Math.cos(yaw) * Math.cos(pitch), Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch));
    Vec3 right = new Vec3(Math.sin(yaw), -Math.cos(yaw), 0);
    return new Basis(forward, right, cross(right, forward));
  }

  private static Basis oriented(Vec3 forward, double rollDegrees) {
    Vec3 referenceUp = Math.abs(forward.z()) > .999 ? new Vec3(0, 1, 0) : new Vec3(0, 0, 1);
    Vec3 right = normalize(cross(forward, referenceUp));
    Vec3 up = cross(right, forward);
    double angle = Math.toRadians(rollDegrees);
    Vec3 rolledRight = right.scale(Math.cos(angle)).add(up.scale(Math.sin(angle)));
    Vec3 rolledUp = up.scale(Math.cos(angle)).add(right.scale(-Math.sin(angle)));
    return new Basis(forward, rolledRight, rolledUp);
  }

  private static Optional<Plane> surfacePlane(RenderScene scene, Surface surface) {
    if (surface.type() == 2
        || surface.vertexCount() < 3
        || surface.firstVertex() < 0
        || (long) surface.firstVertex() + surface.vertexCount() > scene.vertices().size()) {
      return Optional.empty();
    }
    Vec3 first = scene.vertices().get(surface.firstVertex()).position();
    Vec3 normal = scene.vertices().get(surface.firstVertex()).normal();
    if (scene.bsp() != null && surface.id() >= 0 && surface.id() < scene.bsp().faces().size()) {
      normal = scene.bsp().faces().get(surface.id()).normal();
    }
    normal = normalize(normal);
    if (dot(normal, normal) < .5) return Optional.empty();
    Plane plane = new Plane(normal, dot(normal, first));
    for (int i = surface.firstVertex(); i < surface.firstVertex() + surface.vertexCount(); i++) {
      if (Math.abs(plane.signedDistance(scene.vertices().get(i).position())) > .125) {
        return Optional.empty();
      }
    }
    return Optional.of(plane);
  }

  private static double boundsDistanceSquared(Vec3 point, Surface surface) {
    Vec3 min = surface.bounds().min();
    Vec3 max = surface.bounds().max();
    double dx = Math.max(0, Math.max(min.x() - point.x(), point.x() - max.x()));
    double dy = Math.max(0, Math.max(min.y() - point.y(), point.y() - max.y()));
    double dz = Math.max(0, Math.max(min.z() - point.z(), point.z() - max.z()));
    return dx * dx + dy * dy + dz * dz;
  }

  private static Optional<Map<String, String>> target(
      List<Map<String, String>> entities, String name) {
    if (name == null || name.isBlank()) return Optional.empty();
    return entities.stream().filter(entity -> name.equals(entity.get("targetname"))).findFirst();
  }

  private static Vec3 entityForward(Map<String, String> entity) {
    double pitch = 0;
    double yaw = 0;
    if (entity.containsKey("angles")) {
      String[] angles = entity.get("angles").trim().split("\\s+");
      if (angles.length < 2 || angles.length > 3)
        throw new IllegalArgumentException("Invalid portal angles");
      pitch = number(angles[0]);
      yaw = number(angles[1]);
    } else if (entity.containsKey("angle")) {
      yaw = number(entity.get("angle"));
      if (yaw == -1) return new Vec3(0, 0, 1);
      if (yaw == -2) return new Vec3(0, 0, -1);
    }
    double p = Math.toRadians(pitch);
    double y = Math.toRadians(yaw);
    return new Vec3(Math.cos(y) * Math.cos(p), Math.sin(y) * Math.cos(p), -Math.sin(p));
  }

  private static Vec3 vector(String text) {
    String[] parts = text.trim().split("\\s+");
    if (parts.length != 3) throw new IllegalArgumentException("Invalid entity vector");
    return new Vec3(number(parts[0]), number(parts[1]), number(parts[2]));
  }

  private static double number(String text) {
    double value = Double.parseDouble(text);
    if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite entity number");
    return value;
  }
}
