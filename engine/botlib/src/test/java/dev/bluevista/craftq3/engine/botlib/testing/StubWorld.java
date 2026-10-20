package dev.bluevista.craftq3.engine.botlib.testing;

import dev.bluevista.craftq3.engine.botlib.BotWorld;
import dev.bluevista.craftq3.engine.botlib.BspTrace;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.AssetLookup;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Test support: a {@link BotWorld} over in-memory or mounted files with empty space everywhere.
 * Traces are clear unless {@link #tracer} is replaced; prints and client commands are recorded.
 */
public final class StubWorld implements BotWorld {
  private final AssetLookup files;
  private final String entities;
  private final Map<Integer, ModelBounds> models;
  public final List<String> printed = new ArrayList<>();
  public final List<String> commands = new ArrayList<>();
  public final Map<String, byte[]> written = new java.util.LinkedHashMap<>();
  public boolean allowWrites;
  public Function<Vec3f, Integer> contents = point -> 0;
  public Tracer tracer = (start, mins, maxs, end, pass, mask) -> BspTrace.clear(end);

  @FunctionalInterface
  public interface Tracer {
    BspTrace trace(Vec3f start, Vec3f mins, Vec3f maxs, Vec3f end, int pass, int mask);
  }

  public StubWorld(AssetLookup files, String entities, Map<Integer, ModelBounds> models) {
    this.files = files;
    this.entities = entities;
    this.models = Map.copyOf(models);
  }

  public StubWorld(AssetLookup files) {
    this(files, "", Map.of());
  }

  @Override
  public BspTrace trace(
      Vec3f start, Vec3f mins, Vec3f maxs, Vec3f end, int passEntity, int contentMask) {
    return tracer.trace(start, mins, maxs, end, passEntity, contentMask);
  }

  @Override
  public BspTrace entityTrace(
      Vec3f start, Vec3f mins, Vec3f maxs, Vec3f end, int entity, int contentMask) {
    return BspTrace.clear(end);
  }

  @Override
  public int pointContents(Vec3f point) {
    return contents.apply(point);
  }

  @Override
  public boolean inPVS(Vec3f a, Vec3f b) {
    return true;
  }

  @Override
  public String bspEntityData() {
    return entities;
  }

  @Override
  public Optional<ModelBounds> bspModelMinsMaxsOrigin(int model, Vec3f angles) {
    var bounds = models.get(model);
    return bounds == null
        ? Optional.empty()
        : Optional.of(ModelBounds.forAngles(bounds.mins(), bounds.maxs(), angles));
  }

  @Override
  public void botClientCommand(int client, String command) {
    commands.add(client + ":" + command);
  }

  @Override
  public AssetLookup files() {
    return files;
  }

  @Override
  public void print(PrintType type, String message) {
    printed.add(message);
  }

  @Override
  public boolean writeFile(String path, byte[] content) {
    if (!allowWrites) return false;
    written.put(path, content.clone());
    return true;
  }
}
