package dev.bluevista.craftq3.mc.devharness;

import dev.bluevista.craftq3.mc.fabric.CraftQ3Mod;
import dev.bluevista.craftq3.mc.fabric.command.Q3Command;
import dev.bluevista.craftq3.mc.fabric.config.CraftQ3Config;
import dev.bluevista.craftq3.mc.fabric.config.InstallationStatus;
import dev.bluevista.craftq3.mc.fabric.config.Q3Installation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.gametest.framework.GameTestHelper;
import org.spongepowered.asm.mixin.MixinEnvironment;

/**
 * Server GameTests, run on the dedicated GameTest server by {@code runGameTest}. They must never
 * reference client classes: the dedicated server has none.
 */
public final class CraftQ3ServerGameTests {
  /** The common entrypoint ran on a dedicated server, and only the common mixin config applies. */
  @GameTest
  public void commonEntrypointLoadsOnDedicatedServer(GameTestHelper helper) {
    FabricLoader loader = FabricLoader.getInstance();
    helper.assertTrue(
        loader.getEnvironmentType() == EnvType.SERVER,
        "expected a dedicated server, got " + loader.getEnvironmentType());
    boolean initialized =
        loader.getEntrypointContainers("main", ModInitializer.class).stream()
            .filter(c -> c.getProvider().getMetadata().getId().equals(CraftQ3Mod.MOD_ID))
            .map(EntrypointContainer::getEntrypoint)
            .anyMatch(entrypoint -> entrypoint instanceof CraftQ3Mod mod && mod.initialized());
    helper.assertTrue(initialized, "CraftQ3Mod.onInitialize did not run");
    helper.assertTrue(
        MixinEnvironment.getCurrentEnvironment().getSide() == MixinEnvironment.Side.SERVER,
        "Mixin is not running in the server environment, so client mixins could apply");
    helper.succeed();
  }

  /** {@code /q3} is a server command on a dedicated server, restricted to admins. */
  @GameTest
  public void q3CommandRegisteredOnDedicatedServer(GameTestHelper helper) {
    var server = helper.getLevel().getServer();
    var node = server.getCommands().getDispatcher().getRoot().getChild(Q3Command.NAME);
    helper.assertTrue(node != null, "/q3 is not registered on the dedicated server");
    helper.assertTrue(node.getChild("status") != null, "/q3 status is missing");
    helper.assertTrue(node.getChild("path") != null, "/q3 path is missing");
    helper.assertTrue(
        node.canUse(server.createCommandSourceStack()), "the server console cannot use /q3");
    helper.succeed();
  }

  /**
   * Installation discovery never throws: a missing directory, an empty {@code baseq3} and a broken
   * PK3 are reported, a real PK3 is counted, and the config round-trips relative paths.
   */
  @GameTest
  public void installationProbeAndConfig(GameTestHelper helper) {
    Path root = null;
    Path configDir = null;
    try {
      root = Files.createTempDirectory("craftq3-install");
      InstallationStatus missing = InstallationStatus.probe(root.resolve("nowhere"), "baseq3");
      helper.assertTrue(!missing.mounted(), "a missing directory mounted: " + missing.summary());

      InstallationStatus noBase = InstallationStatus.probe(root, "baseq3");
      helper.assertTrue(!noBase.mounted(), "a directory without baseq3 mounted");

      Path base = Files.createDirectories(root.resolve("baseq3"));
      InstallationStatus empty = InstallationStatus.probe(root, "baseq3");
      helper.assertTrue(empty.mounted() && !empty.usable(), "empty baseq3: " + empty.summary());

      Files.write(base.resolve("zero.pk3"), new byte[0]);
      try (ZipOutputStream zip =
          new ZipOutputStream(Files.newOutputStream(base.resolve("a.pk3")))) {
        zip.putNextEntry(new ZipEntry("scripts/test.shader"));
        zip.write("test { }\n".getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      InstallationStatus ok = InstallationStatus.probe(base, "baseq3"); // the baseq3 dir itself
      helper.assertTrue(ok.usable(), "a valid PK3 did not mount: " + ok.summary());
      helper.assertTrue(
          ok.root().equals(root), "a typed baseq3 directory was not mapped to its parent");
      helper.assertTrue(ok.baseArchives() == 1, "expected 1 archive, got " + ok.summary());
      helper.assertTrue(ok.skipped().size() == 1, "the empty PK3 was not skipped: " + ok.summary());

      configDir = Files.createTempDirectory("craftq3-config");
      CraftQ3Config config = CraftQ3Config.load(configDir).withInstallation("../q3");
      config.save();
      CraftQ3Config reloaded = CraftQ3Config.load(configDir);
      helper.assertTrue(
          reloaded.storedInstallation().equals("../q3"), "relative installation not kept");
      helper.assertTrue(
          reloaded.installationPath().orElseThrow().equals(configDir.resolve("../q3").normalize()),
          "relative installation does not resolve against the config dir");
      helper.assertTrue(reloaded.game().equals("baseq3"), "default game is not baseq3");

      Files.writeString(
          CraftQ3Config.file(configDir), "installation=\\u00", StandardCharsets.UTF_8);
      CraftQ3Config broken = CraftQ3Config.load(configDir);
      helper.assertTrue(broken.storedInstallation().isEmpty(), "a malformed file was not ignored");

      Q3Installation installation = CraftQ3Mod.instance().installation();
      helper.assertTrue(installation.status() != null, "no installation status");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      deleteTree(root);
      deleteTree(configDir);
    }
    helper.succeed();
  }

  /**
   * With {@code CRAFTQ3_Q3ROOT} set, the real installation mounts (never committed, see PORTING).
   */
  @GameTest
  public void realInstallationMounts(GameTestHelper helper) {
    String real = System.getenv("CRAFTQ3_Q3ROOT");
    if (real == null || real.isBlank()) {
      helper.succeed();
      return;
    }
    InstallationStatus status = InstallationStatus.probe(Path.of(real), "baseq3");
    LOGGER.info("Real installation: {}", status.summary());
    helper.assertTrue(status.usable(), "the real installation did not mount: " + status.summary());
    helper.succeed();
  }

  private static final org.slf4j.Logger LOGGER =
      org.slf4j.LoggerFactory.getLogger(CraftQ3ServerGameTests.class);

  private static void deleteTree(Path root) {
    if (root == null) return;
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
        Files.deleteIfExists(path);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
