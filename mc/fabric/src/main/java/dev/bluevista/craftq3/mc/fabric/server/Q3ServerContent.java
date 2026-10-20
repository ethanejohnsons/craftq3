package dev.bluevista.craftq3.mc.fabric.server;

import dev.bluevista.craftq3.mc.fabric.config.CraftQ3Config;
import dev.bluevista.craftq3.mc.fabric.config.Q3Installation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The installation content one Minecraft server uses for the bridge and building modes, mounted
 * once on a virtual thread and shared by every level. A changed configuration ({@code /q3 path})
 * mounts afresh on the next request; the previous content stays open for games still using it until
 * the server stops.
 *
 * <p>Created per server start and closed when it stops; its methods are called on the server
 * thread.
 */
public final class Q3ServerContent implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(Q3ServerContent.class);

  private final Q3Installation installation;
  private CompletableFuture<Q3Content> content;
  private CraftQ3Config mountedFor;
  private final java.util.List<CompletableFuture<Q3Content>> all = new java.util.ArrayList<>();

  public Q3ServerContent(Q3Installation installation) {
    this.installation = Objects.requireNonNull(installation, "installation");
  }

  /** The content of the configured installation, mounting it on first use. */
  public CompletableFuture<Q3Content> content() {
    CraftQ3Config config = installation.config();
    if (content != null && config == mountedFor && !content.isCompletedExceptionally())
      return content;
    Optional<Path> root = config.installationPath();
    mountedFor = config;
    if (root.isEmpty()) {
      content =
          CompletableFuture.failedFuture(
              new IOException("No Quake III installation is configured (/q3 path <dir>)"));
      return content;
    }
    CompletableFuture<Q3Content> mounting = new CompletableFuture<>();
    String game = config.game();
    Thread.ofVirtual()
        .name("craftq3-server-mount")
        .start(
            () -> {
              try {
                long start = System.nanoTime();
                Q3Content mounted = Q3Content.mount(root.get(), game);
                LOGGER.info(
                    "CraftQ3: mounted {} ({}) for the server in {} ms",
                    mounted.root(),
                    mounted.game(),
                    (System.nanoTime() - start) / 1_000_000);
                mounting.complete(mounted);
              } catch (Throwable t) {
                mounting.completeExceptionally(t);
              }
            });
    content = mounting;
    all.add(mounting);
    return mounting;
  }

  /** The configured game directory as the search path spells it, e.g. {@code baseq3}. */
  public String gameName() {
    return dev.bluevista.craftq3.engine.fs.VirtualPath.gameDirectory(installation.config().game());
  }

  /** The content if it is already mounted, without starting a mount. */
  public Optional<Q3Content> now() {
    CompletableFuture<Q3Content> c = content;
    if (c == null || !c.isDone() || c.isCompletedExceptionally()) return Optional.empty();
    return Optional.of(c.join());
  }

  @Override
  public void close() {
    for (CompletableFuture<Q3Content> c : all)
      c.thenAccept(
          mounted -> {
            try {
              mounted.close();
            } catch (IOException e) {
              LOGGER.warn("CraftQ3: closing the server's installation mount", e);
            }
          });
    all.clear();
    content = null;
  }
}
