package dev.bluevista.craftq3.engine.fs;

import dev.bluevista.craftq3.engine.common.log.Log;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A loose game directory, indexed once when the search path is built; rebuild the search path to
 * see files added later. Symbolic links inside the directory are not indexed. Every read re-checks
 * that the file is still a regular file inside the directory, so replacing it with a link after the
 * build cannot redirect a read.
 */
public final class DirectoryMount implements Mount {
  /** Deepest directory level indexed below the game directory. */
  public static final int MAX_DEPTH = 32;

  private static final Log LOG = Log.get(DirectoryMount.class);

  private final int index;
  private final String game;
  private final Path root;
  private final List<String> names;
  private final Map<String, Integer> byKey;

  private DirectoryMount(
      int index, String game, Path root, List<String> names, Map<String, Integer> byKey) {
    this.index = index;
    this.game = game;
    this.root = root;
    this.names = names;
    this.byKey = byKey;
  }

  /** Indexes {@code root}, which must already be a real path; at most {@code budget} files. */
  static DirectoryMount scan(int index, String game, Path root, int budget) throws IOException {
    var found = new ArrayList<String>();
    Files.walkFileTree(
        root,
        EnumSet.noneOf(java.nio.file.FileVisitOption.class),
        MAX_DEPTH,
        new FileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            if (!attributes.isRegularFile()) return FileVisitResult.CONTINUE;
            if (found.size() >= budget)
              throw new IOException("Search path exceeds its file budget at " + file);
            found.add(relative(root, file));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException failure) {
            LOG.warnOnce("unreadable:" + file, "Skipping unreadable path " + file + ": " + failure);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException failure) {
            if (failure != null)
              LOG.warnOnce(
                  "unreadable:" + dir, "Stopped listing directory " + dir + ": " + failure);
            return FileVisitResult.CONTINUE;
          }
        });
    // Sorted so that names differing only in case resolve the same way on every run.
    Collections.sort(found);
    var names = new ArrayList<String>(found.size());
    var byKey = new HashMap<String, Integer>(found.size() * 2);
    for (String name : found) {
      VirtualPath path = VirtualPath.parse(name).orElse(null);
      if (path == null) {
        LOG.warnOnce("unsafe:" + root + "/" + name, "Skipping unsafe loose name " + name);
        continue;
      }
      if (byKey.putIfAbsent(path.key(), names.size()) != null) {
        LOG.warnOnce(
            "case:" + root + "/" + path.key(),
            "Loose names in " + root + " differ only in case; using the first: " + name);
        continue;
      }
      names.add(name);
    }
    return new DirectoryMount(index, game, root, List.copyOf(names), byKey);
  }

  private static String relative(Path root, Path file) {
    Path relative = root.relativize(file);
    var text = new StringBuilder();
    for (Path part : relative) {
      if (!text.isEmpty()) text.append('/');
      text.append(part);
    }
    return text.toString();
  }

  @Override
  public int index() {
    return index;
  }

  @Override
  public String game() {
    return game;
  }

  @Override
  public Path location() {
    return root;
  }

  /** Indexed file names relative to the directory, '/'-separated, as spelled on disk, sorted. */
  public List<String> names() {
    return names;
  }

  /** The on-disk spelling of {@code path}, or null when it is not indexed. */
  String spelling(VirtualPath path) {
    Integer at = byKey.get(path.key());
    return at == null ? null : names.get(at);
  }

  /** Checks the file is still a regular file inside the directory and returns its size. */
  private Path checked(VirtualPath path, String spelling, long limit) throws IOException {
    Path file = root.resolve(spelling);
    BasicFileAttributes attributes;
    try {
      attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (NoSuchFileException missing) {
      throw new NoSuchFileException(path.key(), null, "loose file removed since mount");
    }
    if (!attributes.isRegularFile() || !file.toRealPath().startsWith(root))
      throw new IOException("Loose file escaped its directory or changed type: " + path.key());
    if (attributes.size() > limit)
      throw new IOException("Loose file exceeds " + limit + " bytes: " + path.key());
    return file;
  }

  /** Current size of an indexed file. */
  long size(VirtualPath path, String spelling) throws IOException {
    return Files.readAttributes(
            checked(path, spelling, Long.MAX_VALUE),
            BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS)
        .size();
  }

  byte[] read(VirtualPath path, String spelling) throws IOException {
    try (InputStream input = open(path, spelling, Pk3Archive.MAX_FILE_BYTES)) {
      return input.readAllBytes();
    }
  }

  /** A stream bounded to the size observed when it was opened. */
  InputStream open(VirtualPath path, String spelling, long limit) throws IOException {
    Path file = checked(path, spelling, limit);
    var channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    try {
      long size = channel.size();
      if (size > limit) throw new IOException("Loose file exceeds " + limit + " bytes: " + path);
      return new VerifiedStream(
          Channels.newInputStream(channel), size, VerifiedStream.NO_CRC, path.key());
    } catch (IOException | RuntimeException failure) {
      try {
        channel.close();
      } catch (IOException close) {
        failure.addSuppressed(close);
      }
      throw failure;
    }
  }

  @Override
  public String toString() {
    return root.toString();
  }
}
