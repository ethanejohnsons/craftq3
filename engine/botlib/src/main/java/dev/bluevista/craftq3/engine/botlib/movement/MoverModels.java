package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.botlib.BspEntities;
import dev.bluevista.craftq3.engine.botlib.aas.AasWorld;
import dev.bluevista.craftq3.engine.formats.aas.TravelType;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Brush-model classification from the map's entities ({@code BotSetBrushModelTypes}) and the mover
 * reachabilities that ride them ({@code AAS_NextModelReachability}). Move-to-goal uses it to decide
 * what a bot standing on an entity should do: ride a func_plat or func_bobbing, carry on over a
 * door or func_static, or report the entity as an obstacle.
 */
public final class MoverModels {
  /** Inline model numbers botlib tracks ({@code MAX_MODELS}). */
  public static final int MAX_MODELS = 256;

  public enum Type {
    NONE,
    FUNC_PLAT,
    FUNC_BOB,
    FUNC_DOOR,
    FUNC_STATIC
  }

  /** No movers: every entity a bot stands on is an obstacle. */
  public static final MoverModels NONE = new MoverModels(new Type[MAX_MODELS], List.of());

  private final Type[] types;
  private final List<AasWorld.Reachability> reachabilities;

  private MoverModels(Type[] types, List<AasWorld.Reachability> reachabilities) {
    this.types = types;
    this.reachabilities = reachabilities;
  }

  public static MoverModels of(BspEntities entities, AasWorld map) {
    return of(entities.list(), map);
  }

  public static MoverModels of(List<Map<String, String>> entities, AasWorld map) {
    Objects.requireNonNull(map);
    Type[] types = new Type[MAX_MODELS];
    for (var entity : entities) {
      String classname = entity.get("classname");
      String model = entity.get("model");
      if (classname == null || model == null) continue;
      int number = model.isEmpty() ? 0 : BspEntities.atoi(model.substring(1));
      if (number < 0 || number >= MAX_MODELS) continue;
      if (classname.equalsIgnoreCase("func_bobbing")) types[number] = Type.FUNC_BOB;
      else if (classname.equalsIgnoreCase("func_plat")) types[number] = Type.FUNC_PLAT;
      else if (classname.equalsIgnoreCase("func_door")) types[number] = Type.FUNC_DOOR;
      else if (classname.equalsIgnoreCase("func_static")) types[number] = Type.FUNC_STATIC;
    }
    return new MoverModels(types, map.reachabilities());
  }

  public Type type(int model) {
    if (model < 0 || model >= MAX_MODELS || types[model] == null) return Type.NONE;
    return types[model];
  }

  /**
   * The first elevator or bobbing reachability after {@code previous} that rides {@code model}, or
   * zero. Elevators store the model in the face field, bobbing platforms in its low 16 bits.
   */
  public int nextModelReachability(int previous, int model) {
    int start = previous <= 0 ? 1 : previous + 1;
    for (int i = start; i < reachabilities.size(); i++) {
      var reach = reachabilities.get(i);
      if (reach.is(TravelType.ELEVATOR) && reach.face() == model) return i;
      if (reach.is(TravelType.FUNC_BOB) && (reach.face() & 0xffff) == model) return i;
    }
    return 0;
  }
}
