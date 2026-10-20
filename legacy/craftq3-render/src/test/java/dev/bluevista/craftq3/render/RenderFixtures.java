package dev.bluevista.craftq3.render;

import dev.bluevista.craftq3.assets.bsp.BspFixture;
import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;

final class RenderFixtures {
  private RenderFixtures() {}

  static BspMap brushes(List<BspMap.Bounds> boxes) throws Exception {
    BspMap base = BspReader.read(BspFixture.map(false));
    List<BspMap.Plane> planes = new ArrayList<>();
    List<BspMap.BrushSide> sides = new ArrayList<>();
    List<BspMap.Brush> brushes = new ArrayList<>();
    List<BspMap.Effect> effects = new ArrayList<>();
    for (int i = 0; i < boxes.size(); i++) {
      BspMap.Bounds box = boxes.get(i);
      planes.add(new BspMap.Plane(new Vec3(-1, 0, 0), (float) -box.min().x()));
      planes.add(new BspMap.Plane(new Vec3(1, 0, 0), (float) box.max().x()));
      planes.add(new BspMap.Plane(new Vec3(0, -1, 0), (float) -box.min().y()));
      planes.add(new BspMap.Plane(new Vec3(0, 1, 0), (float) box.max().y()));
      planes.add(new BspMap.Plane(new Vec3(0, 0, -1), (float) -box.min().z()));
      planes.add(new BspMap.Plane(new Vec3(0, 0, 1), (float) box.max().z()));
      for (int j = 0; j < 6; j++) sides.add(new BspMap.BrushSide(i * 6 + j, 0));
      brushes.add(new BspMap.Brush(i * 6, 6, 0));
      effects.add(new BspMap.Effect("fog/test" + i, i, 0));
    }
    return new BspMap(
        base.entities(),
        base.textures(),
        planes,
        base.nodes(),
        base.leaves(),
        base.leafFaces(),
        base.leafBrushes(),
        List.of(new BspMap.Model(box(-100, -100, -100, 100, 100, 100), 0, 1, 0, boxes.size())),
        brushes,
        sides,
        base.vertices(),
        base.meshVertices(),
        effects,
        base.faces(),
        base.lightmaps(),
        base.lightVolumes(),
        base.visibility());
  }

  static BspMap.Bounds box(double a, double b, double c, double d, double e, double f) {
    return new BspMap.Bounds(new Vec3(a, b, c), new Vec3(d, e, f));
  }
}
