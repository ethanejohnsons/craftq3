package dev.bluevista.craftq3.server;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.Map;

/** One inert original hurt entity for an explicitly requested local-map inventory admission. */
record MapLoadoutAdmission(BspMap map, PlayerLoadout loadout, Map<Integer, Integer> damageModels) {
  static MapLoadoutAdmission create(BspMap source, PlayerLoadout loadout) {
    java.util.Objects.requireNonNull(loadout);
    int damage = (loadout.health() > 100 ? 200 : 100) - loadout.health();
    if (damage == 0) return new MapLoadoutAdmission(source, loadout, Map.of());
    int model = source.models().size();
    if (model < 1 || model >= 256)
      throw new IllegalArgumentException("Map has no free inline model for loadout admission");
    var models = new ArrayList<>(source.models());
    models.add(
        new BspMap.Model(new BspMap.Bounds(new Vec3(-1, -1, -1), new Vec3(1, 1, 1)), 0, 0, 0, 0));
    var entities = new ArrayList<>(source.entities());
    entities.add(
        Map.of(
            "classname",
            "trigger_hurt",
            "model",
            "*" + model,
            "dmg",
            Integer.toString(damage),
            "spawnflags",
            "4"));
    var map =
        new BspMap(
            entities,
            source.textures(),
            source.planes(),
            source.nodes(),
            source.leaves(),
            source.leafFaces(),
            source.leafBrushes(),
            models,
            source.brushes(),
            source.brushSides(),
            source.vertices(),
            source.meshVertices(),
            source.effects(),
            source.faces(),
            source.lightmaps(),
            source.lightVolumes(),
            source.visibility());
    return new MapLoadoutAdmission(map, loadout, Map.of(model, damage));
  }
}
