package dev.bluevista.craftq3.engine.session;

import dev.bluevista.craftq3.engine.console.ConfigSink;
import dev.bluevista.craftq3.engine.console.ConfigSource;
import dev.bluevista.craftq3.engine.fs.FileView;
import dev.bluevista.craftq3.engine.fs.HomeStore;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * The files a session reads and writes: configs for {@code exec} come from the game's home store
 * first and then the search path (install), {@code writeconfig} and demos go to the home store
 * ({@code <home>/<game>/}). Without a home nothing is written and writes report why.
 */
final class SessionFiles implements AutoCloseable {
  private final HomeStore home;
  private final Supplier<FileView> view;

  /**
   * @param home the game's home store, or null
   * @param view the current read-only file view (it changes when content is remounted)
   */
  SessionFiles(HomeStore home, Supplier<FileView> view) {
    this.home = home;
    this.view = view;
  }

  /** Opens {@code <homeRoot>/<game>}, or returns null (after reporting) when it cannot be used. */
  static HomeStore openHome(Path homeRoot, String game, java.util.function.Consumer<String> out) {
    if (homeRoot == null) return null;
    try {
      return HomeStore.open(homeRoot.resolve(VirtualPath.gameDirectory(game)));
    } catch (IOException | RuntimeException failure) {
      out.accept("Home directory unusable, nothing will be saved: " + failure.getMessage());
      return null;
    }
  }

  Optional<HomeStore> home() {
    return Optional.ofNullable(home);
  }

  /** {@code exec} files: the home copy, else the installed one. */
  ConfigSource configSource() {
    return path -> read(path).map(bytes -> new String(bytes, StandardCharsets.ISO_8859_1));
  }

  /** {@code writeconfig}: atomically replaced in the home store. */
  ConfigSink configSink() {
    return (path, text) -> {
      if (home == null) throw new IOException("No writable game home");
      VirtualPath target =
          VirtualPath.parse(path).orElseThrow(() -> new IOException("Invalid path " + path));
      home.write(target, text.getBytes(StandardCharsets.ISO_8859_1));
    };
  }

  /** A game file, home first. */
  Optional<byte[]> read(String path) {
    Optional<VirtualPath> parsed = VirtualPath.parse(path);
    if (parsed.isEmpty()) return Optional.empty();
    try {
      if (home != null) {
        Optional<byte[]> saved = home.readIfPresent(parsed.get());
        if (saved.isPresent()) return saved;
      }
      FileView files = view.get();
      if (!files.exists(parsed.get())) return Optional.empty();
      return Optional.of(files.read(parsed.get()));
    } catch (IOException unreadable) {
      return Optional.empty();
    }
  }

  /** Whether a game file exists (home or search path). */
  boolean exists(String path) {
    Optional<VirtualPath> parsed = VirtualPath.parse(path);
    if (parsed.isEmpty()) return false;
    if (home != null && home.exists(parsed.get())) return true;
    return view.get().exists(parsed.get());
  }

  /** Opens a game file for streaming, home first. */
  Optional<InputStream> open(String path) throws IOException {
    Optional<VirtualPath> parsed = VirtualPath.parse(path);
    if (parsed.isEmpty()) return Optional.empty();
    if (home != null) {
      Optional<InputStream> saved = home.openRead(parsed.get());
      if (saved.isPresent()) return saved;
    }
    FileView files = view.get();
    if (!files.exists(parsed.get())) return Optional.empty();
    return Optional.of(files.open(parsed.get()));
  }

  /** Names directly in {@code directory} ending with {@code extension}, home and installed. */
  List<String> list(String directory, String extension) {
    TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    if (home != null) {
      try {
        names.addAll(home.list(directory, extension));
      } catch (IOException unreadable) {
        // Listing the home is best effort.
      }
    }
    for (String name : view.get().list(directory, extension))
      if (!name.contains("/")) names.add(name);
    return new ArrayList<>(names);
  }

  /**
   * Starts writing {@code path} in the home store; the returned stream publishes the file when it
   * is closed (after {@code record}'s end marker) unless {@link Recording#discard} ran first.
   */
  Recording create(String path) throws IOException {
    if (home == null) throw new IOException("No writable game home for " + path);
    VirtualPath target =
        VirtualPath.parse(path).orElseThrow(() -> new IOException("Invalid path " + path));
    return new Recording(home.openAtomicWrite(target));
  }

  /** A streamed home write that commits on close. */
  static final class Recording extends FilterOutputStream {
    private final HomeStore.AtomicOutput atomic;
    private boolean discarded, closed;

    Recording(HomeStore.AtomicOutput atomic) {
      super(atomic);
      this.atomic = atomic;
    }

    long byteLimit() {
      return atomic.byteLimit();
    }

    long bytesWritten() {
      return atomic.bytesWritten();
    }

    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
      atomic.write(data, offset, length);
    }

    /** The recording failed: closing will not publish it. */
    void discard() throws IOException {
      discarded = true;
      atomic.abort();
    }

    @Override
    public void close() throws IOException {
      if (closed) return;
      closed = true;
      if (discarded) {
        atomic.abort();
        return;
      }
      try {
        atomic.commit();
      } finally {
        atomic.close();
      }
    }
  }

  /** A demo name as typed ({@code foo}, {@code demos/foo.dm_68}) reduced to a file name. */
  static String demoFileName(String requested) {
    String name = requested.replace('\\', '/');
    if (name.regionMatches(true, 0, "demos/", 0, 6)) name = name.substring(6);
    if (name.isEmpty() || name.contains("/") || VirtualPath.parse(name).isEmpty())
      throw new IllegalArgumentException("Invalid demo name: " + requested);
    return name;
  }

  /** True when {@code name} ends with a demo extension ({@code .dm_NN} or {@code .dm3}). */
  static boolean hasDemoExtension(String name) {
    return name.toLowerCase(Locale.ROOT).matches(".*\\.(dm_\\d+|dm3)");
  }

  @Override
  public void close() throws IOException {
    if (home != null) home.close();
  }
}
