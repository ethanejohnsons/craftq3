package dev.bluevista.craftq3.engine.game.bot;

import dev.bluevista.craftq3.engine.botlib.BotWorld;
import dev.bluevista.craftq3.engine.botlib.BspTrace;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.AssetLookup;
import dev.bluevista.craftq3.engine.game.world.EntityWorld;
import dev.bluevista.craftq3.engine.game.world.GameTrace;
import java.util.Objects;
import java.util.Optional;

/**
 * Botlib's {@link BotWorld} over the server ({@code sv_bot.c}'s {@code BotImport_*}): world traces
 * with the server's entity clipping, single-entity traces, point contents including entities, PVS,
 * the map's entity string and inline model bounds. As the original, trace results carry the surface
 * flags in {@code surface.value} and no contents.
 */
final class ServerBotWorld implements BotWorld {
  private final BotHost host;
  private final GameTrace trace = new GameTrace();
  private final float[] start = new float[3];
  private final float[] end = new float[3];
  private final float[] mins = new float[3];
  private final float[] maxs = new float[3];

  ServerBotWorld(BotHost host) {
    this.host = Objects.requireNonNull(host);
  }

  private EntityWorld world() {
    return host.world();
  }

  private void load(Vec3f s, Vec3f lo, Vec3f hi, Vec3f e) {
    s.copyTo(start);
    e.copyTo(end);
    (lo == null ? Vec3f.ZERO : lo).copyTo(mins);
    (hi == null ? Vec3f.ZERO : hi).copyTo(maxs);
  }

  private BspTrace result() {
    return new BspTrace(
        trace.allSolid,
        trace.startSolid,
        trace.fraction,
        Vec3f.of(trace.endPos),
        Vec3f.of(trace.planeNormal),
        trace.planeDist,
        0,
        0,
        "",
        0,
        trace.surfaceFlags,
        0,
        trace.entityNum);
  }

  @Override
  public BspTrace trace(
      Vec3f start, Vec3f mins, Vec3f maxs, Vec3f end, int passEntity, int contentMask) {
    load(start, mins, maxs, end);
    world()
        .trace(trace, this.start, this.mins, this.maxs, this.end, passEntity, contentMask, false);
    return result();
  }

  @Override
  public BspTrace entityTrace(
      Vec3f start, Vec3f mins, Vec3f maxs, Vec3f end, int entity, int contentMask) {
    load(start, mins, maxs, end);
    world()
        .clipToEntity(
            trace, this.start, this.mins, this.maxs, this.end, entity, contentMask, false);
    return result();
  }

  @Override
  public int pointContents(Vec3f point) {
    point.copyTo(start);
    return world().pointContents(start, -1);
  }

  @Override
  public boolean inPVS(Vec3f a, Vec3f b) {
    a.copyTo(start);
    b.copyTo(end);
    return world().inPvs(start, end, false);
  }

  @Override
  public String bspEntityData() {
    return host.map().entityText();
  }

  @Override
  public Optional<ModelBounds> bspModelMinsMaxsOrigin(int model, Vec3f angles) {
    if (model < 0 || model >= world().collision().modelCount()) return Optional.empty();
    world().collision().modelBounds(model, mins, maxs);
    return Optional.of(ModelBounds.forAngles(Vec3f.of(mins), Vec3f.of(maxs), angles));
  }

  @Override
  public void botClientCommand(int client, String command) {
    host.botClientCommand(client, command);
  }

  @Override
  public AssetLookup files() {
    return host.files();
  }

  @Override
  public void print(PrintType type, String message) {
    String prefix =
        switch (type) {
          case MESSAGE -> "";
          case WARNING -> "^3Warning: ";
          case ERROR -> "^1Error: ";
          case FATAL -> "^1Fatal: ";
          case EXIT -> "^1Exit: ";
        };
    host.print(prefix + message);
  }
}
