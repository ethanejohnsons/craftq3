package dev.bluevista.craftq3.mc.fabric.client.screen;

import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.mc.fabric.client.audio.MinecraftAudio;
import dev.bluevista.craftq3.mc.fabric.client.render.Q3RenderContext;
import dev.bluevista.craftq3.mc.fabric.client.session.Q3SessionFactory;
import dev.bluevista.craftq3.mc.fabric.config.CraftQ3Config;
import dev.bluevista.craftq3.mc.fabric.config.InstallationStatus;
import dev.bluevista.craftq3.mc.fabric.config.Q3Installation;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Opens Quake III from the title screen button and the {@code /q3 menu|map|connect|demo} client
 * commands: a {@link Q3Screen} on the configured installation, or the {@link
 * Q3MissingInstallationScreen} when there is none. Render thread only.
 */
public final class Q3Launcher {
  private final Q3Installation installation;
  private final Q3RenderContext renderContext;
  private final Supplier<MinecraftAudio> audio;
  private final Supplier<Q3SessionFactory> factory;
  private final Path gameDirectory;

  public Q3Launcher(
      Q3Installation installation,
      Q3RenderContext renderContext,
      Supplier<MinecraftAudio> audio,
      Supplier<Q3SessionFactory> factory,
      Path gameDirectory) {
    this.installation = Objects.requireNonNull(installation, "installation");
    this.renderContext = Objects.requireNonNull(renderContext, "renderContext");
    this.audio = Objects.requireNonNull(audio, "audio");
    this.factory = Objects.requireNonNull(factory, "factory");
    this.gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory");
  }

  /**
   * Opens Quake III over {@code parent} (returned to when it closes; null returns to the game).
   * Returns the screen opened, a {@link Q3Screen} or a {@link Q3MissingInstallationScreen}.
   */
  public Screen open(Minecraft minecraft, Screen parent, Q3Launch launch) {
    CraftQ3Config config = installation.config();
    Optional<String> problem = problem(config);
    Screen screen =
        problem.isPresent()
            ? new Q3MissingInstallationScreen(parent, problem.get())
            : Q3Screen.create(
                parent,
                launch,
                factory.get(),
                renderContext,
                audio.get(),
                InstallationStatus.installationRoot(config.installationPath().orElseThrow()),
                config.game(),
                gameDirectory);
    minecraft.gui.setScreen(screen);
    return screen;
  }

  /**
   * Why the configured installation cannot run, or empty when it looks usable: a quick check for
   * {@code baseq3} with at least one PK3 (and the game directory). The full mount happens in the
   * session, which reports its own failures.
   */
  public static Optional<String> problem(CraftQ3Config config) {
    Optional<Path> configured = config.installationPath();
    if (configured.isEmpty()) return Optional.of("No Quake III installation is configured yet.");
    Path root = InstallationStatus.installationRoot(configured.get());
    Path base = root.resolve(SearchPath.BASE_GAME);
    if (!Files.isDirectory(root))
      return Optional.of("The configured directory does not exist: " + root);
    if (!Files.isDirectory(base)) return Optional.of("There is no baseq3 directory in " + root);
    if (!hasPk3(base)) return Optional.of("There are no PK3 files in " + base);
    String game = config.game();
    if (!game.equalsIgnoreCase(SearchPath.BASE_GAME) && !Files.isDirectory(root.resolve(game)))
      return Optional.of("The game directory " + game + " is missing from " + root);
    return Optional.empty();
  }

  private static boolean hasPk3(Path directory) {
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
      for (Path entry : entries)
        if (entry.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pk3")) return true;
      return false;
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }
}
