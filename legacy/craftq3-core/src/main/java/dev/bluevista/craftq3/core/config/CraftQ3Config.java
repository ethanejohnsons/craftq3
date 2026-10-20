package dev.bluevista.craftq3.core.config;

import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Configuration uses host paths only here, never paths provided by a QVM. */
public record CraftQ3Config(Path installation, String game) {
  public CraftQ3Config {
    installation = installation.toAbsolutePath().normalize();
    game = VirtualPath.gameDirectory(game);
  }

  public static CraftQ3Config load(Path file, Path defaultInstallation) throws IOException {
    if (!Files.exists(file)) {
      Files.createDirectories(defaultInstallation.resolve("baseq3"));
      CraftQ3Config config = new CraftQ3Config(defaultInstallation, "baseq3");
      config.save(file);
      return config;
    }
    Properties props = new Properties();
    if (Files.size(file) > 65536) throw new IOException("CraftQ3 config exceeds 64 KiB");
    try (Reader reader = Files.newBufferedReader(file)) {
      props.load(reader);
    }
    try {
      Path installation =
          Path.of(props.getProperty("installation", defaultInstallation.toString()));
      if (!installation.isAbsolute())
        installation = file.toAbsolutePath().getParent().resolve(installation);
      return new CraftQ3Config(installation, props.getProperty("game", "baseq3"));
    } catch (IllegalArgumentException e) {
      throw new IOException("Invalid CraftQ3 config: " + e.getMessage(), e);
    }
  }

  public void save(Path file) throws IOException {
    Path absolute = file.toAbsolutePath();
    Files.createDirectories(absolute.getParent());
    Properties props = new Properties();
    props.setProperty("installation", installation.toString());
    props.setProperty("game", game);
    Path temp = Files.createTempFile(absolute.getParent(), "craftq3-", ".tmp");
    try {
      try (Writer writer = Files.newBufferedWriter(temp)) {
        props.store(writer, "CraftQ3: installation contains baseq3/ and optional mod directories");
      }
      try {
        Files.move(
            temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}
