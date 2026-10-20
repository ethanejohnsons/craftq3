package dev.bluevista.craftq3.mc.fabric.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The installation the mod uses, shared by the client and the dedicated server: the loaded {@link
 * CraftQ3Config} plus the last {@link InstallationStatus}. Probes mount every PK3, which can take
 * seconds on a large installation, so they run on their own short-lived virtual thread and never on
 * the server or render thread; nothing here throws into Minecraft.
 *
 * <p>Thread-safe: the config and status are published through volatile fields.
 */
public final class Q3Installation {
  private static final Logger LOGGER = LoggerFactory.getLogger(Q3Installation.class);

  private volatile CraftQ3Config config;
  private volatile InstallationStatus status;

  public Q3Installation(CraftQ3Config config) {
    this.config = Objects.requireNonNull(config, "config");
    this.status = InstallationStatus.unconfigured(config.game());
  }

  public CraftQ3Config config() {
    return config;
  }

  /** The last probe's result; {@link InstallationStatus#unconfigured} until the first probe. */
  public InstallationStatus status() {
    return status;
  }

  /** Probes the configured installation in the background. */
  public CompletableFuture<InstallationStatus> refresh() {
    CraftQ3Config current = config;
    Optional<Path> root = current.installationPath();
    if (root.isEmpty()) {
      InstallationStatus none = InstallationStatus.unconfigured(current.game());
      status = none;
      return CompletableFuture.completedFuture(none);
    }
    return probeAsync(root.get(), current.game())
        .thenApply(
            result -> {
              if (config == current) status = result;
              return result;
            });
  }

  /** The outcome of {@link #change}. */
  public record Change(InstallationStatus status, boolean saved, String saveError) {}

  /**
   * Probes {@code value} (resolved like the config file's {@code installation}) and, if it mounts
   * with at least one PK3, stores it and saves the config. A directory that fails is reported and
   * not stored.
   */
  public CompletableFuture<Change> change(String value) {
    CraftQ3Config current = config;
    Optional<Path> resolved = current.resolve(value);
    if (resolved.isEmpty())
      return CompletableFuture.completedFuture(
          new Change(InstallationStatus.unconfigured(current.game()), false, "not a valid path"));
    return probeAsync(resolved.get(), current.game())
        .thenApply(
            result -> {
              if (!result.usable()) return new Change(result, false, null);
              // Store what the probe actually mounted (a typed baseq3 directory becomes its
              // parent).
              String stored =
                  result.root().equals(resolved.get()) ? value.strip() : result.root().toString();
              CraftQ3Config next = current.withInstallation(stored);
              config = next;
              status = result;
              try {
                next.save();
                return new Change(result, true, null);
              } catch (IOException | RuntimeException e) {
                LOGGER.warn(
                    "CraftQ3: cannot save {}", CraftQ3Config.file(next.configDirectory()), e);
                return new Change(result, false, e.getMessage());
              }
            });
  }

  private static CompletableFuture<InstallationStatus> probeAsync(Path root, String game) {
    CompletableFuture<InstallationStatus> result = new CompletableFuture<>();
    Thread.ofVirtual()
        .name("craftq3-installation-probe")
        .start(
            () -> {
              try {
                result.complete(InstallationStatus.probe(root, game));
              } catch (Throwable t) {
                result.completeExceptionally(t);
              }
            });
    return result;
  }
}
