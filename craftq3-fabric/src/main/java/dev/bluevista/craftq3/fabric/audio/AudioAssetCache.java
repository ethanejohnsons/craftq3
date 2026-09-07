package dev.bluevista.craftq3.fabric.audio;

import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Asset ownership under the backend lock; retirement batches isolate deferred executor cleanup. */
final class AudioAssetCache<B> {
  static final class Retirement<B> {
    private final List<B> buffers;
    private final long bytes;

    private Retirement(List<B> buffers, long bytes) {
      this.buffers = buffers;
      this.bytes = bytes;
    }
  }

  private record Key(int sound, boolean mono) {}

  private final int maxSounds;
  private final long maxPcmBytes, maxNativeBytes;
  private final List<PcmSound> sounds = new ArrayList<>();
  private final Map<String, Integer> names = new HashMap<>();
  private final Map<Key, B> buffers = new HashMap<>();
  private final Set<Retirement<B>> retired = Collections.newSetFromMap(new IdentityHashMap<>());
  private long pcmBytes, activeNativeBytes, nativeBytes;

  AudioAssetCache(int maxSounds, long maxPcmBytes, long maxNativeBytes) {
    this.maxSounds = maxSounds;
    this.maxPcmBytes = maxPcmBytes;
    this.maxNativeBytes = maxNativeBytes;
  }

  int register(String name, PcmSound sound) {
    String canonical = new VirtualPath(name).value();
    Integer existing = names.get(canonical);
    if (existing != null) return existing;
    Objects.requireNonNull(sound);
    long bytes = (long) sound.frames() * sound.channels() * sound.bits() / 8;
    if (sounds.size() >= maxSounds || pcmBytes + bytes > maxPcmBytes)
      throw new IllegalStateException("Q3 audio registration budget exceeded");
    sounds.add(sound);
    int handle = sounds.size();
    names.put(canonical, handle);
    pcmBytes += bytes;
    return handle;
  }

  PcmSound sound(int handle) {
    if (handle < 1 || handle > sounds.size())
      throw new IllegalArgumentException("Unknown Q3 sound handle " + handle);
    return sounds.get(handle - 1);
  }

  B buffer(int sound, boolean mono) {
    return buffers.get(new Key(sound, mono));
  }

  B buffer(int sound, boolean mono, int bytes, Supplier<B> create) {
    sound(sound);
    Key key = new Key(sound, mono);
    B existing = buffers.get(key);
    if (existing != null) return existing;
    if (bytes < 0 || nativeBytes + bytes > maxNativeBytes)
      throw new IllegalStateException("Q3 native audio buffer budget exceeded");
    B result = Objects.requireNonNull(create.get());
    buffers.put(key, result);
    activeNativeBytes += bytes;
    nativeBytes += bytes;
    return result;
  }

  Retirement<B> reset() {
    sounds.clear();
    names.clear();
    pcmBytes = 0;
    return retireBuffers();
  }

  private Retirement<B> retireBuffers() {
    var batch = new Retirement<>(List.copyOf(buffers.values()), activeNativeBytes);
    if (!buffers.isEmpty()) retired.add(batch);
    buffers.clear();
    activeNativeBytes = 0;
    return batch;
  }

  /** Invoke only after this batch's sources have been released on the host audio executor. */
  void discard(Retirement<B> batch, Consumer<B> dispose) {
    if (!retired.remove(batch)) return;
    RuntimeException failure = null;
    for (B buffer : batch.buffers) {
      try {
        dispose.accept(buffer);
      } catch (RuntimeException error) {
        if (failure == null) failure = error;
        else failure.addSuppressed(error);
      }
    }
    nativeBytes -= batch.bytes;
    if (failure != null) throw failure;
  }

  /** Host teardown has released all sources, including those from pending reset batches. */
  void discardAll(Consumer<B> dispose) {
    retireBuffers();
    RuntimeException failure = null;
    for (var batch : List.copyOf(retired)) {
      try {
        discard(batch, dispose);
      } catch (RuntimeException error) {
        if (failure == null) failure = error;
        else failure.addSuppressed(error);
      }
    }
    if (failure != null) throw failure;
  }

  /** The host has destroyed its context; queued cleanup must not touch those stale native IDs. */
  void forgetBuffers() {
    buffers.clear();
    retired.clear();
    activeNativeBytes = nativeBytes = 0;
  }

  int sounds() {
    return sounds.size();
  }

  int buffers() {
    return buffers.size();
  }

  boolean ownsBuffers() {
    return !buffers.isEmpty() || !retired.isEmpty();
  }
}
