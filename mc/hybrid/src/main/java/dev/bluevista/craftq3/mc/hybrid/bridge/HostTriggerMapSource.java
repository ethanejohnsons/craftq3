package dev.bluevista.craftq3.mc.hybrid.bridge;

import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.entity.BspEntity;
import dev.bluevista.craftq3.engine.game.map.GameMap;
import dev.bluevista.craftq3.engine.game.map.MapSource;
import dev.bluevista.craftq3.mc.hybrid.map.BspAugmenter;
import dev.bluevista.craftq3.mc.hybrid.map.EntityText;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Adds the {@link HostTriggers} to ordinary Quake maps, so a loadout carried in from the bridge can
 * be restored in a real match with the game's own health rules. The server keeps the original
 * checksum (clients load the unmodified BSP); the extra inline models and entities exist only on
 * the server. A map without {@link HostTriggers#MODELS} free inline model slots loads unchanged and
 * reports {@link #firstHostModel(GameMap)} as -1.
 */
public final class HostTriggerMapSource implements MapSource {
  private final MapSource base;

  public HostTriggerMapSource(MapSource base) {
    this.base = Objects.requireNonNull(base);
  }

  @Override
  public boolean exists(String name) {
    return base.exists(name);
  }

  @Override
  public GameMap load(String name) throws IOException {
    GameMap map = base.load(name);
    if (firstHostModel(map) > 0) return map;
    BspAugmenter augmenter = new BspAugmenter(map.bsp());
    if (!augmenter.fits(HostTriggers.MODELS)) return map;
    int first = augmenter.nextModel();
    for (int i = 0; i < HostTriggers.MODELS; i++)
      augmenter.boxModel(
          HostTriggers.mins(), HostTriggers.maxs(), HostTriggers.SHADER, HostTriggers.contents());
    float[] park = park(map.bsp());
    String text = map.entityText() + EntityText.write(HostTriggers.entities(first, park));
    augmenter.entityText(text);
    BspMap bsp = augmenter.build("maps/" + map.name() + ".bsp+host");
    return new GameMap(map.name(), bsp, text, map.checksum(), map.aas(), map.sources());
  }

  /** The inline model of the first host trigger in a loaded map, or -1 when it has none. */
  public static int firstHostModel(GameMap map) {
    List<BspEntity> entities = map.bsp().entities();
    outer:
    for (int i = 0; i + HostTriggers.MODELS + 2 <= entities.size(); i++) {
      BspEntity first = entities.get(i);
      if (!"trigger_hurt".equals(first.classname().orElse(""))) continue;
      String model = first.get("model").orElse("");
      if (!model.startsWith("*")) continue;
      int number;
      try {
        number = Integer.parseInt(model.substring(1));
      } catch (NumberFormatException notNumber) {
        continue;
      }
      for (int bit = 0; bit < ExternalDamage.TRIGGERS; bit++) {
        BspEntity e = entities.get(i + bit);
        if (!"trigger_hurt".equals(e.classname().orElse(""))
            || !Integer.toString(1 << bit).equals(e.get("dmg").orElse(""))
            || !("*" + (number + bit)).equals(e.get("model").orElse(""))) continue outer;
      }
      BspEntity give = entities.get(i + ExternalDamage.TRIGGERS + 1);
      if (HostTriggers.GIVE_NAME.equals(give.get("targetname").orElse(""))) return number;
    }
    return -1;
  }

  /** Below the map, clamped inside the coordinate range. */
  static float[] park(BspMap bsp) {
    float bottom = bsp.models().isEmpty() ? 0 : bsp.models().getFirst().mins().z();
    return new float[] {0, 0, Math.max(bottom - 8192, SyntheticMapSource.PARK_Z)};
  }
}
