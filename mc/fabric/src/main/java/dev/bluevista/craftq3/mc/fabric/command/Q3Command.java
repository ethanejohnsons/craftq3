package dev.bluevista.craftq3.mc.fabric.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.bluevista.craftq3.mc.fabric.config.CraftQ3Config;
import dev.bluevista.craftq3.mc.fabric.config.InstallationStatus;
import dev.bluevista.craftq3.mc.fabric.config.Q3Installation;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import net.minecraft.network.chat.Component;

/**
 * {@code /q3 status} and {@code /q3 path <dir>}, built once for any command source so the client
 * command and the dedicated-server command share one tree. Both probe off-thread and report on the
 * source's main thread.
 */
public final class Q3Command {
  public static final String NAME = "q3";

  /** How a command source reports back. */
  public interface Feedback<S> {
    void success(S source, Component message);

    void failure(S source, Component message);

    /** The thread that may talk to {@code source} (render or server thread). */
    Executor mainThread(S source);
  }

  private Q3Command() {}

  public static <S> LiteralArgumentBuilder<S> build(
      Q3Installation installation, Feedback<S> feedback, Predicate<S> requirement) {
    return LiteralArgumentBuilder.<S>literal(NAME)
        .requires(requirement)
        .then(
            LiteralArgumentBuilder.<S>literal("status")
                .executes(c -> status(c.getSource(), installation, feedback)))
        .then(
            LiteralArgumentBuilder.<S>literal("path")
                .executes(c -> showPath(c.getSource(), installation, feedback))
                .then(
                    RequiredArgumentBuilder.<S, String>argument(
                            "dir", StringArgumentType.greedyString())
                        .executes(c -> setPath(c, installation, feedback))));
  }

  private static <S> int status(S source, Q3Installation installation, Feedback<S> feedback) {
    CraftQ3Config config = installation.config();
    feedback.success(source, Component.literal("CraftQ3: checking " + describe(config) + "..."));
    installation
        .refresh()
        .whenCompleteAsync(
            (status, error) -> report(source, feedback, status, error),
            feedback.mainThread(source));
    return 1;
  }

  private static <S> int showPath(S source, Q3Installation installation, Feedback<S> feedback) {
    feedback.success(source, Component.literal("CraftQ3: " + describe(installation.config())));
    return 1;
  }

  private static <S> int setPath(
      CommandContext<S> context, Q3Installation installation, Feedback<S> feedback) {
    S source = context.getSource();
    String value = unquote(StringArgumentType.getString(context, "dir"));
    feedback.success(source, Component.literal("CraftQ3: checking " + value + "..."));
    installation
        .change(value)
        .whenCompleteAsync(
            (change, error) -> {
              if (error != null) {
                report(source, feedback, null, error);
                return;
              }
              InstallationStatus status = change.status();
              if (!status.usable()) {
                String reason =
                    status.mounted()
                        ? "no PK3s found in " + status.root() + "/baseq3"
                        : status.summary();
                feedback.failure(source, Component.literal("CraftQ3: not changed. " + reason));
                return;
              }
              feedback.success(source, Component.literal("CraftQ3: " + status.summary()));
              if (change.saved())
                feedback.success(
                    source,
                    Component.literal(
                        "CraftQ3: saved to "
                            + CraftQ3Config.file(installation.config().configDirectory())));
              else
                feedback.failure(
                    source,
                    Component.literal("CraftQ3: in use now but not saved: " + change.saveError()));
              if (installation.config().overridden())
                feedback.failure(
                    source,
                    Component.literal(
                        "CraftQ3: a -D"
                            + CraftQ3Config.PROPERTY_INSTALLATION
                            + " override still takes precedence this run"));
            },
            feedback.mainThread(source));
    return 1;
  }

  private static <S> void report(
      S source, Feedback<S> feedback, InstallationStatus status, Throwable error) {
    if (error != null) {
      feedback.failure(source, Component.literal("CraftQ3: check failed: " + error));
    } else if (status.usable()) {
      feedback.success(source, Component.literal("CraftQ3: " + status.summary()));
    } else {
      feedback.failure(source, Component.literal("CraftQ3: " + status.summary()));
    }
  }

  private static String describe(CraftQ3Config config) {
    String installation = config.installation();
    if (installation.isBlank()) return "no installation configured (game " + config.game() + ")";
    String resolved = config.installationPath().map(Object::toString).orElse(installation);
    return "installation " + resolved + " (game " + config.game() + ")";
  }

  private static String unquote(String value) {
    String v = value.strip();
    if (v.length() >= 2
        && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))))
      return v.substring(1, v.length() - 1);
    return v;
  }
}
