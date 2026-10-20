package dev.bluevista.craftq3.mc.hybrid.bridge;

import dev.bluevista.craftq3.engine.collision.Contents;
import dev.bluevista.craftq3.engine.formats.entity.BspEntity;
import dev.bluevista.craftq3.mc.hybrid.map.EntityText;
import java.util.ArrayList;
import java.util.List;

/**
 * The few map entities through which the host acts on the game with the game's own rules, added to
 * a map's entity string with one inline box model each:
 *
 * <ul>
 *   <li>{@link ExternalDamage#TRIGGERS} silent {@code trigger_hurt}s with damage 1, 2, 4, ... 128
 *       (incoming Minecraft damage, and lowering health when a loadout is restored);
 *   <li>a {@code trigger_multiple} firing a {@code target_give} of a suspended, targeted {@code
 *       item_health_mega} (raising health when a loadout is restored).
 * </ul>
 *
 * All of them are parked far below the map, where nothing touches them. To act on a client, the
 * host moves the triggers it needs onto the client and relinks them, runs exactly that client's
 * think (whose {@code G_TouchTriggers} touches them), then parks them again; no other entity runs
 * {@code G_TouchTriggers} in between. Triggers are {@code SVF_NOCLIENT}, so clients never see them,
 * and they are not world geometry, so neither movement nor prediction collides with them.
 */
public final class HostTriggers {
  /** The shader of the trigger brushes. */
  public static final String SHADER = "textures/common/trigger";

  /** Half the size of each trigger box: covers any distance a player moves in one command. */
  public static final float HALF_SIZE = 512;

  /** Inline models these entities use. */
  public static final int MODELS = ExternalDamage.TRIGGERS + 1;

  /** Target names of the healing chain. */
  public static final String GIVE_NAME = "craftq3_host_give";

  public static final String HEAL_ITEM_NAME = "craftq3_host_health";

  /** {@code trigger_hurt} spawnflag SILENT. */
  public static final int HURT_SILENT = 4;

  /** Item spawnflag SUSPENDED: no drop-to-floor trace at spawn. */
  public static final int ITEM_SUSPENDED = 1;

  private HostTriggers() {}

  /** The trigger box in model space. */
  public static float[] mins() {
    return new float[] {-HALF_SIZE, -HALF_SIZE, -HALF_SIZE};
  }

  public static float[] maxs() {
    return new float[] {HALF_SIZE, HALF_SIZE, HALF_SIZE};
  }

  public static int contents() {
    return Contents.TRIGGER;
  }

  /**
   * The entity blocks for triggers using inline models {@code *firstModel .. *(firstModel + MODELS
   * - 1)} (hurt triggers first, then the heal trigger), parked at {@code park}.
   */
  public static List<BspEntity> entities(int firstModel, float[] park) {
    String origin = EntityText.vector(park[0], park[1], park[2]);
    List<BspEntity> out = new ArrayList<>(MODELS + 2);
    for (int i = 0; i < ExternalDamage.TRIGGERS; i++)
      out.add(
          BspEntity.of(
              "classname",
              "trigger_hurt",
              "model",
              "*" + (firstModel + i),
              "origin",
              origin,
              "dmg",
              Integer.toString(1 << i),
              "spawnflags",
              Integer.toString(HURT_SILENT)));
    out.add(
        BspEntity.of(
            "classname",
            "trigger_multiple",
            "model",
            "*" + (firstModel + ExternalDamage.TRIGGERS),
            "origin",
            origin,
            "target",
            GIVE_NAME,
            "wait",
            "0.1"));
    out.add(
        BspEntity.of(
            "classname", "target_give",
            "targetname", GIVE_NAME,
            "target", HEAL_ITEM_NAME,
            "origin", origin));
    out.add(
        BspEntity.of(
            "classname",
            "item_health_mega",
            "targetname",
            HEAL_ITEM_NAME,
            "spawnflags",
            Integer.toString(ITEM_SUSPENDED),
            "origin",
            origin));
    return List.copyOf(out);
  }

  /** The inline model of hurt trigger {@code bit} ({@code 1 << bit} damage). */
  public static int hurtModel(int firstModel, int bit) {
    return firstModel + bit;
  }

  public static int healModel(int firstModel) {
    return firstModel + ExternalDamage.TRIGGERS;
  }
}
