package dev.bluevista.craftq3.engine.render;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Derives the extra views a frame needs: one per visible portal or mirror surface facing the
 * viewer, linked to the cgame's portal entities when present and to the map's portal entities
 * otherwise. Surfaces sharing a plane and destination share one view. As in the original renderer,
 * views are not derived from portal views beyond {@code maxDepth}.
 */
public final class PortalViews {
  public static final int DEFAULT_MAX_DEPTH = 1;

  private PortalViews() {}

  /** A cgame {@code RT_PORTALSURFACE} entity: axes are Q3's forward, left, up. */
  public record PortalEntity(
      Vec3f origin,
      Vec3f oldOrigin,
      Vec3f forward,
      Vec3f left,
      Vec3f up,
      int frame,
      int oldFrame,
      int skinNumber) {
    public PortalEntity {
      Objects.requireNonNull(origin, "origin");
      Objects.requireNonNull(oldOrigin, "oldOrigin");
      Objects.requireNonNull(forward, "forward");
      Objects.requireNonNull(left, "left");
      Objects.requireNonNull(up, "up");
    }
  }

  /** A derived view with the portal it passes through. */
  public record Derived(ViewDefinition view, PortalView portal, int surface) {}

  public static List<Derived> collect(
      RenderScene scene,
      ViewDefinition view,
      VisibleSet visible,
      List<PortalEntity> entities,
      int maxDepth) {
    List<Derived> result = new ArrayList<>();
    if (view.depth() >= maxDepth || !view.worldModel()) return result;
    for (int surface : scene.portalSurfaces()) {
      if (!visible.surfaceVisible(surface)) continue;
      Optional<Plane> plane = PortalView.surfacePlane(scene.bsp(), scene.world(), surface);
      if (plane.isEmpty() || plane.get().signedDistance(view.origin()) < 0) continue;
      Optional<PortalView> portal =
          entities.isEmpty()
              ? scene.staticPortal(surface)
              : linked(plane.get(), entities, view.seconds());
      if (portal.isEmpty() || duplicate(result, portal.get())) continue;
      result.add(new Derived(view.through(portal.get(), surface), portal.get(), surface));
    }
    return result;
  }

  /** The nearest entity within {@link PortalView#LINK_DISTANCE} of the plane, as Q3 links them. */
  private static Optional<PortalView> linked(
      Plane plane, List<PortalEntity> entities, double seconds) {
    PortalEntity best = null;
    float bestDistance = Float.POSITIVE_INFINITY;
    for (PortalEntity entity : entities) {
      float distance = Math.abs(plane.signedDistance(entity.origin()));
      if (distance <= PortalView.LINK_DISTANCE && distance < bestDistance) {
        best = entity;
        bestDistance = distance;
      }
    }
    if (best == null) return Optional.empty();
    return PortalView.fromEntity(
        plane,
        best.origin(),
        best.oldOrigin(),
        best.forward(),
        best.left(),
        best.up(),
        best.frame(),
        best.oldFrame(),
        best.skinNumber(),
        seconds);
  }

  private static boolean duplicate(List<Derived> views, PortalView portal) {
    for (Derived derived : views) {
      PortalView other = derived.portal();
      if (other.sourcePlane().equals(portal.sourcePlane())
          && other.destinationOrigin().equals(portal.destinationOrigin())
          && other.mirrored() == portal.mirrored()) return true;
    }
    return false;
  }
}
