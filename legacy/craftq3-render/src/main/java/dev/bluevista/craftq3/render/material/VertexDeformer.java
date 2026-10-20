package dev.bluevista.craftq3.render.material;

import static dev.bluevista.craftq3.render.material.ShaderMath.*;

import dev.bluevista.craftq3.assets.bsp.BspMap.Vertex;
import dev.bluevista.craftq3.assets.shader.ShaderDefinition;
import dev.bluevista.craftq3.assets.shader.ShaderDefinition.*;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.render.RenderScene.Camera;
import java.util.ArrayList;
import java.util.List;

/** Applies ordered material deformations to an independent triangle-list surface. */
public final class VertexDeformer {
  private VertexDeformer() {}

  /**
   * Text and projection-shadow deforms require future entity state and leave vertices unchanged.
   */
  public static List<Vertex> deform(
      List<Vertex> vertices, ShaderDefinition shader, double seconds, Camera camera) {
    if (shader.deforms().isEmpty()) return vertices;
    return deform(vertices, shader, seconds, camera, PortalView.cameraBasis(camera));
  }

  /** Keeps portal roll and reflection in camera-facing sprites. */
  public static List<Vertex> deform(
      List<Vertex> vertices,
      ShaderDefinition shader,
      double seconds,
      Camera camera,
      PortalView.Basis cameraBasis) {
    if (shader.deforms().isEmpty()) return vertices;
    double time = shader.clampTime() > 0 ? Math.min(seconds, shader.clampTime()) : seconds;
    List<Vertex> current = vertices;
    for (Deform deform : shader.deforms()) {
      if (deform instanceof AutoSprite sprite) {
        current = autosprite(current, camera, sprite.axial(), cameraBasis);
      } else {
        List<Vertex> next = new ArrayList<>(current.size());
        for (Vertex vertex : current) next.add(deform(vertex, deform, time));
        current = List.copyOf(next);
      }
    }
    return current;
  }

  public static boolean isDynamic(ShaderDefinition shader) {
    for (Deform deform : shader.deforms()) {
      boolean dynamic =
          switch (deform) {
            case WaveDeform wave -> wave.wave().amplitude() != 0 && wave.wave().frequency() != 0;
            case MoveDeform move -> move.wave().amplitude() != 0 && move.wave().frequency() != 0;
            case NormalDeform normal -> normal.amplitude() != 0 && normal.frequency() != 0;
            case BulgeDeform bulge -> bulge.height() != 0 && bulge.speed() != 0;
            case AutoSprite ignored -> true;
            case TextDeform ignored -> true;
            case ProjectionShadow ignored -> true;
          };
      if (dynamic) return true;
    }
    return false;
  }

  private static Vertex deform(Vertex vertex, Deform deform, double time) {
    Vec3 position = vertex.position();
    Vec3 normal = vertex.normal();
    switch (deform) {
      case WaveDeform wave -> {
        double spread = wave.divisor() == 0 ? 100 : 1.0 / wave.divisor();
        double offset = (position.x() + position.y() + position.z()) * spread;
        position = position.add(normal.scale(ShaderMath.wave(wave.wave(), time, offset)));
      }
      case MoveDeform move ->
          position = position.add(move.direction().scale(StageEvaluator.wave(move.wave(), time)));
      case BulgeDeform bulge -> {
        double amount =
            Math.sin(vertex.textureUv().u() * bulge.width() + time * bulge.speed())
                * bulge.height();
        position = position.add(normal.scale(amount));
      }
      case NormalDeform deformNormal -> {
        double x = position.x() * 0.98;
        double y = position.y() * 0.98;
        double z = position.z() * 0.98;
        double t = time * deformNormal.frequency();
        Vec3 perturbation =
            new Vec3(noise(x, y, z, t), noise(x + 100, y, z, t), noise(x + 200, y, z, t));
        normal = normalize(normal.add(perturbation.scale(deformNormal.amplitude())));
      }
      case AutoSprite ignored ->
          throw new IllegalArgumentException("Sprite deformation needs a complete surface");
      case ProjectionShadow ignored -> {}
      case TextDeform ignored -> {}
    }
    return new Vertex(position, vertex.textureUv(), vertex.lightmapUv(), normal, vertex.rgba());
  }

