package dev.bluevista.craftq3.engine.client;

import dev.bluevista.craftq3.engine.client.console.ClientConsole;
import dev.bluevista.craftq3.engine.client.host.HostServices;
import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import dev.bluevista.craftq3.engine.common.time.ManualClock;
import dev.bluevista.craftq3.engine.console.ConfigSink;
import dev.bluevista.craftq3.engine.console.Console;
import dev.bluevista.craftq3.engine.fs.FileView;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import dev.bluevista.craftq3.engine.render.frame.CgameFrame;
import dev.bluevista.craftq3.engine.vmabi.service.TimeServices;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** A headless client for tests: a console, host services and a recording render sink. */
public final class ClientRig implements AutoCloseable {
  public final ManualClock clock = new ManualClock(0);
  public final List<CgameFrame> frames = new ArrayList<>();
  public final List<String> printed = new ArrayList<>();
  public final ClientConsole output = new ClientConsole();
  public final Console console;
  public final HostServices services;
  public boolean keepFrames = true;
  public long quads;
  public long views;
  public long framesSeen;

  public ClientRig(FileView view, Path installRoot, boolean strict) {
    ThreadOwner owner = new ThreadOwner("test client");
    owner.claim();
    output.mirror(printed::add);
    console = Console.client(owner, output, path -> read(view, path), ConfigSink.NONE);
    console.cvars().register("vm_strictSyscalls", strict ? "1" : "0", 0);
    RenderSink sink =
        frame -> {
          framesSeen++;
          for (CgameFrame.Command command : frame.commands()) {
            if (command instanceof CgameFrame.Quad) quads++;
            else if (command instanceof CgameFrame.View) views++;
          }
          if (keepFrames) frames.add(frame);
        };
    services =
        new HostServices(
            console,
            output,
            view,
            null,
            installRoot,
            ClientHostServices.headless(sink, 640, 480),
            new TimeServices(clock, java.time.Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)),
            false);
    console.queueStartupConfigs(true);
    console.frame();
  }

  private static Optional<String> read(FileView view, String path) {
    Optional<VirtualPath> parsed = VirtualPath.parse(path);
    if (parsed.isEmpty() || !view.exists(parsed.get())) return Optional.empty();
    try {
      return Optional.of(new String(view.read(parsed.get()), StandardCharsets.ISO_8859_1));
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /** Runs the console's queued commands. */
  public void frameCommands() {
    console.frame();
  }

  public void advance(int millis) {
    clock.advance(millis);
  }

  @Override
  public void close() {
    services.close();
  }
}
