package dev.bluevista.craftq3.engine.session;

import dev.bluevista.craftq3.engine.client.ClientHostServices;
import dev.bluevista.craftq3.engine.client.console.ClientConsole;
import dev.bluevista.craftq3.engine.client.console.ConsoleOverlay;
import dev.bluevista.craftq3.engine.client.host.HostServices;
import dev.bluevista.craftq3.engine.client.host.UiHost;
import dev.bluevista.craftq3.engine.console.Console;
import dev.bluevista.craftq3.engine.fs.FileView;
import dev.bluevista.craftq3.engine.fs.HomeStore;
import dev.bluevista.craftq3.engine.vmabi.service.TimeServices;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The client's content-bound services for one file view: the shared VM host services (assets,
 * renderer, sound, input), the ui VM and the console overlay. Rebuilt when the file view changes (a
 * pure server's pack list, downloaded packs); the console, its cvars and bindings survive.
 *
 * <p>Owned by the client thread.
 */
final class ClientContent implements AutoCloseable {
  final FileView view;
  final HostServices services;
  final UiHost ui;
  final ConsoleOverlay overlay;

  ClientContent(
      Console console,
      ClientConsole output,
      FileView view,
      HomeStore home,
      Path installRoot,
      ClientHostServices platform,
      TimeServices time,
      boolean decodeImages,
      UiHost.Link link) {
    this.view = view;
    this.services =
        new HostServices(console, output, view, home, installRoot, platform, time, decodeImages);
    this.ui = new UiHost(services, link);
    this.overlay = new ConsoleOverlay(services.renderer(), output);
  }

  /** Boots the ui to {@code menu}. */
  void startUi(UiHost.Menu menu) throws IOException {
    ui.start();
    ui.setActiveMenu(menu);
  }

  /** True while the ui VM runs. */
  boolean uiRunning() {
    return ui.state() == UiHost.State.RUNNING;
  }

  @Override
  public void close() {
    ui.shutdown();
    services.close();
  }
}
