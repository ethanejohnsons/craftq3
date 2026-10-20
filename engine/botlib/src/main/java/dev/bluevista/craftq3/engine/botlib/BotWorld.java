package dev.bluevista.craftq3.engine.botlib;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.AssetLookup;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything botlib needs from the host, mirroring the original {@code botlib_import_t}. The game
 * host implements it over its collision world, file system and client command queue; botlib never
 * reaches the world any other way. Entity state is pushed through {@link BotLib#updateEntity}.
 *
 * <p>Implementations are called only from the thread that owns the {@link BotLib}.
 */
public interface BotWorld extends BotCollision {
  /** Message severities, numbered as the original {@code PRT_*} constants. */
  enum PrintType {
    MESSAGE(1),
    WARNING(2),
    ERROR(3),
    FATAL(4),
    EXIT(5);

    private final int id;

    PrintType(int id) {
      this.id = id;
    }

    public int id() {
      return id;
    }
  }

  /** An inline model's bounds and origin, as {@code BSPModelMinsMaxsOrigin} reports them. */
  record ModelBounds(Vec3f mins, Vec3f maxs, Vec3f origin) {
    public ModelBounds {
      Objects.requireNonNull(mins);
      Objects.requireNonNull(maxs);
      Objects.requireNonNull(origin);
    }

    /**
     * The original host's answer for a model with {@code angles}: unrotated bounds, or a cube of
     * the bounds' corner radius when any angle is nonzero. Origin is zero.
     */
    public static ModelBounds forAngles(Vec3f mins, Vec3f maxs, Vec3f angles) {
      if (angles.x() == 0 && angles.y() == 0 && angles.z() == 0)
        return new ModelBounds(mins, maxs, Vec3f.ZERO);
      float x = Math.max(Math.abs(mins.x()), Math.abs(maxs.x()));
      float y = Math.max(Math.abs(mins.y()), Math.abs(maxs.y()));
      float z = Math.max(Math.abs(mins.z()), Math.abs(maxs.z()));
      float radius = (float) Math.sqrt(x * x + y * y + z * z);
      return new ModelBounds(
          new Vec3f(-radius, -radius, -radius), new Vec3f(radius, radius, radius), Vec3f.ZERO);
    }
  }

  /** Traces against the single entity {@code entity} only ({@code EntityTrace}). */
  BspTrace entityTrace(Vec3f start, Vec3f mins, Vec3f maxs, Vec3f end, int entity, int contentMask);

  /** Whether {@code b} is in the potentially visible set of {@code a}. */
  boolean inPVS(Vec3f a, Vec3f b);

  /** The loaded map's entity lump text. */
  String bspEntityData();

  /** Bounds of inline model {@code model} rotated by {@code angles}; empty for unknown models. */
  Optional<ModelBounds> bspModelMinsMaxsOrigin(int model, Vec3f angles);

  /** Queues {@code command} as a client command from bot {@code client}. */
  void botClientCommand(int client, String command);

  /** The game's read-only file view (bot files, AAS files). */
  AssetLookup files();

  void print(PrintType type, String message);

  /**
   * Writes a file into the game's home directory, as the original {@code FS_FOpenFile} write mode
   * does. Returns false when the host does not allow writes, which is the default.
   */
  default boolean writeFile(String path, byte[] content) {
    return false;
  }
}
