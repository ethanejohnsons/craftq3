package dev.bluevista.craftq3.collision;

import dev.bluevista.craftq3.assets.bsp.BspFixture;
import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;

final class CollisionFixtures {
  private CollisionFixtures() {}

  record Box(Vec3 min, Vec3 max, int contents, int flags) {}

  static BspMap boxes(List<Box> boxes) throws Exception {
    var base = BspReader.read(BspFixture.map(false));
    var planes = new ArrayList<BspMap.Plane>();
    var textures = new ArrayList<BspMap.Texture>();
    var brushes = new ArrayList<BspMap.Brush>();
    var sides = new ArrayList<BspMap.BrushSide>();
    var front = new ArrayList<Integer>();
    var back = new ArrayList<Integer>();
    planes.add(new BspMap.Plane(new Vec3(1, 0, 0), 0));
    for (int i = 0; i < boxes.size(); i++) {
      Box box = boxes.get(i);
      textures.add(new BspMap.Texture("textures/fixture" + i, box.flags(), box.contents()));
      int first = planes.size();
      double[] lo = {box.min().x(), box.min().y(), box.min().z()},
          hi = {box.max().x(), box.max().y(), box.max().z()};
      for (int axis = 0; axis < 3; axis++) {
        planes.add(new BspMap.Plane(CollisionMath.AXES[axis].scale(-1), (float) -lo[axis]));
        planes.add(new BspMap.Plane(CollisionMath.AXES[axis], (float) hi[axis]));
        sides.add(new BspMap.BrushSide(first + axis * 2, i));
        sides.add(new BspMap.BrushSide(first + axis * 2 + 1, i));
      }
      brushes.add(new BspMap.Brush(i * 6, 6, i));
      if (box.max().x() >= 0) front.add(i);
      if (box.min().x() <= 0) back.add(i);
    }
    var bounds = new BspMap.Bounds(new Vec3(-4096, -4096, -4096), new Vec3(4096, 4096, 4096));
    var leafBrushes = new ArrayList<>(front);
    leafBrushes.addAll(back);
    return new BspMap(
        base.entities(),
        textures,
        planes,
        List.of(new BspMap.Node(0, -1, -2, bounds)),
        List.of(
            new BspMap.Leaf(0, 0, bounds, 0, 0, 0, front.size()),
            new BspMap.Leaf(1, 0, bounds, 0, 0, front.size(), back.size())),
        List.of(),
        leafBrushes,
        List.of(new BspMap.Model(bounds, 0, 0, 0, boxes.size())),
        brushes,
        sides,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new BspMap.Visibility(0, 0, new BspMap.Bytes(new byte[0])));
  }

  static BspMap withModels(BspMap map, List<BspMap.Model> models) {
    return new BspMap(
        map.entities(),
        map.textures(),
        map.planes(),
        map.nodes(),
        map.leaves(),
        map.leafFaces(),
        map.leafBrushes(),
        models,
        map.brushes(),
        map.brushSides(),
        map.vertices(),
        map.meshVertices(),
        map.effects(),
        map.faces(),
        map.lightmaps(),
        map.lightVolumes(),
        map.visibility());
  }

  static BspMap patch(int flags) throws Exception {
    var base = BspReader.read(BspFixture.map(true));
    return new BspMap(
        base.entities(),
        List.of(new BspMap.Texture("textures/curve", flags, Contents.SOLID)),
        base.planes(),
        base.nodes(),
        base.leaves().stream()
            .map(
                leaf ->
                    new BspMap.Leaf(
                        leaf.cluster(),
                        leaf.area(),
                        leaf.bounds(),
                        leaf.firstFace(),
                        leaf.faceCount(),
                        0,
                        0))
            .toList(),
        base.leafFaces(),
        List.of(),
        List.of(new BspMap.Model(base.models().getFirst().bounds(), 0, 1, 0, 0)),
        List.of(),
        List.of(),
        base.vertices(),
        base.meshVertices(),
        List.of(),
        base.faces(),
        base.lightmaps(),
        base.lightVolumes(),
        base.visibility());
  }
}
