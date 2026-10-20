package dev.bluevista.craftq3.mc.fabric.client.session;

import dev.bluevista.craftq3.engine.session.EngineSession;
import dev.bluevista.craftq3.engine.session.GameLocalServer;
import dev.bluevista.craftq3.engine.session.SessionOptions;
import java.util.List;

/** Starts an {@link EngineSession} on a host. Called on the render thread (the client thread). */
@FunctionalInterface
public interface Q3SessionFactory {
  /**
   * Starts a session at the main menu, with {@code commandLine} queued after the configs ({@code
   * +map q3dm17} style lines without the {@code +}).
   *
   * @throws Exception when the session cannot start (unreadable installation, no ui)
   */
  EngineSession open(MinecraftSessionHost host, List<String> commandLine) throws Exception;

  /** Interactive play: a threaded {@code engine:game} server, configs written, UDP hosting on. */
  Q3SessionFactory DEFAULT =
      (host, commandLine) ->
          EngineSession.start(
              host, SessionOptions.defaults(GameLocalServer.FACTORY).withCommandLine(commandLine));
}
