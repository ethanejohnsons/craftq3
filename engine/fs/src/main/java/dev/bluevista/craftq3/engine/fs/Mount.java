package dev.bluevista.craftq3.engine.fs;

import java.nio.file.Path;

/**
 * One element of a {@link SearchPath}: a PK3 or a loose game directory. Mounts are immutable and
 * owned by their search path, which closes them.
 */
public sealed interface Mount permits PackMount, DirectoryMount {
  /** Position in the search path's build order; 0 is searched first. */
  int index();

  /** The game directory this mount belongs to, in lookup form ({@code baseq3}). */
  String game();

  /** The archive file or directory on disk. */
  Path location();
}
