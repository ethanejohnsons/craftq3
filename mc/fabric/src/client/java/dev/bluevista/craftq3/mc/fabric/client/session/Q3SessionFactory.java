package dev.bluevista.craftq3.mc.fabric.client.session;

/** Opens a {@link Q3Session} on a host. Called on the render thread. */
@FunctionalInterface
public interface Q3SessionFactory {
  /**
   * Starts a session at the main menu. Mounting and loading may continue in the session's own
   * workers; the session reports frames through the host as they become available.
   *
   * @throws Exception when the session cannot start (missing QVMs, unreadable installation)
   */
  Q3Session open(MinecraftSessionHost host) throws Exception;
}
