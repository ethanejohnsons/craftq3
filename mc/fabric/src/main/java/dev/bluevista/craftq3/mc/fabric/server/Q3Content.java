package dev.bluevista.craftq3.mc.fabric.server;

import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import dev.bluevista.craftq3.mc.fabric.config.InstallationStatus;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Optional;

/**
 * One mounted Quake III installation as a Minecraft server uses it: the search path of the
 * configured game, and the identity (SHA-256) of the qagame module it runs, which saved loadouts
 * are tied to. Mounting reads every PK3's directory, so it is done once per server and off the
 * server thread ({@link Q3ServerContent}).
 *
 * <p>The search path is read-only and thread-safe; it is closed when the server stops.
 */
public final class Q3Content implements AutoCloseable {
  private final Path root;
  private final String game;
  private final SearchPath searchPath;
  private final byte[] qagameSha256;

  private Q3Content(Path root, String game, SearchPath searchPath, byte[] qagameSha256) {
    this.root = root;
    this.game = game;
    this.searchPath = searchPath;
    this.qagameSha256 = qagameSha256;
  }

  /**
   * Mounts {@code game} of the installation at {@code root} (blocking).
   *
   * @throws IOException when the installation cannot be mounted or has no qagame module
   */
  public static Q3Content mount(Path root, String game) throws IOException {
    Objects.requireNonNull(root, "root");
    Path installation = InstallationStatus.installationRoot(root);
    SearchPath path = SearchPath.mount(installation, game);
    try {
      byte[] module = path.read(VirtualPath.of("vm/qagame.qvm"));
      return new Q3Content(installation, path.game(), path, sha256(module));
    } catch (IOException missing) {
      path.close();
      throw new IOException("No vm/qagame.qvm in " + game + ": " + missing.getMessage(), missing);
    } catch (RuntimeException failure) {
      path.close();
      throw failure;
    }
  }

  public Path root() {
    return root;
  }

  /** The game directory as mounted, e.g. {@code baseq3}. */
  public String game() {
    return game;
  }

  public SearchPath searchPath() {
    return searchPath;
  }

  /** The SHA-256 of {@code vm/qagame.qvm}. */
  public byte[] qagameSha256() {
    return qagameSha256.clone();
  }

  /** The bytes of a game file, or empty when it does not exist. */
  public Optional<byte[]> read(String path) throws IOException {
    Optional<VirtualPath> parsed = VirtualPath.parse(path);
    if (parsed.isEmpty() || !searchPath.exists(parsed.get())) return Optional.empty();
    return Optional.of(searchPath.read(parsed.get()));
  }

  static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  @Override
  public void close() throws IOException {
    searchPath.close();
  }
}