  private static List<Vertex> autosprite(
      List<Vertex> vertices, Camera camera, boolean axial, PortalView.Basis cameraBasis) {
    List<Vertex> output = new ArrayList<>(vertices);
    // A BSP quad has two triangles. Non-quad trailing geometry is retained rather than guessed.
    for (int first = 0; first + 6 <= vertices.size(); first += 6) {
      List<Vertex> quad = vertices.subList(first, first + 6);
      List<Vertex> corners = new ArrayList<>(4);
      int[] indices = new int[6];
      for (int i = 0; i < 6; i++) {
        int index = -1;
        for (int j = 0; j < corners.size(); j++) {
          if (corners.get(j).position().equals(quad.get(i).position())) {
            index = j;
            break;
          }
        }
        if (index < 0) {
          index = corners.size();
          corners.add(quad.get(i));
        }
        indices[i] = index;
      }
      if (corners.size() != 4) continue;
      List<Vec3> deformed =
          axial ? axialCorners(corners, indices, camera) : facingCorners(corners, cameraBasis);
      Vec3 normal = normalize(subtract(camera.origin(), center(corners)));
      for (int i = 0; i < 6; i++) {
        Vertex source = quad.get(i);
        output.set(
            first + i,
            new Vertex(
                deformed.get(indices[i]),
                source.textureUv(),
                source.lightmapUv(),
                normal,
                source.rgba()));
      }
    }
    return List.copyOf(output);
  }

  private static List<Vec3> facingCorners(List<Vertex> vertices, PortalView.Basis cameraBasis) {
    Vec3 center = center(vertices);
    Vec3 delta = subtract(vertices.getFirst().position(), center);
    double radius = Math.sqrt(dot(delta, delta) / 2);
    Vec3 right = cameraBasis.right();
    Vec3 up = cameraBasis.up();
    float minS = Float.POSITIVE_INFINITY;
    float maxS = Float.NEGATIVE_INFINITY;
    float minT = Float.POSITIVE_INFINITY;
    float maxT = Float.NEGATIVE_INFINITY;
    for (Vertex vertex : vertices) {
      minS = Math.min(minS, vertex.textureUv().u());
      maxS = Math.max(maxS, vertex.textureUv().u());
      minT = Math.min(minT, vertex.textureUv().v());
      maxT = Math.max(maxT, vertex.textureUv().v());
    }
    if (maxS - minS < 1e-6 || maxT - minT < 1e-6) {
      return vertices.stream().map(Vertex::position).toList();
    }
    List<Vec3> output = new ArrayList<>(4);
    for (Vertex vertex : vertices) {
      double s = 2 * (vertex.textureUv().u() - minS) / (maxS - minS) - 1;
      double t = 1 - 2 * (vertex.textureUv().v() - minT) / (maxT - minT);
      output.add(center.add(right.scale(s * radius)).add(up.scale(t * radius)));
    }
    return output;
  }

  /** Keeps the major axis fixed and rotates each short edge around it to face the viewer. */
  private static List<Vec3> axialCorners(List<Vertex> vertices, int[] indices, Camera camera) {
    int[][] edges = new int[4][4];
    for (int triangle = 0; triangle < 2; triangle++) {
      for (int edge = 0; edge < 3; edge++) {
        int a = indices[triangle * 3 + edge];
        int b = indices[triangle * 3 + (edge + 1) % 3];
        edges[Math.min(a, b)][Math.max(a, b)]++;
      }
    }
    int start = -1;
    int end = -1;
    double shortest = Double.POSITIVE_INFINITY;
    for (int a = 0; a < 4; a++) {
      for (int b = a + 1; b < 4; b++) {
        if (edges[a][b] != 1) continue;
        Vec3 delta = subtract(vertices.get(a).position(), vertices.get(b).position());
        double distance = dot(delta, delta);
        if (distance < shortest) {
          shortest = distance;
          start = a;
          end = b;
        }
      }
    }
    if (start < 0) return vertices.stream().map(Vertex::position).toList();
    int[] opposite = new int[2];
    int found = 0;
    for (int i = 0; i < 4; i++) if (i != start && i != end) opposite[found++] = i;
    Vec3 firstCenter = vertices.get(start).position().add(vertices.get(end).position()).scale(0.5);
    Vec3 lastCenter =
        vertices.get(opposite[0]).position().add(vertices.get(opposite[1]).position()).scale(0.5);
    Vec3 axis = normalize(subtract(lastCenter, firstCenter));
    Vec3 oldSide =
        normalize(subtract(vertices.get(end).position(), vertices.get(start).position()));
    Vec3 view = normalize(subtract(camera.origin(), firstCenter.add(lastCenter).scale(0.5)));
    Vec3 side = normalize(cross(view, axis));
    if (dot(side, side) < 1e-8) side = oldSide;
    // Retain corner order and texture orientation as the view rotates around the locked axis.
    if (dot(side, oldSide) < 0) side = side.scale(-1);
    List<Vec3> output = new ArrayList<>(4);
    for (int i = 0; i < 4; i++) {
      Vec3 edgeCenter = i == start || i == end ? firstCenter : lastCenter;
      double width = dot(subtract(vertices.get(i).position(), edgeCenter), oldSide);
      output.add(edgeCenter.add(side.scale(width)));
    }
    return output;
  }

  private static Vec3 center(List<Vertex> vertices) {
    Vec3 result = ZERO;
    for (Vertex vertex : vertices) result = result.add(vertex.position());
    return result.scale(1.0 / vertices.size());
  }
}
