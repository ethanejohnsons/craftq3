package dev.bluevista.craftq3.engine.render;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Objects;
import java.util.Optional;

/**
 * One view the backend renders: the cgame's main refdef or a portal/mirror view derived from it.
 * Portal and mirror views are produced as data ({@link PortalViews}); the backend decides their
 * render-target resolution.
 *
 * @param basis view axes; mirror views have a reflected (left-handed) basis, so the backend must
 *     swap its front-face winding when {@link #mirrored()} is true
 * @param x viewport left in framebuffer pixels (main view; portal views inherit the parent's)
 * @param clipPlane for portal and mirror views, geometry behind this plane is clipped away
 * @param pvsOrigin where visibility is computed from (the portal camera for portal views)
 * @param portalSurface the BSP face that showed this view, or -1 for the main view
 * @param depth portal recursion depth, 0 for the main view
 */
public record ViewDefinition(
    Kind kind,
    Vec3f origin,
    ViewBasis basis,
    float fovX,
    float fovY,
    int x,
    int y,
    int width,
    int height,
    int timeMillis,
    int rdflags,
    AreaMask areaMask,
    Optional<Plane> clipPlane,
    Vec3f pvsOrigin,
    int portalSurface,
    int depth) {
  /** {@code RDF_NOWORLDMODEL}: the view shows only submitted entities (menus, HUD models). */
  public static final int RDF_NOWORLDMODEL = 1;

  /** {@code RDF_HYPERSPACE}: the teleport effect replaces the scene. */
  public static final int RDF_HYPERSPACE = 4;

  /** Default near plane distance in units. */
  public static final double NEAR = 4;

  /** Default far plane distance in units. */
  public static final double FAR = 131072;

  public enum Kind {
    MAIN,
    PORTAL,
    MIRROR
  }

  public ViewDefinition {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(origin, "origin");
    Objects.requireNonNull(basis, "basis");
    Objects.requireNonNull(areaMask, "areaMask");
    Objects.requireNonNull(clipPlane, "clipPlane");
    Objects.requireNonNull(pvsOrigin, "pvsOrigin");
    if (!(fovX > 0 && fovX < 180) || !(fovY > 0 && fovY < 180))
      throw new IllegalArgumentException("Invalid view field of view");
  }

  /** A main view without viewport (for tools and tests). */
  public static ViewDefinition of(Camera camera, float aspect) {
    float fovY =
        (float)
            Math.toDegrees(
                2 * Math.atan(Math.tan(Math.toRadians(camera.horizontalFov()) / 2) / aspect));
    return new ViewDefinition(
        Kind.MAIN,
        camera.origin(),
        camera.basis(),
        camera.horizontalFov(),
        fovY,
        0,
        0,
        0,
        0,
        0,
        0,
        AreaMask.NONE,
        Optional.empty(),
        camera.origin(),
        -1,
        0);
  }

  public boolean mirrored() {
    return basis.mirrored();
  }

  public boolean worldModel() {
    return (rdflags & RDF_NOWORLDMODEL) == 0;
  }

  public double seconds() {
    return timeMillis / 1000.0;
  }

  /** The view volume with the default near and far planes and the clip plane if any. */
  public Frustum frustum() {
    return Frustum.of(
        origin,
        basis,
        Math.tan(Math.toRadians(fovX) / 2),
        Math.tan(Math.toRadians(fovY) / 2),
        NEAR,
        FAR,
        clipPlane.orElse(null));
  }

  /** Yaw/pitch approximation for diagnostics (roll and reflection are not representable). */
  public Camera camera() {
    Vec3f f = basis.forward();
    return new Camera(
        origin,
        (float) Math.toDegrees(Math.atan2(f.y(), f.x())),
        (float) Math.toDegrees(Math.atan2(-f.z(), Math.hypot(f.x(), f.y()))),
        fovX);
  }

  /** This view seen through a portal or mirror. */
  public ViewDefinition through(PortalView portal, int surface) {
    Vec3f eye = portal.transformPoint(origin);
    ViewBasis transformed =
        new ViewBasis(
            portal.transformDirection(basis.forward()),
            portal.transformDirection(basis.right()),
            portal.transformDirection(basis.up()));
    return new ViewDefinition(
        portal.mirrored() ? Kind.MIRROR : Kind.PORTAL,
        eye,
        transformed,
        fovX,
        fovY,
        x,
        y,
        width,
        height,
        timeMillis,
        rdflags,
        areaMask,
        Optional.of(portal.destinationPlane()),
        // A reflected eye can sit inside solid space; a point just in front of the mirror sees
        // what the mirror shows.
        portal.mirrored()
            ? portal.sourceOrigin().multiplyAdd(1, portal.sourcePlane().normal())
            : portal.destinationOrigin(),
        surface,
        depth + 1);
  }
}
